#!/usr/bin/env bash
# compute-expected.sh — emit a draft sidecar JSON for a certificate fixture.
#
# Usage (from repo root, inside the Docker container built by `Dockerfile`):
#   test_data/certificates/tools/compute-expected.sh <fixture-path> > <sidecar-path>
#
# Example:
#   test_data/certificates/tools/compute-expected.sh \
#     test_data/certificates/x509/rsa2048-isrg-root-x1.pem \
#     > test_data/certificates/x509/rsa2048-isrg-root-x1.pem.expected.json
#
# What it does
# ------------
# Detects the fixture type (X.509 PEM/DER, PEM bundle, JKS/PKCS#12/JCEKS,
# SSH pubkey, OpenSSH cert, PGP key, CRL, private key, encrypted private key),
# then shells out to the appropriate external tool (`openssl`, `ssh-keygen`,
# `gpg`, `sha256sum`) to compute SPKI hash, cert hash, algorithm, size/curve,
# sig-alg, subject/issuer DN, validity, etc. Emits a sidecar stub that still
# requires human review.
#
# Discipline
# ----------
# Output is a *draft*. Every field marked `<compute>` or `<review>` in the
# emitted JSON must be filled in or verified by the author before the sidecar
# is committed. The script never guesses a pURL — it only computes the raw
# fingerprints. The pURL structure itself is locked in by the strategy plan
# (Appendix A) and is author-authored.
#
# Dependencies
# ------------
# openssl 3.0.x, ssh-keygen (OpenSSH), gpg 2.2.x, jq, sha256sum, coreutils.
# The project invariant (CLAUDE.md #13) requires these tools run inside
# Docker — see ../tools/Dockerfile.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <fixture-path>" >&2
  exit 2
fi

fixture="$1"

if [[ ! -f "$fixture" ]]; then
  echo "error: fixture not found: $fixture" >&2
  exit 1
fi

filename="$(basename "$fixture")"
sha256="$(sha256sum "$fixture" | awk '{print $1}')"
size="$(stat -c%s "$fixture" 2>/dev/null || stat -f%z "$fixture")"

# Detect crude type by reading the first few KB.
first4k="$(head -c 4096 "$fixture")"

detect_type() {
  local s="$1"
  if [[ "$s" == *"-----BEGIN CERTIFICATE-----"* ]]; then
    local count
    count=$(grep -c -- "-----BEGIN CERTIFICATE-----" <<<"$s" || true)
    if [[ "$count" -gt 1 ]]; then
      echo "pem-bundle"
    else
      echo "pem-cert"
    fi
  elif [[ "$s" == *"-----BEGIN X509 CRL-----"* ]]; then
    echo "pem-crl"
  elif [[ "$s" == *"-----BEGIN PGP PUBLIC KEY BLOCK-----"* ]]; then
    echo "pgp-armored"
  elif [[ "$s" == *"-----BEGIN PGP PRIVATE KEY BLOCK-----"* ]]; then
    echo "pgp-secret"
  elif [[ "$s" == *"-----BEGIN OPENSSH PRIVATE KEY-----"* ]]; then
    echo "openssh-private"
  elif [[ "$s" == *"-----BEGIN ENCRYPTED PRIVATE KEY-----"* ]]; then
    echo "pkcs8-encrypted"
  elif [[ "$s" == *"-----BEGIN PRIVATE KEY-----"* ]]; then
    echo "pkcs8-private"
  elif [[ "$s" == *"-----BEGIN RSA PRIVATE KEY-----"* ]] \
    || [[ "$s" == *"-----BEGIN EC PRIVATE KEY-----"* ]] \
    || [[ "$s" == *"-----BEGIN DSA PRIVATE KEY-----"* ]]; then
    echo "pem-legacy-private"
  elif [[ "$s" =~ ^(ssh-rsa|ssh-dss|ssh-ed25519|ssh-ed448|ecdsa-sha2-nistp[0-9]+)\  ]]; then
    echo "ssh-pubkey"
  elif [[ "$s" =~ ^(ssh-(rsa|ed25519|ed448)-cert-v01@openssh\.com|ecdsa-sha2-nistp[0-9]+-cert-v01@openssh\.com)\  ]]; then
    echo "openssh-cert"
  elif [[ "${s:0:4}" == $'\xfe\xed\xfe\xed' ]]; then
    echo "jks"
  elif [[ "${s:0:4}" == $'\xce\xce\xce\xce' ]]; then
    echo "jceks"
  else
    # Fall back to extension hints and DER detection
    case "${filename##*.}" in
      p12|pfx) echo "pkcs12" ;;
      bks)     echo "bks" ;;
      der|cer|crt)
        # Try X.509 DER first, then CRL DER
        if openssl x509 -inform DER -in "$fixture" -noout 2>/dev/null; then
          echo "der-cert"
        elif openssl crl -inform DER -in "$fixture" -noout 2>/dev/null; then
          echo "der-crl"
        else
          echo "unknown-der"
        fi
        ;;
      *) echo "unknown" ;;
    esac
  fi
}

kind="$(detect_type "$first4k")"

now_date="$(date -u +%Y-%m-%d)"

emit_header() {
  local descr="$1"
  local src="$2"
  local itemCount="$3"
  cat <<EOF
{
  "description": "${descr}",
  "source": "${src}",
  "retrievedAt": "${now_date}",
  "itemCount": ${itemCount},
EOF
}

emit_footer() {
  cat <<'EOF'
  "forbiddenMetadataKeys": [],
  "forbiddenMetadataPatterns": [
    "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "-----BEGIN PGP PRIVATE KEY BLOCK-----",
    "openssh-key-v1"
  ]
}
EOF
}

# --- X.509 single cert (PEM or DER) --------------------------------------
x509_fields() {
  local informFlag="$1"
  local subjectDN issuerDN notBefore notAfter sigAlg keyAlg keySize curve
  local spkiSha cert_der_sha

  # Extract the DER form so fingerprints are reproducible
  local der_tmp
  der_tmp="$(mktemp)"
  openssl x509 ${informFlag} -in "$fixture" -outform DER -out "$der_tmp"
  cert_der_sha="$(sha256sum "$der_tmp" | awk '{print $1}')"
  rm -f "$der_tmp"

  # SPKI SHA-256
  spkiSha="$(openssl x509 ${informFlag} -in "$fixture" -pubkey -noout \
              | openssl pkey -pubin -outform DER \
              | sha256sum | awk '{print $1}')"

  # OpenSSL emits `subject=CN=...` / `issuer=CN=...` with no space after
  # the `=`. Strip the literal `subject=` / `issuer=` prefix.
  subjectDN="$(openssl x509 ${informFlag} -in "$fixture" -noout \
               -subject -nameopt RFC2253 | sed 's/^subject=//')"
  issuerDN="$(openssl x509 ${informFlag} -in "$fixture" -noout \
              -issuer -nameopt RFC2253 | sed 's/^issuer=//')"
  notBefore="$(openssl x509 ${informFlag} -in "$fixture" -noout \
               -startdate | sed 's/^notBefore=//')"
  notAfter="$(openssl x509 ${informFlag} -in "$fixture" -noout \
              -enddate | sed 's/^notAfter=//')"
  sigAlg="$(openssl x509 ${informFlag} -in "$fixture" -noout -text \
            | awk -F': *' '/Signature Algorithm:/{print $2; exit}')"

  emit_header "<review> X.509 certificate ($filename)" \
              "<review> pinned URL here" 1
  cat <<EOF
  "mimeTypes": {
    "mustContain": ["application/x-pem-file", "application/x-x509-ca-cert"]
  },
  "purls": {
    "mustContain": [
      "pkg:generic/x509/spki-sha256@${spkiSha}?alg=<review>&<review>&version=<review>",
      "pkg:generic/x509/cert-sha256@${cert_der_sha}?alg=<review>&<review>&sig-alg=<review>&self-signed=<review>&version=<review>"
    ]
  },
  "metadata": {
    "mustContain": {
      "Name": "<review: subject CN or full DN>",
      "Publisher": "<review: issuer CN or full DN>",
      "Certificates:SubjectDN": "${subjectDN}",
      "Certificates:IssuerDN": "${issuerDN}",
      "Certificates:NotBefore": "<compute: ISO-8601 UTC from ${notBefore}>",
      "Certificates:NotAfter": "<compute: ISO-8601 UTC from ${notAfter}>",
      "Certificates:KeyAlgorithm": "<review: canonical name>",
      "Certificates:SigAlgorithm": "<review: canonical name, raw=${sigAlg}>",
      "Certificates:SpkiSha256": "${spkiSha}",
      "Certificates:CertSha256": "${cert_der_sha}",
      "Certificates:Version": "<review: 1|2|3>"
    }
  },
EOF
  emit_footer
}

# --- dispatch ------------------------------------------------------------
case "$kind" in
  pem-cert)
    x509_fields ""
    ;;
  der-cert)
    x509_fields "-inform DER"
    ;;
  ssh-pubkey)
    fp="$(ssh-keygen -lf "$fixture" | awk '{print $2}')"
    emit_header "<review> SSH public key ($filename)" \
                "<review> pinned URL here" 1
    cat <<EOF
  "mimeTypes": { "mustContain": ["application/x-openssh-public-key"] },
  "purls": {
    "mustContain": [
      "pkg:generic/ssh/sha256@${fp#SHA256:}?alg=<review>&<review>"
    ]
  },
  "metadata": {
    "mustContain": {
      "Certificates:SshFingerprintSha256": "${fp}",
      "Certificates:KeyAlgorithm": "<review>"
    }
  },
EOF
    emit_footer
    ;;
  pem-bundle | pem-crl | der-crl | pgp-armored | pgp-secret | \
  jks | jceks | pkcs12 | bks | openssh-cert | \
  pkcs8-private | pkcs8-encrypted | pem-legacy-private | \
  openssh-private | unknown-der | unknown)
    emit_header "<review> ${kind} ($filename)" \
                "<review> source URL or 'generated by ./generate.sh'" \
                1
    cat <<EOF
  "mimeTypes": { "mustContain": ["<review: MIME from Phase-2 signature table>"] },
  "purls": { "mustContain": ["<review: add pURL(s) per the relevant phase doc>"] },
  "metadata": { "mustContain": { "<review>": "<review>" } },
EOF
    emit_footer
    ;;
  *)
    echo "error: unhandled kind '$kind' for $fixture" >&2
    exit 3
    ;;
esac

# Also emit metadata about this script's computation for the PR
>&2 echo "# detection: $kind"
>&2 echo "# file sha256: $sha256"
>&2 echo "# file size: $size"
