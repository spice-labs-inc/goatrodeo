# IoTGoat CBOM Gap Analysis

## Context

A discovery-driven CBOM regression test, `IoTGoatCbomSuite.T4.5`, was added to run the full Goat Rodeo pipeline against the IoTGoat x86 firmware image (`test_data/IoTGoat-x86.img.gz`) and assert that every static cryptographic artifact found by native inspection is represented as a CycloneDX cryptographic-asset component.

## Current Test Result

The test fails. Goat Rodeo emits exactly **one** CBOM file for the input image, and that CBOM contains only **two** `cryptographic-asset` components. Both components are `related-crypto-material` of type `other`, with identifiers and `java.security:associated_files` properties. They do not correspond to any of the cryptographic material discovered in the image.

Discovered material that is **missing** from the CBOM:

- `/etc/shadow` — MD5-crypt password hashes for `root` and `iotgoatuser`.
- `/etc/opkg/keys/*` — 11 OpenWrt/LEDE `usign` package-signing public keys.
- `/etc/dropbear/dropbear_rsa_host_key` — empty SSH host-key placeholder.
- `/etc/config/uhttpd` — HTTPS service configuration pointing to `/etc/uhttpd.crt` and `/etc/uhttpd.key`.
- `/usr/lib/libmbedx509.so.2.14.1` — mbed TLS x509 library containing embedded certificate delimiters.

In addition, the source repository (`https://github.com/OWASP/IoTGoat`) contains no hardcoded certificates or private keys, only the same password hashes and default OpenWrt configuration files.

## Why the Test Fails

The CBOM emitter only recognizes items whose metadata contains keys from the existing strategies:

```scala
private def isCryptoItem(item: Item): Boolean = {
  item.bodyAsItemMetaData.exists { meta =>
    meta.extra.keys.exists(k =>
      k.startsWith("Certificates:") ||
        k.startsWith("openssl.cnf:") ||
        k.startsWith("java.security:")
    )
  }
}
```

(`src/main/scala/io/spicelabs/goatrodeo/omnibor/CbomEmitter.scala:162`)

None of the discovered artifacts are tagged with those prefixes, so they are not collected into the CBOM.

### Specific gaps

1. **Password hashes (`/etc/shadow`)**
   - Goat Rodeo has no strategy for `shadow`, `passwd`, or `htpasswd` files.
   - `isCryptoItem` does not consider password hashes as cryptographic material.

2. **Package signing keys (`/etc/opkg/keys/*`)**
   - `usign` / `signify` / `minisign` public keys are not recognized by the certificate strategy.
   - The files are small base64-style public keys; there is no dedicated parser or metadata key.

3. **Dropbear SSH host key placeholder**
   - The empty file `/etc/dropbear/dropbear_rsa_host_key` is not detected.
   - There is no strategy for SSH/Dropbear host keys or `authorized_keys`.

4. **TLS service configuration (`/etc/config/uhttpd`)**
   - The file is not an `openssl.cnf` and does not contain a certificate, so it is ignored.
   - The referenced certificate and key paths (`/etc/uhttpd.crt`, `/etc/uhttpd.key`) are not treated as crypto assets even though the service enables HTTPS on port 443.

5. **Embedded certificates in binaries (`/usr/lib/libmbedx509.so.2.14.1`)**
   - The certificate strategy parses standalone PEM/DER files and JKS keystores, but does not scan arbitrary ELF binaries for embedded PEM delimiters or X.509 structures.

6. **False positives from the JavaSecurity detector**
   - The current CBOM components are produced by the JavaSecurity strategy. The detector treats the generic keyword `include` as a Java-security marker, which can match unrelated text files and produce spurious `related-crypto-material` entries.

## Recommendations

Add dedicated strategies and update the CBOM emitter so the discovered material is represented accurately.

1. **Shadow password strategy**
   - Parse `/etc/shadow`, `/etc/passwd`, `/etc/group`, and similar hash stores.
   - Emit `related-crypto-material` with type `password-hash` and properties for the hashing algorithm (e.g., `$1$` MD5, `$5$` SHA-256, `$6$` SHA-512, `$y$` yescrypt) and the affected user.

2. **Usign / package-signing key strategy**
   - Parse `/etc/opkg/keys/*` and similar `usign`/`signify`/`minisign` public-key files.
   - Emit `related-crypto-material` with type `public-key` and algorithm `ed25519`.

3. **SSH/Dropbear host key strategy**
   - Detect `dropbear_*_host_key`, `ssh_host_*_key`, `ssh_host_*_key.pub`, and `authorized_keys` files.
   - Emit `related-crypto-material` with type `public-key` or `private-key` as appropriate, and redact non-empty private keys per the existing redaction logic.

4. **Service TLS configuration strategy**
   - Parse `uhttpd`, `nginx`, `apache`, `lighttpd`, and similar service configs that reference certificate and key files.
   - Emit `related-crypto-material` for the configured cert/key paths and/or a `protocol` component describing the TLS endpoint.

5. **Embedded certificate extraction in binaries**
   - Extend the certificate strategy to scan ELF/PE libraries, firmware blobs, and raw partitions for PEM `BEGIN CERTIFICATE` blocks and DER-encoded X.509 structures.
   - Emit `certificate` components with subject/issuer metadata when parseable.

6. **Tighten JavaSecurity detection**
   - Remove the generic `include` keyword from the standalone detector, or require it to appear alongside actual Java security keys (`jdk.tls.*`, `security.provider.*`, etc.) to avoid false positives.

7. **Update CBOM emitter recognition**
   - Expand `isCryptoItem` to include metadata prefixes added by the new strategies, e.g., `PasswordHash:`, `Usign:`, `SSH:`, `TLS:`, `EmbeddedCertificate:`.
   - Ensure `isPrivateKey` also catches private-key material from the new strategies so secrets are redacted.

8. **Add unit-level strategy tests**
   - For each new strategy, add tests that feed the IoTGoat fixture content and assert the correct metadata keys and CBOM component types are produced.

## Related Files

- `src/test/scala/io/spicelabs/goatrodeo/omnibor/IoTGoatCbomSuite.scala` — the failing regression test.
- `src/main/scala/io/spicelabs/goatrodeo/omnibor/CbomEmitter.scala` — CBOM collection logic.
- `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala` — certificate parsing strategy.
- `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/JavaSecurity.scala` — Java security strategy producing the current false-positive components.
- `test_data/IoTGoat-x86.img.gz` — firmware image under test.
