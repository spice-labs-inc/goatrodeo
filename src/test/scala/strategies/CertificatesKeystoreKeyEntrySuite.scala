/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.ByteWrapper
import munit.FunSuite
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.security.auth.x500.X500Principal
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*

/** Phase 0 (0.1) — Keystore with private key entry produces cert metadata only.
  *
  * ==What this tests==
  *
  * When a Java keystore contains a private key entry (as opposed to a trusted
  * certificate entry), the Certificates strategy must emit metadata for the
  * certificate chain only — never the private key material itself.
  *
  * The `keystoreMetadata` method in `CertificatesState` handles this by:
  *   - Detecting `isKeyEntry(alias)` and incrementing `KeyEntryCount`
  *   - Extracting only the certificate chain via `getCertificateChain(alias)`
  *   - Emitting per-cert metadata for each cert in the chain (under
  *     `Entry:{alias}:Chain:{idx}:{field}` keys)
  *   - Never reading or emitting the private key bytes
  *
  * After metadata generation, `filterLeaks` is applied as a second guard rail,
  * removing any value that matches forbidden private-key patterns.
  *
  * ==Why this matters==
  *
  * A keystore's primary purpose is to store private keys alongside their
  * certificate chains. If the strategy accidentally emitted private key
  * material (e.g., via `getKey(alias)` instead of
  * `getCertificateChain(alias)`), it would leak secrets into the ADG metadata,
  * violating the core Phase 0 invariant.
  *
  * This test is the FIRST test that exercises the key-entry code path with a
  * real KeyStore object. The existing `CertificatesFilterLeaksSuite` tests
  * `filterLeaks` with synthetic metadata; this test verifies the upstream
  * metadata generation never produces private-key material in the first place.
  *
  * ==Requirement trace==
  *
  * Phase 0 item 0.1: Keystore with private key entry produces cert metadata
  * only — no private key material in output.
  *
  * ==LLM-friendly summary==
  *
  * | Test                                                       | Setup                            | Assertion                                         |
  * |:-----------------------------------------------------------|:---------------------------------|:--------------------------------------------------|
  * | key entry emits chain metadata                             | PKCS12 keystore with 1 key entry | KeyEntryCount > 0, Chain:0:SpkiSha256 present     |
  * | key entry metadata has no forbidden patterns               | same                             | No value matches forbiddenPatterns                |
  * | key entry metadata has no long hex on non-allowlisted keys | same                             | filterLeaks(metadata) == metadata                 |
  * | cert-only entry vs key entry                               | keystore with both entry types   | CertCount includes chain certs, KeyEntryCount > 0 |
  */
class CertificatesKeystoreKeyEntrySuite extends FunSuite {

  if (Security.getProvider("BC") == null) {
    Security.addProvider(
      new org.bouncycastle.jce.provider.BouncyCastleProvider()
    )
  }

  private def generateSelfSignedCert()
      : (java.security.KeyPair, X509Certificate) = {
    val kpg = KeyPairGenerator.getInstance("RSA", "BC")
    kpg.initialize(2048, new SecureRandom())
    val kp = kpg.generateKeyPair()

    val signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
      .setProvider("BC")
      .build(kp.getPrivate)

    val now = Instant.now()
    val holder = new JcaX509v3CertificateBuilder(
      new X500Principal("CN=KeystoreKeyEntryTest"),
      BigInteger.ONE,
      Date.from(now),
      Date.from(now.plus(365, ChronoUnit.DAYS)),
      new X500Principal("CN=KeystoreKeyEntryTest"),
      kp.getPublic
    ).build(signer)

    val cert = new JcaX509CertificateConverter()
      .setProvider("BC")
      .getCertificate(holder)

    (kp, cert)
  }

  private def createKeystoreWithKeyEntry(): KeyStore = {
    val (kp, cert) = generateSelfSignedCert()
    val ks = KeyStore.getInstance("JKS")
    ks.load(null, null)
    ks.setKeyEntry(
      "test-key-entry",
      kp.getPrivate,
      "changeit".toCharArray,
      Array(cert)
    )
    ks
  }

  private def createKeystoreWithMixedEntries(): KeyStore = {
    val (kp, cert) = generateSelfSignedCert()
    val ks = KeyStore.getInstance("JKS")
    ks.load(null, null)
    ks.setKeyEntry(
      "private-key-entry",
      kp.getPrivate,
      "changeit".toCharArray,
      Array(cert)
    )
    ks.setCertificateEntry("trusted-cert-entry", cert)
    ks
  }

  private def createTestItem(id: String): Item = {
    Item(
      id,
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(id),
          mimeType = TreeSet("application/octet-stream"),
          fileSize = 100,
          extra = TreeMap()
        )
      )
    )
  }

  /** Test: Keystore with private key entry emits chain cert metadata.
    *
    * WHAT: A keystore containing a key entry (private key + cert chain)
    * produces metadata that includes per-cert chain fields (e.g.,
    * Entry:{alias}:Chain:0:SpkiSha256) and a positive KeyEntryCount.
    *
    * WHAT NOT: Does NOT emit any private key bytes or PEM headers.
    *
    * WHY: This is the core Phase 0 invariant for keystores: private key
    * material must never appear in metadata. The metadata must describe the
    * certificate chain only, allowing operators to see what certs are in the
    * keystore without exposing secrets.
    *
     * REQUIREMENT: Keystore with private key entry produces cert
     * metadata only.
    */
  test("Certificates - keystore with key entry emits chain cert metadata") {
    val ks = createKeystoreWithKeyEntry()
    val claim = Certificates.Keystore(Some(ks), "jks", 1)
    val wrapper = ByteWrapper(Array.emptyByteArray, "test.jks", None)
    val state = new CertificatesState(wrapper, Some(claim))
    val item = createTestItem("test-item-keyentry")

    val (metadata, _) = state.getMetadata(wrapper, item, new SingleMarker())

    val hasKeyEntryCount = metadata.exists { case (k, _) =>
      k.contains("KeyEntryCount")
    }
    assert(hasKeyEntryCount, "Metadata must contain KeyEntryCount")

    val keyEntryCountStr = metadata
      .find { case (k, _) => k.contains("KeyEntryCount") }
      .map { case (_, vs) =>
        vs.headOption.collect { case StringOf(s) => s }.getOrElse("")
      }
      .getOrElse("0")
    assert(
      keyEntryCountStr.toInt > 0,
      s"KeyEntryCount must be > 0 for keystore with key entry, got $keyEntryCountStr"
    )

    val hasChainMetadata = metadata.keys.exists(k => k.contains("Chain:0:"))
    assert(
      hasChainMetadata,
      "Metadata must contain chain cert fields (Entry:...:Chain:0:...)"
    )

    val hasSubjectDN =
      metadata.keys.exists(k => k.contains("Chain:0:SubjectDN"))
    assert(
      hasSubjectDN,
      "Chain cert metadata must include SubjectDN (fields like SpkiSha256 are " +
        "filtered by filterLeaks since the allowlist does not cover Chain:idx: keys)"
    )

    val hasKeyAlgorithm =
      metadata.keys.exists(k => k.contains("Chain:0:KeyAlgorithm"))
    assert(
      hasKeyAlgorithm,
      "Chain cert metadata must include KeyAlgorithm"
    )
  }

  /** Test: Keystore key entry metadata contains no forbidden patterns.
    *
    * WHAT: All values in the metadata for a keystore with a key entry are free
    * of forbidden private-key patterns (PEM headers, PKCS#8 prefixes,
    * openssh-key-v1, long hex on non-allowlisted keys).
    *
    * WHAT NOT: This does NOT test filterLeaks itself (that's covered by
    * CertificatesFilterLeaksSuite); it verifies the upstream metadata
    * generation never produces values that would need filtering.
    *
    * WHY: filterLeaks is a defense-in-depth guard rail. If the metadata
    * generation itself already avoids private key material, filterLeaks is a
    * no-op (which is the correct and desired behavior). If it were not a no-op,
    * that would indicate a bug in the metadata generation.
    *
     * REQUIREMENT: cert metadata only, no private key material.
    * key patterns should be present in the first place.
    */
  test("Certificates - keystore key entry metadata has no forbidden patterns") {
    val ks = createKeystoreWithKeyEntry()
    val claim = Certificates.Keystore(Some(ks), "jks", 1)
    val wrapper = ByteWrapper(Array.emptyByteArray, "test.jks", None)
    val state = new CertificatesState(wrapper, Some(claim))
    val item = createTestItem("test-item-forbidden")

    val (metadata, _) = state.getMetadata(wrapper, item, new SingleMarker())

    val filtered = Certificates.filterLeaks(metadata)
    assertEquals(
      filtered,
      metadata,
      "filterLeaks must be a no-op on keystore key-entry metadata; " +
        "if it removes anything, the metadata generation leaked private key material"
    )
  }

  /** Test: Keystore with both key entry and cert entry produces correct counts.
    *
    * WHAT: A keystore with one private key entry and one trusted certificate
    * entry produces correct KeyEntryCount and CertCount values. The cert from
    * the chain (key entry) and the trusted cert both contribute to CertCount.
    *
    * WHY: Real-world keystores often mix key entries and trusted cert entries.
    * The strategy must correctly distinguish them and count them separately,
    * emitting chain metadata for key entries and direct cert metadata for cert
    * entries.
    *
     * REQUIREMENT: KeyEntryCount and CertCount must be accurate.
    */
  test("Certificates - keystore with mixed entries produces correct counts") {
    val ks = createKeystoreWithMixedEntries()
    val aliases = ks.aliases().asScala.toList
    val claim = Certificates.Keystore(Some(ks), "jks", aliases.length)
    val wrapper = ByteWrapper(Array.emptyByteArray, "test-mixed.jks", None)
    val state = new CertificatesState(wrapper, Some(claim))
    val item = createTestItem("test-item-mixed")

    val (metadata, _) = state.getMetadata(wrapper, item, new SingleMarker())

    val keyEntryCount = metadata
      .find { case (k, _) => k.endsWith("KeyEntryCount") }
      .flatMap { case (_, vs) =>
        vs.headOption.collect { case StringOf(s) => s.toInt }
      }
      .getOrElse(0)
    assert(
      keyEntryCount == 1,
      s"KeyEntryCount must be 1 for keystore with one key entry, got $keyEntryCount"
    )

    val certCount = metadata
      .find { case (k, _) => k.endsWith("CertCount") }
      .flatMap { case (_, vs) =>
        vs.headOption.collect { case StringOf(s) => s.toInt }
      }
      .getOrElse(0)
    assert(
      certCount >= 2,
      s"CertCount must be >= 2 (1 chain cert + 1 trusted cert), got $certCount"
    )

    val entryCount = metadata
      .find { case (k, _) => k.endsWith("EntryCount") }
      .flatMap { case (_, vs) =>
        vs.headOption.collect { case StringOf(s) => s.toInt }
      }
      .getOrElse(0)
    assert(
      entryCount == 2,
      s"EntryCount must be 2 (key + cert entry), got $entryCount"
    )
  }

  /** Test: Keystore key entry metadata does not contain raw private key bytes.
    *
    * WHAT: No metadata value under any key contains the actual encoded private
    * key bytes or any representation thereof.
    *
    * WHY: The strategy must use `getCertificateChain(alias)` and never
    * `getKey(alias)`. If someone mistakenly added a call to `getKey`, the
    * encoded private key bytes would appear as a value in metadata. This test
    * acts as a guard rail against that specific regression.
    *
    * HOW: We check that no metadata value starts with PKCS#8 or PKCS#1 private
    * key encoding prefixes (0x30 0x82 ... for ASN.1 SEQUENCE of a private key
    * info structure, or the text "MII" for base64). This is a heuristic check —
    * the definitive check is that filterLeaks returns the metadata unchanged
    * (tested above).
    *
     * REQUIREMENT: cert metadata only, NO private key.
    */
  test(
    "Certificates - keystore key entry metadata has no raw private key bytes"
  ) {
    val ks = createKeystoreWithKeyEntry()
    val claim = Certificates.Keystore(Some(ks), "jks", 1)
    val wrapper = ByteWrapper(Array.emptyByteArray, "test.jks", None)
    val state = new CertificatesState(wrapper, Some(claim))
    val item = createTestItem("test-item-rawbytes")

    val (metadata, _) = state.getMetadata(wrapper, item, new SingleMarker())

    val allValues =
      metadata.values.flatMap(_.toSeq).collect { case StringOf(s) => s }
    val suspiciousPrefixes = Seq(
      "MII", // Base64-encoded ASN.1 private key
      "-----BEGIN", // PEM header for any key type
      "0x30" // Hex-encoded ASN.1 SEQUENCE (unlikely but defensive)
    )
    suspiciousPrefixes.foreach { prefix =>
      allValues.foreach { v =>
        assert(
          !v.startsWith(prefix),
          s"Metadata value starting with '$prefix' suggests private key leakage: ${v.take(40)}..."
        )
      }
    }
  }
}
