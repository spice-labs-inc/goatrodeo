# MIME Type Detection and Handling

> **Navigation:** [Documentation Index](README.md) | [MIME Filtering](block_list.md) | [How It Works](goat_rodeo_operation.md)

## Overview

MIME types are an attempt to assign a category for the content of a stream of data. It is a relatively simple mechanism, but it tends to over-simplify content.

Goat rodeo uses [Apache Tika](https://tika.apache.org/) to classify the content of files, which is does very well, to the limits that are available.

As such, it is expedient for goat rodeo to be able to make some MIME types more specific than what it provided by Tika. Tika has extensibility which can do that, but there is no direct API and instead uses configurations and external code which look like that are an attack surface.

It is simpler in the short term to let a default Tika installation do the heavy lifting and then do post processing of the detected MIME type.

## Post-Processing Refinements

At present there are two post-processing operations that refine MIME types:

| Original | Refined |
|----------|---------|
| `text/plain` | `application/json` (when content is valid JSON) |
| `application/x-msdownload; format=pe32` | `application/x-msdownload; format=pe32-dotnet` (for .NET assemblies) |

## CryptoDetector augmentation (Certificates strategy)

Where Tika falls short for cryptographic file types — most crypto
files just look like generic binary or PEM text — the
[CryptoDetector](../src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala)
augmenter (registered after Saffron in
`ArtifactWrapper.augmenters`) inspects the first 4 KB of each
artifact and ADDS specific MIME types based on content signatures.

**Pure-addition design:** unlike `SaffronDetector`, which strips
`text/*` MIMEs when it finds a binary signature, `CryptoDetector` is
purely additive — it never removes a Tika-detected MIME, only adds
its own. Rationale: PEM-encoded artifacts legitimately are BOTH
`text/plain` (Tika) AND `application/x-pem-file` (CryptoDetector);
downstream filtering chooses what to act on.

**Verified by:**
[`CertificatesStubTests::[INVARIANT] CryptoDetector.mimeTypeAugmenter never strips MIME types beginning with text/`](../src/test/scala/strategies/CertificatesStubTests.scala)
+
[`...is always a superset of input (additive)`](../src/test/scala/strategies/CertificatesStubTests.scala).

### Detection signature inventory

The detector recognizes the following content patterns. Most match
within the first 4 KB read budget; a few (DER X.509 / DER CRL / DER
PKCS#7) use a larger 1 MB DER-parser-probe budget.

| Signature | Added MIME |
|---|---|
| `-----BEGIN CERTIFICATE-----` | `application/x-pem-file` + `application/x-x509-ca-cert` |
| `-----BEGIN X509 CRL-----` | `application/x-pem-file` + `application/pkix-crl` |
| `-----BEGIN PKCS7-----` / `-----BEGIN CMS-----` | `application/pkcs7-mime` |
| `-----BEGIN PUBLIC KEY-----` | `application/x-pem-file` + `application/x-pem-public-key` |
| `-----BEGIN RSA/EC/DSA PRIVATE KEY-----` / `-----BEGIN PRIVATE KEY-----` | `application/x-pem-file` + `application/x-pem-private-key` |
| `-----BEGIN ENCRYPTED PRIVATE KEY-----` | `application/x-pem-file` + `application/x-pem-encrypted-private-key` |
| `-----BEGIN OPENSSH PRIVATE KEY-----` | `application/x-openssh-private-key` |
| `-----BEGIN PGP PUBLIC KEY BLOCK-----` / `PRIVATE KEY BLOCK` | `application/pgp-keys` |
| `-----BEGIN PGP SIGNATURE-----` | `application/pgp-signature` |
| `-----BEGIN PGP MESSAGE-----` | `application/pgp-message` |
| Multi-block PEM with ≥ 2 `BEGIN CERTIFICATE` blocks | `application/x-pem-file` + `application/x-pem-bundle` |
| SSH wire-format public-key line (`ssh-rsa AAAAB3…`, `ssh-ed25519 …`, `ecdsa-sha2-nistpXXX …`, `ssh-dss …`, `sk-ssh-ed25519@openssh.com …`, `sk-ecdsa-sha2-nistp256@openssh.com …`) | `application/x-openssh-public-key` |
| OpenSSH cert wire-format line (`ssh-{rsa,dss,ed25519,ed448}-cert-v01@openssh.com`, `ecdsa-sha2-nistp{256,384,521}-cert-v01@openssh.com`) | `application/x-openssh-certificate` |
| DER X.509 cert (probe via BC X.509 parser) | `application/pkix-cert` |
| DER X.509 CRL (probe via BC CRL parser) | `application/pkix-crl` |
| DER PKCS#7 SignedData (OID 1.2.840.113549.1.7.2 near start) | `application/pkcs7-mime` |
| `0xfe 0xed 0xfe 0xed` magic | `application/x-java-keystore` (JKS) |
| `0xce 0xce 0xce 0xce` magic | `application/x-java-jce-keystore` (JCEKS) |
| `0x30 0x82` DER-prefix + `.p12` / `.pfx` extension | `application/pkcs12` |
| Binary PGP packet — first byte ∈ {0xC6 (new-format tag-6), 0x98, 0x99, 0x9A, 0x9B (old-format tag-6 length variants)} | `application/pgp-keys` |
| `*.bks` filename extension | passed through to BKS keystore parser via filename hint |

The detection-signature catalog is the source of MIMEs the
[Certificates strategy](certificates_strategy.md) routes on.

**Verified by:**
[`CryptoDetectorSuite`](../src/test/scala/strategies/CryptoDetectorSuite.scala)
— 68 unit tests covering every signature above plus negative cases
(typos, false-positive prefixes, budget enforcement).

## Future Work

MIME type transmogrifiers could be made extensible.
