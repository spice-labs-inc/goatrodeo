/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import munit.FunSuite
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.io.File

/** Phase 1: Directory-Based Test Infrastructure & Processing Order
  * Verification.
  *
  * '''What this suite tests:'''
  *   1. The `MavenTestHelpers` infrastructure correctly creates directories,
  *      bundles JAR+POM via `computeMavenFiles`, and processes through the full
  *      pipeline. 2. `parsedPom` is populated by the POM marker and survives to
  *      the JAR marker's `applyAccumulatedAugmentation` (GAP-1 verification).
  *      3. `getElementsToProcess` returns markers in the correct order: POM →
  *      Sources → JavaDocs → Metadata → JAR (REQ-4). 4. Companion POM is
  *      available to sources marker too. 5. A directory with all four artifacts
  *      (JAR, sources, javadoc, POM) is correctly bundled into a single
  *      `MavenToProcess`. 6. Property test: for any random artifact name,
  *      `computeMavenFiles` bundles JAR + POM together.
  *
  * '''Why this matters:''' Without directory-based test infrastructure,
  * companion POM weight (REQ-3) cannot be tested. Without processing order
  * verification, a future refactor could silently break the POM-first ordering
  * that makes companion POM data available to JAR markers.
  *
  * '''Requirements addressed:'''
  *   - REQ-8: Directory-based test setup for MavenStrategy tests
  *   - REQ-4: MavenStrategy MUST process markers in order: POM → Sources →
  *     Javadoc → Main JAR/WAR/EAR
  *   - GAP-1: Verify pipeline ordering — companion POM available to JAR markers
  *
  * '''LLM context:''' This suite has two parts:
  *   - `Phase1DirectoryInfraSuite` (Tests 1.1-1.5): example-based tests using
  *     `MavenTestHelpers` to create directories, bundle artifacts, and verify
  *     processing order.
  *   - `Phase1PropertySuite` (Test 1.6): ScalaCheck property test verifying
  *     `computeMavenFiles` bundles JAR + POM for any name.
  */

// =============================================================================
// Tests 1.1-1.5: Example-based tests
// =============================================================================

class Phase1DirectoryInfraSuite extends FunSuite {

  // -------------------------------------------------------------------------
  // Test 1.1: Directory-based test helper creates valid MavenToProcess bundle
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   The `MavenTestHelpers.computeBundles` helper creates a temp directory
  //   with a JAR and companion POM, calls `computeMavenFiles`, and returns
  //   a `MavenToProcess` with the POM bundled with the JAR.
  //
  // Why it's relevant:
  //   All subsequent tests that verify companion POM weight need this
  //   infrastructure. Without it, tests can only process single JARs in
  //   isolation (via `buildGraphFromArtifactWrapper`).
  //
  // Requirement section:
  //   REQ-8 — "Tests should call computeMavenFiles on the directory, not
  //   buildGraphFromArtifactWrapper on a single JAR."
  //
  // Theory:
  //   Create temp dir, write `foo-1.0.jar` + `foo-1.0.pom` where POM has
  //   groupId=`org.zzz.unmatched` (deliberately different from any
  //   filename-derived value). Call `computeBundles`. Assert the returned
  //   `MavenToProcess` has `pom.isDefined` and `jar.filenameWithNoPath ==
  //   "foo-1.0.jar"`. Using a groupId that is demonstrably different from
  //   the filename ensures the test doesn't pass trivially if `parsedPom`
  //   is `None`.
  //
  // Expected (RED):
  //   Helper doesn't exist yet — test won't compile.
  //
  // Expected (GREEN):
  //   Helper creates the directory, `computeMavenFiles` returns a bundle
  //   with POM + JAR.

  test("Test 1.1: computeBundles creates MavenToProcess with companion POM") {
    MavenTestHelpers.withTempDir("test-1-1") { dir =>
      // Write a JAR with no Maven metadata (just a dummy entry)
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
      // Write companion POM with a groupId that can't come from filename
      MavenTestHelpers.writePom(
        dir,
        "foo-1.0.pom",
        "org.zzz.unmatched",
        "foo",
        "1.0"
      )

      val bundles = MavenTestHelpers.computeBundles(dir)

      assert(bundles.nonEmpty, "Should have at least one bundle")
      val bundle = bundles.head
      assert(
        bundle.jar.filenameWithNoPath == "foo-1.0.jar",
        s"JAR should be foo-1.0.jar, got ${bundle.jar.filenameWithNoPath}"
      )
      assert(
        bundle.pom.isDefined,
        "Companion POM should be bundled with the JAR"
      )
      assert(
        bundle.source.isEmpty,
        "No sources JAR in this test"
      )
      assert(
        bundle.javaDoc.isEmpty,
        "No javadoc JAR in this test"
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 1.2: parsedPom is populated when JAR marker's
  //           applyAccumulatedAugmentation runs
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When a directory with JAR + companion POM is processed through the
  //   full pipeline (`ToProcess.process`), `parsedPom` on `MavenState` is
  //   `Some(...)` when the JAR marker's `applyAccumulatedAugmentation` runs.
  //
  // Why it's relevant:
  //   This is the core verification of GAP-1. If `parsedPom` is `None` when
  //   the JAR marker runs, the companion POM contributes nothing to canonical
  //   pURL resolution, making REQ-3 impossible to satisfy.
  //
  // Requirement section:
  //   REQ-4 — "The pipeline must process the POM marker on the MavenState
  //   BEFORE the JAR/Sources/JavaDocs markers' applyAccumulatedAugmentation
  //   runs."
  //
  // Theory:
  //   Create temp dir with JAR + POM where POM has groupId=`org.zzz.unmatched`
  //   (distinct from filename). The JAR has NO pom.properties and NO manifest,
  //   so the only possible source of groupId is the companion POM (via
  //   `parsedPom`). Process through `processDirectoryWithStore`. If the
  //   pURL contains `org.zzz.unmatched`, then `parsedPom` was definitively
  //   populated (this groupId could NOT come from filename or manifest). If
  //   the pURL's groupId is `foo` (from filename fallback), `parsedPom` was
  //   `None`.
  //
  // Expected (RED):
  //   No test exists. Once written, it should PASS because the code already
  //   processes POM first. But if it fails, it reveals a pipeline ordering
  //   bug. Marked as REGRESSION-GUARD (green from inception if code is
  //   correct).
  //
  // Expected (GREEN):
  //   Test passes, confirming `parsedPom` is populated.

  test("Test 1.2: parsedPom is populated and used by JAR marker (GAP-1)") {
    MavenTestHelpers.withTempDir("test-1-2") { dir =>
      // JAR with NO pom.properties and NO manifest — the only possible
      // source of groupId is the companion POM
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
      // POM with groupId that cannot come from filename or manifest
      MavenTestHelpers.writePom(
        dir,
        "foo-1.0.pom",
        "org.zzz.unmatched",
        "foo",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // The JAR marker should emit a pURL with org.zzz.unmatched as groupId.
      // This groupId can ONLY come from the companion POM (via parsedPom).
      // If parsedPom were None, the pURL would fall back to filename,
      // giving pkg:maven/foo/foo@1.0 (groupId=foo from filename fallback).
      val jarPurls = purls.filter(p =>
        p.contains("org.zzz.unmatched") && !p.contains("classifier=pom")
      )
      assert(
        jarPurls.nonEmpty,
        s"""Expected a JAR pURL with org.zzz.unmatched (NOT classifier=pom),
           |proving parsedPom was populated. Got: ${purls}
           |If groupId is "foo" instead, parsedPom was None.""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 1.3: Processing order is POM → Sources → Javadoc → JAR
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   `getElementsToProcess` returns markers in the exact order:
  //   POM, Sources, JavaDocs, Metadata, JAR.
  //
  // Why it's relevant:
  //   If the order changes, companion POM data won't be available to JAR
  //   markers. This is a guardrail test.
  //
  // Requirement section:
  //   REQ-4 — "MavenStrategy MUST process markers in the following fixed
  //   order: POM → Sources → Javadoc → Main JAR/WAR/EAR."
  //
  // Theory:
  //   Create a `MavenToProcess` with all five artifact types. Call
  //   `getElementsToProcess()`. Assert the returned sequence has
  //   POM at index 0, Sources at index 1, JavaDocs at index 2, Metadata
  //   at index 3, JAR at index 4.
  //
  // Expected (RED):
  //   No test exists. Should PASS immediately (code is correct) but serves
  //   as regression protection.
  //
  // Expected (GREEN):
  //   Test passes, confirming correct order.

  test(
    "Test 1.3: getElementsToProcess returns POM → Sources → JavaDocs → Metadata → JAR"
  ) {
    MavenTestHelpers.withTempDir("test-1-3") { dir =>
      // Create all four artifact types + the JAR
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0-sources.jar",
        Seq("Foo.java" -> "class Foo {}")
      )
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0-javadoc.jar",
        Seq("Foo.html" -> "<html></html>")
      )
      MavenTestHelpers.writePom(dir, "foo-1.0.pom", "org.example", "foo", "1.0")
      // Write a maven-metadata.xml file
      val metaFile = new File(dir, "maven-metadata.xml")
      io.spicelabs.goatrodeo.util.Helpers.writeOverFile(
        metaFile,
        """<?xml version="1.0" encoding="UTF-8"?>
          |<metadata>
          |  <groupId>org.example</groupId>
          |  <artifactId>foo</artifactId>
          |  <versioning>
          |    <latest>1.0</latest>
          |    <release>1.0</release>
          |  </versioning>
          |</metadata>""".stripMargin
      )

      val bundles = MavenTestHelpers.computeBundles(dir)
      assert(bundles.nonEmpty, "Should have at least one bundle")

      val bundle = bundles.head
      val (elements, _) = bundle.getElementsToProcess()

      // Assert exact order: POM → Sources → JavaDocs → Metadata → JAR
      assertEquals(
        elements.length,
        5,
        s"Expected 5 elements, got ${elements.length}"
      )
      assertEquals(
        elements(0)._2,
        MavenMarkers.POM,
        "Element 0 should be POM"
      )
      assertEquals(
        elements(1)._2,
        MavenMarkers.Sources,
        "Element 1 should be Sources"
      )
      assertEquals(
        elements(2)._2,
        MavenMarkers.JavaDocs,
        "Element 2 should be JavaDocs"
      )
      assertEquals(
        elements(3)._2,
        MavenMarkers.Metadata,
        "Element 3 should be Metadata"
      )
      assertEquals(
        elements(4)._2,
        MavenMarkers.JAR,
        "Element 4 should be JAR"
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 1.4: Companion POM available to sources marker
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   When a directory with sources JAR + companion POM is processed,
  //   `parsedPom` is populated when the sources marker's
  //   `applyAccumulatedAugmentation` runs.
  //
  // Why it's relevant:
  //   Sources JARs need the companion POM for canonical pURL resolution
  //   too. If POM is processed before Sources (per REQ-4), `parsedPom`
  //   should be available.
  //
  // Requirement section:
  //   REQ-4 — processing order must put POM before Sources.
  //
  // Theory:
  //   Create temp dir with sources JAR + POM. The sources JAR has NO
  //   pom.properties and NO manifest. Process through pipeline. If the
  //   canonical pURL for the sources JAR uses the companion POM's
  //   groupId (`org.zzz.unmatched`), then `parsedPom` was populated.
  //   If the pURL falls back to filename, `parsedPom` was None.
  //
  // Expected (RED):
  //   No test exists. Should PASS (code is correct).
  //
  // Expected (GREEN):
  //   Test passes.

  test("Test 1.4: Companion POM available to sources marker") {
    MavenTestHelpers.withTempDir("test-1-4") { dir =>
      // Main JAR is needed so computeMavenFiles bundles the POM as a companion.
      // Without a main JAR, the sources JAR would be claimed as a standalone
      // classifier JAR (second pass) with NO companion POM.
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
      // Sources JAR with NO pom.properties and NO manifest — the only
      // possible source of groupId is the companion POM (via parsedPom)
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0-sources.jar",
        Seq("com/example/Foo.java" -> "package com.example; class Foo {}")
      )
      // POM with groupId that cannot come from filename
      MavenTestHelpers.writePom(
        dir,
        "foo-1.0.pom",
        "org.zzz.unmatched",
        "foo",
        "1.0"
      )

      val store = MavenTestHelpers.processDirectoryWithStore(dir)
      val purls = store.purls().toSet

      // The sources JAR should emit a pURL with org.zzz.unmatched as groupId.
      // This groupId can ONLY come from the companion POM (via parsedPom).
      // If parsedPom were None, the pURL would fall back to filename,
      // giving pkg:maven/foo/foo@1.0-sources?packaging=sources
      // (groupId=foo from filename fallback).
      val sourcesPurls = purls.filter(p =>
        p.contains("org.zzz.unmatched") && p.contains("packaging=sources")
      )
      assert(
        sourcesPurls.nonEmpty,
        s"""Expected a sources pURL with org.zzz.unmatched and packaging=sources,
           |proving parsedPom was populated for the sources marker.
           |Got: ${purls}""".stripMargin
      )
    }
  }

  // -------------------------------------------------------------------------
  // Test 1.5: Directory-based setup with sources + javadoc + main JAR + POM
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   A directory containing all four artifacts (JAR, sources JAR, javadoc
  //   JAR, POM) is correctly bundled into a single `MavenToProcess` with
  //   all companions set.
  //
  // Why it's relevant:
  //   This is the realistic case for Maven artifacts. All four files should
  //   be bundled together.
  //
  // Requirement section:
  //   REQ-8 — directory-based setup.
  //
  // Theory:
  //   Create temp dir with `foo-1.0.jar`, `foo-1.0-sources.jar`,
  //   `foo-1.0-javadoc.jar`, `foo-1.0.pom`. Call `computeBundles`. Assert
  //   the returned `MavenToProcess` has all four fields populated.
  //
  // Expected (RED):
  //   Helper doesn't exist yet.
  //
  // Expected (GREEN):
  //   All four fields populated in the bundle.

  test("Test 1.5: All four artifacts bundled into single MavenToProcess") {
    MavenTestHelpers.withTempDir("test-1-5") { dir =>
      MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0-sources.jar",
        Seq("Foo.java" -> "class Foo {}")
      )
      MavenTestHelpers.writeJar(
        dir,
        "foo-1.0-javadoc.jar",
        Seq("Foo.html" -> "<html></html>")
      )
      MavenTestHelpers.writePom(dir, "foo-1.0.pom", "org.example", "foo", "1.0")

      val bundles = MavenTestHelpers.computeBundles(dir)

      assertEquals(
        bundles.length,
        1,
        s"Expected 1 bundle, got ${bundles.length}"
      )
      val bundle = bundles.head

      assert(
        bundle.jar.filenameWithNoPath == "foo-1.0.jar",
        s"JAR should be foo-1.0.jar"
      )
      assert(
        bundle.pom.isDefined,
        "POM should be bundled"
      )
      assert(
        bundle.source.isDefined,
        "Sources JAR should be bundled"
      )
      assert(
        bundle.source.get.filenameWithNoPath == "foo-1.0-sources.jar",
        s"Sources JAR should be foo-1.0-sources.jar"
      )
      assert(
        bundle.javaDoc.isDefined,
        "Javadoc JAR should be bundled"
      )
      assert(
        bundle.javaDoc.get.filenameWithNoPath == "foo-1.0-javadoc.jar",
        s"Javadoc JAR should be foo-1.0-javadoc.jar"
      )
    }
  }
}

// =============================================================================
// Test 1.6: Property-based test
// =============================================================================

class Phase1PropertySuite extends ScalaCheckSuite {

  // -------------------------------------------------------------------------
  // Property-Based Test 1.6: For any directory with JAR + POM,
  //                         computeMavenFiles bundles them
  // -------------------------------------------------------------------------
  //
  // What it tests:
  //   For any randomly generated artifact name, if both a `.jar` and `.pom`
  //   file exist in the same directory, `computeMavenFiles` bundles them
  //   together.
  //
  // Why it's relevant:
  //   The bundling logic uses filename matching (`noExtName + ".pom"`).
  //   This property test verifies it works for any valid filename.
  //
  // Requirement section:
  //   REQ-8.
  //
  // Theory:
  //   ScalaCheck generator produces random artifact names (alphanumeric with
  //   hyphens and dots, starting with a letter, ending with a digit to form
  //   a version pattern). Create files with those names. Assert bundling.
  //
  // Expected (RED):
  //   No test exists.
  //
  // Expected (GREEN):
  //   All generated names are correctly bundled.
  //
  // ScalaCheck generator design:
  //   - Use `Gen.listOfN` for bounded strings to avoid discard issues
  //   - Generate names like `foo-1.0` where `foo` is alphanumeric+hyphens
  //     and `1.0` is a version starting with a digit
  //   - Keep generated strings to reasonable lengths (1-30 chars)

  /** Generator for artifact names: alphanumeric with hyphens, 3-20 chars.
    * Starts with a letter to ensure valid Maven artifactId.
    */
  val genArtifactName: Gen[String] = for {
    first <- Gen.alphaChar
    len <- Gen.choose(2, 19)
    rest <- Gen.listOfN(
      len,
      Gen.frequency(
        (7, Gen.alphaNumChar),
        (3, Gen.const('-'))
      )
    )
  } yield (first :: rest).mkString

  /** Generator for version strings: starts with a digit, 1-10 chars, containing
    * digits and dots.
    */
  val genVersion: Gen[String] = for {
    first <- Gen.numChar
    len <- Gen.choose(0, 9)
    rest <- Gen.listOfN(
      len,
      Gen.frequency(
        (6, Gen.numChar),
        (4, Gen.const('.'))
      )
    )
  } yield (first :: rest).mkString

  /** Generator for full artifact filenames: `name-version` */
  val genArtifactFilename: Gen[String] = for {
    name <- genArtifactName
    version <- genVersion
  } yield s"$name-$version"

  property(
    "Test 1.6: computeMavenFiles bundles JAR + POM for any artifact name"
  ) {
    forAll(genArtifactFilename) { (baseName: String) =>
      MavenTestHelpers.withTempDir("test-1-6") { dir =>
        // Write a JAR and a POM with the same base name
        MavenTestHelpers.writeJar(
          dir,
          s"$baseName.jar",
          Seq("dummy.txt" -> "dummy")
        )
        MavenTestHelpers.writePom(
          dir,
          s"$baseName.pom",
          "org.example",
          "test",
          "1.0"
        )

        val bundles = MavenTestHelpers.computeBundles(dir)

        // There should be exactly one bundle
        (bundles.length == 1) &&
        // The bundle should have the JAR
        (bundles.head.jar.filenameWithNoPath == s"$baseName.jar") &&
        // The bundle should have the companion POM
        bundles.head.pom.isDefined &&
        // The POM should have the right filename
        (bundles.head.pom.get.filenameWithNoPath == s"$baseName.pom")
      }
    }
  }
}
