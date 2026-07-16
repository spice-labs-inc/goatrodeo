/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies
import munit.FunSuite
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.io.File

/* Phase 4: Secondary pURL Classifier Fix
 *
 * '''What this suite tests:'''
 *   - REQ-1: ALL pURLs from sources/javadoc JARs include the classifier
 *     (?packaging=sources or ?classifier=javadoc) — not just the canonical,
 *     but every secondary pURL too.
 *   - GAP-3: Secondary pURLs do NOT use filename (guard test)
 *   - GAP-4: Fix sources/javadoc secondary pURL classifiers
 *   - Deduplication: duplicate pom.properties tuples produce one pURL
 *   - Error isolation: malformed pom.properties does not prevent valid pURLs
 *   - False positive guard: regular JAR with "sources" in name doesn't
 *     get ?packaging=sources on secondary pURLs
 *
 * '''LLM context:'''
 *   The bug was at Maven.scala:1487 — secondary pURLs always passed `None`
 *   as the classifier to `buildPackageURL`, even for sources/javadoc JARs.
 *   The fix passes the same `classifier` variable used for the canonical
 *   pURL.
 */
class Phase4SecondaryClassifierSuite extends FunSuite {

  // =========================================================================
  // Test 4.1: All pURLs from sources JAR have sources classifier
  // =========================================================================
  //
  // What it tests:
  //   When a sources JAR with 3 embedded pom.properties entries is
  //   processed, ALL emitted pURLs contain ?packaging=sources — not just
  //   the canonical, but every secondary pURL too.
  //
  // Why it's relevant:
  //   REQ-1 — "ALL pURLs from a sources JAR include ?packaging=sources."
  //   Distinguishing between gitoids that represent class files vs
  //   documentation is critical.
  //
  // Requirement section:
  //   REQ-1, details on sources/javadoc classifier.
  //
  // Theory:
  //   Create temp dir with sources JAR containing 3 pom.properties entries
  //   + companion POM. Process through pipeline. Collect all pURLs from
  //   store. Assert purls.size >= 3 AND every pURL contains
  //   ?packaging=sources.

  test("Test 4.1: All pURLs from sources JAR have sources classifier") {
    MavenTestHelpers.withTempDir("test-4-1") { dir =>
      MavenTestHelpers.writeJar(
        dir,
        "lib-1.0-sources.jar",
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            """groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.other/dep/pom.properties" ->
            """groupId=org.other
              |artifactId=dep
              |version=2.0
              |""".stripMargin,
          "META-INF/maven/org.third/utils/pom.properties" ->
            """groupId=org.third
              |artifactId=utils
              |version=3.0
              |""".stripMargin
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "lib-1.0-sources.pom",
        "com.example",
        "lib",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filter out the POM marker pURL (which has ?type=pom)
      val jarPurls = purls.filterNot(_.contains("type=pom"))

      assert(
        jarPurls.size >= 3,
        s"Expected at least 3 pURLs, got ${jarPurls.size}: $jarPurls"
      )
      assert(
        jarPurls.forall(PurlParts.hasQualifier(_, "packaging", "sources")),
        s"All pURLs from sources JAR must have ?packaging=sources.\npURLs: $jarPurls"
      )
    }
  }

  // =========================================================================
  // Test 4.2: All pURLs from javadoc JAR have javadoc classifier
  // =========================================================================
  //
  // What it tests:
  //   Same as 4.1 but for javadoc JAR with ?classifier=javadoc.
  //
  // Requirement section:
  //   REQ-1 — "ALL pURLs from a javadoc JAR include ?classifier=javadoc."

  test("Test 4.2: All pURLs from javadoc JAR have javadoc classifier") {
    MavenTestHelpers.withTempDir("test-4-2") { dir =>
      MavenTestHelpers.writeJar(
        dir,
        "lib-1.0-javadoc.jar",
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            """groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.other/dep/pom.properties" ->
            """groupId=org.other
              |artifactId=dep
              |version=2.0
              |""".stripMargin
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "lib-1.0-javadoc.pom",
        "com.example",
        "lib",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filter out the POM marker pURL
      val jarPurls = purls.filterNot(_.contains("type=pom"))

      assert(
        jarPurls.size >= 2,
        s"Expected at least 2 pURLs, got ${jarPurls.size}: $jarPurls"
      )
      assert(
        jarPurls.forall(PurlParts.hasQualifier(_, "classifier", "javadoc")),
        s"All pURLs from javadoc JAR must have ?classifier=javadoc.\npURLs: $jarPurls"
      )
    }
  }

  // =========================================================================
  // Test 4.3: Secondary pURLs do NOT use filename (guard test)
  // =========================================================================
  //
  // What it tests:
  //   Secondary pURLs come exclusively from pom.properties — never from
  //   the filename. This is a regression guard.
  //
  // Why it's relevant:
  //   GAP-3 was already resolved in the current code, but without a test,
  //   a future change could reintroduce the bug.
  //
  // Requirement section:
  //   REQ-1 — "Secondary pURLs must NOT use the filename."

  test("Test 4.3: Secondary pURLs do NOT use filename") {
    MavenTestHelpers.withTempDir("test-4-3") { dir =>
      // JAR with 2 embedded pom.properties and NO MANIFEST.MF
      // Companion POM has different artifactId than filename to distinguish
      MavenTestHelpers.writeJar(
        dir,
        "myapp-1.0.jar",
        Seq(
          "META-INF/maven/com.first/pkg1/pom.properties" ->
            """groupId=com.first
              |artifactId=pkg1
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.second/pkg2/pom.properties" ->
            """groupId=org.second
              |artifactId=pkg2
              |version=2.0
              |""".stripMargin
        )
      )
      // Companion POM with artifactId different from filename
      MavenTestHelpers.writePom(
        dir,
        "myapp-1.0.pom",
        "com.example",
        "real-art",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filter out the POM marker pURL
      val jarPurls = purls.filterNot(_.contains("type=pom"))

      // Must have at least 2 pURLs (canonical from POM + secondaries from pom.properties)
      // This prevents a vacuous pass where zero pURLs are emitted.
      assert(
        jarPurls.size >= 2,
        s"Expected at least 2 pURLs (canonical + secondary), got ${jarPurls.size}: $jarPurls"
      )

      // Should NOT have a pURL derived from filename ("myapp")
      // The canonical pURL uses "real-art" from companion POM
      // Secondary pURLs use "pkg1" and "pkg2" from pom.properties
      val filenamePurls = jarPurls.filter(_.contains("myapp"))
      assert(
        filenamePurls.isEmpty,
        s"Secondary pURLs should NOT be derived from filename 'myapp'.\npURLs: $jarPurls"
      )
    }
  }

  // =========================================================================
  // Test 4.4: Regular JAR secondary pURLs have no classifier
  // =========================================================================
  //
  // What it tests:
  //   When a regular JAR (not sources, not javadoc) has embedded
  //   pom.properties entries, secondary pURLs do NOT have any classifier.
  //
  // Why it's relevant:
  //   Only sources and javadoc JARs get classifiers. Regular JARs must not.

  test("Test 4.4: Regular JAR secondary pURLs have no classifier") {
    MavenTestHelpers.withTempDir("test-4-4") { dir =>
      MavenTestHelpers.writeJar(
        dir,
        "myapp-1.0.jar",
        Seq(
          "META-INF/maven/com.example/myapp/pom.properties" ->
            """groupId=com.example
              |artifactId=myapp
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.other/dep/pom.properties" ->
            """groupId=org.other
              |artifactId=dep
              |version=2.0
              |""".stripMargin
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "myapp-1.0.pom",
        "com.example",
        "myapp",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filter out the POM marker pURL
      val jarPurls = purls.filterNot(_.contains("type=pom"))

      // Check secondary pURLs (the one with org.other) do NOT have classifier
      val secondaryPurls = jarPurls.filter(_.contains("org.other"))
      assert(
        secondaryPurls.nonEmpty,
        "Should have secondary pURL for org.other"
      )

      assert(
        !secondaryPurls.exists(p =>
          PurlParts.parse(p).exists(_.qualifiers.contains("packaging"))
        ),
        s"Regular JAR secondary pURLs should NOT have ?packaging=.\npURLs: $secondaryPurls"
      )
      assert(
        !secondaryPurls.exists(p =>
          PurlParts.parse(p).exists(_.qualifiers.contains("classifier"))
        ),
        s"Regular JAR secondary pURLs should NOT have ?classifier=.\npURLs: $secondaryPurls"
      )
    }
  }

  // =========================================================================
  // Test 4.5: Duplicate pom.properties tuples produce exactly one pURL
  // =========================================================================
  //
  // What it tests:
  //   When a JAR has two identical pom.properties entries (same
  //   groupId/artifactId/version), only one pURL is emitted.
  //
  // Why it's relevant:
  //   REQ-1 requires deduplication.

  test("Test 4.5: Duplicate pom.properties tuples produce exactly one pURL") {
    MavenTestHelpers.withTempDir("test-4-5") { dir =>
      MavenTestHelpers.writeJar(
        dir,
        "myapp-1.0.jar",
        Seq(
          "META-INF/maven/com.example/myapp/pom.properties" ->
            """groupId=com.example
              |artifactId=myapp
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/com.example/myapp/pom2.properties" ->
            """groupId=com.example
              |artifactId=myapp
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.other/dep/pom.properties" ->
            """groupId=org.other
              |artifactId=dep
              |version=2.0
              |""".stripMargin
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "myapp-1.0.pom",
        "com.example",
        "myapp",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filter out the POM marker pURL
      val jarPurls = purls.filterNot(_.contains("type=pom"))

      // Should have exactly 2 unique pURLs (com.example/myapp + org.other/dep)
      // The duplicate com.example/myapp should produce only 1 pURL
      val myappPurls =
        jarPurls.filter(p => p.contains("com.example") && p.contains("myapp"))
      assertEquals(
        myappPurls.size,
        1,
        s"Duplicate should produce 1 pURL, got $myappPurls"
      )
    }
  }

  // =========================================================================
  // Test 4.6: Malformed pom.properties does not prevent valid pURLs
  // =========================================================================
  //
  // What it tests:
  //   When a JAR has 1 valid pom.properties + 1 with empty groupId,
  //   only the valid one produces a pURL. Malformed ones are silently skipped.
  //
  // Why it's relevant:
  //   REQ-1 — "Malformed tuples are silently skipped."

  test("Test 4.6: Malformed pom.properties does not prevent valid pURLs") {
    MavenTestHelpers.withTempDir("test-4-6") { dir =>
      MavenTestHelpers.writeJar(
        dir,
        "myapp-1.0.jar",
        Seq(
          "META-INF/maven/com.example/myapp/pom.properties" ->
            """groupId=com.example
              |artifactId=myapp
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.bad/badpkg/pom.properties" ->
            """groupId=
              |artifactId=badpkg
              |version=1.0
              |""".stripMargin
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "myapp-1.0.pom",
        "com.example",
        "myapp",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filter out the POM marker pURL
      val jarPurls = purls.filterNot(_.contains("type=pom"))

      // Should have only the valid pURL (com.example/myapp), not badpkg
      val hasBad = jarPurls.exists(_.contains("badpkg"))
      assert(!hasBad, "Malformed pom.properties should not produce a pURL")

      val hasValid =
        jarPurls.exists(p => p.contains("com.example") && p.contains("myapp"))
      assert(hasValid, "Valid pom.properties should produce a pURL")
    }
  }

  // =========================================================================
  // Test 4.7: detectClassifierFromFilename false positive guard
  // =========================================================================
  //
  // What it tests:
  //   A regular JAR named "my-sources-lib-1.0.jar" does NOT get
  //   ?packaging=sources on its secondary pURLs.
  //
  // Why it's relevant:
  //   Guard rail against false-positive classifier detection.

  test(
    "Test 4.7: Regular JAR with 'sources' in name does not get sources classifier"
  ) {
    MavenTestHelpers.withTempDir("test-4-7") { dir =>
      MavenTestHelpers.writeJar(
        dir,
        "my-sources-lib-1.0.jar",
        Seq(
          "META-INF/maven/com.example/my-sources-lib/pom.properties" ->
            """groupId=com.example
              |artifactId=my-sources-lib
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.other/dep/pom.properties" ->
            """groupId=org.other
              |artifactId=dep
              |version=2.0
              |""".stripMargin
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "my-sources-lib-1.0.pom",
        "com.example",
        "my-sources-lib",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filter out the POM marker pURL
      val jarPurls = purls.filterNot(_.contains("type=pom"))

      // Must have at least 2 pURLs (canonical + secondary from pom.properties)
      // This prevents a vacuous pass where zero pURLs are emitted.
      assert(
        jarPurls.size >= 2,
        s"Expected at least 2 pURLs (canonical + secondary), got ${jarPurls.size}: $jarPurls"
      )

      // Check NO pURLs have ?packaging=sources
      val sourcesPurls =
        jarPurls.filter(PurlParts.hasQualifier(_, "packaging", "sources"))
      assert(
        sourcesPurls.isEmpty,
        s"Regular JAR with 'sources' in name should NOT get ?packaging=sources.\npURLs: $jarPurls"
      )
    }
  }

  // =========================================================================
  // Test 4.8: Try wrapping catches PurlException and continues
  // =========================================================================
  //
  // What it tests:
  //   When buildPackageURL throws (e.g., invalid characters in groupId),
  //   the Try wrapper catches it and remaining pURLs are still emitted.
  //
  // Why it's relevant:
  //   Verifies the error isolation guard rail.

  test("Test 4.8: Invalid pom.properties does not prevent valid pURLs") {
    MavenTestHelpers.withTempDir("test-4-8") { dir =>
      MavenTestHelpers.writeJar(
        dir,
        "myapp-1.0.jar",
        Seq(
          "META-INF/maven/com.example/myapp/pom.properties" ->
            """groupId=com.example
              |artifactId=myapp
              |version=1.0
              |""".stripMargin,
          "META-INF/maven/org.bad/dep/pom.properties" ->
            """groupId=org.bad
              |artifactId=dep
              |version=
              |""".stripMargin
        )
      )
      MavenTestHelpers.writePom(
        dir,
        "myapp-1.0.pom",
        "com.example",
        "myapp",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filter out the POM marker pURL
      val jarPurls = purls.filterNot(_.contains("type=pom"))

      // The valid pURL should still be present
      val hasValid =
        jarPurls.exists(p => p.contains("com.example") && p.contains("myapp"))
      assert(
        hasValid,
        "Valid pURL should be emitted despite invalid one failing"
      )
    }
  }
}

/* Property-based tests for Phase 4.
 *
 * '''What this suite tests:'''
 *   - Property 4.9: For any sources JAR, all pURLs share the same classifier
 *   - Property 4.10: For any javadoc JAR, all pURLs share the same classifier
 */
class Phase4SecondaryClassifierPropertySuite extends ScalaCheckSuite {

  private val genGroupId: Gen[String] = Gen.oneOf(
    "com.example",
    "org.test",
    "io.spicelabs",
    "net.demo",
    "co.sample"
  )

  private val genArtifactId: Gen[String] = Gen.oneOf(
    "lib",
    "core",
    "utils",
    "api",
    "model",
    "common",
    "base"
  )

  private val genVersion: Gen[String] = for {
    major <- Gen.choose(1, 10)
    minor <- Gen.choose(0, 20)
  } yield s"$major.$minor"

  private val genTuple: Gen[(String, String, String)] = for {
    g <- genGroupId
    a <- genArtifactId
    v <- genVersion
  } yield (g, a, v)

  private def buildSourcesJar(
      dir: File,
      name: String,
      tuples: Seq[(String, String, String)]
  ): File = {
    val entries = tuples.map { case (g, a, v) =>
      s"META-INF/maven/$g/$a/pom.properties" ->
        s"""groupId=$g
           |artifactId=$a
           |version=$v
           |""".stripMargin
    }
    MavenTestHelpers.writeJar(dir, name, entries)
  }

  // =========================================================================
  // Property 4.9: For any sources JAR, all pURLs share the same classifier
  // =========================================================================

  property("Property 4.9: All pURLs from sources JAR have ?packaging=sources") {
    forAll(Gen.nonEmptyListOf(genTuple).suchThat(_.nonEmpty)) { tuples =>
      MavenTestHelpers.withTempDir("prop-4-9") { dir =>
        // Deduplicate by (groupId, artifactId) — different versions of the
        // same G/A produce the same pom.properties path, causing ZipException
        val uniqueTuples =
          tuples.distinctBy { case (g, a, _) => (g, a) }.take(5)
        val jarName = "lib-1.0-sources.jar"
        buildSourcesJar(dir, jarName, uniqueTuples)
        MavenTestHelpers.writePom(
          dir,
          "lib-1.0-sources.pom",
          "com.example",
          "lib",
          "1.0"
        )

        val store = MavenTestHelpers.processDirectoryWithStore(dir)
        val purls = store.purls().toSet.filterNot(_.contains("type=pom"))

        // Must have at least 1 pURL — a vacuous pass on empty pURLs
        // would hide a regression where the pipeline emits nothing.
        purls.nonEmpty && purls.forall(
          PurlParts.hasQualifier(_, "packaging", "sources")
        )
      }
    }
  }

  // =========================================================================
  // Property 4.10: For any javadoc JAR, all pURLs share the same classifier
  // =========================================================================

  property(
    "Property 4.10: All pURLs from javadoc JAR have ?classifier=javadoc"
  ) {
    forAll(Gen.nonEmptyListOf(genTuple).suchThat(_.nonEmpty)) { tuples =>
      MavenTestHelpers.withTempDir("prop-4-10") { dir =>
        // Deduplicate by (groupId, artifactId) — different versions of the
        // same G/A produce the same pom.properties path, causing ZipException
        val uniqueTuples =
          tuples.distinctBy { case (g, a, _) => (g, a) }.take(5)
        val jarName = "lib-1.0-javadoc.jar"
        buildSourcesJar(dir, jarName, uniqueTuples)
        MavenTestHelpers.writePom(
          dir,
          "lib-1.0-javadoc.pom",
          "com.example",
          "lib",
          "1.0"
        )

        val store = MavenTestHelpers.processDirectoryWithStore(dir)
        val purls = store.purls().toSet.filterNot(_.contains("type=pom"))

        // Must have at least 1 pURL — a vacuous pass on empty pURLs
        // would hide a regression where the pipeline emits nothing.
        purls.nonEmpty && purls.forall(
          PurlParts.hasQualifier(_, "classifier", "javadoc")
        )
      }
    }
  }
}
