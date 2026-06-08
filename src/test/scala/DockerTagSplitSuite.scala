/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

import io.spicelabs.goatrodeo.omnibor.strategies.DockerMarkers
import io.spicelabs.goatrodeo.omnibor.strategies.DockerState
import io.spicelabs.goatrodeo.omnibor.strategies.ManifestInfo
import io.spicelabs.goatrodeo.util.ByteWrapper
import munit.FunSuite
import org.json4s.*
import org.json4s.JsonAST.*

/** Phase 0 (0.10) — Docker tag split Nil guard rail.
  *
  * ## What this tests
  *
  * The `computePurls` method in `DockerState` uses a pattern match on
  * `base.split("/").toList` with a `Nil` case. This test verifies that
  * `"".split("/").toList` produces `List("")` (not `Nil`), confirming that the
  * `Nil` case is defensive programming rather than a currently reachable path.
  *
  * ## Why this matters
  *
  * If Scala's `String.split` behavior ever changed, or if a different splitting
  * method were substituted, the `Nil` case would become reachable. This test
  * documents the current behavior and guards against future regressions that
  * could cause a `MatchError` crash.
  *
  * ## Requirement trace
  *
  * Phase 0 item 0.10: Docker tag split handles the empty-string base
  * gracefully; the Nil case is documented as defensive.
  *
  * ## LLM-friendly summary
  *
  * | Test                   | Input                           | Expected                  |
  * |:-----------------------|:--------------------------------|:--------------------------|
  * | empty string split     | "".split("/").toList            | List(""), not Nil         |
  * | single component split | "foo".split("/").toList         | List("foo")               |
  * | two component split    | "ns/path".split("/").toList     | List("ns", "path")        |
  * | three component split  | "ns/sub/path".split("/").toList | List("ns", "sub", "path") |
  */
class DockerTagSplitSuite extends FunSuite {

  test("Docker - empty base split handled gracefully") {

    /** What: Evaluates "".split("/").toList and confirms it is not Nil. Why:
      * The computePurls match has a Nil case that is defensive. If this split
      * ever returned Nil, the code would still be safe, but we document that it
      * currently does not. Requirement: Phase 0 §0.10 — empty string split does
      * not produce Nil.
      */
    val result = "".split("/").toList
    assert(
      result != Nil,
      "Empty string split should produce List(\"\"), not Nil — the Nil case is defensive"
    )
    assertEquals(result, List(""), "Empty string split should yield List(\"\")")
  }

  test("Docker - single component split produces expected pattern") {

    /** What: Evaluates "foo".split("/").toList and confirms it matches the
      * `blob :: Nil` case in computePurls. Why: A Docker tag like
      * "myimage:latest" has no namespace separator, so the split yields a
      * single element. Requirement: Phase 0 §0.10 — single component matches
      * blob :: Nil.
      */
    val result = "foo".split("/").toList
    assertEquals(result, List("foo"))
    result match {
      case blob :: Nil =>
        assertEquals(blob, "foo")
      case _ => fail("Single component should match `blob :: Nil`")
    }
  }

  test("Docker - two component split produces expected pattern") {

    /** What: Evaluates "ns/path".split("/").toList and confirms it matches the
      * `path :: subPath :: Nil` case in computePurls. Why: A Docker tag like
      * "myorg/myimage:latest" has exactly one slash, yielding two components
      * with no namespace. Requirement: Phase 0 §0.10 — two components match
      * path :: subPath :: Nil.
      */
    val result = "ns/path".split("/").toList
    assertEquals(result, List("ns", "path"))
    result match {
      case path :: subPath :: Nil =>
        assertEquals(path, "ns")
        assertEquals(subPath, "path")
      case _ => fail("Two components should match `path :: subPath :: Nil`")
    }
  }

  test("Docker - three component split produces expected pattern") {

    /** What: Evaluates "registry/ns/path".split("/").toList and confirms it
      * matches the `namespace :: pathAndSubpath` case in computePurls. Why: A
      * Docker tag like "registry.example.com/myorg/myimage:latest" has two or
      * more slashes, yielding a namespace + remaining path. Requirement: Phase
      * 0 §0.10 — three+ components match namespace :: pathAndSubpath.
      */
    val result = "registry/ns/path".split("/").toList
    assertEquals(result, List("registry", "ns", "path"))
    result match {
      case namespace :: pathAndSubpath =>
        assertEquals(namespace, "registry")
        assertEquals(pathAndSubpath.reduceLeft((a, b) => s"$a/$b"), "ns/path")
      case _ =>
        fail("Three+ components should match `namespace :: pathAndSubpath`")
    }
  }

  // ==================== maybePackageTag Tests ====================

  /** Helper to build a ManifestInfo with the given manifestConfig JValue. */
  private def makeManifestInfo(manifestConfig: JValue): ManifestInfo = {
    val dummyWrapper = ByteWrapper(Array.emptyByteArray, "dummy", None)
    ManifestInfo(
      manifest = dummyWrapper,
      manifestConfig = manifestConfig,
      configHash = "abc123",
      configFile = dummyWrapper,
      configJson = JObject(),
      layers = List.empty
    )
  }

  test("Docker - maybePackageTag returns Some with version for normal tag") {

    /** What: A Config marker with a RepoTags entry like "myimage:latest"
      * produces Some(PackageTagInfo) where version is Some("latest"). Why: The
      * normal Docker tag format "repo:tag" should split at the last colon to
      * extract the version component. This is the primary use case for
      * maybePackageTag. Requirement: Phase 0 §0.10 — normal tag returns Some
      * with version.
      */
    val manifestConfig = JObject(
      "RepoTags" -> JArray(List(JString("myimage:latest")))
    )
    val info = makeManifestInfo(manifestConfig)
    val marker = DockerMarkers.Config(info)
    val state = DockerState(Map())

    val result = state.maybePackageTag(marker)
    assert(
      result.isDefined,
      "maybePackageTag should return Some for Config with RepoTags"
    )
    val tagInfo = result.get
    assertEquals(tagInfo.name, "myimage:latest")
    assertEquals(tagInfo.version, Some("latest"))
  }

  test(
    "Docker - maybePackageTag returns Some with None version for tag without colon"
  ) {

    /** What: A Config marker with a RepoTags entry like "myimage" (no colon)
      * produces Some(PackageTagInfo) where version is None. Why: Docker tags
      * without a colon (no explicit version) are valid. The lastIndexOf(":")
      * returns -1, so versionOpt is None. The tag name is the full string. This
      * edge case must be handled without crashing. Requirement: Phase 0 §0.10 —
      * empty tag split returns None for version.
      */
    val manifestConfig = JObject(
      "RepoTags" -> JArray(List(JString("myimage")))
    )
    val info = makeManifestInfo(manifestConfig)
    val marker = DockerMarkers.Config(info)
    val state = DockerState(Map())

    val result = state.maybePackageTag(marker)
    assert(
      result.isDefined,
      "maybePackageTag should return Some for Config with RepoTags"
    )
    val tagInfo = result.get
    assertEquals(tagInfo.name, "myimage")
    assertEquals(
      tagInfo.version,
      None,
      "Version must be None for tag without colon"
    )
  }

  test(
    "Docker - maybePackageTag returns Some with None version for colon at position 0"
  ) {

    /** What: A Config marker with a RepoTags entry like ":latest" (colon at
      * position 0) produces Some(PackageTagInfo) where version is None, because
      * lastIndexOf(":") returns 0, which is NOT > 0. Why: The code checks `x >
      * 0` not `x >= 0`, so a colon at position 0 is treated the same as no
      * colon — version is None. This is a defensive edge case; real Docker tags
      * never start with a colon. Requirement: Phase 0 §0.10 — colon at position
      * 0 yields None version.
      */
    val manifestConfig = JObject(
      "RepoTags" -> JArray(List(JString(":latest")))
    )
    val info = makeManifestInfo(manifestConfig)
    val marker = DockerMarkers.Config(info)
    val state = DockerState(Map())

    val result = state.maybePackageTag(marker)
    assert(result.isDefined)
    val tagInfo = result.get
    assertEquals(tagInfo.name, ":latest")
    assertEquals(
      tagInfo.version,
      None,
      "Version must be None when colon is at position 0"
    )
  }

  test("Docker - maybePackageTag returns None for Manifest marker") {

    /** What: A Manifest marker (not Config) produces None. Why: Only Config
      * markers carry RepoTags; Manifest markers have no tag information to
      * contribute. Requirement: Phase 0 §0.10 — non-Config markers return None.
      */
    val state = DockerState(Map())
    val result = state.maybePackageTag(DockerMarkers.Manifest)
    assert(
      result.isEmpty,
      "maybePackageTag should return None for Manifest marker"
    )
  }

  test("Docker - maybePackageTag returns None for Layer marker") {

    /** What: A Layer marker produces None. Why: Layers are tarballs, not image
      * configurations; they have no package tag. Requirement: Phase 0 §0.10 —
      * Layer markers return None.
      */
    val state = DockerState(Map())
    val result = state.maybePackageTag(DockerMarkers.Layer("sha256:abc"))
    assert(
      result.isEmpty,
      "maybePackageTag should return None for Layer marker"
    )
  }

  test("Docker - maybePackageTag returns None for Config without RepoTags") {

    /** What: A Config marker whose manifestConfig has no RepoTags field
      * produces None. Why: A manifest entry without RepoTags (e.g., a manifest
      * list or an imported image with no tag) has no tag information to
      * contribute. Requirement: Phase 0 §0.10 — Config without RepoTags returns
      * None.
      */
    val manifestConfig = JObject() // no RepoTags
    val info = makeManifestInfo(manifestConfig)
    val marker = DockerMarkers.Config(info)
    val state = DockerState(Map())

    val result = state.maybePackageTag(marker)
    assert(
      result.isEmpty,
      "maybePackageTag should return None for Config without RepoTags"
    )
  }

  test("Docker - maybePackageTag uses first RepoTag when multiple exist") {

    /** What: A Config marker with multiple RepoTags (e.g., "foo:1.0" and
      * "foo:latest") uses the first tag via headOption. Why: Docker images can
      * have multiple tags; the strategy picks the first one as the primary
      * package tag. Requirement: Phase 0 §0.10 — first RepoTag is used.
      */
    val manifestConfig = JObject(
      "RepoTags" -> JArray(List(JString("foo:1.0"), JString("foo:latest")))
    )
    val info = makeManifestInfo(manifestConfig)
    val marker = DockerMarkers.Config(info)
    val state = DockerState(Map())

    val result = state.maybePackageTag(marker)
    assert(result.isDefined)
    val tagInfo = result.get
    assertEquals(tagInfo.name, "foo:1.0")
    assertEquals(tagInfo.version, Some("1.0"))
  }

  test("Docker - maybePackageTag handles namespaced tag with version") {

    /** What: A Config marker with a namespaced RepoTag like
      * "spicelabs/bigtent:0.8.3" produces a tag with the full name and version
      * extracted from after the last colon. Why: Namespaced Docker images
      * (org/image:tag) are the most common format. The version extraction must
      * split at the LAST colon, not the first (which would incorrectly treat
      * the org as the version). Requirement: Phase 0 §0.10 — lastIndexOf(":")
      * extracts version.
      */
    val manifestConfig = JObject(
      "RepoTags" -> JArray(List(JString("spicelabs/bigtent:0.8.3")))
    )
    val info = makeManifestInfo(manifestConfig)
    val marker = DockerMarkers.Config(info)
    val state = DockerState(Map())

    val result = state.maybePackageTag(marker)
    assert(result.isDefined)
    val tagInfo = result.get
    assertEquals(tagInfo.name, "spicelabs/bigtent:0.8.3")
    assertEquals(tagInfo.version, Some("0.8.3"))
  }
}
