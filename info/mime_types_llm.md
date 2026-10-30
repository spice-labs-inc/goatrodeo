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
| `application/x-itunes-ipa` (Tika) | `application/zip` (`.ipa` files whose 4-byte magic is a ZIP local-file header `PK\x03\x04` or the empty-ZIP EOCD `PK\x05\x06`) |

**Verified by:** `IpaDetectionSuite.Ipa-0.1` (real ZIP `.ipa` massaged and
walked; `Payload/…/Info.plist` children appear), `Ipa-0.2` (non-zip `.ipa`
NOT massaged), `Ipa-0.6` (empty-zip `.ipa` accepted), `Ipa-0.5` (property:
massage decision equals magic ∧ extension predicate). The massage keys on
magic, never on the detected MIME, so Android APKs and plain ZIPs (shared
magic, different extension) are unaffected — `Ipa-0.3`, `Ipa-0.7`.

### Producer-stamped MIME hints (spec §5)

- **Producers may stamp an authoritative MIME hint** on an artifact
  wrapper (`ArtifactWrapper.newWrapper(..., mimeHint = Some(...))`). The
  hint is unioned into the wrapper's effective MIME set and is
  authoritative — never re-checked against content.
- **No hint is ever produced by content sniffing.** Detection only adds
  MIMEs from the signatures below; it never fabricates kind MIMEs such as
  `application/pkcs7-signature`.
- Content sniffing of a DER PKCS#7 SignedData yields
  `application/pkcs7-mime` (below); the authoritative
  `application/pkcs7-signature` MIME is stamped by the producer (e.g. the
  .NET walker's Authenticode cert children) and is what the Certificates
  strategy claims (see the certificates strategy doc).

Verified by `MimeHintSuite.T5.1–T5.8` (hint default/union/spill/
never-sniffed/authoritative/monotonic property) and
`CertificatesPkcs7Suite.T6.x` (claim semantics).

### Per-augmenter short-circuits (there is no global MIME gate)

There is no "terminal" MIME type, and no `augmentationCannotApply`. A MIME type is
an additive claim about what an artifact is (a JAR is also a ZIP, also a walked
container, and may also hold stored certificate bytes), so nothing may decide on
everyone's behalf that no further claim is possible.

Dispatch, in `ArtifactWrapper.augmentMimeTypes`:

```scala
mimeTypeAugmenters.get().foldLeft((mimes, Map.empty[...])) { case ((curMimes, curEv), augmenter) =>
  if (!augmenter.rule(curMimes)) (curMimes, curEv)   // this augmenter only
  else augmenter.fn(artifact, curMimes, curEv, new RetainedProbe(artifact, augmenter.budget))
}
```

The rules, each local, each block-shaped via `ArtifactWrapper.noneOf`. Its contract:
a blocked name ending in a slash plus an asterisk is a family wildcard and matches
as a prefix (`"text/*"` blocks `text/plain`); anything else matches exactly:

```scala
  def noneOf(blocked: String*)(mimes: Set[String]): Boolean = {
    !blocked.exists{
      b => 
        if (b.endsWith("/*")) {
          val realB = b.substring(0, b.length() - 1)
          mimes.exists(m => m.startsWith(realB))
        } else mimes.contains(b)
    }
  }
```


| Augmenter | Blocked families | Why it cannot use them |
|---|---|---|
| `DotnetDetector` | `text/*`, `application/xml`, `application/java-vm` | a PE image is binary; XML and class files carry no COFF header |
| `SaffronDetector` | `application/java-vm` | only class files are provably impossible; `.vhd`/`.raw` are mislabelled `text/*`/`image/*` by extension, so those stay probed |
| `CryptoDetector` | `application/java-vm` | a class file's constant pool is not a DER/PEM artefact |
| `OpenSSLConfigDetector` | `image/*`, `audio/*`, `video/*`, `application/java-vm`, `application/java-archive`, `application/vnd.android.package-archive`, `application/xml` | a config file is text |
| `JavaSecurityDetector` | same list as OpenSSL | same reason |
| `JavaArchiveDetector` | `text/*`, `application/xml`, `application/java-vm` | a ZIP is binary and holds no XML header |
| `CryptoContentDetector` | `application/java-vm` | class constant pools carry no PEM text |
| `CarvedCertAugmenter` | `text/*`, `application/xml`, `application/json`, `application/java-vm` | a DER certificate is binary; base64 in text is `CryptoContentDetector`'s job |

Consequences that are tested rather than assumed:

- A family blocked by one augmenter is still probed by the others: a JAR is
  refused by `JavaSecurityDetector` and still probed by `CryptoContentDetector`.
- Class files are the only family every augmenter refuses — eight local
  decisions, visible as such, not a shared gate.
- Claims are additive: one artifact can carry several augmenters' MIME types at
  once, and none suppresses another.
- `SaffronDetector` never removes a MIME; it *adds*
  `application/x-saffron-raw-disk`, which `FileWalker.saffronMimeTypes` uses to
  offer the disk to Saffron, so an `image/x-raw-*` or `text/x-vhdl` misreading
  from Tika cannot hide a cloud disk image.

Tests: `AugmenterLocalShortCircuitSuite.LR-1` (no family blocked by every
augmenter except class files; every augmenter refuses class files in its own
rule), `LR-2` (JAR: blocked for `JavaSecurityDetector`, probed by
`CryptoContentDetector`), `LR-3` (same payload in text fires both), `LR-4`
(additive claims), `MimeAugmenterRuleSuite.R-1` (per-rule block lists, including
`text/*` and `image/*` prefix matching), `MimeAugmenterRuleSuite.R-2` (a real class
file is augmented by nothing), and `SaffronImageItemSuite.S-5` (a `.raw` disk is
walked).

## CryptoDetector augmentation (Certificates strategy)

Where Tika falls short for cryptographic file types — most crypto
files just look like generic binary or PEM text — the
[CryptoDetector](../src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala)
augmenter (registered after Saffron in
`ArtifactWrapper.augmenters`) inspects the first 4 KB of each
artifact and ADDS specific MIME types based on content signatures.

**Pure-addition design:** every augmenter is purely additive, `SaffronDetector`
included. None removes a Tika-detected MIME. `SaffronDetector` adds
`application/x-saffron-raw-disk`; it does not delete the `text/x-vhdl` or
`image/x-raw-*` misreading. Rationale: PEM-encoded artifacts legitimately are BOTH
`text/plain` (Tika) AND `application/x-pem-file` (`CryptoDetector`); downstream
filtering chooses what to act on.

**Verified by:**
[`CertificatesStubTests::[INVARIANT] CryptoDetector.mimeTypeAugmenter never strips MIME types beginning with text/`](../src/test/scala/strategies/CertificatesStubTests.scala)
+
[`...is always a superset of input (additive)`](../src/test/scala/strategies/CertificatesStubTests.scala).

### Per-augmenter applicability rules

Every registered augmenter is a `MimeAugmenter(id, rule, budget, fn)` registered
with `ArtifactWrapper.addMimeTypeAugmenter`; its `rule` is the only statement about
which MIME sets it will look at, and it affects no other augmenter. Rules only
block MIMEs that provably cannot match — unknown or degenerate MIMEs (inner
package fragments, future ecosystems) are always probed, so a wrong rule can
only cost performance, never a false negative.

| Augmenter | Blocked MIMEs |
|-----------|---------------|
| `CryptoDetector` | `application/java-vm` |
| `CryptoContentDetector` | `application/java-vm` |
| `OpenSSLConfigDetector` | media (`image/*`·`audio/*`·`video/*`), class mimes, `application/java-archive`, `application/vnd.android.package-archive`, `application/xml` |
| `JavaSecurityDetector` | same as OpenSSLConfigDetector |
| `JavaArchiveDetector` | `text/*`, `application/xml`, `application/java-vm` |
| `DotnetDetector` | `text/*`, `application/xml`, `application/java-vm` |
| `SaffronDetector` | `application/java-vm` only (text families stay probed: Tika mislabels `.vhd` as `text/x-vhdl` and Saffron exists to re-check those) |
| `CarvedCertAugmenter` | `text/*`, `application/xml`, `application/json`, `application/java-vm` (probes binaries/unknown fragments for carved DER X.509 certs) |

Verified by `MimeAugmenterRuleSuite` (R-1 rule table, R-2 class files
augment nothing, R-3 XML keeps crypto detection while skipping
binary/text-config augmenters, R-4 degenerate fragments stay probed, R-5
binary PEM carriers still detected).

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

## Archive Extensions

The following additional archive extensions are recognized:

| Extension | Typical content |
|-----------|---------------|
| `.ear`    | Enterprise archive (EAR) |
| `.par`    | Provisioning archive |
| `.sar`    | JBoss service archive |
| `.nar`    | NetBeans archive |
| `.jpi`    | Jenkins plugin |
| `.hpi`    | Hudson/Jenkins plugin |
| `.kar`    | Karaf KAR archive |
| `.far`    | FAR archive |
| `.lpkg`   | Liferay marketplace package |
| `.rar`    | Resource adapter archive |
| `.zap`    | ZAP configuration bundle |

## Future Work

MIME type transmogrifiers could be made extensible.
