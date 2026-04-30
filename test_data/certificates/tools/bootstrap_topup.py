#!/usr/bin/env python3
"""Top-up — close the last few per-category gaps below plan minima.

After `bootstrap_compliance.py` the remaining gaps were:
  - X.509 leaves: 29 → need 30 (+1)
  - X.509 intermediates: 18 → need 20 (+2)
  - Java keystores (jks/jceks): 6 → need 8 (+2)
  - PKCS#12: 4 → need 6 (+2)
  - PEM bundles: 7 → need 8 (+1)
  - PGP keys: 12 → need 15 (+3)

This script closes those with focused additions.
"""

from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import random

# Phase 0 plan: 'the script sets a pinned seed' — pin Python entropy.
# External tools (ssh-keygen, gpg, keytool, openssl) do not honor this
# and rely on the committed-bytes-canonical policy in ADR-002.
PINNED_SEED = 0x60_47_47_47_47_47_47_47
random.seed(PINNED_SEED)
import urllib.request
from datetime import date
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cert_sidecar  # noqa: E402

CORPUS_ROOT = Path(__file__).resolve().parents[1]
TODAY = date.today().isoformat()


def _http_get(url: str, timeout: int = 30) -> bytes | None:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.read()
    except Exception as e:
        print(f"  skip {url}: {e}", file=sys.stderr)
        return None


def _sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def _append_section(sources_md: Path, header: str, rows: list[str]) -> None:
    if not rows:
        return
    text = "\n" + header + "\n\n"
    text += "| Filename | Source | Retrieved | SHA-256 |\n"
    text += "|---|---|---|---|\n"
    text += "\n".join(rows) + "\n"
    with sources_md.open("a", encoding="utf-8") as f:
        f.write(text)


def _hand_sidecar(
    dir_: Path, fixture_name: str, *,
    description: str, source: str,
    mime_types: list[str], purls: list[str],
    metadata: dict[str, str] | None = None,
) -> None:
    sc = {
        "description": description, "source": source,
        "retrievedAt": TODAY, "itemCount": 1,
        "mimeTypes": {"mustContain": mime_types},
        "purls": {"mustContain": purls},
        "metadata": {"mustContain": metadata or {}},
        "forbiddenMetadataKeys": [],
        "forbiddenMetadataPatterns": list(
            cert_sidecar.FORBIDDEN_PATTERNS_DEFAULT),
    }
    cert_sidecar.write_sidecar(
        str(dir_ / f"{fixture_name}.expected.json"), sc
    )


def fetch_more_chains() -> tuple[list[str], list[str]]:
    """Connect to additional TLS hosts to harvest more leaves+intermediates."""
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization
    from cryptography.x509.oid import ExtensionOID, NameOID
    leaf_dir = CORPUS_ROOT / "x509" / "leaves"
    inter_dir = CORPUS_ROOT / "x509" / "intermediates"
    leaf_dir.mkdir(parents=True, exist_ok=True)
    inter_dir.mkdir(parents=True, exist_ok=True)
    seen_leaf_sha = {p.read_bytes() for p in leaf_dir.glob("*.der")}
    seen_inter_sha = {p.read_bytes() for p in inter_dir.glob("*.der")}
    seen_sha: set[str] = set()
    for d in [leaf_dir, inter_dir]:
        for p in d.glob("*.der"):
            seen_sha.add(_sha256(p.read_bytes()))
    leaf_rows: list[str] = []
    inter_rows: list[str] = []
    additional_hosts = [
        "ietf.org", "iana.org", "rsf.org", "fsf.org",
        "akamai.com", "fastly.com", "f5.com", "duckduckgo.com",
        "archlinux.org", "fedoraproject.org", "centos.org",
        "ubuntu.com", "redhat.com", "suse.com", "alpinelinux.org",
    ]
    import re
    def _sanitize(name: str) -> str:
        s = re.sub(r"[^A-Za-z0-9._-]+", "-", name.strip())
        return s.strip("-").lower()[:60] or "unnamed"
    for host in additional_hosts:
        if len(leaf_rows) >= 1 and len(inter_rows) >= 2:
            break
        try:
            proc = subprocess.run(
                ["openssl", "s_client", "-connect", f"{host}:443",
                 "-servername", host, "-showcerts"],
                input="", check=False, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=15, text=True,
            )
            out = proc.stdout
        except Exception:
            continue
        if not out:
            continue
        ders: list[bytes] = []
        s_idx = 0
        while True:
            s = out.find("-----BEGIN CERTIFICATE-----", s_idx)
            if s < 0:
                break
            e = out.find("-----END CERTIFICATE-----", s)
            if e < 0:
                break
            pem = out[s:e + len("-----END CERTIFICATE-----")] + "\n"
            try:
                cert = x509.load_pem_x509_certificate(pem.encode())
                ders.append(cert.public_bytes(serialization.Encoding.DER))
            except Exception:
                pass
            s_idx = e + 1
        for idx, der in enumerate(ders):
            sha = _sha256(der)
            if sha in seen_sha:
                continue
            seen_sha.add(sha)
            try:
                cert = x509.load_der_x509_certificate(der)
            except Exception:
                continue
            try:
                bc = cert.extensions.get_extension_for_oid(
                    ExtensionOID.BASIC_CONSTRAINTS)
                is_ca = bool(bc.value.ca)
            except x509.ExtensionNotFound:
                is_ca = False
            cn = "unknown"
            for attr in cert.subject:
                if attr.oid == NameOID.COMMON_NAME:
                    cn = str(attr.value); break
            stem = f"{_sanitize(host)}__{_sanitize(cn)}__{sha[:10]}"
            if is_ca and idx > 0 and len(inter_rows) < 2:
                target = inter_dir / f"{stem}.der"
                target.write_bytes(der)
                sc = cert_sidecar.x509_sidecar(
                    der,
                    description=f"Intermediate CA from TLS chain of {host} ({cn})",
                    source=f"openssl s_client {host}:443 (chain idx {idx})",
                    retrieved_at=TODAY,
                    mime_types=["application/pkix-cert"],
                )
                cert_sidecar.write_sidecar(
                    str(inter_dir / f"{stem}.der.expected.json"), sc
                )
                inter_rows.append(
                    f"| intermediates/{stem}.der | "
                    f"openssl s_client {host}:443 chain[{idx}] | "
                    f"{TODAY} | sha256:{sha} |"
                )
            elif not is_ca and idx == 0 and len(leaf_rows) < 1:
                target = leaf_dir / f"{stem}.der"
                target.write_bytes(der)
                sc = cert_sidecar.x509_sidecar(
                    der,
                    description=f"TLS leaf from {host} ({cn})",
                    source=f"openssl s_client {host}:443 (chain idx 0)",
                    retrieved_at=TODAY,
                    mime_types=["application/pkix-cert"],
                )
                cert_sidecar.write_sidecar(
                    str(leaf_dir / f"{stem}.der.expected.json"), sc
                )
                leaf_rows.append(
                    f"| leaves/{stem}.der | "
                    f"openssl s_client {host}:443 chain[0] | "
                    f"{TODAY} | sha256:{sha} |"
                )
    return leaf_rows, inter_rows


def more_keystores() -> list[str]:
    rows: list[str] = []
    dir_ = CORPUS_ROOT / "keystores" / "synthetic"
    dir_.mkdir(parents=True, exist_ok=True)
    variants = [
        ("encrypted-jks-multi-entry.jks", "JKS",
         [("e1", "RSA", "2048", "CN=Multi 1"),
          ("e2", "EC", "256", "CN=Multi 2")]),
        ("encrypted-jceks-multi-entry.jceks", "JCEKS",
         [("e1", "RSA", "2048", "CN=Multi JCEKS 1"),
          ("e2", "RSA", "3072", "CN=Multi JCEKS 2")]),
        ("encrypted-p12-multi-entry.p12", "PKCS12",
         [("e1", "EC", "256", "CN=Multi P12 1"),
          ("e2", "EC", "384", "CN=Multi P12 2")]),
        ("encrypted-p12-dsa.p12", "PKCS12",
         [("dsa", "DSA", "2048", "CN=DSA P12")]),
    ]
    ext_to_mime = {
        "JKS": "application/x-java-keystore",
        "JCEKS": "application/x-java-jce-keystore",
        "PKCS12": "application/pkcs12",
    }
    kstype = {"JKS": "jks", "JCEKS": "jceks", "PKCS12": "pkcs12"}
    for fname, fmt, entries in variants:
        target = dir_ / fname
        if target.exists():
            continue
        ok = True
        for alias, alg, size, dn in entries:
            try:
                subprocess.run([
                    "keytool", "-genkeypair",
                    "-alias", alias, "-dname", f"{dn},O=Goat Rodeo,C=US",
                    "-keyalg", alg, "-keysize", size,
                    "-validity", "3650",
                    "-keystore", str(target),
                    "-storetype", fmt,
                    "-storepass", "GoatRodeoTestFixture",
                    "-keypass", "GoatRodeoTestFixture",
                ], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception as e:
                print(f"  skip {fname}/{alias}: {e}", file=sys.stderr)
                ok = False
                break
        if not ok or not target.exists():
            continue
        _hand_sidecar(
            dir_, fname,
            description=(
                f"Multi-entry encrypted {fmt} keystore "
                f"({len(entries)} entries); strategy null-pwd → "
                f"envelope-only path"
            ),
            source="generated by tools/bootstrap_topup.py via keytool",
            mime_types=[ext_to_mime[fmt]],
            purls=[],
            metadata={
                "Certificates:KeystoreType": kstype[fmt],
                "Certificates:KeystoreEncrypted": "true",
            },
        )
        rows.append(
            f"| synthetic/{fname} | tools/bootstrap_topup.py keytool "
            f"({len(entries)} entries) | "
            f"{TODAY} | sha256:{_sha256(target.read_bytes())} |"
        )
    return rows


def more_pem_bundle() -> list[str]:
    rows: list[str] = []
    dir_ = CORPUS_ROOT / "pem-bundles" / "synthetic"
    dir_.mkdir(parents=True, exist_ok=True)
    target = dir_ / "all-canonical-plus-historical.pem"
    if target.exists():
        return rows
    canonical = sorted(
        (CORPUS_ROOT / "x509" / "canonical").glob("*.pem")
    )
    historical = sorted(
        p for p in (CORPUS_ROOT / "x509" / "historical").glob("*.pem")
        if p.read_bytes().startswith(b"-----BEGIN")
    )
    parts = canonical + historical
    if len(parts) < 2:
        return rows
    bundle = b""
    for p in parts:
        data = p.read_bytes()
        if not data.endswith(b"\n"):
            data += b"\n"
        bundle += data
    target.write_bytes(bundle)
    sidecar = {
        "description": (
            f"Combined real-roots + historical bundle ({len(parts)} certs)"
        ),
        "source": "synthetic concat of canonical/*.pem + historical/*.pem",
        "retrievedAt": TODAY,
        "itemCount": 1,
        "mimeTypes": {"mustContain": [
            "application/x-pem-file", "application/x-pem-bundle"]},
        "purls": {"mustContain": ["<computed in Phase 4>"]},
        "metadata": {"mustContain": {
            "Certificates:KeystoreType": "pem-bundle",
            "Certificates:EntryCount": str(len(parts)),
        }},
        "forbiddenMetadataKeys": [],
        "forbiddenMetadataPatterns": list(
            cert_sidecar.FORBIDDEN_PATTERNS_DEFAULT),
    }
    cert_sidecar.write_sidecar(
        str(dir_ / "all-canonical-plus-historical.pem.expected.json"),
        sidecar)
    rows.append(
        f"| synthetic/all-canonical-plus-historical.pem | "
        f"synthetic concat ({len(parts)} certs) | {TODAY} | "
        f"sha256:{_sha256(bundle)} |"
    )
    return rows


def more_pgp_synthetic() -> list[str]:
    """Generate 3 more synthetic PGP v4 keys with different algorithms."""
    import tempfile
    rows: list[str] = []
    dir_ = CORPUS_ROOT / "pgp" / "synthetic"
    dir_.mkdir(parents=True, exist_ok=True)
    variants = [
        ("rsa3072", "Key-Type: RSA\nKey-Length: 3072\nKey-Usage: cert,sign"),
        ("rsa2048", "Key-Type: RSA\nKey-Length: 2048\nKey-Usage: cert,sign"),
        ("dsa-elgamal",
         "Key-Type: DSA\nKey-Length: 2048\nKey-Usage: sign\n"
         "Subkey-Type: ELG-E\nSubkey-Length: 2048\nSubkey-Usage: encrypt"),
    ]
    for kind, params in variants:
        out = dir_ / f"v4-{kind}-pub.asc"
        if out.exists():
            continue
        with tempfile.TemporaryDirectory() as td:
            subprocess.run(["gpg", "--homedir", td, "--batch", "--list-keys"],
                           check=False, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)
            spec = (
                "%no-protection\n"
                f"{params}\n"
                f"Name-Real: GoatRodeo Test {kind}\n"
                f"Name-Email: goatrodeo-{kind}@test.invalid\n"
                "Expire-Date: 0\n"
                "%commit\n"
            )
            try:
                subprocess.run(
                    ["gpg", "--homedir", td, "--batch", "--quiet",
                     "--gen-key"],
                    input=spec.encode(), check=True,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
                r = subprocess.run(
                    ["gpg", "--homedir", td, "--armor", "--export",
                     f"goatrodeo-{kind}@test.invalid"],
                    check=True, stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                )
                pub = r.stdout
            except Exception as e:
                print(f"  skip {kind}: {e}", file=sys.stderr)
                continue
        out.write_bytes(pub)
        _hand_sidecar(
            dir_, out.name,
            description=f"PGP v4 {kind} public key (armored)",
            source="generated by tools/bootstrap_topup.py via gpg",
            mime_types=["application/pgp-keys"],
            purls=["<computed in Phase 6>"],
            metadata={"Certificates:PgpKeyCount": "<computed in Phase 6>"},
        )
        rows.append(
            f"| synthetic/{out.name} | tools/bootstrap_topup.py | "
            f"{TODAY} | sha256:{_sha256(pub)} |"
        )
    return rows


def main() -> int:
    print("=== more leaves + intermediates from TLS chains ===",
          file=sys.stderr)
    leaf_rows, inter_rows = fetch_more_chains()
    print(f"  added leaves={len(leaf_rows)} intermediates={len(inter_rows)}",
          file=sys.stderr)
    print("=== more keystores (multi-entry) ===", file=sys.stderr)
    ks_rows = more_keystores()
    print(f"  added keystores={len(ks_rows)}", file=sys.stderr)
    print("=== one more PEM bundle ===", file=sys.stderr)
    bundle_rows = more_pem_bundle()
    print(f"  added bundles={len(bundle_rows)}", file=sys.stderr)
    print("=== more synthetic PGP variants ===", file=sys.stderr)
    pgp_rows = more_pgp_synthetic()
    print(f"  added pgp={len(pgp_rows)}", file=sys.stderr)

    _append_section(CORPUS_ROOT / "x509" / "SOURCES.md",
                    "## Topup TLS-chain leaves", leaf_rows)
    _append_section(CORPUS_ROOT / "x509" / "SOURCES.md",
                    "## Topup TLS-chain intermediates", inter_rows)
    _append_section(CORPUS_ROOT / "keystores" / "SOURCES.md",
                    "## Topup keystore variants", ks_rows)
    _append_section(CORPUS_ROOT / "pem-bundles" / "SOURCES.md",
                    "## Topup PEM bundle", bundle_rows)
    _append_section(CORPUS_ROOT / "pgp" / "SOURCES.md",
                    "## Topup PGP synthetic", pgp_rows)
    total = (len(leaf_rows) + len(inter_rows) + len(ks_rows)
             + len(bundle_rows) + len(pgp_rows))
    print(f"=== topup: {total} fixtures ===", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
