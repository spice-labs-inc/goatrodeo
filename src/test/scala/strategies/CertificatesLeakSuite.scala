/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.PairOf
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File
import java.util.regex.Pattern
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Phase 0.1 / Phase 8 — corpus-wide private-key leak sweep + hostile-reviewer
  * sentinel check.
  *
  * The `filterLeaks` method removes private-key material from metadata (instead
  * of throwing, as the old `assertNoLeak` did). This suite independently
  * verifies the output is free of leaks by sweeping emitted metadata against
  * the Appendix C forbidden-pattern regexes.
  *
  * The hostile-reviewer sentinel check (per HS-2 step 3):
  *
  * > Introduce a sentinel leak (add `"-----BEGIN RSA PRIVATE KEY-----test"` >
  * as a metadata value), confirm `filterLeaks` removes it, then remove > the
  * sentinel.
  *
  * Without this check, "the leak suite passes" is not evidence the plumbing
  * actually fires.
  */
class CertificatesLeakSuite extends FunSuite {

  private val appendixCPatterns: Seq[Pattern] = Seq(
    "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "-----BEGIN PGP PRIVATE KEY BLOCK-----",
    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----",
    "MIIEvQIBADAN",
    "MIIEpAIBAAKCAQEA",
    "MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+",
    "openssh-key-v1"
  ).map(Pattern.compile)

  private def stubItem(): Item = Item(
    identifier = "gitoid:blob:sha256:phase8-leak-suite-stub",
    connections = TreeSet.empty,
    bodyMimeType = Some(ItemMetaData.mimeType),
    body = Some(
      ItemMetaData(
        fileNames = TreeSet.empty,
        mimeType = TreeSet.empty,
        fileSize = 0L,
        extra = TreeMap.empty
      )
    )
  )

  /** Walk every fixture in the corpus, run it through the strategy, collect
    * emitted metadata, and sweep against the forbidden-pattern regex list.
    */
  test("[LEAK SWEEP] zero forbidden-pattern matches across the entire corpus") {
    val corpusRoot = java.nio.file.Paths.get("test_data/certificates")
    if (!java.nio.file.Files.exists(corpusRoot)) {
      fail(
        "test_data/certificates does not exist; corpus is required for leak sweep"
      )
    }
    import scala.jdk.CollectionConverters.*
    val artifacts = java.nio.file.Files
      .walk(corpusRoot)
      .iterator()
      .asScala
      .filter(p => java.nio.file.Files.isRegularFile(p))
      .filter(p => !p.toString.endsWith(".expected.json"))
      .filter(p => !p.toString.contains("/tools/"))
      .filter(p => !p.toString.endsWith("/SOURCES.md"))
      .filter(p => !p.toString.endsWith("/generate.sh"))
      .filter(p => !p.toString.endsWith("/.gitattributes"))
      .filter(p => !p.toString.endsWith("/README.md"))
      .toVector

    assert(
      artifacts.nonEmpty,
      "corpus walk found zero artifacts — fixture-discovery bug"
    )

    val violations = scala.collection.mutable.ListBuffer[String]()
    var checked = 0
    artifacts.foreach { path =>
      val w = FileWrapper(new File(path.toString), path.toString, None)
      val claimOpt =
        scala.util.Try(Certificates.classifyAndParse(w)).toOption.flatten
      claimOpt.foreach { claim =>
        val state = new CertificatesState(w, Some(claim))
        scala.util
          .Try {
            val (md, _) = state.getMetadata(w, stubItem(), SingleMarker())
            checked += 1
            md.foreach { case (key, values) =>
              values.foreach { v =>
                val text = v match {
                  case StringOf(s)   => s
                  case PairOf(_, s2) => s2
                }
                appendixCPatterns.foreach { pat =>
                  if (pat.matcher(text).find()) {
                    violations += s"FIXTURE=${path} KEY=$key PATTERN=/${pat.pattern}/ VALUE=$text"
                  }
                }
              }
            }
          }
          .recover { case ex: RuntimeException =>
            violations += s"FIXTURE=${path} UNEXPECTED-CRASH: ${ex.getClass.getSimpleName}: ${ex.getMessage}"
          }
      }
    }
    assert(
      checked > 0,
      s"expected to check at least 1 fixture's metadata; only walked artifacts=${artifacts.length}"
    )
    if (violations.nonEmpty) {
      fail(
        s"forbidden-pattern leak detected (${violations.length} violations):\n  " +
          violations.take(10).mkString("\n  ")
      )
    }
  }

  /** HOSTILE-REVIEWER SENTINEL CHECK. Per the Phase 8 HS-2 step 3: "introduce a
    * sentinel leak, confirm the leak suite catches it, then remove the
    * sentinel."
    *
    * We inject the sentinel IN-MEMORY only — no fixture or committed code
    * carries it. The test asserts `filterLeaks` removes the offending entry.
    */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes a sentinel leak (RSA private-key banner)"
  ) {
    val sentinel = "-----BEGIN RSA PRIVATE KEY-----test"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestSentinel" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestSentinel"),
      "filterLeaks must remove the sentinel entry"
    )
  }

  /** Companion sentinel: PKCS#8 private-key DER prefix encoded as base64. */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes a PKCS#8 base64-prefix sentinel"
  ) {
    val sentinel =
      "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcw"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPkcs8" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestPkcs8"),
      "filterLeaks must remove the PKCS#8 prefix entry"
    )
  }

  /** Companion sentinel: long-hex on a non-allowlisted key. */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes a 32+ char hex run on a non-allowlisted key"
  ) {
    val sentinel =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestRawHex" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestRawHex"),
      "filterLeaks must remove the long-hex entry on non-allowlisted key"
    )
  }

  /** Companion sentinel: confirm the allowlist DOESN'T filter on
    * legitimately-long-hex values on allowlisted keys.
    */
  test(
    "[HOSTILE REVIEWER] filterLeaks preserves long-hex on Certificates:SpkiSha256"
  ) {
    val sha256 =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val ok: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:SpkiSha256" -> TreeSet(StringOrPair(sha256))
      )
    val result = Certificates.filterLeaks(ok)
    assertEquals(result, ok, "Allowlisted long-hex must be preserved")
  }

  test("filterLeaks preserves allowlisted long hex on CertSha256") {
    val sha256 =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val ok: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:CertSha256" -> TreeSet(StringOrPair(sha256))
      )
    val result = Certificates.filterLeaks(ok)
    assertEquals(
      result,
      ok,
      "Allowlisted CertSha256 long-hex must be preserved"
    )
  }

  /** Companion sentinel: openssh-key-v1 magic must be filtered. */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes openssh-key-v1 magic in metadata"
  ) {
    val sentinel = "openssh-key-v1\u0000abcdef"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestOpensshMagic" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestOpensshMagic"),
      "filterLeaks must remove openssh-key-v1 magic entry"
    )
  }

  /** Sentinel for PKCS#8-encrypted banner (Appendix C pattern #2). */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes PKCS#8 ENCRYPTED PRIVATE KEY banner"
  ) {
    val sentinel = "-----BEGIN ENCRYPTED PRIVATE KEY-----xyz"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPkcs8EncryptedBanner" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestPkcs8EncryptedBanner"),
      "filterLeaks must remove encrypted private key banner entry"
    )
  }

  /** Sentinel for PGP private-key-block banner (Appendix C pattern #3). */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes PGP PRIVATE KEY BLOCK banner"
  ) {
    val sentinel = "-----BEGIN PGP PRIVATE KEY BLOCK-----xyz"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPgpPrivateBanner" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestPgpPrivateBanner"),
      "filterLeaks must remove PGP private key block banner entry"
    )
  }

  /** Sentinel for full-PEM-body regex (Appendix C pattern #4). */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes a complete PEM private-key body (BEGIN/.../END)"
  ) {
    val sentinel =
      "-----BEGIN RSA PRIVATE KEY-----\n" +
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ\n" +
        "-----END RSA PRIVATE KEY-----"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestFullPemBody" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestFullPemBody"),
      "filterLeaks must remove full PEM body entry"
    )
  }

  /** Sentinel for the SECOND PKCS#8 base64 prefix (Appendix C pattern #6). */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes MIIEpAIBAAKCAQEA PKCS#8 prefix"
  ) {
    val sentinel = "MIIEpAIBAAKCAQEAxyz"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPkcs8Prefix2" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestPkcs8Prefix2"),
      "filterLeaks must remove MIIEpAIBAAKCAQEA prefix entry"
    )
  }

  /** Sentinel for the regex-shaped PKCS#8 prefix (Appendix C pattern #7). */
  test(
    "[HOSTILE REVIEWER] filterLeaks removes regex-shaped MIIB...QIB... PKCS#8 prefix"
  ) {
    val sentinel = "MIIBabcdefghQIBxyz"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPkcs8RegexPrefix" ->
          TreeSet(StringOrPair(sentinel))
      )
    val result = Certificates.filterLeaks(planted)
    assert(
      !result.contains("Certificates:LeakTestPkcs8RegexPrefix"),
      "filterLeaks must remove MIIB...QIB... prefix entry"
    )
  }

  private val patternSamples: Map[String, String] = Map(
    "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----" ->
      "-----BEGIN RSA PRIVATE KEY-----xxx",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----" ->
      "-----BEGIN ENCRYPTED PRIVATE KEY-----yyy",
    "-----BEGIN PGP PRIVATE KEY BLOCK-----" ->
      "-----BEGIN PGP PRIVATE KEY BLOCK-----zzz",
    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----" ->
      "-----BEGIN RSA PRIVATE KEY-----\nMII\n-----END RSA PRIVATE KEY-----",
    "MIIEvQIBADAN" ->
      "MIIEvQIBADANabc",
    "MIIEpAIBAAKCAQEA" ->
      "MIIEpAIBAAKCAQEAabc",
    "MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+" ->
      "MIIBabcdefghQIBxyz",
    "openssh-key-v1" ->
      "openssh-key-v1\u0000magic",
    "(?i)PRAGMA\\s+(key|rekey)\\s*=\\s*(\"[^\"]{8,}\"|'[^']{8,}'|[A-Za-z0-9+/=_-]{8,})" ->
      "PRAGMA key = 's3cr3tpassphrase'",
    "key_material\\s*=\\s*\"[A-Za-z0-9+/=]+\"" ->
      "key_material = \"AAAAAAbase64blob==\""
  )

  /** Verify filterLeaks passes clean metadata through unchanged. */
  test(
    "Certificates - filterLeaks returns clean metadata unchanged"
  ) {
    val clean: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap(
        "Name" -> TreeSet(StringOrPair("clean value")),
        "Description" -> TreeSet(StringOrPair("Another safe entry"))
      )
    val result = Certificates.filterLeaks(clean)
    assertEquals(result, clean, "Clean metadata must pass through unchanged")
  }

  /** Verify a keystore with a private-key entry produces cert metadata only (no
    * private key material leaks in values) and does not crash.
    */
  test(
    "Certificates - keystore with private key entry produces cert metadata only"
  ) {
    // Build an empty JKS keystore; in production a keystore with a private-key
    // entry would be loaded with null password (fails for key entries), falling
    // back to envelope-only (Keystore(None, ...)). For this unit test we use an
    // empty loaded keystore and verify metadata values contain no private-key
    // PEM banners or DER prefixes.
    import java.security.KeyStore
    val ks = KeyStore.getInstance("JKS")
    ks.load(null, null)
    val emptyKs = Certificates.Keystore(Some(ks), "jks", 0)
    val wrapper = java.io.File.createTempFile("test", ".jks")
    wrapper.deleteOnExit()
    val aw = io.spicelabs.goatrodeo.util
      .FileWrapper(wrapper, wrapper.getAbsolutePath, None)
    val state = new CertificatesState(aw, Some(emptyKs))
    val item = io.spicelabs.goatrodeo.omnibor.Item(
      identifier = "gitoid:test",
      connections = scala.collection.immutable.TreeSet.empty,
      bodyMimeType = None,
      body = None
    )
    val (meta, _) =
      state.getMetadata(aw, item, io.spicelabs.goatrodeo.omnibor.SingleMarker())
    val allValues = meta.values.flatten.map(_.value.toLowerCase)
    assert(
      !allValues.exists(_.contains("-----begin private key-----"))
    )
    assert(
      !allValues.exists(_.contains("-----begin rsa private key-----"))
    )
    assert(
      !allValues.exists(_.contains("miievqibadan")),
      "Metadata values must not contain private-key DER base64 prefix"
    )
  }

  /** META: verify every forbidden pattern is enforced by filterLeaks. */
  test(
    "[HOSTILE REVIEWER META] every Appendix-C pattern is enforced by filterLeaks (programmatic check)"
  ) {
    val patterns = Certificates.forbiddenPatterns.map(_.pattern)
    assertEquals(
      patterns.length,
      10,
      "Appendix C lists 8 forbidden patterns plus 2 cloud/db-encryption patterns; strategy must have 10"
    )
    val missing = patterns.filterNot(patternSamples.contains)
    assert(
      missing.isEmpty,
      s"patternSamples is missing entries for: ${missing.mkString(", ")} " +
        s"— add a sample literal that matches the pattern so this " +
        s"test can verify filterLeaks catches it"
    )
    patterns.foreach { pat =>
      val sample = patternSamples(pat)
      val planted: TreeMap[String, TreeSet[StringOrPair]] =
        TreeMap[String, TreeSet[StringOrPair]](
          s"Certificates:LeakTestProgrammatic" ->
            TreeSet(StringOrPair(sample))
        )
      val result = Certificates.filterLeaks(planted)
      assert(
        !result.contains("Certificates:LeakTestProgrammatic"),
        s"filterLeaks did not remove entry matching pattern /$pat/ " +
          s"with sample '$sample'; this means the leak guard is missing or broken"
      )
    }
  }

  /** Verify the strategy's forbiddenPatterns is a superset of Appendix C (all
    * Appendix-C patterns enforced), plus the cloud/db-encryption patterns.
    */
  test(
    "[LEAK SWEEP META] strategy's forbiddenPatterns covers Appendix C plus cloud/db patterns"
  ) {
    val strategyPatterns = Certificates.forbiddenPatterns.map(_.pattern).toSet
    val appendixPatterns = appendixCPatterns.map(_.pattern).toSet
    assert(
      appendixPatterns.subsetOf(strategyPatterns),
      "Certificates.forbiddenPatterns must include every Appendix C pattern"
    )
    assertEquals(
      Certificates.forbiddenPatterns.length,
      10,
      "Appendix C (8) plus cloud/db-encryption patterns (2); strategy must have 10"
    )
  }
}
