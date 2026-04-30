#!/usr/bin/env python3
"""Download individually-pinned real-world CA roots as standalone fixtures.

These certs are also present in the Mozilla bundle (which the fanout
script ingests), but we commit them separately with their *own* pinned
source URLs so that SOURCES.md has distinct provenance rows pointing at
each CA's canonical distribution page. That makes it possible to detect
the specific case where a single vendor publishes a newer/different
bytes of "the same" root (cross-signings, re-issuances with new
serials) without diffing against the Mozilla bundle.

Idempotent; re-run safely.
"""

from __future__ import annotations

import hashlib
import os
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

from cryptography import x509
from cryptography.hazmat.primitives import serialization

CORPUS_ROOT = Path(__file__).resolve().parents[1]
TODAY = date.today().isoformat()

# (stem, url, description)
CANONICAL = [
    ("letsencrypt-isrgrootx1",
     "https://letsencrypt.org/certs/isrgrootx1.pem",
     "Let's Encrypt ISRG Root X1 (RSA-4096, self-signed)"),
    ("letsencrypt-isrg-root-x2",
     "https://letsencrypt.org/certs/isrg-root-x2.pem",
     "Let's Encrypt ISRG Root X2 (ECDSA P-384, self-signed)"),
    ("letsencrypt-r3",
     "https://letsencrypt.org/certs/lets-encrypt-r3.pem",
     "Let's Encrypt R3 intermediate (RSA-2048, signed by ISRG Root X1)"),
    ("letsencrypt-e1",
     "https://letsencrypt.org/certs/lets-encrypt-e1.pem",
     "Let's Encrypt E1 intermediate (ECDSA P-384, signed by ISRG Root X2)"),
    ("letsencrypt-e2",
     "https://letsencrypt.org/certs/lets-encrypt-e2.pem",
     "Let's Encrypt E2 intermediate (ECDSA P-384, signed by ISRG Root X2)"),
    ("digicert-global-root-g2",
     "https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem",
     "DigiCert Global Root G2 (RSA-2048)"),
]


def main() -> int:
    dir_ = CORPUS_ROOT / "x509" / "canonical"
    dir_.mkdir(parents=True, exist_ok=True)
    rows = []
    for stem, url, descr in CANONICAL:
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                pem = r.read()
        except Exception as e:
            print(f"  skip {url}: {e}", file=sys.stderr)
            continue
        try:
            cert = x509.load_pem_x509_certificate(pem)
        except Exception as e:
            print(f"  skip {url}: not a valid PEM cert ({e})", file=sys.stderr)
            continue
        # Write fixture
        fixture = dir_ / f"{stem}.pem"
        fixture.write_bytes(pem)
        # Compute sidecar from the cert's DER bytes (ground truth)
        der = cert.public_bytes(serialization.Encoding.DER)
        sc = cert_sidecar.x509_sidecar(
            der, description=descr, source=url, retrieved_at=TODAY,
        )
        cert_sidecar.write_sidecar(str(dir_ / f"{stem}.pem.expected.json"), sc)
        rows.append(
            f"| canonical/{stem}.pem | {url} | {TODAY} | sha256:{hashlib.sha256(pem).hexdigest()} |"
        )
        print(f"  OK {stem}", file=sys.stderr)
    # Append SOURCES.md
    sources = CORPUS_ROOT / "x509" / "SOURCES.md"
    if rows:
        with sources.open("a", encoding="utf-8") as f:
            f.write("\n## Canonical real-world roots (individually pinned)\n\n")
            f.write("| Filename | Source | Retrieved | SHA-256 |\n")
            f.write("|---|---|---|---|\n")
            f.write("\n".join(rows) + "\n")
    print(f"Wrote {len(rows)} canonical-root fixtures", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
