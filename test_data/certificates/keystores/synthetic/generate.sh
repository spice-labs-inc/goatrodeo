#!/usr/bin/env bash
# Regenerate `trust-only-null-password.p12`.
#
# Must run from the repo root. Produces a PKCS#12 trust-only bundle
# containing ISRG Root X1, readable with a null/empty password.
#
# `keytool` refuses empty passwords (>= 6 chars required), so we
# shell to a single-file Java program that uses the KeyStore API
# directly.
#
# Invariant #13: runs with JDK 21+ (JVM is in the baseline tool set,
# so no Docker wrapper needed).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
CERT="${REPO_ROOT}/test_data/certificates/x509/canonical/letsencrypt-isrgrootx1.pem"
OUT_DIR="${REPO_ROOT}/test_data/certificates/keystores/synthetic"
OUT="${OUT_DIR}/trust-only-null-password.p12"
JAVA_SRC="${REPO_ROOT}/test_data/certificates/tools/GenerateTrustOnlyNullPassword.java"

if [[ ! -f "$CERT" ]]; then
  echo "error: source cert missing: $CERT" >&2
  exit 1
fi

cd "$OUT_DIR"
java --source 21 "$JAVA_SRC" "$CERT" "$OUT" "letsencrypt-isrg-root-x1"

sha256sum "$OUT"
