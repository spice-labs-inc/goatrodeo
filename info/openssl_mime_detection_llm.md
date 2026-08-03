# OpenSSL Configuration MIME Detection

> **Navigation:** [Documentation Index](README.md) | [MIME Types](mime_types.md)

## Purpose

Give the OpenSSL capture strategy a distinct MIME type so it can route on `application/x-openssl-config` instead of scanning every `text/plain` file.

## Why Tika is insufficient

Apache Tika 3.2.3 returns `text/plain` for all OpenSSL `.cnf` files. `text/plain` is too broad (README, shell scripts, INI files), so a custom augmenter is required.

**Verified by:** `OpenSSLMimeDetectionSuite` (`Tika classifies...` test).

## Augmenter

- **File:** `src/main/scala/io/spicelabs/goatrodeo/util/OpenSSLConfigDetector.scala`
- **Added MIME:** `application/x-openssl-config`
- **Registration:** `ArtifactWrapper` line 260, after `CryptoDetector`
- **Design:** pure-addition, bounded read, no exceptions, immutable
- **Verified by:** `OpenSSLConfigDetectorSuite` (registration + additive tests)

## Detection algorithm

1. Read ≤ 4 KB.
2. If > 10% non-whitespace control chars → binary → return `Set.empty`.
3. Lowercase the prefix.
4. Claim if:
   - any strong signal is present, OR
   - at least one section header AND any medium signal is present.

## Signals

- **Strong (claim alone):** `openssl_conf`, `ssl_conf`, `.include`, `config_diagnostics`, `cipherstring`, `ciphersuites`, `minprotocol`, `maxprotocol`, `oid_section`, `default_ca`, `distinguished_name`, `req_extensions`, `x509_extensions`, X.509 extension names, `ssleay::`.
- **Medium (need section header too):** `options`, `curves`, `signaturealgorithms`, `default_bits`, `default_keyfile`, `default_md`, `encrypt_key`, `prompt`, `randfile`, `oid_file`, `new_oids`.

## Test fixtures

- **Directory:** `test_data/openssl_configs/`
- **Count:** 153 `.cnf` files
- **Source:** OpenSSL source releases 0.9.8zh through 3.2.1
- **Collector:** `workspace/collect_openssl_configs.py`

## Tests

- `OpenSSLMimeDetectionSuite`: corpus detection rate ≥ 85%.
- `OpenSSLConfigDetectorSuite`: unit behavior, registration, additive property, read budget, encoding, binary early exit, false-positive rejection.

## Trade-off

Conservative detection prefers false negatives over false positives. Missed configs fall through to `GenericFile`, which is safe.

## Related

- ADR: `docs/adr/0001-openssl-config-mime-augmenter.md`
- Implementation: `src/main/scala/io/spicelabs/goatrodeo/util/OpenSSLConfigDetector.scala`
- Registration: `src/main/scala/io/spicelabs/goatrodeo/util/ArtifactWrapper.scala:260`
