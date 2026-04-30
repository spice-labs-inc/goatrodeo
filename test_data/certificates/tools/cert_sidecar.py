#!/usr/bin/env python3
"""Compute Certificates-strategy sidecars from cryptographic artifacts.

This module is the ground-truth computation engine for Phase 0b corpus
bootstrapping. It produces the exact `.expected.json` sidecar shape
declared in `certificates-strategy/appendices.md` Appendix B, using
field values derived from OpenSSL / cryptography-library introspection of
the input bytes.

Per project invariants:
  - Invariant #13: this script may run inside the Docker container built
    from `Dockerfile` in this directory (updated to include Python 3 and
    the `cryptography` package). It can also run directly on a host that
    has those dependencies.
  - Invariant #12: the sidecars this tool emits are ground truth; the
    Certificates strategy (Phase 3+) is tested against them.

Key fields computed per X.509 fixture:
  - cert SHA-256 (hex)
  - SPKI SHA-256 (hex)  -- SHA-256 of DER-encoded SubjectPublicKeyInfo
  - subject DN (RFC 2253)
  - issuer DN  (RFC 2253)
  - NotBefore / NotAfter (ISO-8601 UTC, e.g., 2015-06-04T11:04:38Z)
  - canonical key algorithm (rsa/dsa/ec/ed25519/ed448/...)
  - key size (for RSA/DSA)
  - curve (for EC)
  - canonical signature algorithm (see Appendix A)
  - IsCA (basic constraints)
  - SelfSigned (subject == issuer AND sig verifies against own SPKI)
  - Version (1, 2, 3)
  - KeyUsage / ExtendedKeyUsage (comma-separated lowercase-hyphenated)
  - SubjectAlternativeNames (comma-separated DNS:/IP:/email: entries)

Canonical mapping tables match `certificates-strategy/appendices.md`
Appendix A exactly.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import timezone
from typing import Any

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import (
    dsa,
    ec,
    ed25519,
    ed448,
    rsa,
)
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from cryptography.x509.oid import ExtensionOID, NameOID

# Forbidden-pattern list — MUST match Appendix C in
# `certificates-strategy/appendices.md`. Any metadata value in any
# emitted Item that matches one of these is a private-key leak.
FORBIDDEN_PATTERNS_DEFAULT = [
    # PEM envelope markers (raw or re-emitted verbatim)
    "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "-----BEGIN PGP PRIVATE KEY BLOCK-----",
    # Full PEM private-key body (any base64 block between BEGIN/END)
    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----",
    # PKCS#8 private key DER prefixes as base64 (common first bytes)
    "MIIEvQIBADAN",
    "MIIEpAIBAAKCAQEA",
    "MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+",
    # OpenSSH private envelope magic as text
    "openssh-key-v1",
]

# --- canonical key-algorithm / curve / sig-alg mappings (Appendix A) -----

_CURVE_CANONICAL: dict[str, tuple[str, str]] = {
    # openssl name -> (alg, curve)
    "secp256r1": ("ec", "p-256"),
    "prime256v1": ("ec", "p-256"),
    "secp384r1": ("ec", "p-384"),
    "secp521r1": ("ec", "p-521"),
    "secp256k1": ("ec", "secp256k1"),
    "brainpoolP256r1": ("ec", "brainpoolp256r1"),
    "brainpoolP384r1": ("ec", "brainpoolp384r1"),
    "brainpoolP512r1": ("ec", "brainpoolp512r1"),
}

# Map cryptography's SignatureAlgorithmOID dotted string -> canonical sig-alg.
# Refs: RFC 5754, RFC 8410, RFC 3279.
_SIG_ALG_CANONICAL: dict[str, str] = {
    "1.2.840.113549.1.1.4": "md5-rsa",      # md5WithRSAEncryption
    "1.2.840.113549.1.1.5": "sha1-rsa",     # sha1WithRSAEncryption
    "1.2.840.113549.1.1.11": "sha256-rsa",  # sha256WithRSAEncryption
    "1.2.840.113549.1.1.12": "sha384-rsa",  # sha384WithRSAEncryption
    "1.2.840.113549.1.1.13": "sha512-rsa",  # sha512WithRSAEncryption
    "1.2.840.113549.1.1.10": "rsa-pss",     # rsassaPss (hash is in params)
    "1.2.840.10045.4.1":    "sha1-ecdsa",   # ecdsa-with-SHA1
    "1.2.840.10045.4.3.2":  "sha256-ecdsa", # ecdsa-with-SHA256
    "1.2.840.10045.4.3.3":  "sha384-ecdsa", # ecdsa-with-SHA384
    "1.2.840.10045.4.3.4":  "sha512-ecdsa", # ecdsa-with-SHA512
    "1.3.101.112":          "ed25519",      # id-Ed25519
    "1.3.101.113":          "ed448",        # id-Ed448
    "1.2.840.10040.4.3":    "sha1-dsa",     # dsa-with-sha1
    "2.16.840.1.101.3.4.3.2": "sha256-dsa", # dsa-with-sha256
}

# Map KeyUsage attribute names -> lowercase-hyphenated canonical.
_KEY_USAGE_NAMES: list[tuple[str, str]] = [
    ("digital_signature", "digital-signature"),
    ("content_commitment", "non-repudiation"),
    ("key_encipherment", "key-encipherment"),
    ("data_encipherment", "data-encipherment"),
    ("key_agreement", "key-agreement"),
    ("key_cert_sign", "key-cert-sign"),
    ("crl_sign", "crl-sign"),
    # encipher_only / decipher_only are conditional on key_agreement
    # — the cryptography lib raises if accessed when key_agreement is false.
]

# Map EKU OIDs -> lowercase-hyphenated canonical name.
_EKU_OID_NAMES: dict[str, str] = {
    "1.3.6.1.5.5.7.3.1": "server-auth",
    "1.3.6.1.5.5.7.3.2": "client-auth",
    "1.3.6.1.5.5.7.3.3": "code-signing",
    "1.3.6.1.5.5.7.3.4": "email-protection",
    "1.3.6.1.5.5.7.3.8": "time-stamping",
    "1.3.6.1.5.5.7.3.9": "ocsp-signing",
}


def _iso_utc(dt) -> str:
    """Convert a naive-or-aware datetime to ISO-8601 UTC with 'Z' suffix."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    else:
        dt = dt.astimezone(timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _canonical_key_fields(pub) -> dict[str, str]:
    """Derive canonical alg/size/curve fields from a cryptography public key."""
    if isinstance(pub, rsa.RSAPublicKey):
        return {"KeyAlgorithm": "rsa", "KeySize": str(pub.key_size)}
    if isinstance(pub, dsa.DSAPublicKey):
        return {"KeyAlgorithm": "dsa", "KeySize": str(pub.key_size)}
    if isinstance(pub, ed25519.Ed25519PublicKey):
        return {"KeyAlgorithm": "ed25519"}
    if isinstance(pub, ed448.Ed448PublicKey):
        return {"KeyAlgorithm": "ed448"}
    if isinstance(pub, ec.EllipticCurvePublicKey):
        curve_name = pub.curve.name  # e.g. "secp256r1"
        if curve_name in _CURVE_CANONICAL:
            _, canon_curve = _CURVE_CANONICAL[curve_name]
            return {"KeyAlgorithm": "ec", "Curve": canon_curve}
        # Unknown curve — downgrade to raw name (sidecar review will catch)
        return {"KeyAlgorithm": "ec", "Curve": curve_name.lower()}
    # X25519 / X448 / unknown
    cls = type(pub).__name__.lower()
    if "x25519" in cls:
        return {"KeyAlgorithm": "x25519"}
    if "x448" in cls:
        return {"KeyAlgorithm": "x448"}
    return {"KeyAlgorithm": "unknown"}


def _canonical_sig_alg(cert: x509.Certificate) -> str:
    """Return canonical sig-alg value from Appendix A, or `<unknown-oid>`."""
    oid = cert.signature_algorithm_oid.dotted_string
    if oid in _SIG_ALG_CANONICAL:
        return _SIG_ALG_CANONICAL[oid]
    return f"<unknown-oid-{oid}>"


def _spki_sha256(pub) -> str:
    """Compute SHA-256 of the DER-encoded SubjectPublicKeyInfo for `pub`."""
    spki_der = pub.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return hashlib.sha256(spki_der).hexdigest()


def _rfc2253_name(name: x509.Name) -> str:
    """Return the RFC-2253 string form of an X.509 Name."""
    return name.rfc4514_string()  # cryptography uses RFC 4514, which is
    # a near-superset of RFC 2253 for all attributes we emit; OpenSSL's
    # -nameopt RFC2253 output matches this for the CN/O/OU/C set we use.


def _cn_or_dn(name: x509.Name) -> str:
    """Return the first CN attribute value, or fallback to full RFC2253 DN."""
    for attr in name:
        if attr.oid == NameOID.COMMON_NAME:
            return str(attr.value)
    return _rfc2253_name(name)


def _key_usage(cert: x509.Certificate) -> str | None:
    try:
        ku_ext = cert.extensions.get_extension_for_oid(ExtensionOID.KEY_USAGE)
    except x509.ExtensionNotFound:
        return None
    ku = ku_ext.value
    names: list[str] = []
    for attr, canon in _KEY_USAGE_NAMES:
        if getattr(ku, attr):
            names.append(canon)
    # encipher_only / decipher_only only queryable when key_agreement True
    if ku.key_agreement:
        try:
            if ku.encipher_only:
                names.append("encipher-only")
            if ku.decipher_only:
                names.append("decipher-only")
        except ValueError:
            pass
    return ",".join(names) if names else None


def _ext_key_usage(cert: x509.Certificate) -> str | None:
    try:
        eku_ext = cert.extensions.get_extension_for_oid(
            ExtensionOID.EXTENDED_KEY_USAGE
        )
    except x509.ExtensionNotFound:
        return None
    names = []
    for oid in eku_ext.value:
        if oid.dotted_string in _EKU_OID_NAMES:
            names.append(_EKU_OID_NAMES[oid.dotted_string])
        else:
            names.append(oid.dotted_string)
    return ",".join(names) if names else None


def _san(cert: x509.Certificate) -> str | None:
    try:
        san_ext = cert.extensions.get_extension_for_oid(
            ExtensionOID.SUBJECT_ALTERNATIVE_NAME
        )
    except x509.ExtensionNotFound:
        return None
    parts: list[str] = []
    for general_name in san_ext.value:
        if isinstance(general_name, x509.DNSName):
            parts.append(f"DNS:{general_name.value}")
        elif isinstance(general_name, x509.IPAddress):
            parts.append(f"IP:{general_name.value}")
        elif isinstance(general_name, x509.RFC822Name):
            parts.append(f"email:{general_name.value}")
        elif isinstance(general_name, x509.UniformResourceIdentifier):
            parts.append(f"URI:{general_name.value}")
        else:
            parts.append(f"OTHER:{type(general_name).__name__}")
    return ",".join(parts) if parts else None


def _is_ca(cert: x509.Certificate) -> bool:
    try:
        bc = cert.extensions.get_extension_for_oid(
            ExtensionOID.BASIC_CONSTRAINTS
        )
        return bool(bc.value.ca)
    except x509.ExtensionNotFound:
        return False


def _is_self_signed(cert: x509.Certificate) -> bool:
    """Subject == issuer AND signature verifies against own public key."""
    if cert.subject != cert.issuer:
        return False
    try:
        pub = cert.public_key()
        if isinstance(pub, rsa.RSAPublicKey):
            from cryptography.hazmat.primitives.asymmetric import padding
            pub.verify(
                cert.signature,
                cert.tbs_certificate_bytes,
                padding.PKCS1v15(),
                cert.signature_hash_algorithm,
            )
            return True
        if isinstance(pub, ec.EllipticCurvePublicKey):
            pub.verify(
                cert.signature,
                cert.tbs_certificate_bytes,
                ec.ECDSA(cert.signature_hash_algorithm),
            )
            return True
        if isinstance(pub, ed25519.Ed25519PublicKey):
            pub.verify(cert.signature, cert.tbs_certificate_bytes)
            return True
        if isinstance(pub, ed448.Ed448PublicKey):
            pub.verify(cert.signature, cert.tbs_certificate_bytes)
            return True
        if isinstance(pub, dsa.DSAPublicKey):
            pub.verify(
                cert.signature,
                cert.tbs_certificate_bytes,
                cert.signature_hash_algorithm,
            )
            return True
    except Exception:
        return False
    return False


def _companion_qualifier(kf: dict[str, str]) -> str:
    """Build the `&{size-or-curve-or-params}` segment for the pURL."""
    if "KeySize" in kf:
        return f"size={kf['KeySize']}"
    if "Curve" in kf:
        return f"curve={kf['Curve']}"
    if "Params" in kf:
        return f"params={kf['Params']}"
    # ed25519 / ed448 / x25519 / x448 have no companion; use empty.
    return ""


def x509_sidecar(
    der_bytes: bytes,
    *,
    description: str,
    source: str,
    retrieved_at: str,
    mime_types: list[str] | None = None,
    pem_bytes: bytes | None = None,
) -> dict[str, Any]:
    """Compute a complete sidecar dict for an X.509 certificate.

    `mime_types` defaults to the single-cert set from the plan's Phase 3
    claim logic (`application/x-pem-file` + `application/x-x509-ca-cert`).
    Pass a different list for DER-only fixtures.
    """
    cert = x509.load_der_x509_certificate(der_bytes)
    cert_sha = hashlib.sha256(der_bytes).hexdigest()
    pub = cert.public_key()
    spki_sha = _spki_sha256(pub)
    kf = _canonical_key_fields(pub)
    sig_alg = _canonical_sig_alg(cert)
    is_ca = _is_ca(cert)
    self_signed = _is_self_signed(cert)
    version_int = cert.version.value + 1  # v1=0 in ASN.1 → 1 in field
    companion = _companion_qualifier(kf)

    def _pURL(id_: str, extra_qualifiers: list[str]) -> str:
        quals = [f"alg={kf['KeyAlgorithm']}"]
        if companion:
            quals.append(companion)
        quals.extend(extra_qualifiers)
        quals.append(f"version={version_int}")
        return f"pkg:x509/{id_}?{'&'.join(quals)}"

    spki_purl = _pURL(f"spki-sha256@{spki_sha}", [])
    cert_purl = _pURL(
        f"cert-sha256@{cert_sha}",
        [f"sig-alg={sig_alg}", f"self-signed={'true' if self_signed else 'false'}"],
    )

    metadata: dict[str, str] = {
        "Name": _cn_or_dn(cert.subject),
        "Publisher": _cn_or_dn(cert.issuer),
        "Description": f"X.509 v{version_int} certificate",
        "Certificates:SubjectDN": _rfc2253_name(cert.subject),
        "Certificates:IssuerDN": _rfc2253_name(cert.issuer),
        "Certificates:Serial": format(cert.serial_number, "x"),
        "Certificates:NotBefore": _iso_utc(cert.not_valid_before),
        "Certificates:NotAfter": _iso_utc(cert.not_valid_after),
        "Certificates:KeyAlgorithm": kf["KeyAlgorithm"],
        "Certificates:SigAlgorithm": sig_alg,
        "Certificates:SpkiSha256": spki_sha,
        "Certificates:CertSha256": cert_sha,
        "Certificates:IsCA": "true" if is_ca else "false",
        "Certificates:SelfSigned": "true" if self_signed else "false",
        "Certificates:Version": str(version_int),
    }
    if "KeySize" in kf:
        metadata["Certificates:KeySize"] = kf["KeySize"]
    if "Curve" in kf:
        metadata["Certificates:Curve"] = kf["Curve"]

    san = _san(cert)
    if san is not None:
        metadata["Certificates:SAN"] = san
    ku = _key_usage(cert)
    if ku is not None:
        metadata["Certificates:KeyUsage"] = ku
    eku = _ext_key_usage(cert)
    if eku is not None:
        metadata["Certificates:ExtendedKeyUsage"] = eku

    if mime_types is None:
        mime_types = [
            "application/x-pem-file",
            "application/x-x509-ca-cert",
        ]

    sidecar = {
        "description": description,
        "source": source,
        "retrievedAt": retrieved_at,
        "itemCount": 1,
        "mimeTypes": {"mustContain": mime_types},
        "purls": {"mustContain": [spki_purl, cert_purl]},
        "metadata": {"mustContain": metadata},
        "forbiddenMetadataKeys": [],
        "forbiddenMetadataPatterns": list(FORBIDDEN_PATTERNS_DEFAULT),
    }
    return sidecar


def write_sidecar(path: str, sidecar: dict[str, Any]) -> None:
    """Write a sidecar dict to `path` with stable formatting."""
    with open(path, "w", encoding="utf-8") as f:
        json.dump(sidecar, f, indent=2, sort_keys=False)
        f.write("\n")


# --- minimal DER navigator for PQC certs ----------------------------------
#
# The Python `cryptography` library (v41) does not construct PublicKey
# objects for ML-DSA / SLH-DSA / Falcon / composite algorithms — it
# raises on `cert.public_key()`. To compute SPKI SHA-256 anyway we walk
# the DER structure by hand and slice out the SubjectPublicKeyInfo
# SEQUENCE whole.
#
# Per RFC 5280 the certificate has fixed shape:
#
#   Certificate ::= SEQUENCE {
#     TBSCertificate ::= SEQUENCE {
#       [0] EXPLICIT Version (optional)
#       serialNumber (INTEGER)
#       signature (SEQUENCE)
#       issuer (SEQUENCE)
#       validity (SEQUENCE)
#       subject (SEQUENCE)
#       subjectPublicKeyInfo (SEQUENCE)   ← what we want
#       ...
#     }
#     ...
#   }
#
# We only navigate to and slice out the SPKI; we never parse inside it.
# Defensive against malformed DER — out-of-bounds reads raise DerError.


class DerError(RuntimeError):
    """Raised when DER parsing fails (truncated, bad length, missing field)."""


def _read_len(data: bytes, off: int) -> tuple[int, int]:
    """Decode a DER length field starting at `off`. Returns (length, next_off)."""
    if off >= len(data):
        raise DerError("truncated length")
    first = data[off]
    if first < 0x80:
        return first, off + 1
    n = first & 0x7F
    if n == 0 or off + 1 + n > len(data):
        raise DerError("bad/truncated long length")
    length = 0
    for i in range(n):
        length = (length << 8) | data[off + 1 + i]
    return length, off + 1 + n


def _read_tlv(data: bytes, off: int) -> tuple[int, int, int, int]:
    """Decode one TLV. Returns (tag, content_off, content_len, next_off)."""
    if off >= len(data):
        raise DerError("truncated tag")
    tag = data[off]
    length, header_end = _read_len(data, off + 1)
    end = header_end + length
    if end > len(data):
        raise DerError(
            f"truncated content: need {end} bytes, have {len(data)}"
        )
    return tag, header_end, length, end


def extract_spki_der(cert_der: bytes) -> bytes:
    """Return the raw DER bytes of the SubjectPublicKeyInfo SEQUENCE.

    Walks TBSCertificate fields in order, skipping the optional [0]
    version, serial, signature, issuer, validity, subject, then
    returns the next field — which is, by RFC 5280, the SPKI.
    """
    tag, body_off, _, _ = _read_tlv(cert_der, 0)
    if tag != 0x30:
        raise DerError(f"expected outer SEQUENCE (tag 0x30), got 0x{tag:02x}")

    tag, tbs_off, tbs_len, _ = _read_tlv(cert_der, body_off)
    if tag != 0x30:
        raise DerError(f"expected TBSCertificate SEQUENCE, got 0x{tag:02x}")
    end = tbs_off + tbs_len

    cur = tbs_off
    # Optional [0] EXPLICIT version
    if cur < end and cert_der[cur] == 0xA0:
        _, _, _, cur = _read_tlv(cert_der, cur)
    # serial (INTEGER)
    if cur >= end or cert_der[cur] != 0x02:
        raise DerError("missing serialNumber")
    _, _, _, cur = _read_tlv(cert_der, cur)
    # signature (SEQUENCE)
    if cur >= end or cert_der[cur] != 0x30:
        raise DerError("missing signature alg")
    _, _, _, cur = _read_tlv(cert_der, cur)
    # issuer (SEQUENCE)
    if cur >= end or cert_der[cur] != 0x30:
        raise DerError("missing issuer")
    _, _, _, cur = _read_tlv(cert_der, cur)
    # validity (SEQUENCE)
    if cur >= end or cert_der[cur] != 0x30:
        raise DerError("missing validity")
    _, _, _, cur = _read_tlv(cert_der, cur)
    # subject (SEQUENCE)
    if cur >= end or cert_der[cur] != 0x30:
        raise DerError("missing subject")
    _, _, _, cur = _read_tlv(cert_der, cur)
    # SubjectPublicKeyInfo (SEQUENCE) — return the whole TLV
    if cur >= end or cert_der[cur] != 0x30:
        raise DerError("missing SubjectPublicKeyInfo")
    _, _, _, spki_end = _read_tlv(cert_der, cur)
    return cert_der[cur:spki_end]


def pqc_x509_sidecar(
    der_bytes: bytes,
    *,
    description: str,
    source: str,
    retrieved_at: str,
    alg: str,
    params: str | None = None,
    sig_alg: str | None = None,
    mime_types: list[str] | None = None,
) -> dict[str, Any]:
    """Compute a sidecar for a PQC X.509 cert.

    The Python `cryptography` library v41 cannot construct PublicKey
    objects for ML-DSA / SLH-DSA / Falcon / composite algorithms, so the
    standard [[x509_sidecar]] path fails on `public_key()`. This function
    computes everything `x509_sidecar` does **except** verify the
    signature (we record `self-signed` as the structural test
    `subject == issuer` only) and uses a hand-walked DER slice for SPKI
    rather than the library's `getEncoded()`.

    Args:
      alg:     canonical pURL alg value, e.g. ``ml-dsa``, ``slh-dsa``,
               ``falcon``, ``ml-dsa+rsa-pss``, ``ml-dsa+ecdsa``.
      params:  canonical pURL params value, e.g. ``65``, ``128f``,
               ``512`` — required for non-composite PQC algs.
      sig_alg: canonical pURL sig-alg value. If omitted defaults to
               ``alg`` itself (the cert is self-signed by its own PQC
               key).
    """
    if mime_types is None:
        mime_types = ["application/pkix-cert"]
    cert = x509.load_der_x509_certificate(der_bytes)
    cert_sha = hashlib.sha256(der_bytes).hexdigest()
    spki = extract_spki_der(der_bytes)
    spki_sha = hashlib.sha256(spki).hexdigest()
    self_signed = cert.subject == cert.issuer
    version_int = cert.version.value + 1
    sig_alg_value = sig_alg if sig_alg is not None else alg

    quals_spki = [f"alg={alg}"]
    if params:
        quals_spki.append(f"params={params}")
    quals_spki.append(f"version={version_int}")
    spki_purl = f"pkg:x509/spki-sha256@{spki_sha}?" + "&".join(quals_spki)

    quals_cert = [f"alg={alg}"]
    if params:
        quals_cert.append(f"params={params}")
    quals_cert.append(f"sig-alg={sig_alg_value}")
    quals_cert.append(f"self-signed={'true' if self_signed else 'false'}")
    quals_cert.append(f"version={version_int}")
    cert_purl = f"pkg:x509/cert-sha256@{cert_sha}?" + "&".join(quals_cert)

    metadata: dict[str, str] = {
        "Name": _cn_or_dn(cert.subject),
        "Publisher": _cn_or_dn(cert.issuer),
        "Description": f"X.509 v{version_int} certificate ({alg})",
        "Certificates:SubjectDN": _rfc2253_name(cert.subject),
        "Certificates:IssuerDN": _rfc2253_name(cert.issuer),
        "Certificates:Serial": format(cert.serial_number, "x"),
        "Certificates:NotBefore": _iso_utc(cert.not_valid_before),
        "Certificates:NotAfter": _iso_utc(cert.not_valid_after),
        "Certificates:KeyAlgorithm": alg,
        "Certificates:SigAlgorithm": sig_alg_value,
        "Certificates:SpkiSha256": spki_sha,
        "Certificates:CertSha256": cert_sha,
        "Certificates:SelfSigned": "true" if self_signed else "false",
        "Certificates:Version": str(version_int),
    }
    if params:
        metadata["Certificates:Params"] = params

    # IsCA: best-effort via the `cryptography` extension API (BasicConstraints
    # is plain ASN.1, not algorithm-specific, so the library parses it OK).
    try:
        bc = cert.extensions.get_extension_for_oid(
            __import__("cryptography").x509.oid.ExtensionOID.BASIC_CONSTRAINTS
        )
        metadata["Certificates:IsCA"] = "true" if bc.value.ca else "false"
    except Exception:
        pass

    return {
        "description": description,
        "source": source,
        "retrievedAt": retrieved_at,
        "itemCount": 1,
        "mimeTypes": {"mustContain": mime_types},
        "purls": {"mustContain": [spki_purl, cert_purl]},
        "metadata": {"mustContain": metadata},
        "forbiddenMetadataKeys": [],
        "forbiddenMetadataPatterns": list(FORBIDDEN_PATTERNS_DEFAULT),
    }


def split_pem_bundle(bundle_path: str) -> list[bytes]:
    """Return a list of individual PEM cert blobs from a bundle file."""
    with open(bundle_path, "rb") as f:
        data = f.read()
    blocks = []
    start = b"-----BEGIN CERTIFICATE-----"
    end = b"-----END CERTIFICATE-----"
    pos = 0
    while True:
        s = data.find(start, pos)
        if s < 0:
            break
        e = data.find(end, s)
        if e < 0:
            break
        blocks.append(data[s : e + len(end)] + b"\n")
        pos = e + len(end)
    return blocks


def pem_to_der(pem_bytes: bytes) -> bytes:
    """Strip a single PEM CERTIFICATE block to its DER bytes."""
    cert = x509.load_pem_x509_certificate(pem_bytes)
    return cert.public_bytes(serialization.Encoding.DER)
