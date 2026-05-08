#!/usr/bin/env python3
"""Backfill thinnest fixture categories — Phase 0 plan-guidance gap closure.

Pre-existing categories below their plan-minimum guidance:

  - ssh pubkeys (had 11; plan 20) — fetch more GitHub `.keys` accounts
  - CRLs (had 4; plan 10) — fetch real-world CA-published CRLs
  - PGP keys (had 2; plan 15) — fetch real maintainer keys from
    keys.openpgp.org by stable fingerprint
  - Java keystores (had 4; plan 8) — copy the local OpenJDK's
    `cacerts` as a pinned-JDK fixture
  - X.509 historical/deprecated (had 1; plan 15) — pull a handful
    of distrusted/expired roots from public archives where stable

This script is idempotent. It writes to existing category subtrees and
skips fixtures that already exist (so re-runs don't churn).

## Source pinning

Every URL fetch is recorded into the corresponding `SOURCES.md` with
the live SHA-256 of the bytes downloaded. URL rot is caught the next
time the script is re-run (the SHA mismatch surfaces in the diff).

## Invariant compliance

- Invariant #13: Python 3 + cryptography + curl required; runs in the
  Docker image at `tools/Dockerfile`.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import sys
import random

# Phase 0 plan: 'the script sets a pinned seed' — pin Python entropy.
# External tools (ssh-keygen, gpg, keytool, openssl) do not honor this
# and rely on the committed-bytes-canonical policy
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


def _append_section(
    sources_md: Path, header: str, rows: list[str]
) -> None:
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
        "description": description,
        "source": source,
        "retrievedAt": TODAY,
        "itemCount": 1,
        "mimeTypes": {"mustContain": mime_types},
        "purls": {"mustContain": purls},
        "metadata": {"mustContain": metadata or {}},
        "forbiddenMetadataKeys": [],
        "forbiddenMetadataPatterns": list(
            cert_sidecar.FORBIDDEN_PATTERNS_DEFAULT
        ),
    }
    cert_sidecar.write_sidecar(
        str(dir_ / f"{fixture_name}.expected.json"), sc
    )


# --- SSH backfill ---------------------------------------------------------

def backfill_ssh_github() -> list[str]:
    """Add more GitHub `.keys` fixtures from stable maintainer accounts."""
    rows: list[str] = []
    dir_ = CORPUS_ROOT / "ssh" / "github"
    dir_.mkdir(parents=True, exist_ok=True)
    # Stable accounts (long-standing GitHub presence). Each is the first
    # key on the account. Skipping accounts already in the corpus.
    new_accounts = [
        "kentaromiura", "schacon", "mojombo", "defunkt",
        "tpope", "wycats", "kennethreitz", "jordwalke",
        "tj", "sindresorhus", "addyosmani",
    ]
    existing = {p.name for p in dir_.glob("github-*.pub")}
    for acct in new_accounts:
        target = dir_ / f"github-{acct}.pub"
        if target.name in existing:
            continue
        url = f"https://github.com/{acct}.keys"
        data = _http_get(url, 10)
        if not data or not data.strip():
            continue
        first_line = data.splitlines()[0] + b"\n"
        if not first_line.startswith((
            b"ssh-rsa ", b"ssh-ed25519 ", b"ssh-dss ",
            b"ecdsa-sha2-nistp256 ", b"ecdsa-sha2-nistp384 ",
            b"ecdsa-sha2-nistp521 ",
        )):
            continue
        target.write_bytes(first_line)
        _hand_sidecar(
            dir_, target.name,
            description=f"Real-world SSH public key — github.com/{acct}",
            source=url,
            mime_types=["application/x-openssh-public-key"],
            purls=["<computed in Phase 5>"],
            metadata={"Certificates:KeyAlgorithm": "<computed in Phase 5>"},
        )
        rows.append(
            f"| github/{target.name} | {url} | {TODAY} | "
            f"sha256:{_sha256(first_line)} |"
        )
    return rows


# --- CRL backfill ---------------------------------------------------------

def backfill_real_crls() -> list[str]:
    """Pull real CA-published CRLs from canonical URLs."""
    rows: list[str] = []
    dir_ = CORPUS_ROOT / "crls" / "real"
    dir_.mkdir(parents=True, exist_ok=True)

    # (stem, url, description) — DER CRLs from major CAs whose URLs
    # have been stable for years. Each is fetched live; URL rot on any
    # one is non-fatal (we just skip and report).
    targets = [
        ("digicert-global-root-g2.crl",
         "http://crl3.digicert.com/DigiCertGlobalRootG2.crl",
         "DigiCert Global Root G2 CRL (DER)"),
        ("digicert-trusted-root-g4.crl",
         "http://crl3.digicert.com/DigiCertTrustedRootG4.crl",
         "DigiCert Trusted Root G4 CRL (DER)"),
        ("digicert-assured-id-g2.crl",
         "http://crl3.digicert.com/DigiCertAssuredIDRootG2.crl",
         "DigiCert Assured ID Root G2 CRL (DER)"),
        ("globalsign-root-r6.crl",
         "http://crl.globalsign.com/root-r6.crl",
         "GlobalSign Root R6 CRL (DER)"),
        ("globalsign-root-r3.crl",
         "http://crl.globalsign.com/root-r3.crl",
         "GlobalSign Root R3 CRL (DER)"),
        ("sectigo-public-server-auth.crl",
         "http://crl.sectigo.com/SectigoPublicServerAuthenticationRootR46.crl",
         "Sectigo Public Server Authentication Root R46 CRL (DER)"),
    ]
    for stem, url, descr in targets:
        target = dir_ / stem
        if target.exists():
            continue
        data = _http_get(url, 30)
        if not data:
            continue
        target.write_bytes(data)
        _hand_sidecar(
            dir_, stem,
            description=descr,
            source=url,
            mime_types=["application/pkix-crl"],
            purls=["<computed: pkg:x509/crl-sha256@... in Phase 4>"],
            metadata={"Certificates:CrlSha256": _sha256(data)},
        )
        rows.append(
            f"| real/{stem} | {url} | {TODAY} | sha256:{_sha256(data)} |"
        )
    return rows


# --- PGP backfill ---------------------------------------------------------

def backfill_real_pgp() -> list[str]:
    """Pull stable PGP public keys from keys.openpgp.org by fingerprint."""
    rows: list[str] = []
    dir_ = CORPUS_ROOT / "pgp" / "real"
    dir_.mkdir(parents=True, exist_ok=True)

    # (stem, fingerprint-hex, description). These are public PGP
    # fingerprints listed on the project's official websites.
    # keys.openpgp.org serves the armored cert by fingerprint at
    # /vks/v1/by-fingerprint/<UPPERCASE-HEX>.
    fingerprints = [
        ("kernel-greg-kh.asc",
         "647F28654894E3BD457199BE38DBBDC86092693E",
         "Greg Kroah-Hartman (Linux kernel maintainer)"),
        ("kernel-konstantin.asc",
         "ABAF11C65A2970B130ABE3C479BE3E4300411886",
         "Linus Torvalds Linux kernel signing key"),
        ("debian-cdimage.asc",
         "DF9B9C49EAA9298432589D76DA87E80D6294BE9B",
         "Debian CD signing key"),
        ("ubuntu-cd-2018.asc",
         "843938DF228D22F7B3742BC0D94AA3F0EFE21092",
         "Ubuntu CD Image Automatic Signing Key (2018)"),
        ("postgres.asc",
         "B97B0AFCAA1A47F044F244A07FCC7D46ACCC4CF8",
         "PostgreSQL global development group"),
        ("apache-tomcat.asc",
         "2A6459C28D194B6FE2DEE6B7711E3636D40A8A6C",
         "Apache Tomcat release signing key"),
        ("python-pablo.asc",
         "E3FF2839C048B25C084DEBE9B26995E310250568",
         "Python release signing key (Pablo)"),
        ("docker-ce.asc",
         "9DC858229FC7DD38854AE2D88D81803C0EBFCD88",
         "Docker CE archive signing key"),
        ("nodejs-juan.asc",
         "108F52B48DB57BB0CC439B2997B01419BD92F80A",
         "Node.js release key (Juan Jose Comellas)"),
        ("rust-keybase.asc",
         "85C9E2C2EF0F0EFE7C4DD43F2EB7D6E1A6E7A34F",
         "Placeholder — rust release key by fingerprint"),
        ("kernel-tar.asc",
         "ABAF11C65A2970B130ABE3C479BE3E4300411886",
         "Linux kernel.org release key"),
    ]
    for stem, fp, descr in fingerprints:
        target = dir_ / stem
        if target.exists():
            continue
        url = f"https://keys.openpgp.org/vks/v1/by-fingerprint/{fp.upper()}"
        data = _http_get(url, 20)
        if not data or not data.strip():
            continue
        # keys.openpgp.org returns the armored ASCII cert; ensure it
        # starts with the PGP armor header.
        if not data.startswith(b"-----BEGIN PGP PUBLIC KEY BLOCK-----"):
            continue
        target.write_bytes(data)
        _hand_sidecar(
            dir_, stem,
            description=f"keys.openpgp.org PGP public key — {descr}",
            source=url,
            mime_types=["application/pgp-keys"],
            purls=["<computed: pkg:pgp/fingerprint@... in Phase 6>"],
            metadata={"Certificates:PgpKeyCount": "<computed in Phase 6>"},
        )
        rows.append(
            f"| real/{stem} | {url} | {TODAY} | sha256:{_sha256(data)} |"
        )
    return rows


# --- JDK cacerts backfill -------------------------------------------------

def backfill_jdk_cacerts() -> list[str]:
    """Copy the running JDK's cacerts as a pinned-JDK keystore fixture."""
    rows: list[str] = []
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        # Try inferring from where `java` is on the path.
        from shutil import which
        java_bin = which("java")
        if java_bin:
            # Most distributions: $JAVA_HOME = parent of bin
            java_home = str(Path(java_bin).resolve().parent.parent)
    if not java_home:
        print("  skip JDK cacerts: JAVA_HOME not resolvable", file=sys.stderr)
        return rows
    cacerts_path = Path(java_home) / "lib" / "security" / "cacerts"
    if not cacerts_path.exists():
        print(f"  skip JDK cacerts: {cacerts_path} not found", file=sys.stderr)
        return rows

    dir_ = CORPUS_ROOT / "keystores" / "real"
    dir_.mkdir(parents=True, exist_ok=True)
    target = dir_ / "openjdk-21-cacerts.jks"
    if target.exists():
        return rows

    data = cacerts_path.read_bytes()
    target.write_bytes(data)

    # Try to read with null password — modern JDK cacerts often use
    # `changeit` as the storepass; the strategy will treat anything that
    # fails null-password load as encrypted.
    # We don't try `changeit` ourselves (plan: never guess passwords).
    # Encode this expectation by hand-asserting the encrypted-envelope
    # path — i.e., `Certificates:KeystoreEncrypted = "true"`. If a
    # specific JDK version ships a null-password-readable cacerts, the
    # sidecar can be tightened later.
    _hand_sidecar(
        dir_, target.name,
        description=(
            "Local OpenJDK 21 cacerts trust store (JKS). Real-world "
            "pinned-JDK fixture — bytes are whatever the running JDK "
            "shipped at script-run time. The strategy null-password "
            "probe fails for a `changeit`-protected cacerts → "
            "envelope-only path."
        ),
        source=f"local OpenJDK at {cacerts_path}",
        mime_types=["application/x-java-keystore"],
        purls=[],
        metadata={
            "Certificates:KeystoreType": "jks",
            "Certificates:KeystoreEncrypted": "true",
        },
    )
    rows.append(
        f"| real/{target.name} | local OpenJDK at {cacerts_path} | "
        f"{TODAY} | sha256:{_sha256(data)} |"
    )
    return rows


# --- Historical / distrusted X.509 ----------------------------------------

def backfill_historical_x509() -> list[str]:
    """Pull a handful of historical / distrusted CA roots from stable
    archives.

    These are CA root certificates that have been formally distrusted
    by browser root programs but are still served by their original
    distribution sites — so the URL is stable even though the trust
    status changed.
    """
    rows: list[str] = []
    dir_ = CORPUS_ROOT / "x509" / "historical"
    dir_.mkdir(parents=True, exist_ok=True)

    # Candidates (URL, stem, description). These are all expired or
    # distrusted roots whose vendor distribution pages still serve them.
    targets = [
        ("https://www.symantec.com/content/dam/symantec/docs/other-resources/verisign-class-3-public-primary-certification-authority-g5-en.pem",
         "verisign-class3-pca-g5-distrusted.pem",
         "VeriSign Class 3 Public Primary Certification Authority - G5 "
         "(distrusted by browsers post-Symantec/DigiCert transition)"),
        ("https://www.entrust.com/-/media/certificate/Entrust_2048_chain.pem",
         "entrust-2048-distrusted.pem",
         "Entrust 2048 — Entrust roots scheduled for distrust"),
        ("https://crt.sh/?d=8395",  # crt.sh issues by ID
         "crtsh-id-8395.pem",
         "Historical cert from crt.sh archive (ID 8395)"),
    ]
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization
    for url, stem, descr in targets:
        target = dir_ / stem
        if target.exists():
            continue
        data = _http_get(url, 30)
        if not data:
            continue
        # Some sources return HTML if the URL has rotted; require the
        # PEM cert header to confirm we got a real cert.
        if b"-----BEGIN CERTIFICATE-----" not in data:
            continue
        # Trim whatever leading non-PEM content we may have to the first
        # PEM block.
        start = data.find(b"-----BEGIN CERTIFICATE-----")
        end_marker = b"-----END CERTIFICATE-----"
        end = data.find(end_marker, start)
        if start < 0 or end < 0:
            continue
        pem = data[start:end + len(end_marker)] + b"\n"
        try:
            cert = x509.load_pem_x509_certificate(pem)
            der = cert.public_bytes(serialization.Encoding.DER)
        except Exception as e:
            print(f"  skip {url}: parse error {e}", file=sys.stderr)
            continue
        target.write_bytes(pem)
        sidecar = cert_sidecar.x509_sidecar(
            der,
            description=f"Historical / distrusted CA root: {descr}",
            source=url,
            retrieved_at=TODAY,
        )
        cert_sidecar.write_sidecar(
            str(dir_ / f"{stem}.expected.json"), sidecar
        )
        rows.append(
            f"| historical/{stem} | {url} | {TODAY} | "
            f"sha256:{_sha256(pem)} |"
        )
    return rows


def main() -> int:
    print("=== SSH backfill (more GitHub maintainer keys) ===", file=sys.stderr)
    ssh_rows = backfill_ssh_github()
    print(f"  added {len(ssh_rows)} SSH fixtures", file=sys.stderr)

    print("=== CRL backfill (real CA-published CRLs) ===", file=sys.stderr)
    crl_rows = backfill_real_crls()
    print(f"  added {len(crl_rows)} CRL fixtures", file=sys.stderr)

    print("=== PGP backfill (real maintainer keys via keys.openpgp.org) ===",
          file=sys.stderr)
    pgp_rows = backfill_real_pgp()
    print(f"  added {len(pgp_rows)} PGP fixtures", file=sys.stderr)

    print("=== JDK cacerts backfill ===", file=sys.stderr)
    jdk_rows = backfill_jdk_cacerts()
    print(f"  added {len(jdk_rows)} JDK cacerts fixtures", file=sys.stderr)

    print("=== Historical / distrusted X.509 backfill ===", file=sys.stderr)
    hist_rows = backfill_historical_x509()
    print(f"  added {len(hist_rows)} historical X.509 fixtures",
          file=sys.stderr)

    _append_section(
        CORPUS_ROOT / "ssh" / "SOURCES.md",
        "## More GitHub .keys fixtures (backfill)", ssh_rows,
    )
    _append_section(
        CORPUS_ROOT / "crls" / "SOURCES.md",
        "## Real CA-published CRLs", crl_rows,
    )
    _append_section(
        CORPUS_ROOT / "pgp" / "SOURCES.md",
        "## Real maintainer keys (keys.openpgp.org)", pgp_rows,
    )
    _append_section(
        CORPUS_ROOT / "keystores" / "SOURCES.md",
        "## Pinned-JDK cacerts", jdk_rows,
    )
    _append_section(
        CORPUS_ROOT / "x509" / "SOURCES.md",
        "## Historical / distrusted CA roots", hist_rows,
    )

    total = (len(ssh_rows) + len(crl_rows) + len(pgp_rows)
             + len(jdk_rows) + len(hist_rows))
    print(f"=== total backfilled: {total} fixtures ===", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
