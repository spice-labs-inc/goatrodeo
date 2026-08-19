# JKS v1 / v2 synthetic keystore corpus

20 matched pairs of Java KeyStore (JKS) files: each variant exists as JKS
**version 1** (`jks-v1/`) and JKS **version 2** (`jks-v2/`).

## Why this exists

JKS has two file-format versions. v1 (JDK ≤ 8 era) assumes every certificate
is X.509; v2 (JDK 9+, also written by current JDK 8 updates) stores a
certificate-type field before each certificate. The certificate strategy must
recognize and parse both — a v1 store must not silently degrade to
envelope-only.

## How the files were made (reproducible)

- `jks-v2/`: written by JDK 8u144 `keytool` in a container
  (`workspace/gen_jks_v1_corpus.sh`).
- `jks-v1/`: no reachable JDK writes v1 natively anymore (8u144, the oldest
  image on Docker Hub, already writes v2), so the v2 files are down-converted
  per the OpenJDK `JavaKeyStore` format specification: version field 2→1,
  per-certificate type fields stripped, keyed SHA-1 integrity digest
  recomputed as `SHA1(UTF-16BE(storepass) || "Mighty Aphrodite" || body)`
  (`workspace/JksV1Converter.java`, run inside the JDK 8 container by
  `workspace/convert_and_validate_jks_v1.sh`).
- **Validation:** every v1 file loads successfully with the real SUN keytool —
  JDK 8 (password-verified *and* password-less) and JDK 21 (password-verified)
  — so each file is a faithful v1 store, not a hand-crafted approximation.

## Inventory

All files use storepass `changeit` unless noted; keypass `changeit` unless
noted. Certificates are throwaway self-signed/CA-signed test certs
(`CN=goatrodeo-jks-v1-test`, `CN=goatrodeo-root-ca`, `CN=goatrodeo-inter-ca`).

| # | File (same name in `jks-v1/` and `jks-v2/`) | Contents |
|---|---------------------------------------------|----------|
| 01 | `jks-v1-01-rsa-key-single.jks` | 1 RSA-2048 key entry, self-signed |
| 02 | `jks-v1-02-rsa-key-chain2.jks` | RSA key + 2-cert chain (root-signed leaf) |
| 03 | `jks-v1-03-rsa-key-chain3.jks` | RSA key + 3-cert chain (leaf←inter←root) |
| 04 | `jks-v1-04-ec-p256-key.jks` | EC secp256r1 key entry |
| 05 | `jks-v1-05-dsa-key.jks` | DSA-1024 key entry |
| 06 | `jks-v1-06-trusted-single.jks` | 1 trusted-cert entry |
| 07 | `jks-v1-07-trusted-five.jks` | 5 trusted-cert entries |
| 08 | `jks-v1-08-mixed-1key-2trusted.jks` | 1 key + 2 trusted |
| 09 | `jks-v1-09-mixed-2key-3trusted.jks` | 2 keys (RSA, EC) + 3 trusted |
| 10 | `jks-v1-10-empty.jks` | zero entries |
| 11 | `jks-v1-11-two-aliases-same-cert.jks` | same cert under 2 aliases |
| 12 | `jks-v1-12-mixedcase-aliases.jks` | aliases `Alpha`, `beta`, `GAMMA` (JKS lowercases aliases) |
| 13 | `jks-v1-13-long-alias.jks` | 100-char alias |
| 14 | `jks-v1-14-rsa-4096-key.jks` | RSA-4096 key entry |
| 15 | `jks-v1-15-ec-p384-key.jks` | EC secp384r1 key entry |
| 16 | `jks-v1-16-custom-storepass.jks` | storepass `S3cret!Store` |
| 17 | `jks-v1-17-diff-keypass.jks` | keypass `K3y!Pass` (storepass `changeit`) |
| 18 | `jks-v1-18-ten-keys.jks` | 10 RSA key entries |
| 19 | `jks-v1-19-expired-cert.jks` | certificate expired (2015) |
| 20 | `jks-v1-20-future-cert.jks` | certificate not-yet-valid (starts 2030) |
