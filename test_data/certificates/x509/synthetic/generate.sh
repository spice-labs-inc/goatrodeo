#!/usr/bin/env bash
# Per-category generate.sh — Phase 0 plan acceptance criterion:
# "Every generated fixture has a paired deterministic generate.sh."
#
# This wrapper delegates to test_data/certificates/tools/bootstrap_synthetic.py
# with the category name. The Python bootstrap is the actual source of truth
# (see ADR-002 for the determinism policy — synthetic fixture bytes are
# one-time-canonical, not byte-deterministic across runs).
#
# Usage (from repo root or from this directory):
#   ./generate.sh
#
# Re-running this script:
#   - regenerates this category's fixtures with fresh entropy
#   - regenerates the matching sidecars from the new bytes
#   - appends new SOURCES.md rows (manual cleanup of duplicate rows
#     may be needed; existing rows are not deleted)
#
# Invariant #13: Python 3 + the `cryptography` package + (for some
# categories) ssh-keygen / gpg / openssl / keytool are required.
# `tools/Dockerfile` ships an image with all of them.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
CATEGORY="x509"

cd "$REPO_ROOT"
exec python3 test_data/certificates/tools/bootstrap_synthetic.py "$CATEGORY"
