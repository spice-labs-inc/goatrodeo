# IoTGoat CBOM Gap Analysis (LLM Version)

## Purpose

This document is a quick-reference for LLMs. It explains why `IoTGoatCbomSuite.T4.5` fails and what concrete changes are needed in Goat Rodeo to make it pass.

## Test Under Discussion

- **File:** `src/test/scala/io/spicelabs/goatrodeo/omnibor/IoTGoatCbomSuite.scala`
- **Test name:** `T4.5 IoTGoat x86 CBOM contains all discovered static cryptographic material`
- **Fixture:** `test_data/IoTGoat-x86.img.gz`
- **Current state:** Fails. Only 2 unrelated CBOM components are emitted.

## What the Test Expects

The CBOM must contain a `cryptographic-asset` component for each of these discovered artifacts:

| Path | Kind | Expected CBOM representation |
|------|------|------------------------------|
| `/etc/shadow` | password hashes (`$1$` MD5) | `related-crypto-material` type `password-hash` |
| `/etc/opkg/keys/*` | 11 `usign` Ed25519 public keys | `related-crypto-material` type `public-key` |
| `/etc/dropbear/dropbear_rsa_host_key` | empty SSH host-key placeholder | `related-crypto-material` type `public-key` or `private-key` |
| `/etc/config/uhttpd` | HTTPS service config (`cert`, `key` options) | `related-crypto-material` or `protocol` |
| `/usr/lib/libmbedx509.so.2.14.1` | ELF library with embedded cert strings | `certificate` or `related-crypto-material` |

Guardrail: the open wireless AP must **not** produce a fake PSK/WPA component.

## Why It Fails

`CbomEmitter.isCryptoItem` only recognizes three metadata prefixes:

- `Certificates:`
- `openssl.cnf:`
- `java.security:`

None of the discovered artifacts are tagged with those prefixes, so they are not collected. The two existing components come from the `JavaSecurity` strategy, which emits `java.security:associated_files` properties; they do not match the discovered crypto material and appear to be caused by the generic `include` keyword in the JavaSecurity detector.

## Required Enhancements

1. **Password-hash strategy**
   - Parse `/etc/shadow`, `/etc/passwd`, `/etc/group`.
   - Add metadata prefix `PasswordHash:` with algorithm and user.
   - Emit `related-crypto-material` type `password-hash`.

2. **Usign/signify public-key strategy**
   - Parse `/etc/opkg/keys/*`.
   - Add metadata prefix `Usign:` (or `PackageSigningKey:`).
   - Emit `related-crypto-material` type `public-key` algorithm `ed25519`.

3. **SSH/Dropbear key strategy**
   - Detect `dropbear_*_host_key`, `ssh_host_*_key`, `ssh_host_*_key.pub`, `authorized_keys`.
   - Add metadata prefix `SSH:`.
   - Redact non-empty private keys.

4. **Service TLS config strategy**
   - Parse `uhttpd`, `nginx`, `apache`, `lighttpd` configs.
   - Add metadata prefix `TLS:` or `ServiceCert:` for configured cert/key paths.
   - Emit `related-crypto-material` or `protocol`.

5. **Embedded certificate extraction**
   - Scan ELF/PE/firmware blobs for PEM `BEGIN CERTIFICATE` and DER X.509.
   - Add metadata prefix `EmbeddedCertificate:`.
   - Emit `certificate` when parseable.

6. **Tighten JavaSecurity detector**
   - Do not treat the word `include` alone as a Java security marker; require actual Java security keys.

7. **Update CBOM emitter**
   - Expand `isCryptoItem` to include the new prefixes.
   - Expand `isPrivateKey` to cover private keys from the new strategies.

## Implementation Hints

- New strategies should follow the existing pattern in `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/`.
- CBOM mapping logic lives in `src/main/scala/io/spicelabs/goatrodeo/omnibor/CbomEmitter.scala` (`cryptoPropertiesFor`, `isCryptoItem`, `isPrivateKey`).
- Add unit tests for each strategy before wiring them into the CBOM emitter.

## Verification

Run `sbt "testOnly *IoTGoatCbomSuite"`. It should pass once all recommended strategies are implemented.
