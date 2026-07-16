/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import munit.FunSuite
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.io.File

/** Phase 2: Canonical pURL Priority Fix.
  *
  * '''What this suite tests:'''
  *   - REQ-2: Canonical pURL in metadata with correct priority
  *   - REQ-3: Companion POM as highest priority for canonical pURL
  *   - GAP-2: Fix canonical pURL priority — companion POM must be first
  *   - GAP-7: Verify canonical pURL in metadata for sources/javadoc
  *
  * '''Priority chain (after fix):''' external POM (companion) → pom.properties
  * → embedded pom.xml → manifest → filename
  *
  * '''LLM context:''' These tests verify that the companion POM is the highest
  * priority source for canonical pURL resolution. Tests 2.1-2.2 verify the core
  * behavioral change. Tests 2.3-2.4 verify canonical pURL metadata for
  * sources/javadoc. Tests 2.5-2.10 cover edge cases.
  */

class Phase2CanonicalPrioritySuite extends FunSuite {

  /** Helper: find all CanonicalPurl values in the store's items. */
  private def findCanonicalPurls(
      store: io.spicelabs.goatrodeo.omnibor.MemStorage
  ): Set[String] = {
    store
      .keys()
      .flatMap { key =>
        store
          .read(key)
          .flatMap(_.bodyAsItemMetaData)
          .flatMap(_.extra.get(MetadataKeyConstants.CANONICAL_PURL))
      }
      .flatMap(_.toSeq.map(_.value))
      .toSet
  }

  // -------------------------------------------------------------------------
  // Test 2.1: Companion POM groupId wins over pom.properties for canonical pURL
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When a JAR has embedded pom.properties with groupId=org.embedded
  //   and a companion POM with groupId=org.companion, the canonical pURL
  //   uses groupId=org.companion (companion POM wins).
  //
  // Why it's relevant:
  //   This is the core behavioral change of REQ-3. The companion POM is
  //   the authoritative published metadata.
  //
  // Requirement section:
  //   REQ-3 — "The companion POM file is the HIGHEST priority source
  //   for determining the canonical pURL's groupId/artifactId/version."
  //
  // Theory:
  //   Create temp dir with JAR containing pom.properties
  //   (groupId=org.embedded, artifactId=embedded-art, version=1.0) and
  //   companion POM (groupId=org.companion, artifactId=companion-art,
  //   version=2.0). Process through pipeline. Assert canonical pURL
  //   contains org.companion (NOT org.embedded).
  //
  // Expected (RED):
  //   Current code produces pURL with org.embedded because pom.properties
  //   has higher priority.
  //
  // Expected (GREEN):
  //   After fix, produces pURL with org.companion.

  test("Test 2.1: Companion POM wins over pom.properties for canonical pURL") {
    MavenTestHelpers.withTempDir("test-2-1") { dir =>
      // JAR with pom.properties (groupId=org.embedded, artifactId=foo
      // — artifactId MUST match filename "foo" so pom.properties is
      // selected as primary by determinePrimaryGroupIdArtifactIdVersion)
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0.jar",
        Seq(
          "META-INF/maven/org.embedded/foo/pom.properties" ->
            """groupId=org.embedded
              |artifactId=foo
              |version=1.0
              |""".stripMargin,
          "com/example/Foo.class" -> "CAFEBABE"
        )
      )
      // Companion POM with DIFFERENT groupId/artifactId/version
      MavenTestHelpers.writePom(
        dir,
        "foo-1.0.pom",
        "org.companion",
        "companion-art",
        "2.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // The JAR's canonical pURL should use the companion POM's groupId
      // (org.companion), NOT pom.properties' groupId (org.embedded).
      // Filter out the POM marker's pURL (which has classifier=pom).
      val jarPurls = purls.filter(p =>
        p.contains("org.companion") && !p.contains("classifier=pom")
      )
      assert(
        jarPurls.nonEmpty,
        s"""Expected JAR pURL with org.companion (from companion POM),
           |but got: ${purls}
           |If only org.embedded appears (without classifier=pom),
           |pom.properties is winning over companion POM (priority is wrong).""".stripMargin
      )

      // Also verify pom.properties' groupId does NOT appear in JAR pURLs
      val embeddedJarPurls = purls.filter(p =>
        p.contains("org.embedded") && !p.contains("classifier=pom")
      )
      assert(
        embeddedJarPurls.isEmpty,
        s"""JAR pURL should NOT use org.embedded (from pom.properties)
           |when companion POM has org.companion.
           |Got: ${embeddedJarPurls}""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.2: Companion POM consulted when pom.properties absent
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When a JAR has NO embedded pom.properties but has a companion POM,
  //   the canonical pURL uses the companion POM values.
  //
  // Why it's relevant:
  //   Many JARs don't have pom.properties inside them. The companion POM
  //   is the only authoritative source.
  //
  // Requirement section:
  //   REQ-3 — "For any JAR with no embedded pom.properties but a companion
  //   POM, the canonical pURL's groupId/artifactId/version matches the
  //   companion POM."
  //
  // Theory:
  //   Create temp dir with JAR (no pom.properties, just manifest with
  //   Implementation-Title=foo) and companion POM (groupId=org.real,
  //   artifactId=real-art, version=3.1). Process. Assert canonical pURL
  //   is pkg:maven/org.real/real-art@3.1 (not manifest-derived, not
  //   filename).
  //
  // Expected (GREEN):
  //   Canonical pURL uses companion POM values. This should already PASS
  //   because when pom.properties is absent, external POM is the first
  //   available source in both the old and new priority chains.

  test("Test 2.2: Companion POM used when pom.properties absent") {
    MavenTestHelpers.withTempDir("test-2-2") { dir =>
      // JAR with manifest but NO pom.properties
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0.jar",
        Seq(
          "META-INF/MANIFEST.MF" ->
            """Manifest-Version: 1.0
              |Implementation-Title: foo
              |Implementation-Version: 1.0
              |""".stripMargin
        )
      )
      // Companion POM with different groupId/artifactId/version
      MavenTestHelpers.writePom(
        dir,
        "foo-1.0.pom",
        "org.real",
        "real-art",
        "3.1"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Canonical pURL should use companion POM values, not manifest
      val realPurls = purls.filter(p =>
        p.contains("org.real") && p.contains("real-art") && p.contains("3.1")
      )
      assert(
        realPurls.nonEmpty,
        s"""Expected pURL with org.real/real-art@3.1 (from companion POM),
           |got: ${purls}""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.3: Canonical pURL in metadata for sources JAR
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   After processing a sources JAR with a companion POM, the gitoid
  //   Item's metadata has CanonicalPurl key with a pURL containing
  //   ?packaging=sources.
  //
  // Why it's relevant:
  //   GAP-7 — no test currently verifies canonical pURL metadata for
  //   sources/javadoc. The canonical pURL must include the correct
  //   classifier.
  //
  // Requirement section:
  //   REQ-2 — "The canonical pURL for a sources JAR includes
  //   ?packaging=sources."
  //
  // Theory:
  //   Create temp dir with main JAR + sources JAR + companion POM.
  //   Process through pipeline. Read gitoid Items from store. Find
  //   CanonicalPurl metadata. Assert one contains ?packaging=sources.
  //
  // Expected (GREEN):
  //   CanonicalPurl present with ?packaging=sources.

  test(
    "Test 2.3: Canonical pURL in metadata for sources JAR has ?packaging=sources"
  ) {
    MavenTestHelpers.withTempDir("test-2-3") { dir =>
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0-sources.jar",
        Seq("com/example/Foo.java" -> "package com.example; class Foo {}")
      )
      MavenTestHelpers.writePom(dir, "foo-1.0.pom", "org.example", "foo", "1.0")

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val canonicalPurls = findCanonicalPurls(store)

      assert(
        canonicalPurls.nonEmpty,
        s"Expected at least one CanonicalPurl in metadata, got none. Keys: ${store.keys().take(10)}"
      )
      val sourcesCanonical =
        canonicalPurls.filter(_.contains("packaging=sources"))
      assert(
        sourcesCanonical.nonEmpty,
        s"""Expected CanonicalPurl with ?packaging=sources for sources JAR.
           |Got: ${canonicalPurls}""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.4: Canonical pURL in metadata for javadoc JAR
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   Same as 2.3 but for javadoc JAR with ?classifier=javadoc.
  //
  // Requirement section:
  //   REQ-2 — "The canonical pURL for a javadoc JAR includes
  //   ?classifier=javadoc."

  test(
    "Test 2.4: Canonical pURL in metadata for javadoc JAR has ?classifier=javadoc"
  ) {
    MavenTestHelpers.withTempDir("test-2-4") { dir =>
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0-javadoc.jar",
        Seq("com/example/Foo.html" -> "<html></html>")
      )
      MavenTestHelpers.writePom(dir, "foo-1.0.pom", "org.example", "foo", "1.0")

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val canonicalPurls = findCanonicalPurls(store)

      assert(
        canonicalPurls.nonEmpty,
        s"Expected at least one CanonicalPurl in metadata, got none."
      )
      val javadocCanonical =
        canonicalPurls.filter(_.contains("classifier=javadoc"))
      assert(
        javadocCanonical.nonEmpty,
        s"""Expected CanonicalPurl with ?classifier=javadoc for javadoc JAR.
           |Got: ${canonicalPurls}""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.5: Parent POM groupId fallback through full pipeline
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When the companion POM's <groupId> is absent but
  //   <parent><groupId> is present, the canonical pURL uses the
  //   parent's groupId.
  //
  // Why it's relevant:
  //   Many POMs inherit groupId from parent. The PomParser already
  //   handles this (line 134), but no test verifies it through the full
  //   pipeline.
  //
  // Requirement section:
  //   REQ-3 — "Parent POM groupId: When the companion POM's own <groupId>
  //   is absent, the parser falls back to <parent><groupId>."
  //
  // Theory:
  //   Create temp dir with JAR + companion POM where POM has no <groupId>
  //   but has <parent><groupId>org.inherited</groupId></parent>.
  //   Process. Assert canonical pURL's groupId is org.inherited.
  //
  // Expected (GREEN):
  //   Canonical pURL uses parent groupId. Should PASS (PomParser already
  //   handles this).

  test("Test 2.5: Parent POM groupId fallback through full pipeline") {
    MavenTestHelpers.withTempDir("test-2-5") { dir =>
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
      // POM with no <groupId> but <parent><groupId>
      val pomFile = new File(dir, "foo-1.0.pom")
      io.spicelabs.goatrodeo.util.Helpers.writeOverFile(
        pomFile,
        s"""<?xml version="1.0" encoding="UTF-8"?>
           |<project xmlns="http://maven.apache.org/POM/4.0.0">
           |  <modelVersion>4.0.0</modelVersion>
           |  <parent>
           |    <groupId>org.inherited</groupId>
           |    <artifactId>parent-art</artifactId>
           |    <version>1.0</version>
           |  </parent>
           |  <artifactId>foo</artifactId>
           |  <version>1.0</version>
           |</project>""".stripMargin
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Canonical pURL should use parent's groupId (org.inherited)
      val inheritedPurls = purls.filter(_.contains("org.inherited"))
      assert(
        inheritedPurls.nonEmpty,
        s"""Expected pURL with org.inherited (from parent POM groupId),
           |got: ${purls}""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.6: Filename is last resort for canonical pURL
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When no companion POM and no JAR contents (no pom.properties, no
  //   manifest with useful data) exist, the canonical pURL is derived
  //   from the filename.
  //
  // Requirement section:
  //   REQ-2 — "Filename (lowest priority, last resort)."

  test("Test 2.6: Filename is last resort for canonical pURL") {
    MavenTestHelpers.withTempDir("test-2-6") { dir =>
      // JAR with no pom.properties, no manifest, no companion POM
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Should produce a pURL from filename: foo-1.0.jar → foo/foo@1.0
      // (groupId falls back to artifactId when no groupId source exists)
      val filenamePurls =
        purls.filter(p => p.contains("foo") && p.contains("1.0"))
      assert(
        filenamePurls.nonEmpty,
        s"Expected pURL from filename fallback, got: ${purls}"
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.7: No canonical pURL when nothing resolvable
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When no groupId/artifactId/version can be resolved from any source
  //   (no companion POM, no pom.properties, no manifest, and filename
  //   yields nothing), the CanonicalPurl key is ABSENT from metadata.
  //
  // Requirement section:
  //   REQ-2 — "If no groupId/artifactId/version can be resolved at all,
  //   no canonical pURL is written (the key is absent, not empty)."
  //
  // Theory:
  //   Create a JAR named "x.jar" (no version, no POM, no pom.properties,
  //   no manifest). The filename "x.jar" has no dash-digit pattern, so
  //   extractIdentityFromFilename returns None. Process. Assert no
  //   CanonicalPurl in metadata.

  test("Test 2.7: No canonical pURL when nothing resolvable") {
    MavenTestHelpers.withTempDir("test-2-7") { dir =>
      // JAR with a name that yields no groupId/artifactId/version
      // "x.jar" has no dash-digit pattern → extractIdentityFromFilename returns None
      MavenTestHelpers.writeJar(dir, "x.jar", Seq("x.txt" -> "x"))

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val canonicalPurls = findCanonicalPurls(store)

      // The finalGroupId = groupId.orElse(artifactId) fallback means
      // even "x.jar" might produce a pURL if artifactId is resolved.
      // But "x.jar" has no dash-digit pattern, so extractIdentityFromFilename
      // returns None for all fields. With no other sources, no pURL.
      // However, the POM marker might still emit a pURL if parsedPom is
      // populated — but there's no POM file here.
      //
      // Actually, "x.jar" → isMavenArchive("x.jar") = true (ends with .jar)
      // → computeMavenFiles will claim it. But with no POM, no pom.properties,
      // no manifest, and filename yielding nothing, resolveGroupIdArtifactIdVersion
      // returns (None, None, None) → no pURL.
      //
      // We check that no CanonicalPurl exists with a meaningful value.
      // If the finalGroupId fallback kicks in (artifactId as groupId),
      // there might be a pURL. But artifactId is also None here.
      assert(
        canonicalPurls.isEmpty || canonicalPurls.forall(_.isEmpty),
        s"""Expected no CanonicalPurl when nothing is resolvable.
           |Got: ${canonicalPurls}""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.8: Corrupt companion POM does not crash pipeline
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When the companion POM contains invalid XML, the pipeline does not
  //   crash and the canonical pURL falls back to JAR contents/filename.
  //
  // Requirement section:
  //   REQ-2 — fallback chain.

  test("Test 2.8: Corrupt companion POM does not crash pipeline") {
    MavenTestHelpers.withTempDir("test-2-8") { dir =>
      // JAR with pom.properties (so we have a fallback source)
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0.jar",
        Seq(
          "META-INF/maven/org.embedded/foo/pom.properties" ->
            """groupId=org.embedded
              |artifactId=foo
              |version=1.0
              |""".stripMargin
        )
      )
      // Corrupt POM file
      val pomFile = new File(dir, "foo-1.0.pom")
      io.spicelabs.goatrodeo.util.Helpers.writeOverFile(
        pomFile,
        "<<invalid xml>>"
      )

      // Should not throw
      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Should fall back to pom.properties (org.embedded)
      val fallbackPurls = purls.filter(_.contains("org.embedded"))
      assert(
        fallbackPurls.nonEmpty,
        s"Expected fallback pURL from pom.properties when POM is corrupt, got: ${purls}"
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.9: Companion POM with partial data — field-level merge fills gaps
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When the companion POM has groupId but NO version, and pom.properties
  //   has version, the canonical pURL uses POM groupId + pom.properties
  //   version (field-level merge, not all-or-nothing).
  //
  // Why it's relevant:
  //   Verifies field-level independence. The companion POM is highest
  //   priority PER FIELD, not all-or-nothing.
  //
  // Requirement section:
  //   REQ-3 — companion POM as highest priority per field.

  test("Test 2.9: Companion POM partial data — field-level merge fills gaps") {
    MavenTestHelpers.withTempDir("test-2-9") { dir =>
      // JAR with pom.properties (groupId=org.embedded, artifactId=foo, version=1.0)
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0.jar",
        Seq(
          "META-INF/maven/org.embedded/foo/pom.properties" ->
            """groupId=org.embedded
              |artifactId=foo
              |version=1.0
              |""".stripMargin
        )
      )
      // Companion POM with groupId and artifactId but NO version
      val pomFile = new File(dir, "foo-1.0.pom")
      io.spicelabs.goatrodeo.util.Helpers.writeOverFile(
        pomFile,
        s"""<?xml version="1.0" encoding="UTF-8"?>
           |<project xmlns="http://maven.apache.org/POM/4.0.0">
           |  <modelVersion>4.0.0</modelVersion>
           |  <groupId>org.companion</groupId>
           |  <artifactId>companion-art</artifactId>
           |</project>""".stripMargin
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // groupId should come from companion POM (org.companion)
      // artifactId should come from companion POM (companion-art)
      // version should come from pom.properties (1.0) — field-level merge
      val mergedPurls = purls.filter(p =>
        p.contains("org.companion") && p.contains("companion-art") && p
          .contains("1.0")
      )
      assert(
        mergedPurls.nonEmpty,
        s"""Expected field-level merge: groupId=org.companion (POM),
           | artifactId=companion-art (POM), version=1.0 (pom.properties).
           | Got: ${purls}""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 2.10: finalGroupId fallback — artifactId used as groupId
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When no groupId is resolved from any source, the code at
  //   Maven.scala:348 uses groupId.orElse(artifactId) — artifactId as
  //   groupId. This test documents this behavior.
  //
  // Requirement section:
  //   REQ-2 — filename as last resort. The finalGroupId fallback is
  //   intentional: better to have a lookupable pURL than none.

  test("Test 2.10: finalGroupId fallback — artifactId used as groupId") {
    MavenTestHelpers.withTempDir("test-2-10") { dir =>
      // JAR with filename mylib-1.0.jar (no POM, no pom.properties, no manifest)
      MavenTestHelpers.writeJar(dir, "mylib-1.0.jar", Seq("x.txt" -> "x"))

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // Filename: mylib-1.0.jar → artifactId=mylib, version=1.0
      // No groupId from any source → finalGroupId = artifactId = mylib
      // pURL: pkg:maven/mylib/mylib@1.0
      val fallbackPurls =
        purls.filter(p => p.contains("mylib") && p.contains("1.0"))
      assert(
        fallbackPurls.nonEmpty,
        s"Expected pURL with artifactId-as-groupId fallback (mylib/mylib@1.0), got: ${purls}"
      )
    }
  }
}

// =============================================================================
// Property-Based Test 2.11: Companion POM is always highest priority
// =============================================================================

class Phase2PropertySuite extends ScalaCheckSuite {

  /** Generator for groupId strings. */
  val genGroupId: Gen[String] = for {
    prefix <- Gen.oneOf("com", "org", "io")
    rest <- Gen.listOfN(3, Gen.alphaChar)
  } yield s"$prefix.${rest.mkString}"

  /** Generator for artifactId strings. */
  val genArtifactId: Gen[String] = for {
    first <- Gen.alphaChar
    rest <- Gen.listOfN(Gen.choose(2, 10).sample.get, Gen.alphaNumChar)
  } yield (first :: rest).mkString

  /** Generator for version strings. */
  val genVersion: Gen[String] = for {
    major <- Gen.choose(1, 20)
    minor <- Gen.choose(0, 20)
  } yield s"$major.$minor"

  /** Generator for a complete (groupId, artifactId, version) tuple. */
  val genCoordinates: Gen[(String, String, String)] = for {
    g <- genGroupId
    a <- genArtifactId
    v <- genVersion
  } yield (g, a, v)

  // -------------------------------------------------------------------------
  // Property Test 2.11: Companion POM is always highest priority
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   For any JAR with a companion POM, the canonical pURL's
  //   groupId/artifactId/version always matches the companion POM,
  //   regardless of what's inside the JAR.
  //
  // Why it's relevant:
  //   This is the fundamental property of REQ-3.
  //
  // Requirement section:
  //   REQ-3.
  //
  // Theory:
  //   ScalaCheck generates random companion POM coordinates and random
  //   JAR pom.properties coordinates. For any combination, the canonical
  //   pURL must match the companion POM.
  //
  // Expected (RED):
  //   Fails because pom.properties currently wins.
  //
  // Expected (GREEN):
  //   Passes after priority fix.

  property("Test 2.11: Companion POM always wins over pom.properties") {
    forAll(genCoordinates, genCoordinates) {
      case ((pomG, pomA, pomV), (propsG, propsA, propsV)) =>
        // Skip if coordinates are the same (trivial case)
        if (pomG == propsG && pomA == propsA && pomV == propsV) true
        else {
          MavenTestHelpers.withTempDir("test-2-11") { dir =>
            // JAR filename uses "test" as artifactId so pom.properties
            // with artifactId="test" is selected as primary by
            // determinePrimaryGroupIdArtifactIdVersion
            val jarName = s"test-$pomV.jar"
            MavenTestHelpers.writeJar(
              dir,
              jarName,
              Seq(
                s"META-INF/maven/$propsG/test/pom.properties" ->
                  s"""groupId=$propsG
                     |artifactId=test
                     |version=$propsV
                     |""".stripMargin
              )
            )
            // Companion POM with different coordinates
            MavenTestHelpers.writePom(
              dir,
              s"test-$pomV.pom",
              pomG,
              pomA,
              pomV
            )

            val store = MavenTestHelpers.processDirectoryWithStore(dir)
            val purls = store.purls().toSet

            // The canonical pURL should use companion POM values
            // (pomG, pomA, pomV), NOT pom.properties values
            // (propsG, propsA, propsV)
            val hasPomGroup = purls.exists(_.contains(pomG))
            val hasPropsGroup = purls.exists(_.contains(propsG))

            hasPomGroup && !hasPropsGroup
          }
        }
    }
  }
}
