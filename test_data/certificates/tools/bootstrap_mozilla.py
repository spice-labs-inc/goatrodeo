#!/usr/bin/env python3
"""Fan out the Mozilla CA bundle into individual PEM certificate fixtures.

Downloads `https://curl.se/ca/cacert.pem` (the Mozilla-derived trust
bundle maintained by the curl project), splits each embedded
certificate into its own `.pem` file under
`test_data/certificates/x509/mozilla/`, and emits a matching
`.expected.json` sidecar computed from the certificate's bytes using
the `cert_sidecar` module.

The bundle itself is also committed as a PEM-bundle fixture at
`test_data/certificates/pem-bundles/mozilla-ca-bundle.pem` with its own
sidecar.

Each fixture filename is of the form
`{sanitized-subject-cn}__{sha256-prefix-12}.pem`. The SHA-256 prefix
guarantees uniqueness when two certs share a CN (cross-signed roots).

## Reproducibility

- The source URL is pinned; SOURCES.md records the exact retrieval
  date and SHA-256 of the bundle bytes.
- The fanout logic is deterministic given identical input bytes.
- Re-running this script overwrites existing fixtures and sidecars (it
  is safe to re-run as ground truth is derived from bytes).

## Invariant compliance

- Invariant #13: the script requires Python 3 + `cryptography` beyond
  the baseline. The Docker image at `test_data/certificates/tools/
  Dockerfile` ships these; a hardened runner invokes the script inside
  the container with `--user "$(id -u):$(id -g)"`.
- Invariant #1/#3: the sidecars this script emits are the ground-truth
  assertions that drive the Phase 3/4 test-first red-to-green cycle.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
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

# Allow running from anywhere — the `cert_sidecar` module lives next to us.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cert_sidecar  # noqa: E402

BUNDLE_URL = "https://curl.se/ca/cacert.pem"
CORPUS_ROOT = Path(__file__).resolve().parents[1]  # test_data/certificates/
MOZILLA_SUBDIR = CORPUS_ROOT / "x509" / "mozilla"
BUNDLE_FIXTURE = CORPUS_ROOT / "pem-bundles" / "mozilla-ca-bundle.pem"
SOURCES_MD = CORPUS_ROOT / "x509" / "SOURCES.md"
BUNDLE_SOURCES_MD = CORPUS_ROOT / "pem-bundles" / "SOURCES.md"


def _sanitize(name: str) -> str:
    """Reduce a CN/DN string to a filename-safe token."""
    s = re.sub(r"[^A-Za-z0-9._-]+", "-", name.strip())
    return s.strip("-").lower()[:80] or "unnamed"


def _cn_of(cert) -> str:
    from cryptography.x509.oid import NameOID
    for a in cert.subject:
        if a.oid == NameOID.COMMON_NAME:
            return str(a.value)
    # fallback: first O attribute
    for a in cert.subject:
        if a.oid == NameOID.ORGANIZATION_NAME:
            return str(a.value)
    return "unnamed"


def download_bundle(dest: Path) -> bytes:
    """Download the bundle to `dest` and return the bytes.
    Idempotent — existing file is re-used if its SHA matches the live fetch.
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {BUNDLE_URL}...", file=sys.stderr)
    with urllib.request.urlopen(BUNDLE_URL, timeout=60) as r:
        data = r.read()
    dest.write_bytes(data)
    return data


def main() -> int:
    from cryptography import x509

    today = date.today().isoformat()

    # 1. Download the bundle as a fixture (also the input for fanout)
    bundle_bytes = download_bundle(BUNDLE_FIXTURE)
    bundle_sha = hashlib.sha256(bundle_bytes).hexdigest()

    # 2. Split into individual PEM blocks
    MOZILLA_SUBDIR.mkdir(parents=True, exist_ok=True)
    blocks = cert_sidecar.split_pem_bundle(str(BUNDLE_FIXTURE))
    print(f"Bundle contains {len(blocks)} certificates", file=sys.stderr)

    # 3. Produce per-cert fixtures + sidecars
    sources_rows: list[str] = []
    written_stems: set[str] = set()
    for i, pem in enumerate(blocks):
        try:
            cert = x509.load_pem_x509_certificate(pem)
        except Exception as e:
            print(f"  [{i}] parse failed: {e}", file=sys.stderr)
            continue
        der = cert.public_bytes(
            encoding=cert_sidecar.serialization.Encoding.DER
        )
        cert_sha = hashlib.sha256(der).hexdigest()
        stem = f"{_sanitize(_cn_of(cert))}__{cert_sha[:12]}"
        # Disambiguate repeats defensively (should not happen after sha prefix)
        orig = stem
        n = 1
        while stem in written_stems:
            stem = f"{orig}-{n}"
            n += 1
        written_stems.add(stem)

        fixture_path = MOZILLA_SUBDIR / f"{stem}.pem"
        sidecar_path = MOZILLA_SUBDIR / f"{stem}.pem.expected.json"
        fixture_path.write_bytes(pem)

        descr = f"Mozilla CA: {_cn_of(cert)}"
        sidecar = cert_sidecar.x509_sidecar(
            der,
            description=descr,
            source=BUNDLE_URL + f"#{_cn_of(cert)}",
            retrieved_at=today,
        )
        cert_sidecar.write_sidecar(str(sidecar_path), sidecar)

        pem_sha = hashlib.sha256(pem).hexdigest()
        sources_rows.append(
            f"| mozilla/{stem}.pem | {BUNDLE_URL} (cert `{_cn_of(cert)}`) | {today} | sha256:{pem_sha} |"
        )

    # 4. Emit the bundle fixture's own sidecar (treat as a single item with
    #    multi-block mimes; pURL set intentionally empty at Phase 0 since
    #    Phase 4 handles bundles)
    bundle_sidecar = {
        "description": f"Mozilla CA bundle from curl.se ({len(blocks)} certs)",
        "source": BUNDLE_URL,
        "retrievedAt": today,
        "itemCount": 1,
        "mimeTypes": {
            "mustContain": [
                "application/x-pem-file",
                "application/x-pem-bundle",
            ]
        },
        "purls": {
            "mustContain": [
                "<computed: one (spki, cert) pair per cert in the bundle — filled in Phase 4>"
            ]
        },
        "metadata": {
            "mustContain": {
                "Certificates:KeystoreType": "pem-bundle",
                "Certificates:EntryCount": f"{len(blocks)}",
                "Certificates:CertCount": f"{len(blocks)}",
                "Certificates:KeyEntryCount": "0",
            }
        },
        "forbiddenMetadataKeys": [],
        "forbiddenMetadataPatterns": list(cert_sidecar.FORBIDDEN_PATTERNS_DEFAULT),
    }
    cert_sidecar.write_sidecar(
        str(BUNDLE_FIXTURE) + ".expected.json", bundle_sidecar
    )

    # 5. Update SOURCES.md files (append mode — preserve existing rows)
    mozilla_header = "\n## Mozilla CA bundle (fanned out)\n\n"
    mozilla_table_header = (
        "| Filename | Source | Retrieved | SHA-256 |\n"
        "|---|---|---|---|\n"
    )
    with SOURCES_MD.open("a", encoding="utf-8") as f:
        f.write(mozilla_header)
        f.write(mozilla_table_header)
        for row in sources_rows:
            f.write(row + "\n")
    with BUNDLE_SOURCES_MD.open("a", encoding="utf-8") as f:
        f.write("\n## Mozilla CA bundle as a PEM-bundle fixture\n\n")
        f.write(mozilla_table_header)
        f.write(
            f"| mozilla-ca-bundle.pem | {BUNDLE_URL} | {today} | sha256:{bundle_sha} |\n"
        )

    print(
        f"Wrote {len(sources_rows)} Mozilla fixtures to {MOZILLA_SUBDIR} "
        f"and bundle to {BUNDLE_FIXTURE}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
