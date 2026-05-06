/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.{Item, ItemMetaData, SingleMarker, StringOrPair}
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File
import java.util.regex.Pattern
import scala.collection.immutable.{TreeMap, TreeSet}

/** Phase 8 — corpus-wide private-key leak sweep + hostile-reviewer
  * sentinel check.
  *
  * Per the plan (`certificates-strategy/phases-8-9-tests-docs.md`
  * lines 33-37):
  *
  * > Run all fixture tests and property-based tests, collect every
  * > Item produced, then sweep every metadata value and every Item
  * > body (if any) against `appendices.md` Appendix C's forbidden-
  * > pattern regexes. Zero matches allowed. One match = test failure
  * > with a clear message identifying which fixture, which Item,
  * > which metadata key, and which pattern matched.
  *
  * > This suite runs **last** so it has all Items to check. It's the
  * > single most important test in the strategy — the no-leak
  * > invariant is a hard rule.
  *
  * The hostile-reviewer sentinel check (per HS-2 step 3 in the plan):
  *
  * > Introduce a sentinel leak (add `"-----BEGIN RSA PRIVATE KEY-----test"`
  * > as a metadata value in a feature branch), confirm the leak suite
  * > catches it, then remove the sentinel. Without this check, "the
  * > leak suite passes" is not evidence the leak suite works.
  *
  * Implementation: the sentinel test injects the forbidden string
  * into a synthetic metadata table IN-MEMORY (no fixture / committed
  * code carries the sentinel) and asserts `assertNoLeak` raises. This
  * provides the "leak suite plumbing actually fires" evidence without
  * shipping the leak risk.
  */
class CertificatesLeakSuite extends FunSuite {

  // Appendix C forbidden patterns — independent copy from
  // certificates-strategy/appendices.md so the test suite verifies
  // the strategy's `forbiddenPatterns` against the source-of-truth
  // list, not against itself.
  private val appendixCPatterns: Seq[Pattern] = Seq(
    "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "-----BEGIN PGP PRIVATE KEY BLOCK-----",
    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----",
    "MIIEvQIBADAN",
    "MIIEpAIBAAKCAQEA",
    "MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+",
    "openssh-key-v1",
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
        extra = TreeMap.empty,
      )
    ),
  )

  /** Walk every fixture in the corpus, run it through the strategy,
    * collect emitted metadata, and sweep against the forbidden-pattern
    * regex list. */
  test("[LEAK SWEEP] zero forbidden-pattern matches across the entire corpus") {
    val corpusRoot = java.nio.file.Paths.get("test_data/certificates")
    if (!java.nio.file.Files.exists(corpusRoot)) {
      fail("test_data/certificates does not exist; corpus is required for leak sweep")
    }
    import scala.jdk.CollectionConverters.*
    val artifacts = java.nio.file.Files.walk(corpusRoot).iterator().asScala
      .filter(p => java.nio.file.Files.isRegularFile(p))
      .filter(p => !p.toString.endsWith(".expected.json"))
      .filter(p => !p.toString.contains("/tools/"))
      .filter(p => !p.toString.endsWith("/SOURCES.md"))
      .filter(p => !p.toString.endsWith("/generate.sh"))
      .filter(p => !p.toString.endsWith("/.gitattributes"))
      .filter(p => !p.toString.endsWith("/README.md"))
      .toVector

    assert(artifacts.nonEmpty, "corpus walk found zero artifacts — fixture-discovery bug")

    val violations = scala.collection.mutable.ListBuffer[String]()
    var checked = 0
    artifacts.foreach { path =>
      val w = FileWrapper(new File(path.toString), path.toString, None)
      val claimOpt = scala.util.Try(Certificates.classifyAndParse(w)).toOption.flatten
      claimOpt.foreach { claim =>
        val state = new CertificatesState(w, Some(claim))
        scala.util.Try {
          val (md, _) = state.getMetadata(w, stubItem(), SingleMarker())
          checked += 1
          // Sweep every metadata value.
          md.foreach { case (key, values) =>
            values.foreach { v =>
              val text = v match {
                case io.spicelabs.goatrodeo.omnibor.StringOf(s)   => s
                case io.spicelabs.goatrodeo.omnibor.PairOf(_, s2) => s2
              }
              appendixCPatterns.foreach { pat =>
                if (pat.matcher(text).find()) {
                  violations += s"FIXTURE=${path} KEY=$key PATTERN=/${pat.pattern}/ VALUE=$text"
                }
              }
            }
          }
        }.recover {
          case ex: RuntimeException
              if Option(ex.getMessage).exists(_.contains("Certificates leak guard")) =>
            // EXPECTED: the strategy's `assertNoLeak` correctly fired.
            // This is the strategy doing its job; the outer leak suite
            // is a second-line defense and shouldn't flag a fixture
            // where the strategy already raised.
            ()
          case ex: RuntimeException =>
            // UNEXPECTED: a non-leak-guard RuntimeException slipped through.
            // (NPE in metadata builder, ClassCastException, BC parser
            // failure, etc.) Record as a violation — A3 in the v2 review:
            // the recover used to swallow these, hiding bugs.
            violations += s"FIXTURE=${path} UNEXPECTED-CRASH: ${ex.getClass.getSimpleName}: ${ex.getMessage}"
        }
      }
    }
    assert(checked > 0,
      s"expected to check at least 1 fixture's metadata; only walked artifacts=${artifacts.length}")
    if (violations.nonEmpty) {
      fail(s"forbidden-pattern leak detected (${violations.length} violations):\n  " +
           violations.take(10).mkString("\n  "))
    }
  }

  /** HOSTILE-REVIEWER SENTINEL CHECK. Per the Phase 8 HS-2 step 3:
    * "introduce a sentinel leak, confirm the leak suite catches it,
    * then remove the sentinel."
    *
    * We inject the sentinel IN-MEMORY only — no fixture or committed
    * code carries it. The test asserts the strategy's `assertNoLeak`
    * raises on the planted leak. If this test passes, the leak-sweep
    * plumbing is genuinely live; if it fails, the sweep is broken
    * and the previous "[LEAK SWEEP]" passing is meaningless. */
  test("[HOSTILE REVIEWER] assertNoLeak fires when a sentinel leak is planted (RSA private-key banner)") {
    val sentinel = "-----BEGIN RSA PRIVATE KEY-----test"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestSentinel" ->
          TreeSet(StringOrPair(sentinel))
      )
    val ex = intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
    val msg = ex.getMessage
    assert(msg.contains("Certificates leak guard"),
      s"expected leak-guard message; got: $msg")
    assert(msg.contains("Certificates:LeakTestSentinel"),
      s"expected violating-key name in message; got: $msg")
  }

  /** Companion sentinel: PKCS#8 private-key DER prefix encoded as
    * base64 (the kind of leak that a `.toString()` on a parsed
    * PrivateKey could produce). Verifies the base64-prefix regexes
    * are live. */
  test("[HOSTILE REVIEWER] assertNoLeak fires on a PKCS#8 base64-prefix sentinel") {
    val sentinel = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcw" // matches MIIEvQIBADAN
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPkcs8" ->
          TreeSet(StringOrPair(sentinel))
      )
    intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
  }

  /** Companion sentinel: long-hex on a non-allowlisted key. Verifies
    * the Appendix-C long-hex sweep (Phase 7 extension) is live. */
  test("[HOSTILE REVIEWER] assertNoLeak fires on a 32+ char hex run on a non-allowlisted key") {
    // 64 hex chars — could be an Ed25519 private seed or a SHA-256
    // fingerprint. The allowlist permits long-hex on
    // Certificates:SpkiSha256 etc.; on a non-allowlisted key, it must
    // be rejected.
    val sentinel = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestRawHex" ->
          TreeSet(StringOrPair(sentinel))
      )
    val ex = intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
    val msg = ex.getMessage
    assert(msg.contains("32+ char lowercase-hex run") ||
           msg.contains("long-hex allowlist"),
      s"expected long-hex-allowlist message; got: $msg")
  }

  /** Companion sentinel: confirm the allowlist DOESN'T fire on
    * legitimately-long-hex values on allowlisted keys. */
  test("[HOSTILE REVIEWER] assertNoLeak does NOT fire on long-hex on Certificates:SpkiSha256") {
    val sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val ok: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:SpkiSha256" -> TreeSet(StringOrPair(sha256))
      )
    Certificates.assertNoLeak(ok) // must not throw
  }

  /** Companion sentinel: openssh-key-v1 magic must be caught. */
  test("[HOSTILE REVIEWER] assertNoLeak fires on openssh-key-v1 magic in metadata") {
    val sentinel = "openssh-key-v1\u0000abcdef"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestOpensshMagic" ->
          TreeSet(StringOrPair(sentinel))
      )
    intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
  }

  /** Sentinel for PKCS#8-encrypted banner (Appendix C pattern #2). */
  test("[HOSTILE REVIEWER] assertNoLeak fires on PKCS#8 ENCRYPTED PRIVATE KEY banner") {
    val sentinel = "-----BEGIN ENCRYPTED PRIVATE KEY-----xyz"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPkcs8EncryptedBanner" ->
          TreeSet(StringOrPair(sentinel))
      )
    intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
  }

  /** Sentinel for PGP private-key-block banner (Appendix C pattern #3). */
  test("[HOSTILE REVIEWER] assertNoLeak fires on PGP PRIVATE KEY BLOCK banner") {
    val sentinel = "-----BEGIN PGP PRIVATE KEY BLOCK-----xyz"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPgpPrivateBanner" ->
          TreeSet(StringOrPair(sentinel))
      )
    intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
  }

  /** Sentinel for full-PEM-body regex (Appendix C pattern #4). The
    * pattern is a multi-line regex — `[\s\S]+?` matches any char
    * including newlines. The sentinel is a complete fake PEM body. */
  test("[HOSTILE REVIEWER] assertNoLeak fires on a complete PEM private-key body (BEGIN/.../END)") {
    val sentinel =
      "-----BEGIN RSA PRIVATE KEY-----\n" +
      "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ\n" +
      "-----END RSA PRIVATE KEY-----"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestFullPemBody" ->
          TreeSet(StringOrPair(sentinel))
      )
    intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
  }

  /** Sentinel for the SECOND PKCS#8 base64 prefix `MIIEpAIBAAKCAQEA`
    * (Appendix C pattern #6). My original sentinel covered only
    * `MIIEvQIBADAN`. */
  test("[HOSTILE REVIEWER] assertNoLeak fires on MIIEpAIBAAKCAQEA PKCS#8 prefix") {
    val sentinel = "MIIEpAIBAAKCAQEAxyz"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPkcs8Prefix2" ->
          TreeSet(StringOrPair(sentinel))
      )
    intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
  }

  /** Sentinel for the regex-shaped PKCS#8 prefix
    * `MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+` (Appendix C pattern #7).
    * Construct a string matching the pattern. */
  test("[HOSTILE REVIEWER] assertNoLeak fires on regex-shaped MIIB...QIB... PKCS#8 prefix") {
    val sentinel = "MIIBabcdefghQIBxyz"
    val planted: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]](
        "Certificates:LeakTestPkcs8RegexPrefix" ->
          TreeSet(StringOrPair(sentinel))
      )
    intercept[RuntimeException] {
      Certificates.assertNoLeak(planted)
    }
  }

  /** Robust meta-coverage: rather than reading our own source file
    * and counting comment markers (brittle — A2 in the v2 review),
    * we directly verify that EVERY pattern in `Certificates.forbiddenPatterns`
    * triggers the leak guard when a sample matching value is planted.
    * If a pattern were silently dropped, this test fires.
    *
    * For each pattern, we construct a minimal sample that's
    * guaranteed to match. Patterns are regex-shaped so we hand-pick
    * a literal that satisfies each. */
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
  )

  test("[HOSTILE REVIEWER META] every Appendix-C pattern is enforced by the leak guard (programmatic check)") {
    val patterns = Certificates.forbiddenPatterns.map(_.pattern)
    assertEquals(patterns.length, 8,
      "Appendix C lists 8 forbidden patterns; strategy must have 8")
    // Verify the patternSamples map covers every strategy pattern.
    val missing = patterns.filterNot(patternSamples.contains)
    assert(missing.isEmpty,
      s"patternSamples is missing entries for: ${missing.mkString(", ")} " +
      s"— add a sample literal that matches the pattern so this " +
      s"test can verify the leak guard catches it")
    // For each pattern, plant the sample and confirm assertNoLeak fires.
    patterns.foreach { pat =>
      val sample = patternSamples(pat)
      val planted: TreeMap[String, TreeSet[StringOrPair]] =
        TreeMap[String, TreeSet[StringOrPair]](
          s"Certificates:LeakTestProgrammatic" ->
            TreeSet(StringOrPair(sample))
        )
      val ex = scala.util.Try(Certificates.assertNoLeak(planted))
      assert(ex.isFailure,
        s"assertNoLeak did not fire on pattern /$pat/ with sample '$sample'; " +
        s"this means the leak guard is missing or broken for that pattern")
    }
  }

  /** Verify the strategy's forbiddenPatterns list matches Appendix C
    * exactly (count + each pattern). Catches "we silently dropped a
    * forbidden pattern" regressions. */
  test("[LEAK SWEEP META] strategy's forbiddenPatterns matches Appendix C exactly") {
    val strategyPatterns = Certificates.forbiddenPatterns.map(_.pattern).toSet
    val appendixPatterns = appendixCPatterns.map(_.pattern).toSet
    assertEquals(strategyPatterns, appendixPatterns,
      "Certificates.forbiddenPatterns must mirror Appendix C exactly")
    assertEquals(Certificates.forbiddenPatterns.length, 8,
      "Appendix C lists 8 forbidden patterns; strategy must have 8")
  }
}
