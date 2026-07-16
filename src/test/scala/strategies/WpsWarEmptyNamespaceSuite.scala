/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.PURLHelpers
import io.spicelabs.goatrodeo.util.PURLHelpers.Ecosystems
import munit.FunSuite

import java.io.File
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Tests for the empty-namespace PurlException bug.
  *
  * '''Bug:''' When a JAR's manifest contains `Implementation-Vendor-Id:` with
  * an empty value (e.g. `collections-generic-4.01.jar` inside
  * `wps-demo-1.3.0.war`), `resolveGroupIdArtifactIdVersionFromManifest` returns
  * `Some("")` for groupId instead of `None`. The match at
  * `applyAccumulatedAugmentation` line 1096 then treats this as a valid
  * groupId/artifactId/version and calls `buildPackageURL(Ecosystems.Maven,
  * Some(""), ...).toCanonical()`, which throws `PurlException: purl type
  * "maven" requires a namespace`.
  *
  * '''Root cause:''' `implVendorOpt` at Maven.scala:292-295 does NOT filter
  * empty strings (unlike the `bundleSymOpt` path at line 326-327 which has
  * `.filter(_.nonEmpty)`). An empty `Implementation-Vendor-Id` header value
  * passes through as `Some("")`.
  *
  * '''Test data:''' `test_data/wps_war_test/` contains `wps-demo-1.3.0.war`
  * alongside its POM, sources JAR, and javadoc JAR. The WAR contains 23
  * dependency JARs in `WEB-INF/lib/`, at least two of which have
  * `Implementation-Vendor-Id:` with empty values
  * (`collections-generic-4.01.jar`, `commons-codec-1.2.jar`).
  *
  * '''LLM context:''' This suite tests that empty-string manifest values are
  * treated as missing (`None`), not as present-but-empty (`Some("")`). The pURL
  * library rejects empty namespaces for Maven pURLs. The test builds the ADG
  * via `ToProcess.strategyForDirectory` + `ToProcess.buildGraphForToProcess`,
  * which recursively traverses into the WAR and processes each child JAR
  * through its own `applyAccumulatedAugmentation`.
  */
class WpsWarEmptyNamespaceSuite extends FunSuite {

  // ==================== Test 1: resolveGroupIdArtifactIdVersion unit test ====================

  /** Tests that `resolveGroupIdArtifactIdVersion` returns `None` for groupId
    * when the manifest has `Implementation-Vendor-Id:` with an empty value.
    *
    * '''What it tests:''' `resolveGroupIdArtifactIdVersionFromManifest` should
    * treat an empty `Implementation-Vendor-Id` as absent, returning `None`
    * rather than `Some("")`.
    *
    * '''Why:''' An empty string groupId causes `PurlException` when
    * `applyAccumulatedAugmentation` builds a Maven pURL.
    *
    * '''Requirement:''' R1 — `resolveGroupIdArtifactIdVersion` must never
    * return `Some("")` for any groupId/artifactId/version component; empty
    * strings must be normalized to `None`.
    *
    * '''Current behavior (buggy):''' Returns `Some("")` — FAILS until fixed.
    */
  test(
    "resolveGroupIdArtifactIdVersion returns non-empty groupId when Implementation-Vendor-Id is empty"
  ) {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-vendor-id" -> TreeSet(StringOrPair("")),
      "implementation-title" -> TreeSet(StringOrPair("collections-generic")),
      "implementation-version" -> TreeSet(StringOrPair("4.01"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "collections-generic-4.01.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      a,
      Some("collections-generic"),
      "artifactId from Implementation-Title"
    )
    assertEquals(v, Some("4.01"), "version from Implementation-Version")
    assert(
      g.isDefined,
      "groupId must be defined (not None) — empty Implementation-Vendor-Id must not produce Some(\"\")"
    )
    assert(g.get.nonEmpty, "groupId must be non-empty — not Some(\"\")")
    assertEquals(
      g,
      Some("collections-generic"),
      "groupId falls back to artifactId when no other source found"
    )
  }

  // ==================== Test 2: ADG build on wps_war_test directory ====================

  /** Tests that building the ADG on `test_data/wps_war_test/` (which contains
    * the WAR, POM, sources, and javadoc) completes without `PurlException`.
    *
    * '''What it tests:''' `ToProcess.buildGraphForToProcess` recursively
    * traverses into the WAR and processes each child JAR through
    * `applyAccumulatedAugmentation`. Child JARs like
    * `collections-generic-4.01.jar` have `Implementation-Vendor-Id:` (empty
    * value), causing `resolveGroupIdArtifactIdVersion` to return `Some("")` for
    * groupId, which triggers `PurlException` during pURL construction at
    * Maven.scala:1098.
    *
    * '''Why:''' This reproduces the exact production failure. The stack trace
    * shows the exception propagating from a child JAR's
    * `applyAccumulatedAugmentation` up through `withinArchiveStream` and being
    * caught as a generic `Exception` at FileWalker.scala:559, silently losing
    * all child artifacts.
    *
    * '''Requirement:''' R2 — ADG build on `test_data/wps_war_test/` must not
    * throw `PurlException`.
    *
    * '''Current behavior (buggy):''' Throws `PurlException` — FAILS until
    * fixed.
    */
  test("ADG build on wps_war_test directory does not throw PurlException") {
    val source = File("test_data/wps_war_test")
    assert(
      source.isDirectory,
      s"Test data not found: ${source.getAbsolutePath}"
    )

    val strategy = ToProcess.strategyForDirectory(source, false, None)
    assert(strategy.nonEmpty, "Expected at least one strategy from directory")

    val store = ToProcess.buildGraphForToProcess(
      strategy,
      args = Config()
    )

    // pURLs are stored in the store via store.addPurl() during
    // applyAccumulatedAugmentation. The purlOut callback is NOT used
    // by buildGraphForToProcess — it's a dead parameter.
    val allPurls = store.purls().toVector

    // Before the fix, the PurlException from collections-generic-4.01.jar
    // (empty Implementation-Vendor-Id) was caught inside withinArchiveStream
    // and aborted processing of all sibling JARs after it alphabetically.
    // After the fix, all 28 JARs in WEB-INF/lib/ should produce pURLs.
    val hasLog4jPurl = allPurls.exists(_.contains("log4j"))
    val hasJtsPurl = allPurls.exists(_.contains("jts"))
    val hasCollectionsGenericPurl =
      allPurls.exists(_.contains("collections-generic"))
    val hasCodecPurl = allPurls.exists(_.contains("codec"))

    assert(
      hasLog4jPurl,
      "log4j pURL must be present — if missing, PurlException in " +
        "collections-generic-4.01.jar aborted processing of sibling JARs"
    )
    assert(
      hasCodecPurl,
      "commons-codec pURL must be present — if missing, PurlException in " +
        "collections-generic-4.01.jar aborted processing of sibling JARs"
    )
    assert(
      hasJtsPurl,
      "jts pURL must be present — if missing, PurlException in " +
        "collections-generic-4.01.jar aborted processing of sibling JARs"
    )
    assert(
      hasCollectionsGenericPurl,
      "collections-generic pURL must be present — the JAR with empty " +
        "Implementation-Vendor-Id must now produce a valid pURL"
    )
  }

  // ==================== Test 3: Bundle-SymbolicName groupId when Implementation-Vendor-Id is empty ====================

  /** Tests that Bundle-SymbolicName provides groupId when
    * Implementation-Vendor-Id is empty.
    *
    * '''What it tests:''' With an empty Implementation-Vendor-Id, the groupId
    * should be derived from Bundle-SymbolicName's parent path. This was
    * previously blocked because `Some("")` short-circuited the `.orElse` chain.
    *
    * '''Requirement:''' R3 — Bundle-SymbolicName fallback for groupId must work
    * when Implementation-Vendor-Id is empty.
    */
  /** Tests that Bundle-SymbolicName provides groupId when
    * Implementation-Vendor-Id is empty, and that artifactId comes from the
    * filename (field-level merge: filename priority 4 > manifest priority 5 for
    * artifactId).
    *
    * '''What it tests:''' With field-level merge, the artifactId is derived
    * from the filename ("commons-lang"), not from the Bundle-SymbolicName
    * ("org.apache.commons.lang"). The Bundle-SymbolicName is the OSGi bundle
    * name, not the Maven artifactId. The filename matches the Maven artifactId.
    *
    * '''Why:''' The Maven artifactId for this artifact is "commons-lang"
    * (verified: org.apache.commons:commons-lang:2.4 exists in Maven Central).
    * The OSGi Bundle-SymbolicName "org.apache.commons.lang" is a different
    * naming convention. Field-level merge correctly picks the filename-derived
    * artifactId because filename has higher priority than manifest for the
    * artifactId field.
    *
    * '''Requirement:''' R3 — Bundle-SymbolicName fallback for groupId must work
    * when Implementation-Vendor-Id is empty. ArtifactId comes from filename per
    * field-level merge priority.
    *
    * '''Note:''' This test was updated from asserting `a =
    * Some("org.apache.commons.lang")` (OSGi name) to `a = Some("commons-lang")`
    * (Maven artifactId) per the field-level merge plan. The filename-derived
    * value is correct regardless of whether the Maven Bundle Plugin heuristic
    * fires (no `created-by` header in this test).
    */
  test(
    "Bundle-SymbolicName provides groupId when Implementation-Vendor-Id is empty"
  ) {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-vendor-id" -> TreeSet(StringOrPair("")),
      "bundle-symbolicname" -> TreeSet(StringOrPair("org.apache.commons.lang")),
      "bundle-version" -> TreeSet(StringOrPair("2.4"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "commons-lang-2.4.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      g,
      Some("org.apache.commons"),
      "groupId from Bundle-SymbolicName parent path"
    )
    assertEquals(
      a,
      Some("commons-lang"),
      "artifactId from filename (Maven artifactId), not Bundle-SymbolicName (OSGi name)"
    )
    assertEquals(v, Some("2.4"))
  }

  // ==================== Test 4: Extension-Name provides groupId ====================

  /** Tests that Extension-Name provides groupId when no other source has it.
    *
    * '''What it tests:''' When the manifest has Extension-Name with a
    * package-path value (e.g. `org.apache.commons.codec.*`), groupId is derived
    * by stripping `.*` and taking the parent path.
    *
    * '''Requirement:''' R4 — Extension-Name fallback for groupId.
    */
  test("Extension-Name provides groupId when no other source") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "extension-name" -> TreeSet(StringOrPair("org.apache.commons.codec.*")),
      "implementation-version" -> TreeSet(StringOrPair("1.2"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "commons-codec-1.2.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      g,
      Some("org.apache.commons"),
      "groupId from Extension-Name parent path"
    )
    assertEquals(
      a,
      Some("commons-codec"),
      "artifactId from filename (field-level merge: filename > manifest)"
    )
    assertEquals(v, Some("1.2"))
  }

  // ==================== Test 5: Automatic-Module-Name provides groupId ====================

  /** Tests that Automatic-Module-Name provides groupId when no other source.
    *
    * '''Requirement:''' R5 — Automatic-Module-Name fallback for groupId.
    */
  test("Automatic-Module-Name provides groupId when no other source") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "automatic-module-name" -> TreeSet(
        StringOrPair("org.apache.commons.codec")
      ),
      "extension-name" -> TreeSet(StringOrPair("commons-codec")),
      "implementation-version" -> TreeSet(StringOrPair("1.2"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "commons-codec-1.2.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      g,
      Some("org.apache.commons"),
      "groupId from Automatic-Module-Name parent path"
    )
    assertEquals(a, Some("commons-codec"), "artifactId from Extension-Name")
    assertEquals(v, Some("1.2"))
  }

  // ==================== Test 6: Package header provides groupId ====================

  /** Tests that the Package manifest header provides groupId when no other
    * source has it.
    *
    * '''Requirement:''' R6 — Package header fallback for groupId.
    */
  test("Package header provides groupId when no other source") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "package" -> TreeSet(StringOrPair("org.apache.commons.collections15")),
      "extension-name" -> TreeSet(StringOrPair("collections-generic")),
      "implementation-version" -> TreeSet(StringOrPair("4.01"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "collections-generic-4.01.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      g,
      Some("org.apache.commons"),
      "groupId from Package parent path"
    )
    assertEquals(
      a,
      Some("collections-generic"),
      "artifactId from Extension-Name"
    )
    assertEquals(v, Some("4.01"))
  }

  // ==================== Test 7: ArtifactId-as-groupId fallback ====================

  /** Tests that resolveGroupIdArtifactIdVersion uses artifactId as groupId when
    * no groupId source is available.
    *
    * '''What it tests:''' When a JAR has only a filename with no dot-separated
    * prefix (e.g. `collections-generic-4.01.jar`), and no manifest headers with
    * package paths, groupId falls back to the artifactId.
    *
    * '''Requirement:''' R7 — artifactId-as-groupId fallback.
    */
  test(
    "resolveGroupIdArtifactIdVersion falls back to artifactId as groupId when no groupId found"
  ) {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-title" -> TreeSet(StringOrPair("collections-generic")),
      "implementation-version" -> TreeSet(StringOrPair("4.01"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "collections-generic-4.01.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      g,
      Some("collections-generic"),
      "groupId falls back to artifactId"
    )
    assertEquals(
      a,
      Some("collections-generic"),
      "artifactId from Implementation-Title"
    )
    assertEquals(v, Some("4.01"))
  }

  // ==================== Test 8: Empty strings from pom.properties are normalized ====================

  /** Tests that empty strings in pom.properties are treated as missing,
    * allowing the orElse chain to fall through to the next priority.
    *
    * '''What it tests:''' If pom.properties has `groupId=` (empty), the
    * fromProps level returns None, and resolveGroupIdArtifactIdVersion falls
    * through to the manifest or filename.
    *
    * '''Requirement:''' R8 — empty strings normalized at each priority level.
    */
  test(
    "resolveGroupIdArtifactIdVersion normalizes empty strings from pom.properties"
  ) {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-vendor-id" -> TreeSet(StringOrPair("com.example")),
      "implementation-title" -> TreeSet(StringOrPair("my-artifact")),
      "implementation-version" -> TreeSet(StringOrPair("1.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "my-artifact-1.0.jar", None),
      None,
      manifest,
      Map("groupId" -> "", "artifactId" -> "my-artifact", "version" -> "1.0"),
      None
    )
    // pom.properties has empty groupId → should fall through to manifest
    assertEquals(
      g,
      Some("com.example"),
      "groupId from manifest, not empty pom.properties"
    )
    assertEquals(a, Some("my-artifact"))
    assertEquals(v, Some("1.0"))
  }

  // ==================== Test 9: pURL is valid when groupId = artifactId ====================

  /** Tests that a pURL with groupId = artifactId is valid (does not throw).
    *
    * '''What it tests:''' `PURLHelpers.buildPackageURL` with
    * `Some("collections-generic")` as namespace should produce a valid pURL
    * that survives `.toCanonical()`.
    *
    * '''Requirement:''' R9 — artifactId-as-groupId produces valid pURL.
    */
  test("pURL is valid when groupId equals artifactId") {
    val purl = PURLHelpers
      .buildPackageURL(
        Ecosystems.Maven,
        Some("collections-generic"),
        "collections-generic",
        "4.01",
        None
      )
      .toCanonical()
      .nn
    assert(
      purl.toString.contains("collections-generic"),
      s"pURL must contain artifactId: ${purl.toString}"
    )
  }

  // ==================== Test 10: Property test — resolveGroupIdArtifactIdVersion never returns Some("") ====================

  /** Tests that resolveGroupIdArtifactIdVersion never returns Some("") for any
    * groupId/artifactId/version component, regardless of manifest header
    * values.
    *
    * '''What it tests:''' For a variety of manifest configurations including
    * empty values, no component should be Some("").
    *
    * '''Requirement:''' R10 — no Some("") in any groupId/artifactId/version
    * component.
    */
  test(
    "resolveGroupIdArtifactIdVersion never returns Some(empty) for any groupId/artifactId/version component"
  ) {
    val state = MavenState()
    val manifests = List(
      // Empty Implementation-Vendor-Id
      TreeMap(
        "implementation-vendor-id" -> TreeSet(StringOrPair("")),
        "implementation-title" -> TreeSet(StringOrPair("x")),
        "implementation-version" -> TreeSet(StringOrPair("1"))
      ),
      // All empty
      TreeMap(
        "implementation-vendor-id" -> TreeSet(StringOrPair("")),
        "implementation-title" -> TreeSet(StringOrPair("")),
        "implementation-version" -> TreeSet(StringOrPair(""))
      ),
      // No headers at all
      TreeMap.empty[String, TreeSet[StringOrPair]],
      // Only empty Extension-Name
      TreeMap(
        "extension-name" -> TreeSet(StringOrPair("")),
        "implementation-version" -> TreeSet(StringOrPair("1"))
      )
    )
    for (manifest <- manifests) {
      val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
        ByteWrapper(Array.emptyByteArray, "test-1.0.jar", None),
        None,
        manifest,
        Map.empty,
        None
      )
      assert(
        g != Some(""),
        s"groupId must not be Some(\"\") for manifest: $manifest"
      )
      assert(
        a != Some(""),
        s"artifactId must not be Some(\"\") for manifest: $manifest"
      )
      assert(
        v != Some(""),
        s"version must not be Some(\"\") for manifest: $manifest"
      )
    }
  }

  // ==================== Test 11: Property test — if artifactId is Some, groupId is also Some ====================

  /** Tests that after the artifactId-as-groupId fallback, groupId is always
    * defined when artifactId is defined.
    *
    * '''Requirement:''' R11 — groupId is Some whenever artifactId is Some.
    */
  test(
    "resolveGroupIdArtifactIdVersion groupId is Some whenever artifactId is Some"
  ) {
    val state = MavenState()
    val manifests = List(
      // Only filename provides artifactId
      TreeMap.empty[String, TreeSet[StringOrPair]],
      // Manifest with only version
      TreeMap("implementation-version" -> TreeSet(StringOrPair("1.0"))),
      // Manifest with title (no dots) → artifactId fallback for groupId
      TreeMap(
        "implementation-title" -> TreeSet(StringOrPair("my-lib")),
        "implementation-version" -> TreeSet(StringOrPair("1.0"))
      )
    )
    for (manifest <- manifests) {
      val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
        ByteWrapper(Array.emptyByteArray, "my-lib-1.0.jar", None),
        None,
        manifest,
        Map.empty,
        None
      )
      if (a.isDefined) {
        assert(
          g.isDefined,
          s"groupId must be Some when artifactId is Some, for manifest: $manifest"
        )
      }
    }
  }
}
