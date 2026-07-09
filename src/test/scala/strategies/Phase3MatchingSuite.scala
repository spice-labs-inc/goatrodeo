/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import munit.FunSuite
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/* Phase 3: Matching Improvement
 *
 * '''What this suite tests:'''
 *   - GAP-9: Replace bidirectional `contains` matching with exact match
 *     (priority 1) > prefix match with separator (priority 2) > reverse
 *     prefix match (priority 3) > None.
 *   - Security: No short artifactId can hijack the canonical pURL of a
 *     JAR with a longer, more specific artifactId.
 *   - `filenameArt` extraction bug: `takeWhile(_ != '.')` produces
 *     `"guava-33"` for `guava-33.0.0-jre.jar`. Fix uses
 *     `extractIdentityFromFilename` for consistency.
 *   - DoS guard: pom.properties values > 1024 chars are skipped.
 *
 * '''LLM context:'''
 *   Tests 3.1-3.5 are direct unit tests of `determinePrimaryGroupIdArtifactIdVersion`.
 *   Tests 3.6-3.7 are edge case unit tests (empty/short filenameArt).
 *   Tests 3.8-3.9 are edge case tests (extraction, separator requirement).
 *   Test 3.10 is a tie-breaking unit test (longest prefix preferred).
 *   Tests 3.10b-3.10c test the reverse-prefix (score 1) path.
 *   Tests 3.11-3.13 are pipeline-level integration tests.
 *   Property tests 3.14-3.15 are in Phase3MatchingPropertySuite.
 */

class Phase3MatchingSuite extends FunSuite {

  // =========================================================================
  // Test 3.1: Exact match preferred over substring
  // =========================================================================
  //
  // What it tests:
  //   When multiple embedded pom.properties entries exist, the one whose
  //   artifactId exactly matches the filename-derived artifact name is
  //   selected as primary.
  //
  // Why it's relevant:
  //   Exact match is unambiguous. Substring match can select the wrong
  //   package (e.g., "core" matches "coreutils").
  //
  // Requirement section:
  //   GAP-9 — "Replace with exact match as primary."
  //
  // Theory:
  //   Call determinePrimaryGroupIdArtifactIdVersion with two tuples:
  //   - ("org.fake", "core", "1.0")
  //   - ("org.real", "coreutils", "2.0")
  //   filenameArt = "coreutils". Assert the method returns
  //   ("org.real", "coreutils", "2.0") (exact match), not
  //   ("org.fake", "core", "1.0") (substring match).
  //
  // Expected (RED):
  //   Current code matches "core" first via bidirectional contains
  //   ("coreutils".contains("core") is true).
  //
  // Expected (GREEN):
  //   Exact match "coreutils" is selected.

  test("Test 3.1: Exact match preferred over substring") {
    val state = MavenState()
    val tuples = Vector(
      ("org.fake", "core", "1.0"),
      ("org.real", "coreutils", "2.0")
    )
    val result =
      state.determinePrimaryGroupIdArtifactIdVersion(tuples, "coreutils")
    assertEquals(
      result,
      Some(("org.real", "coreutils", "2.0")),
      "Exact match 'coreutils' must be selected, not substring match 'core'"
    )
  }

  // =========================================================================
  // Test 3.2: Prefix match as fallback
  // =========================================================================
  //
  // What it tests:
  //   When no exact match exists, the artifactId that is a prefix of the
  //   filename (followed by a version separator '-' or '_') is selected.
  //
  // Why it's relevant:
  //   Some artifactIds have suffixes in the filename (e.g., "guava" vs
  //   "guava-33.0.0-jre"). Prefix match handles this.
  //
  // Requirement section:
  //   GAP-9 — "with prefix match as fallback."
  //
  // Theory:
  //   Call with tuples:
  //   - ("com.google.guava", "guava", "33.0.0-jre")
  //   - ("org.other", "other", "1.0")
  //   filenameArt = "guava". No exact match for "guava" in the filename.
  //   Wait — "guava" IS an exact match for filenameArt "guava". Let me
  //   rethink. The filenameArt is extracted from the JAR filename. For
  //   "guava-33.0.0-jre.jar", extractIdentityFromFilename produces
  //   artifactId="guava". So filenameArt="guava". And the pom.properties
  //   has artifactId="guava". This is an exact match, not a prefix match.
  //
  //   To test prefix match, we need filenameArt that is LONGER than the
  //   artifactId. For example, filenameArt="guava-jre" and artifactId="guava".
  //   This would happen if the version separator logic doesn't strip "-jre".
  //
  //   Actually, with the fix, filenameArt is extracted by
  //   extractIdentityFromFilename which correctly splits at the first
  //   '-' followed by a digit. So "guava-33.0.0-jre.jar" → "guava".
  //   The prefix match is needed when filenameArt doesn't exactly match
  //   any artifactId but one artifactId is a prefix of it.
  //
  //   A real scenario: JAR named "mylib-1.0.jar" with pom.properties
  //   artifactId="mylib". After extraction, filenameArt="mylib" — exact
  //   match. Prefix match is needed when the extraction fails or when
  //   the filename doesn't follow standard Maven naming.
  //
  //   Let me use: filenameArt="spring-core" with artifactId="spring".
  //   "spring" is a prefix of "spring-core" followed by "-". This
  //   tests the prefix match fallback.

  test("Test 3.2: Prefix match as fallback when no exact match") {
    val state = MavenState()
    val tuples = Vector(
      ("org.other", "other", "1.0"),
      ("org.spring", "spring", "5.3")
    )
    // filenameArt = "spring-core" — no exact match, but "spring" is
    // a prefix followed by '-'
    val result =
      state.determinePrimaryGroupIdArtifactIdVersion(tuples, "spring-core")
    assertEquals(
      result,
      Some(("org.spring", "spring", "5.3")),
      "Prefix match 'spring' should be selected for filenameArt 'spring-core'"
    )
  }

  // =========================================================================
  // Test 3.3: No pURL hijacking via short artifactId
  // =========================================================================
  //
  // What it tests:
  //   A malicious dependency with artifactId="commons" does NOT hijack
  //   the canonical pURL of "commons-collections4".
  //
  // Why it's relevant:
  //   This is the security vulnerability identified in the security review.
  //   A short artifactId placed first in the list could match via
  //   bidirectional contains and hijack the canonical pURL.
  //
  // Requirement section:
  //   GAP-9 — security vulnerability.
  //
  // Theory:
  //   Call with tuples where "commons" appears FIRST:
  //   - ("org.evil", "commons", "1.0")
  //   - ("org.apache.commons", "commons-collections4", "4.4")
  //   filenameArt = "commons-collections4". Assert the method returns
  //   the exact match ("org.apache.commons", "commons-collections4", "4.4"),
  //   NOT the substring match ("org.evil", "commons", "1.0").
  //
  // Expected (RED):
  //   Current code matches "commons" first (substring:
  //   "commons-collections4".contains("commons") is true).
  //
  // Expected (GREEN):
  //   Exact match "commons-collections4" is selected.

  test("Test 3.3: No pURL hijacking via short artifactId") {
    val state = MavenState()
    val tuples = Vector(
      ("org.evil", "commons", "1.0"),
      ("org.apache.commons", "commons-collections4", "4.4")
    )
    val result = state.determinePrimaryGroupIdArtifactIdVersion(
      tuples,
      "commons-collections4"
    )
    assertEquals(
      result,
      Some(("org.apache.commons", "commons-collections4", "4.4")),
      "Exact match 'commons-collections4' must be selected, not hijacked by 'commons'"
    )
  }

  // =========================================================================
  // Test 3.4: No match returns None
  // =========================================================================
  //
  // What it tests:
  //   When no embedded pom.properties artifactId matches the filename
  //   (exactly or by prefix), the method returns None.
  //
  // Why it's relevant:
  //   If no match, the canonical pURL falls back to the full resolution
  //   chain. The method should not force a match.
  //
  // Requirement section:
  //   REQ-2 — "If no embedded groupId/artifactId/version matches the
  //   filename, the canonical pURL is derived from the full resolution
  //   chain."
  //
  // Theory:
  //   Call with a tuple that doesn't match:
  //   - ("org.unrelated", "completely-different", "1.0")
  //   filenameArt = "myapp". Assert returns None.

  test("Test 3.4: No match returns None") {
    val state = MavenState()
    val tuples = Vector(
      ("org.unrelated", "completely-different", "1.0")
    )
    val result = state.determinePrimaryGroupIdArtifactIdVersion(tuples, "myapp")
    assertEquals(result, None, "No match should return None")
  }

  // =========================================================================
  // Test 3.5: Multiple exact matches — first one wins
  // =========================================================================
  //
  // What it tests:
  //   When multiple embedded packages have the same artifactId (exact
  //   match), the first one is selected.
  //
  // Why it's relevant:
  //   Duplicate artifactIds are rare but possible. The behavior should
  //   be deterministic.
  //
  // Requirement section:
  //   GAP-9.
  //
  // Theory:
  //   Call with two tuples, both with artifactId="foo" but different
  //   groupIds. Assert the first one is selected.

  test("Test 3.5: Multiple exact matches — first one wins") {
    val state = MavenState()
    val tuples = Vector(
      ("org.first", "foo", "1.0"),
      ("org.second", "foo", "2.0")
    )
    val result = state.determinePrimaryGroupIdArtifactIdVersion(tuples, "foo")
    assertEquals(
      result,
      Some(("org.first", "foo", "1.0")),
      "First exact match should be selected"
    )
  }

  // =========================================================================
  // Test 3.6: Empty filenameArt returns None
  // =========================================================================
  //
  // What it tests:
  //   When filenameArt is empty, the method returns None.
  //
  // Why it's relevant:
  //   Guard rail — empty filenameArt should not match anything.
  //
  // Theory:
  //   Call with filenameArt = "". Assert returns None.

  test("Test 3.6: Empty filenameArt returns None") {
    val state = MavenState()
    val tuples = Vector(
      ("org.example", "foo", "1.0")
    )
    val result = state.determinePrimaryGroupIdArtifactIdVersion(tuples, "")
    assertEquals(result, None, "Empty filenameArt should return None")
  }

  // =========================================================================
  // Test 3.7: Short filenameArt (< 2 chars) returns None
  // =========================================================================
  //
  // What it tests:
  //   When filenameArt is a single character, the method returns None.
  //
  // Why it's relevant:
  //   A single-character artifactId is too short to be meaningful.
  //   The minimum length guard prevents trivial matches.

  test("Test 3.7: Single-character filenameArt returns None") {
    val state = MavenState()
    val tuples = Vector(
      ("org.example", "a", "1.0")
    )
    val result = state.determinePrimaryGroupIdArtifactIdVersion(tuples, "a")
    assertEquals(result, None, "Single-char filenameArt should return None")
  }

  // =========================================================================
  // Test 3.8: extractIdentityFromFilename strips version correctly
  // =========================================================================
  //
  // What it tests:
  //   extractIdentityFromFilename("guava-33.0.0-jre.jar") produces
  //   artifactId = "guava", NOT "guava-33".
  //
  // Why it's relevant:
  //   The matching fix depends on correct filenameArt extraction. If
  //   extraction includes version fragments (e.g., "guava-33"), exact
  //   matching never fires because no pom.properties has artifactId
  //   "guava-33".
  //
  // Theory:
  //   Call extractIdentityFromFilename with various filenames. Assert
  //   artifactId is the name without version.

  test("Test 3.8: extractIdentityFromFilename strips version correctly") {
    val state = MavenState()
    // Standard version
    val (g1, a1, v1) =
      state.resolveGroupIdArtifactIdVersionFromFilename("guava-33.0.0-jre.jar")
    assertEquals(
      a1,
      Some("guava"),
      "artifactId should be 'guava' not 'guava-33'"
    )
    assertEquals(v1, Some("33.0.0-jre"))

    // Simple version
    val (_, a2, v2) =
      state.resolveGroupIdArtifactIdVersionFromFilename("foo-1.0.jar")
    assertEquals(a2, Some("foo"))
    assertEquals(v2, Some("1.0"))

    // No version (just extension)
    val (_, a3, v3) =
      state.resolveGroupIdArtifactIdVersionFromFilename("nolibrary.jar")
    assertEquals(a3, None, "No version found should return None for all fields")
    assertEquals(v3, None)

    // Multi-segment artifactId
    val (_, a4, v4) = state.resolveGroupIdArtifactIdVersionFromFilename(
      "commons-collections4-4.4.jar"
    )
    assertEquals(a4, Some("commons-collections4"))
    assertEquals(v4, Some("4.4"))
  }

  // =========================================================================
  // Test 3.9: Prefix match requires separator after matched prefix
  // =========================================================================
  //
  // What it tests:
  //   A prefix match only succeeds if the character after the matched
  //   prefix is a version separator ('-' or '_'). This prevents "spring"
  //   from matching "springframework" (no separator).
  //
  // Why it's relevant:
  //   Without the separator requirement, "spring" would match
  //   "springframework" — a completely different artifact.

  test("Test 3.9: Prefix match requires separator after matched prefix") {
    val state = MavenState()
    val tuples = Vector(
      ("org.spring", "spring", "5.3")
    )
    // "springframework" — "spring" is a prefix but no separator follows
    val result =
      state.determinePrimaryGroupIdArtifactIdVersion(tuples, "springframework")
    assertEquals(
      result,
      None,
      "Prefix match without separator should not match"
    )
  }

  // =========================================================================
  // Test 3.10: Longest prefix match preferred
  // =========================================================================
  //
  // What it tests:
  //   When multiple artifactIds are prefixes of the filename, the longest
  //   (most specific) one is preferred.
  //
  // Why it's relevant:
  //   "spring-core" and "spring" both match "spring-core-5.3". The longer
  //   match is more specific and should be preferred.

  test("Test 3.10: Longest prefix match preferred") {
    val state = MavenState()
    val tuples = Vector(
      ("org.spring", "spring", "5.3"),
      ("org.spring.core", "spring-core", "5.3")
    )
    // filenameArt = "spring-core-5.3" — both "spring" and "spring-core"
    // are prefixes followed by '-'. The longer one should win.
    val result =
      state.determinePrimaryGroupIdArtifactIdVersion(tuples, "spring-core-5.3")
    assertEquals(
      result,
      Some(("org.spring.core", "spring-core", "5.3")),
      "Longest prefix match 'spring-core' should be preferred over 'spring'"
    )
  }

  // =========================================================================
  // Test 3.10b: Reverse prefix match — filename shorter than artifactId
  // =========================================================================
  //
  // What it tests:
  //   When the filename-derived artifact name is SHORTER than an
  //   artifactId in the embedded pom.properties, and the artifactId
  //   starts with the filename followed by a separator ('-' or '_'),
  //   the match scores 1 (reverse prefix). This is the only test that
  //   exercises the score=1 path in matchScore.
  //
  // Why it's relevant:
  //   The reverse-prefix path (score 1) was completely untested. An
  //   off-by-one error in the charAt index would go undetected.
  //
  // Requirement section:
  //   GAP-9 — reverse prefix match (priority 3).
  //
  // Theory:
  //   filenameArt = "spring", artifactId = "spring-core".
  //   "spring-core".startsWith("spring") is true, and
  //   "spring-core".charAt(6) == '-' (separator). Score = 1.
  //   Assert the method returns Some(("org.spring", "spring-core", "5.3")).

  test("Test 3.10b: Reverse prefix match — filename shorter than artifactId") {
    val state = MavenState()
    val tuples = Vector(
      ("org.other", "other", "1.0"),
      ("org.spring", "spring-core", "5.3")
    )
    // filenameArt = "spring" — shorter than artifactId "spring-core"
    // "spring-core".startsWith("spring") is true, char at index 6 is '-'
    val result =
      state.determinePrimaryGroupIdArtifactIdVersion(tuples, "spring")
    assertEquals(
      result,
      Some(("org.spring", "spring-core", "5.3")),
      "Reverse prefix match 'spring-core' should be selected for filenameArt 'spring'"
    )
  }

  // =========================================================================
  // Test 3.10c: Reverse prefix without separator returns None
  // =========================================================================
  //
  // What it tests:
  //   When the filename is a prefix of the artifactId but the character
  //   after the prefix is NOT a separator, the match fails (score 0).
  //   This prevents "spring" from matching "springframework".
  //
  // Why it's relevant:
  //   The separator requirement applies to reverse-prefix matches too,
  //   not just forward-prefix matches. Without this test, an off-by-one
  //   or missing separator check in the score=1 path would go undetected.
  //
  // Requirement section:
  //   GAP-9 — separator requirement for all prefix matches.
  //
  // Theory:
  //   filenameArt = "spring", artifactId = "springframework".
  //   "springframework".startsWith("spring") is true, but
  //   "springframework".charAt(6) == 'f' (not separator). Score = 0.
  //   Assert returns None.

  test("Test 3.10c: Reverse prefix without separator returns None") {
    val state = MavenState()
    val tuples = Vector(
      ("org.spring", "springframework", "5.3")
    )
    // filenameArt = "spring" — "springframework".startsWith("spring") is true
    // but char at index 6 is 'f' (not separator), so score = 0
    val result =
      state.determinePrimaryGroupIdArtifactIdVersion(tuples, "spring")
    assertEquals(
      result,
      None,
      "Reverse prefix without separator should not match"
    )
  }

  // =========================================================================
  // Test 3.11: Pipeline-level — no pURL hijacking via short artifactId
  // =========================================================================
  //
  // What it tests:
  //   A JAR named "commons-collections4-4.4.jar" containing embedded
  //   pom.properties for both "commons" (malicious) and
  //   "commons-collections4" (legitimate) produces a canonical pURL
  //   using "commons-collections4", not "commons".
  //
  // Why it's relevant:
  //   This is the end-to-end security test. If the matching bug allows
  //   hijacking, the canonical pURL will be wrong.

  test("Test 3.11: Pipeline — no pURL hijacking via short artifactId") {
    MavenTestHelpers.withTempDir("test-3-11") { dir =>
      // JAR with two embedded pom.properties — malicious "commons" first
      MavenTestHelpers.writeJar(
        dir,
        "commons-collections4-4.4.jar",
        Seq(
          "META-INF/maven/org.evil/commons/pom.properties" ->
            """groupId=org.evil
              |artifactId=commons
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.apache.commons/commons-collections4/pom.properties" ->
            """groupId=org.apache.commons
              |artifactId=commons-collections4
              |version=4.4
              |""".stripMargin,
          "org/apache/commons/collections4/Utils.class" -> "CAFEBABE"
        )
      )
      // Companion POM with correct coordinates
      MavenTestHelpers.writePom(
        dir,
        "commons-collections4-4.4.pom",
        "org.apache.commons",
        "commons-collections4",
        "4.4"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)

      // Find the canonical pURL — it should use org.apache.commons,
      // not org.evil. The secondary pURL org.evil/commons@1.0 is
      // legitimate (it's an embedded package found inside the JAR)
      // and may appear in the store.
      val canonicalPurls = store
        .keys()
        .flatMap { key =>
          store
            .read(key)
            .flatMap(_.bodyAsItemMetaData)
            .flatMap(_.extra.get(MetadataKeyConstants.CANONICAL_PURL))
        }
        .flatMap(_.toSeq.map(_.value))
        .toSet

      val hasEvilCanonical = canonicalPurls.exists(_.contains("org.evil"))
      val hasCorrectCanonical =
        canonicalPurls.exists(_.contains("org.apache.commons"))

      assert(
        !hasEvilCanonical,
        s"Canonical pURL should NOT contain org.evil (hijacked by short artifactId).\nCanonical pURLs: $canonicalPurls"
      )
      assert(
        hasCorrectCanonical,
        s"Canonical pURL should contain org.apache.commons.\nCanonical pURLs: $canonicalPurls"
      )
    }
  }

  // =========================================================================
  // Test 3.12: Pipeline — filenameArt extraction uses extractIdentityFromFilename
  // =========================================================================
  //
  // What it tests:
  //   A JAR named "guava-33.0.0-jre.jar" with embedded pom.properties
  //   artifactId="guava" is correctly matched (exact match) because
  //   filenameArt is extracted as "guava", not "guava-33".
  //
  // Why it's relevant:
  //   If filenameArt were "guava-33" (from takeWhile), no pom.properties
  //   artifactId would match exactly. The fix ensures correct extraction.

  test(
    "Test 3.12: Pipeline — filenameArt extraction correct for dotted versions"
  ) {
    MavenTestHelpers.withTempDir("test-3-12") { dir =>
      MavenTestHelpers.writeJar(
        dir,
        "guava-33.0.0-jre.jar",
        Seq(
          "META-INF/maven/com.google.guava/guava/pom.properties" ->
            """groupId=com.google.guava
              |artifactId=guava
              |version=33.0.0-jre
              |""".stripMargin,
          "com/google/common/collect/Lists.class" -> "CAFEBABE"
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "guava-33.0.0-jre.pom",
        "com.google.guava",
        "guava",
        "33.0.0-jre"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // The pURL should use guava (not guava-33)
      val hasGuava = purls.exists(p => p.contains("/guava@"))
      assert(
        hasGuava,
        s"pURL should contain '/guava@' (correct artifactId).\npURLs: $purls"
      )
    }
  }

  // =========================================================================
  // Test 3.13: DoS guard — pom.properties values > 1024 chars are skipped
  // =========================================================================
  //
  // What it tests:
  //   When pom.properties contains a value > 1024 characters, that
  //   entry is silently skipped (not used for pURL construction).
  //
  // Why it's relevant:
  //   Security — pom.properties values flow directly into pURL construction.
  //   A malicious JAR could include extremely long values to cause memory
  //   issues or generate pathological pURLs.

  test(
    "Test 3.13: DoS guard — pom.properties values > 1024 chars are skipped"
  ) {
    MavenTestHelpers.withTempDir("test-3-13") { dir =>
      val longValue = "A" * 2000
      MavenTestHelpers.writeJar(
        dir,
        "mylib-1.0.jar",
        Seq(
          "META-INF/maven/org.evil/mylib/pom.properties" ->
            s"""groupId=$longValue
               |artifactId=mylib
               |version=1.0
               |""".stripMargin,
          "org/example/MyLib.class" -> "CAFEBABE"
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "mylib-1.0.pom",
        "org.example",
        "mylib",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // The long groupId should NOT appear in any pURL
      val hasLongValue = purls.exists(_.contains(longValue))
      assert(
        !hasLongValue,
        s"pURL should NOT contain the 2000-char value.\npURLs: $purls"
      )
    }
  }
}

/* Property-based tests for Phase 3 matching improvement.
 *
 * '''What this suite tests:'''
 *   - Property 3.14: For any set of tuples where one exactly matches
 *     the filename, the exact match is always selected.
 *   - Property 3.15: For any pair where one artifactId is a substring of
 *     another, the longer (more specific) one is preferred when it
 *     matches the filename.
 */
class Phase3MatchingPropertySuite extends ScalaCheckSuite {

  private val genArtifactId: Gen[String] = for {
    prefix <- Gen.oneOf(
      "core",
      "commons",
      "spring",
      "guava",
      "mylib",
      "foo",
      "bar"
    )
    suffix <- Gen.oneOf("", "-core", "-utils", "-collections4", "-api")
  } yield prefix + suffix

  private val genVersion: Gen[String] = Gen.choose(1, 99).map(n => s"$n.0")

  private val genTuple: Gen[(String, String, String)] = for {
    g <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    a <- genArtifactId
    v <- genVersion
  } yield (g, a, v)

  // =========================================================================
  // Property 3.14: Exact match always preferred over substring
  // =========================================================================
  //
  // What it tests:
  //   For any set of embedded groupId/artifactId/version tuples where one
  //   exactly matches the filename, the exact match is always selected.
  //
  // Requirement section:
  //   GAP-9.
  //
  // Theory:
  //   ScalaCheck generates a set of tuples where one artifactId exactly
  //   matches a generated filename. Assert the exact match is selected
  //   regardless of other tuples in the set.

  property("Property 3.14: Exact match always preferred over substring") {
    forAll(genTuple, Gen.listOf(genTuple)) { (exact, others) =>
      val state = MavenState()
      val filenameArt = exact._2
      // Ensure no other tuple has the same artifactId
      val uniqueOthers = others.filterNot(_._2 == filenameArt)
      val tuples = exact +: uniqueOthers
      val result = state.determinePrimaryGroupIdArtifactIdVersion(
        tuples.toVector,
        filenameArt
      )
      result == Some(exact)
    }
  }

  // =========================================================================
  // Property 3.15: No short artifactId hijacks a longer one
  // =========================================================================
  //
  // What it tests:
  //   For any set of tuples where one artifactId is a substring of another,
  //   the longer (more specific) artifactId is preferred when it matches
  //   the filename.
  //
  // Requirement section:
  //   GAP-9 — security.
  //
  // Theory:
  //   ScalaCheck generates pairs where art1 is a substring of art2 and
  //   filenameArt == art2. Assert art2 is selected (exact match wins
  //   over substring match).

  property("Property 3.15: Exact match wins over substring match") {
    forAll(genArtifactId, Gen.listOf(genArtifactId), genVersion) {
      (baseArt, suffixes, version) =>
        // Create a longer artifactId by appending a suffix
        val longerArt = suffixes.headOption match {
          case Some(s) if s.nonEmpty && !baseArt.contains(s) => baseArt + s
          case _ => baseArt + "-extended"
        }
        // Skip if the longer art equals baseArt (shouldn't happen)
        if (longerArt == baseArt) true
        else {
          val state = MavenState()
          val tuples = Vector(
            ("org.short", baseArt, "1.0"), // short, substring of longerArt
            ("org.long", longerArt, version) // exact match for filenameArt
          )
          val result =
            state.determinePrimaryGroupIdArtifactIdVersion(tuples, longerArt)
          result == Some(("org.long", longerArt, version))
        }
    }
  }
}
