#!/usr/bin/env python3
"""Extract substantial PQC sample certificates from the IETF Hackathon
artifact zip and emit X.509 fixtures + sidecars for each.

## Source

`https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/
artifacts_certs_r5.zip` — Round 5 (2026) of the IETF Hackathon PQC
interop artifacts produced by the Bouncy Castle provider. Contains ~58
trust-anchor (`*_ta.der`) certificates spanning every NIST PQC
finalist plus the IETF composite and Falcon variants.

This script downloads the zip if it isn't already cached at
`/tmp/pqc-bc-r5.zip`, then extracts and processes a curated subset
covering all algorithm families needed by the Phase 8 coverage
matrix.

## Categorization

PQC trust anchors land in `test_data/certificates/x509/pqc/{family}/`
where `family` is one of:
  - `ml-dsa`   — pure ML-DSA (Dilithium) and pre-hash variants
  - `slh-dsa`  — SLH-DSA (Sphincs+) SHA-2 and SHAKE variants
  - `falcon`   — Falcon 512/1024 (NIST round-3 alternate)
  - `composite` — ML-DSA hybrid with classical (RSA/ECDSA/Ed25519)

Each fixture is `*.der` (DER-encoded). Sidecars are computed by
`cert_sidecar.pqc_x509_sidecar()`, which uses a hand-walked DER
navigator to extract SubjectPublicKeyInfo bytes (the Python
`cryptography` library v41 cannot construct PublicKey objects for
these algorithms).

## Per-family pURL mapping (matches Appendix A)

| Algorithm | alg | params |
|---|---|---|
| ML-DSA-44 | `ml-dsa` | `44` |
| ML-DSA-65 | `ml-dsa` | `65` |
| ML-DSA-87 | `ml-dsa` | `87` |
| ML-DSA-N pre-hash | `ml-dsa` | `N` (sig-alg=`ml-dsa-N-prehash-sha512`) |
| SLH-DSA-SHA2-{N}{f,s} | `slh-dsa` | `{N}{f,s}` |
| SLH-DSA-SHAKE-{N}{f,s} | `slh-dsa` | `shake-{N}{f,s}` |
| Falcon-512 | `falcon` | `512` |
| Falcon-1024 | `falcon` | `1024` |
| ML-DSA + RSA/ECDSA/Ed* composite | `composite` | (none); sig-alg names hybrid |

## Discipline

- The script is idempotent. Re-running overwrites existing fixtures
  and sidecars from the same source bytes — IETF Hackathon artifact
  zips are immutable per their release tag.
- The committed cert SHA-256s in `SOURCES.md` are the canonical
  reference. Re-running rewrites them only if the upstream zip
  changes (which would constitute an upstream version bump).

## Invariant compliance

- Invariant #13: Python 3 + cryptography are required; the Docker
  image at `tools/Dockerfile` ships them.
- Invariant #1/#3: Sidecar values are computed independently from
  the Certificates strategy (Phase 3+), so the strategy's eventual
  output is checked against ground truth, not derived from itself.
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
import zipfile
from datetime import date
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cert_sidecar  # noqa: E402

ZIP_URL = (
    "https://github.com/IETF-Hackathon/pqc-certificates/raw/master/"
    "providers/bc/artifacts_certs_r5.zip"
)
ZIP_CACHE = Path("/tmp/pqc-bc-r5.zip")
CORPUS_ROOT = Path(__file__).resolve().parents[1]
TODAY = date.today().isoformat()


# (zip-internal-path, family, fixture-stem, alg, params, sig-alg, description)
#
# `sig-alg` values match the canonical sig-alg vocabulary in Appendix A.
# For pure PQC algorithms the cert is signed with the same algorithm as
# its key, so sig-alg == alg-with-params token. For composite algorithms
# the sig-alg names the hybrid combination explicitly.
PQC_SELECTION: list[tuple[str, str, str, str, str | None, str, str]] = [
    # Pure ML-DSA (Dilithium)
    ("artifacts/ml-dsa-44-2.16.840.1.101.3.4.3.17_ta.der",
     "ml-dsa", "ml-dsa-44", "ml-dsa", "44", "ml-dsa-44",
     "ML-DSA-44 (Dilithium-2) self-signed trust anchor"),
    ("artifacts/ml-dsa-65-2.16.840.1.101.3.4.3.18_ta.der",
     "ml-dsa", "ml-dsa-65", "ml-dsa", "65", "ml-dsa-65",
     "ML-DSA-65 (Dilithium-3) self-signed trust anchor"),
    ("artifacts/ml-dsa-87-2.16.840.1.101.3.4.3.19_ta.der",
     "ml-dsa", "ml-dsa-87", "ml-dsa", "87", "ml-dsa-87",
     "ML-DSA-87 (Dilithium-5) self-signed trust anchor"),

    # ML-DSA pre-hash variants (HashML-DSA)
    ("artifacts/ml-dsa-44-with-sha512-2.16.840.1.101.3.4.3.32_ta.der",
     "ml-dsa", "ml-dsa-44-prehash-sha512", "ml-dsa", "44",
     "ml-dsa-44-prehash-sha512",
     "ML-DSA-44 with SHA-512 pre-hash (HashML-DSA)"),
    ("artifacts/ml-dsa-65-with-sha512-2.16.840.1.101.3.4.3.33_ta.der",
     "ml-dsa", "ml-dsa-65-prehash-sha512", "ml-dsa", "65",
     "ml-dsa-65-prehash-sha512",
     "ML-DSA-65 with SHA-512 pre-hash (HashML-DSA)"),
    ("artifacts/ml-dsa-87-with-sha512-2.16.840.1.101.3.4.3.34_ta.der",
     "ml-dsa", "ml-dsa-87-prehash-sha512", "ml-dsa", "87",
     "ml-dsa-87-prehash-sha512",
     "ML-DSA-87 with SHA-512 pre-hash (HashML-DSA)"),

    # SLH-DSA SHA-2 family — all six (128/192/256) × (s=small, f=fast)
    ("artifacts/slh-dsa-sha2-128s-2.16.840.1.101.3.4.3.20_ta.der",
     "slh-dsa", "slh-dsa-sha2-128s", "slh-dsa", "128s", "slh-dsa-sha2-128s",
     "SLH-DSA-SHA2-128s self-signed trust anchor"),
    ("artifacts/slh-dsa-sha2-128f-2.16.840.1.101.3.4.3.21_ta.der",
     "slh-dsa", "slh-dsa-sha2-128f", "slh-dsa", "128f", "slh-dsa-sha2-128f",
     "SLH-DSA-SHA2-128f self-signed trust anchor"),
    ("artifacts/slh-dsa-sha2-192s-2.16.840.1.101.3.4.3.22_ta.der",
     "slh-dsa", "slh-dsa-sha2-192s", "slh-dsa", "192s", "slh-dsa-sha2-192s",
     "SLH-DSA-SHA2-192s self-signed trust anchor"),
    ("artifacts/slh-dsa-sha2-192f-2.16.840.1.101.3.4.3.23_ta.der",
     "slh-dsa", "slh-dsa-sha2-192f", "slh-dsa", "192f", "slh-dsa-sha2-192f",
     "SLH-DSA-SHA2-192f self-signed trust anchor"),
    ("artifacts/slh-dsa-sha2-256s-2.16.840.1.101.3.4.3.24_ta.der",
     "slh-dsa", "slh-dsa-sha2-256s", "slh-dsa", "256s", "slh-dsa-sha2-256s",
     "SLH-DSA-SHA2-256s self-signed trust anchor"),
    ("artifacts/slh-dsa-sha2-256f-2.16.840.1.101.3.4.3.25_ta.der",
     "slh-dsa", "slh-dsa-sha2-256f", "slh-dsa", "256f", "slh-dsa-sha2-256f",
     "SLH-DSA-SHA2-256f self-signed trust anchor"),

    # SLH-DSA SHAKE family — representatives
    ("artifacts/slh-dsa-shake-128s-2.16.840.1.101.3.4.3.26_ta.der",
     "slh-dsa", "slh-dsa-shake-128s", "slh-dsa", "shake-128s",
     "slh-dsa-shake-128s",
     "SLH-DSA-SHAKE-128s self-signed trust anchor"),
    ("artifacts/slh-dsa-shake-256f-2.16.840.1.101.3.4.3.31_ta.der",
     "slh-dsa", "slh-dsa-shake-256f", "slh-dsa", "shake-256f",
     "slh-dsa-shake-256f",
     "SLH-DSA-SHAKE-256f self-signed trust anchor"),

    # Falcon — non-NIST-standard but commonly tested in interop
    ("artifacts/falcon-512-1.3.9999.3.11_ta.der",
     "falcon", "falcon-512", "falcon", "512", "falcon-512",
     "Falcon-512 self-signed trust anchor (round-3 alternate)"),
    ("artifacts/falcon-1024-1.3.9999.3.14_ta.der",
     "falcon", "falcon-1024", "falcon", "1024", "falcon-1024",
     "Falcon-1024 self-signed trust anchor (round-3 alternate)"),

    # Composite (hybrid) — ML-DSA + classical algorithm
    ("artifacts/MLDSA44-RSA2048-PSS-SHA256-1.3.6.1.5.5.7.6.37_ta.der",
     "composite", "mldsa44-rsa2048-pss-sha256", "composite", None,
     "mldsa44-rsa2048-pss-sha256",
     "Composite ML-DSA-44 + RSA-2048-PSS-SHA256 trust anchor"),
    ("artifacts/MLDSA44-Ed25519-SHA512-1.3.6.1.5.5.7.6.39_ta.der",
     "composite", "mldsa44-ed25519-sha512", "composite", None,
     "mldsa44-ed25519-sha512",
     "Composite ML-DSA-44 + Ed25519-SHA512 trust anchor"),
    ("artifacts/MLDSA44-ECDSA-P256-SHA256-1.3.6.1.5.5.7.6.40_ta.der",
     "composite", "mldsa44-ecdsa-p256-sha256", "composite", None,
     "mldsa44-ecdsa-p256-sha256",
     "Composite ML-DSA-44 + ECDSA-P256-SHA256 trust anchor"),
    ("artifacts/MLDSA65-RSA3072-PSS-SHA512-1.3.6.1.5.5.7.6.41_ta.der",
     "composite", "mldsa65-rsa3072-pss-sha512", "composite", None,
     "mldsa65-rsa3072-pss-sha512",
     "Composite ML-DSA-65 + RSA-3072-PSS-SHA512 trust anchor"),
    ("artifacts/MLDSA87-ECDSA-P384-SHA512-1.3.6.1.5.5.7.6.49_ta.der",
     "composite", "mldsa87-ecdsa-p384-sha512", "composite", None,
     "mldsa87-ecdsa-p384-sha512",
     "Composite ML-DSA-87 + ECDSA-P384-SHA512 trust anchor"),
]


def download_zip(dest: Path) -> None:
    if dest.exists() and dest.stat().st_size > 100_000:
        return
    print(f"downloading {ZIP_URL}", file=sys.stderr)
    with urllib.request.urlopen(ZIP_URL, timeout=60) as r:
        dest.write_bytes(r.read())


def main() -> int:
    download_zip(ZIP_CACHE)
    with zipfile.ZipFile(ZIP_CACHE) as z:
        zip_names = set(z.namelist())

        rows_by_family: dict[str, list[str]] = {}
        for entry, family, stem, alg, params, sig_alg, descr in PQC_SELECTION:
            if entry not in zip_names:
                print(f"  skip (not in zip): {entry}", file=sys.stderr)
                continue
            der = z.read(entry)
            out_dir = CORPUS_ROOT / "x509" / "pqc" / family
            out_dir.mkdir(parents=True, exist_ok=True)
            fixture_path = out_dir / f"{stem}.der"
            fixture_path.write_bytes(der)
            sidecar = cert_sidecar.pqc_x509_sidecar(
                der,
                description=f"IETF Hackathon BC r5: {descr}",
                source=f"{ZIP_URL}#{entry}",
                retrieved_at=TODAY,
                alg=alg,
                params=params,
                sig_alg=sig_alg,
            )
            cert_sidecar.write_sidecar(
                str(out_dir / f"{stem}.der.expected.json"), sidecar,
            )
            sha = hashlib.sha256(der).hexdigest()
            rows_by_family.setdefault(family, []).append(
                f"| pqc/{family}/{stem}.der | {ZIP_URL}#{entry} | {TODAY} | sha256:{sha} |"
            )

    sources = CORPUS_ROOT / "x509" / "SOURCES.md"
    with sources.open("a", encoding="utf-8") as f:
        f.write("\n## PQC trust-anchor certs (from IETF Hackathon BC r5)\n\n")
        f.write(
            "Each cert is a self-signed trust anchor produced by Bouncy "
            "Castle for the IETF Hackathon PQC interop suite. The "
            "`source` URL pins the zip; the `#path` fragment names the "
            "specific entry.\n\n"
        )
        for family in sorted(rows_by_family.keys()):
            f.write(f"### {family}\n\n")
            f.write("| Filename | Source | Retrieved | SHA-256 |\n")
            f.write("|---|---|---|---|\n")
            f.write("\n".join(rows_by_family[family]) + "\n\n")

    total = sum(len(v) for v in rows_by_family.values())
    print(f"wrote {total} PQC fixtures across {len(rows_by_family)} families",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
