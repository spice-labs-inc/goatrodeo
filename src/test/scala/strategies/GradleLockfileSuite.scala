/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.TreeSet

/** Phase 7 — Gradle Lockfile Parsing Strategy test suite.
  *
  * Tests GradleLockfile parsing for modern and legacy lockfile formats, pURL
  * generation, metadata emission, and robustness.
  */
class GradleLockfileSuite extends FunSuite {

  private def createTestItem(id: String): Item =
    Item(id, TreeSet.empty, None, None)

  private def loadLockfile(name: String): String = {
    val path = s"test_data/gradle/$name"
    val f = new File(path)
    if (!f.exists()) fail(s"Missing test corpus: $path")
    Files.readString(f.toPath)
  }

  // ==================== 7.2 Modern Format ====================

  test("GradleLockfile - parses modern lockfile format") {
    val wrapper = FileWrapper(
      new File("test_data/gradle/gradle.lockfile"),
      "test_data/gradle/gradle.lockfile",
      None
    )
    val content = wrapper.withStream(Helpers.slurpInputToString(_))
    val deps = GradleLockfile.parseLockfile(content, None)

    assertEquals(
      deps.size,
      3,
      "Should parse 3 dependencies (ignoring empty= line)"
    )

    val dep1 = deps.find(d => d.artifactId == "commons-text").get
    assertEquals(dep1.groupId, "org.apache.commons")
    assertEquals(dep1.version, "1.8")
    assertEquals(
      dep1.configurations,
      Vector("compileClasspath", "runtimeClasspath")
    )

    val dep2 = deps.find(d => d.artifactId == "slf4j-api").get
    assertEquals(dep2.groupId, "org.slf4j")
    assertEquals(dep2.version, "2.0.7")
    assertEquals(
      dep2.configurations,
      Vector("compileClasspath", "runtimeClasspath")
    )

    val dep3 = deps.find(d => d.artifactId == "guava").get
    assertEquals(dep3.groupId, "com.google.guava")
    assertEquals(dep3.version, "31.1-jre")
    assertEquals(dep3.configurations, Vector("compileClasspath"))
  }

  test("GradleLockfile - generates pURLs for each dependency") {
    val content = loadLockfile("gradle.lockfile")
    val deps = GradleLockfile.parseLockfile(content, None)
    val state = new GradleLockfileState(deps)

    val wrapper = FileWrapper(
      new File("test_data/gradle/gradle.lockfile"),
      "test_data/gradle/gradle.lockfile",
      None
    )

    val (purlSet, _) =
      state.getPurls(wrapper, createTestItem("t"), SingleMarker())
    val purls = purlSet.canonicalStrings
    assertEquals(purls.size, 3, "Should generate 3 pURLs")

    assert(
      purls.exists(_.contains("org.apache.commons")),
      "Should contain commons pURL"
    )
    assert(purls.exists(_.contains("slf4j-api")), "Should contain slf4j pURL")
    assert(purls.exists(_.contains("guava")), "Should contain guava pURL")

    // Verify one is a proper maven pURL
    val commons = purls.find(_.contains("commons-text")).get
    assert(
      commons.startsWith("pkg:maven/"),
      s"pURL should start with pkg:maven/: $commons"
    )
    assert(commons.contains("1.8"), s"pURL should contain version: $commons")
  }

  test("GradleLockfile - skips comment lines") {
    val contentWithComments = """# This is a Gradle generated lockfile.
      |# Please DO NOT manually edit this file.
      |org.example:lib:1.0=compileClasspath
      |""".stripMargin
    val deps = GradleLockfile.parseLockfile(contentWithComments, None)
    assertEquals(deps.size, 1, "Should skip comment lines")
    assertEquals(deps.head.artifactId, "lib")
  }

  test("GradleLockfile - skips empty= line") {
    val content = """org.example:lib:1.0=compileClasspath
      |empty=annotationProcessor
      |""".stripMargin
    val deps = GradleLockfile.parseLockfile(content, None)
    assertEquals(deps.size, 1, "Should skip empty= sentinel line")
    assertEquals(deps.head.artifactId, "lib")
  }

  test("GradleLockfile - preserves configuration list in metadata") {
    val content = loadLockfile("gradle.lockfile")
    val deps = GradleLockfile.parseLockfile(content, None)
    val state = new GradleLockfileState(deps)

    val wrapper = FileWrapper(
      new File("test_data/gradle/gradle.lockfile"),
      "test_data/gradle/gradle.lockfile",
      None
    )

    val (meta, _) =
      state.getMetadata(wrapper, createTestItem("t"), SingleMarker())
    assert(
      meta.contains(MetadataKeyConstants.DEPENDENCIES),
      "Metadata should contain Dependencies key"
    )
    val depJson = meta(MetadataKeyConstants.DEPENDENCIES).head.value
    assert(
      depJson.contains("compileClasspath"),
      s"JSON should contain configuration: $depJson"
    )
    assert(
      depJson.contains("runtimeClasspath"),
      s"JSON should contain runtimeClasspath: $depJson"
    )
  }

  test("GradleLockfile - handles malformed lines gracefully") {
    val content = """org.example:lib:1.0=compileClasspath
      |badline
      |too:many:colons:here:1.0=compileClasspath
      |incomplete=
      |org.good:artifact:2.0=compileClasspath
      |""".stripMargin
    val deps = GradleLockfile.parseLockfile(content, None)
    assertEquals(deps.size, 2, "Should skip malformed lines")
    assert(deps.exists(_.artifactId == "lib"))
    assert(deps.exists(_.artifactId == "artifact"))
  }

  // ==================== 7.3 Legacy Format ====================

  test("GradleLockfile - parses legacy per-configuration lockfile") {
    val wrapper = FileWrapper(
      new File("test_data/gradle/dependency-locks/compileClasspath.lockfile"),
      "test_data/gradle/dependency-locks/compileClasspath.lockfile",
      None
    )
    val content = wrapper.withStream(Helpers.slurpInputToString(_))
    val deps = GradleLockfile.parseLockfile(content, None)

    assertEquals(deps.size, 2, "Should parse legacy format")
    val commons = deps.find(_.artifactId == "commons-text").get
    assertEquals(commons.version, "1.8")
    assertEquals(
      commons.configurations,
      Vector.empty,
      "Legacy without explicit config has empty configs when no filename config"
    )
  }

  test("GradleLockfile - associates config name from filename") {
    val wrapper = FileWrapper(
      new File("test_data/gradle/dependency-locks/compileClasspath.lockfile"),
      "test_data/gradle/dependency-locks/compileClasspath.lockfile",
      None
    )
    val content = wrapper.withStream(Helpers.slurpInputToString(_))
    // The filename is compileClasspath.lockfile, so configFromFilename should be compileClasspath
    val configFromFilename = Some("compileClasspath")
    val deps = GradleLockfile.parseLockfile(content, configFromFilename)

    assert(
      deps.forall(_.configurations == Vector("compileClasspath")),
      "Legacy lockfile should inherit config from filename"
    )
  }

  // ==================== 7.4 Corpus Integration ====================

  test("corpus buildscript-gradle.lockfile produces pURLs") {
    val wrapper = FileWrapper(
      new File("test_data/gradle/buildscript-gradle.lockfile"),
      "test_data/gradle/buildscript-gradle.lockfile",
      None
    )
    val content = wrapper.withStream(Helpers.slurpInputToString(_))
    val deps = GradleLockfile.parseLockfile(content, None)
    val state = new GradleLockfileState(deps)

    val (purlSet, _) =
      state.getPurls(wrapper, createTestItem("t"), SingleMarker())
    val purls = purlSet.canonicalStrings
    assert(purls.nonEmpty, "Buildscript lockfile should produce pURLs")
    assert(
      purls.forall(_.startsWith("pkg:maven/")),
      "All pURLs should be Maven type"
    )
  }

  test("corpus runtimeClasspath legacy lockfile produces pURLs") {
    val wrapper = FileWrapper(
      new File("test_data/gradle/dependency-locks/runtimeClasspath.lockfile"),
      "test_data/gradle/dependency-locks/runtimeClasspath.lockfile",
      None
    )
    val content = wrapper.withStream(Helpers.slurpInputToString(_))
    val configFromFilename = Some("runtimeClasspath")
    val deps = GradleLockfile.parseLockfile(content, configFromFilename)
    val state = new GradleLockfileState(deps)

    val (purlSet, _) =
      state.getPurls(wrapper, createTestItem("t"), SingleMarker())
    val purls = purlSet.canonicalStrings
    assert(purls.nonEmpty, "Runtime lockfile should produce pURLs")
    assert(
      purls.exists(_.contains("postgresql")),
      "Should contain postgresql dependency"
    )
  }
}
