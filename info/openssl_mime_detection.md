# OpenSSL Configuration MIME Detection

> **Navigation:** [Documentation Index](README.md) | [MIME Types](mime_types.md)

## Overview

Goat Rodeo routes artifacts to analysis strategies by MIME type. Strategies must not read every file at a layer to decide which files to claim; they rely on pre-computed MIME type sets from `ArtifactWrapper`.

Apache Tika classifies OpenSSL configuration files (`.cnf`) as `text/plain`, the same type used for README files, shell scripts, and generic INI files. Because `text/plain` is too broad, a custom MIME augmenter is required to give the OpenSSL capture strategy a distinct routing signal.

**Verified by:** `OpenSSLMimeDetectionSuite` — Tika-only assertion on a representative fixture plus corpus detection-rate assertion.

## The OpenSSLConfigDetector augmenter

The [`OpenSSLConfigDetector`](../src/main/scala/io/spicelabs/goatrodeo/util/OpenSSLConfigDetector.scala) augmenter is registered in `ArtifactWrapper` after `CryptoDetector` and adds the MIME type `application/x-openssl-config` when a file looks like an OpenSSL config.

**Verified by:** `OpenSSLConfigDetectorSuite` — registration and pure-addition tests.

It is a pure-addition augmenter: it never removes or replaces a MIME type that Tika already produced. If the probe is inconclusive, the artifact keeps its existing MIME type set.

## Detection heuristic

The augmenter reads at most the first 4 KB of an artifact:

1. **Binary early exit:** If more than 10% of characters are non-whitespace control characters, the prefix is treated as binary and the augmenter returns `Set.empty`.
2. **Lowercase scan:** The prefix is converted to lowercase and scanned for OpenSSL-specific markers.
3. **Claim rule:** The file is claimed as `application/x-openssl-config` if either:
   - a strong OpenSSL signal is present anywhere in the prefix, OR
   - the prefix contains at least one INI-style section header AND at least one medium OpenSSL signal.

This two-tier design keeps false positives low while still catching a wide range of real configs, including main `openssl.cnf` files and test/demo configs.

## Signal inventory

### Strong signals

A match on any of these is sufficient by itself:

- `openssl_conf`
- `ssl_conf`
- `.include`
- `config_diagnostics`
- `cipherstring`
- `ciphersuites`
- `minprotocol`
- `maxprotocol`
- `oid_section`
- `default_ca`
- `distinguished_name`
- `req_extensions`
- `x509_extensions`
- X.509 extension names such as `basicconstraints`, `keyusage`, `subjectkeyidentifier`, `authoritykeyidentifier`, `subjectaltname`, `issueraltname`, `issuersigntool`, `sbgp-autonomoussysnum`, `sbgp-ipaddrblock`, `issuingdistributionpoint`
- `ssleay::`

### Medium signals

These are only considered when a section header is also present, to avoid claiming generic INI/TOML files:

- `options`
- `curves`
- `signaturealgorithms`
- `default_bits`
- `default_keyfile`
- `default_md`
- `encrypt_key`
- `prompt`
- `randfile`
- `oid_file`
- `new_oids`

## Test corpus

A corpus of 153 real OpenSSL `.cnf` files is maintained in [`test_data/openssl_configs/`](../test_data/openssl_configs/). The files were collected from official OpenSSL source releases from 0.9.8zh through 3.2.1 by [`workspace/collect_openssl_configs.py`](../workspace/collect_openssl_configs.py). The corpus is part of the test suite and is checked into the repository.

## Verification

- `OpenSSLMimeDetectionSuite` verifies that the collected corpus is detected at a rate of at least 85%. In practice the rate is higher.
- `OpenSSLConfigDetectorSuite` verifies unit behavior, including strong/medium signals, case-insensitive matching, encoding handling, binary early exit, registration in `ArtifactWrapper`, pure-addition property, and the 4 KB read budget.

## Why this is conservative

A missed OpenSSL config is safe: it falls through to `GenericFile` and is not claimed as cryptographic configuration material. A false positive is worse because it would send unrelated files through the OpenSSL parser. The detector therefore errs on the side of false negatives.

## Related

- ADR: [`docs/adr/0001-openssl-config-mime-augmenter.md`](../docs/adr/0001-openssl-config-mime-augmenter.md)
- Implementation: [`src/main/scala/io/spicelabs/goatrodeo/util/OpenSSLConfigDetector.scala`](../src/main/scala/io/spicelabs/goatrodeo/util/OpenSSLConfigDetector.scala)
- Registration: [`src/main/scala/io/spicelabs/goatrodeo/util/ArtifactWrapper.scala`](../src/main/scala/io/spicelabs/goatrodeo/util/ArtifactWrapper.scala) line 260
