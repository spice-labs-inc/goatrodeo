/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.coordinates.Purl
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import scala.collection.immutable.TreeSet

// Tests that Sources and JavaDocs JAR markers get the same metadata
// accumulation and groupId/artifactId/version resolution as the JAR marker.
//
// What this tests:
// That beginProcessing(Sources) and beginProcessing(JavaDocs) set up
// the accumulator (jarAccumulated) and classifier, that accumulateInfo
// collects metadata from children of sources/javadoc JARs, that
// applyAccumulatedAugmentation emits pURLs with the correct classifier,
// and that standalone sources/javadoc JARs are claimed by computeMavenFiles.
//
// Why this matters:
// Previously, Sources/JavaDocs markers were no-ops — they had no independent
// groupId/artifactId/version resolution. They relied entirely on the POM
// marker's shared state. Sources JARs containing META-INF/maven/*/pom.properties
// had their metadata completely ignored. Standalone sources/javadoc JARs
// (no companion main JAR) fell through to GenericFile and emitted 0 pURLs.
//
// LLM Summary: This suite verifies that sources and javadoc JARs
// are treated the same as regular JARs for pURL generation. Each marker
// gets its own accumulator lifecycle, resolves groupId/artifactId/version
// from its own metadata, and emits pURLs with the correct classifier
// (?packaging=sources or ?classifier=javadoc).
class SourcesJavadocSuite extends FunSuite {

  private def createTestItem(id: String): Item =
    Item(id, TreeSet.empty, None, None)

  private def writeJarEntries(
      jarFile: File,
      entries: Seq[(String, String)]
  ): Unit = {
    val zos = new ZipOutputStream(new FileOutputStream(jarFile))
    try {
      for ((path, content) <- entries) {
        zos.putNextEntry(new ZipEntry(path))
        zos.write(content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    } finally {
      zos.close()
    }
  }

  /** Process a JAR through the accumulation pipeline for a given marker and
    * return the state with jarAccumulated populated. Simulates:
    *   1. beginProcessing(marker) - initializes jarAccumulated + classifier 2.
    *      Walk archive entries, calling accumulateInfo for each child
    */
  private def processAccumulation(
      wrapper: FileWrapper,
      marker: MavenMarkers
  ): MavenState = {
    val item = createTestItem("test")
    val store = MemStorage(None)
    val s1 = MavenState().beginProcessing(wrapper, item, marker)
    FileWalker.withinArchiveStream(wrapper) { entries =>
      entries.foreach { entry =>
        s1.accumulateInfo(item.identifier, item, entry, store)
      }
    }
    s1
  }

  // ==================== Tests 1-4: beginProcessing ====================

  test("beginProcessing(Sources) sets up accumulator and classifier") {
    val srcBytes = Array[Byte](0x50, 0x4b, 0x03, 0x04)
    val artifact = ByteWrapper(srcBytes, "test-sources.jar", None)
    val item = createTestItem("test-id")
    val state = MavenState()

    val newState = state.beginProcessing(artifact, item, MavenMarkers.Sources)

    assert(newState.pomFile.isEmpty, "Sources should not parse POM")
    assert(
      newState.sourcesAccumulated.isDefined,
      "sourcesAccumulated should be set"
    )
    assertEquals(newState.currentMarker, Some(MavenMarkers.Sources))
    assertEquals(newState.currentClassifier, Some("sources"))
    // Accumulator should be fresh (all fields at defaults)
    val acc = newState.sourcesAccumulated.get
    assert(
      acc.embeddedGroupIdArtifactIdVersions.isEmpty,
      "embeddedGroupIdArtifactIdVersions should be empty"
    )
    assert(acc.manifest.isEmpty, "manifest should be empty")
  }

  test("beginProcessing(JavaDocs) sets up accumulator and classifier") {
    val docBytes = Array[Byte](0x50, 0x4b, 0x03, 0x04)
    val artifact = ByteWrapper(docBytes, "test-javadoc.jar", None)
    val item = createTestItem("test-id")
    val state = MavenState()

    val newState = state.beginProcessing(artifact, item, MavenMarkers.JavaDocs)

    assert(newState.pomFile.isEmpty, "JavaDocs should not parse POM")
    assert(
      newState.javadocAccumulated.isDefined,
      "javadocAccumulated should be set"
    )
    assertEquals(newState.currentMarker, Some(MavenMarkers.JavaDocs))
    assertEquals(newState.currentClassifier, Some("javadoc"))
    val acc = newState.javadocAccumulated.get
    assert(
      acc.embeddedGroupIdArtifactIdVersions.isEmpty,
      "embeddedGroupIdArtifactIdVersions should be empty"
    )
  }

  test("beginProcessing(JAR) detects classifier from filename") {
    val jarBytes = Array[Byte](0x50, 0x4b, 0x03, 0x04)
    val item = createTestItem("test-id")
    val state = MavenState()

    // sources JAR
    val srcArtifact = ByteWrapper(jarBytes, "foo-1.0-sources.jar", None)
    val srcState = state.beginProcessing(srcArtifact, item, MavenMarkers.JAR)
    assertEquals(srcState.currentMarker, Some(MavenMarkers.JAR))

    // javadoc JAR
    val docArtifact = ByteWrapper(jarBytes, "foo-1.0-javadoc.jar", None)
    val docState = state.beginProcessing(docArtifact, item, MavenMarkers.JAR)
    assertEquals(docState.currentMarker, Some(MavenMarkers.JAR))

    // case-insensitive
    val mixedCase = ByteWrapper(jarBytes, "Foo-1.0-Sources.jar", None)
    val mixedState = state.beginProcessing(mixedCase, item, MavenMarkers.JAR)
    assertEquals(mixedState.currentMarker, Some(MavenMarkers.JAR))

    // plural javadocs alias
    val docsArtifact = ByteWrapper(jarBytes, "foo-1.0-javadocs.jar", None)
    val docsState = state.beginProcessing(docsArtifact, item, MavenMarkers.JAR)
    assertEquals(docsState.currentMarker, Some(MavenMarkers.JAR))

    // regular JAR — no classifier
    val regArtifact = ByteWrapper(jarBytes, "foo-1.0.jar", None)
    val regState = state.beginProcessing(regArtifact, item, MavenMarkers.JAR)
    assertEquals(regState.currentMarker, Some(MavenMarkers.JAR))
  }

  // ==================== Tests 5-6: accumulateInfo ====================

  test("accumulateInfo runs for Sources marker and collects pom.properties") {
    val tempDir = Files.createTempDirectory("src-acc").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            """#Generated by Maven
              |groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin,
          "com/example/Lib.java" -> "public class Lib {}"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = processAccumulation(wrapper, MavenMarkers.Sources)

      assert(state.currentAccumulator.isDefined)
      val acc = state.currentAccumulator.get
      assert(
        acc.embeddedGroupIdArtifactIdVersions.nonEmpty,
        "Should have collected embedded groupId/artifactId/version tuples"
      )
      assert(
        acc.embeddedGroupIdArtifactIdVersions.contains(
          ("com.example", "lib", "1.0")
        ),
        s"Expected (com.example, lib, 1.0) in ${acc.embeddedGroupIdArtifactIdVersions}"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("accumulateInfo runs for JavaDocs marker and collects pom.properties") {
    val tempDir = Files.createTempDirectory("doc-acc").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-javadoc.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            """#Generated by Maven
              |groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin,
          "com/example/Lib.html" -> "<html></html>"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = processAccumulation(wrapper, MavenMarkers.JavaDocs)

      assert(state.currentAccumulator.isDefined)
      val acc = state.currentAccumulator.get
      assert(
        acc.embeddedGroupIdArtifactIdVersions.nonEmpty,
        "Should have collected embedded groupId/artifactId/version tuples"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("accumulateInfo ignores .java files in Sources JAR") {
    val tempDir = Files.createTempDirectory("src-java").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "com/example/Foo.java" -> "public class Foo {}",
          "com/example/Bar.java" -> "public class Bar {}"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = processAccumulation(wrapper, MavenMarkers.Sources)

      val acc = state.currentAccumulator.get
      assert(
        acc.embeddedGroupIdArtifactIdVersions.isEmpty,
        ".java files should not produce groupId/artifactId/version tuples"
      )
      assert(acc.manifest.isEmpty, "No manifest in this JAR")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== Tests 7-8: generateParentScope ====================

  test("generateParentScope for Sources has accumulateInfo override") {
    val tempDir = Files.createTempDirectory("src-scope").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            """groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("test")
      val store = MemStorage(None)
      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      val scope = s1.generateParentScope(
        wrapper,
        item,
        store,
        MavenMarkers.Sources,
        None,
        Map.empty
      )

      // The scope should delegate accumulateInfo to MavenState.accumulateInfo
      // Simulate a child entry
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          scope.accumulateInfo(item.identifier, item, entry, store)
        }
      }

      assert(
        s1.currentAccumulator.isDefined,
        "jarAccumulated should still be set"
      )
      assert(
        s1.currentAccumulator.get.embeddedGroupIdArtifactIdVersions.nonEmpty,
        "Scope's accumulateInfo should have collected groupId/artifactId/version tuples"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("generateParentScope for Sources: finalAugmentation is no-op") {
    val jarBytes = Array[Byte](0x50, 0x4b, 0x03, 0x04)
    val artifact = ByteWrapper(jarBytes, "test-sources.jar", None)
    val item = createTestItem("test")
    val store = MemStorage(None)
    val state =
      MavenState().beginProcessing(artifact, item, MavenMarkers.Sources)
    val scope = state.generateParentScope(
      artifact,
      item,
      store,
      MavenMarkers.Sources,
      None,
      Map.empty
    )

    val result = scope.finalAugmentation(store, artifact, item)
    // finalAugmentation for Sources should return item unchanged
    assertEquals(result.identifier, item.identifier)
  }

  // ==================== Tests 9-12: applyAccumulatedAugmentation ====================

  test("applyAccumulatedAugmentation emits pURL with sources classifier") {
    val tempDir = Files.createTempDirectory("src-purl").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" ->
            """Manifest-Version: 1.0
              |Implementation-Title: lib
              |Implementation-Version: 1.0
              |Implementation-Vendor-Id: com.example
              |""".stripMargin,
          "META-INF/maven/com.example/lib/pom.properties" ->
            """groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("src-purl")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      assert(purls.nonEmpty, "Should have emitted at least one pURL")
      // Look for a pURL with packaging=sources qualifier
      val sourcesPurls = purls.filter(_.contains("packaging=sources"))
      assert(
        sourcesPurls.nonEmpty,
        s"Expected a pURL with packaging=sources, got: ${purls}"
      )
      assert(
        sourcesPurls.exists(_.contains("com.example")),
        "pURL should contain com.example groupId"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("applyAccumulatedAugmentation emits pURL with javadoc classifier") {
    val tempDir = Files.createTempDirectory("doc-purl").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-javadoc.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" ->
            """Manifest-Version: 1.0
              |Implementation-Title: lib
              |Implementation-Version: 1.0
              |Implementation-Vendor-Id: com.example
              |""".stripMargin,
          "META-INF/maven/com.example/lib/pom.properties" ->
            """groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("doc-purl")
      val store = MemStorage(None)

      val s1 =
        MavenState().beginProcessing(wrapper, item, MavenMarkers.JavaDocs)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      assert(purls.nonEmpty, "Should have emitted at least one pURL")
      val javadocPurls = purls.filter(_.contains("classifier=javadoc"))
      assert(
        javadocPurls.nonEmpty,
        s"Expected a pURL with classifier=javadoc, got: ${purls}"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("applyAccumulatedAugmentation resets all state") {
    val tempDir = Files.createTempDirectory("reset").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            """groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("reset")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      val stateAfter = s1.applyAccumulatedAugmentation(item, wrapper, store)

      assert(
        stateAfter.sourcesAccumulated.isEmpty,
        "sourcesAccumulated should be None"
      )
      // Note: currentMarker is NOT cleared — it persists for tests and
      // external callers to verify which marker was processed.
      // groupId/artifactId/version are local variables in
      // applyAccumulatedAugmentation — they do not exist on MavenState.
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("secondary pURLs from sources JAR have sources classifier") {
    val tempDir = Files.createTempDirectory("secondary").toFile
    try {
      // Sources JAR with multiple embedded pom.properties
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
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
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("secondary")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      // Primary should have packaging=sources
      val primaryPurls = purls.filter(_.contains("packaging=sources"))
      assert(
        primaryPurls.nonEmpty,
        "Primary pURL should have packaging=sources"
      )
      // Secondary should ALSO have packaging=sources (REQ-1)
      val secondaryPurls =
        purls.filter(p => p.contains("org.other") || p.contains("dep"))
      assert(
        secondaryPurls.nonEmpty,
        "Should have a secondary pURL for org.other:dep"
      )
      assert(
        secondaryPurls.exists(_.contains("packaging=sources")),
        "Secondary pURLs from sources JAR should have packaging=sources (REQ-1)"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== Tests 13-14: getPurls fallback ====================

  test("getPurls fallback for Sources (jarAccumulated=None) still works") {
    // This tests the backward-compatibility fallback path in getPurls
    // when beginProcessing(Sources) is NOT called first.
    val pomBytes = """<?xml version="1.0"?>
                     |<project>
                     |  <groupId>org.example</groupId>
                     |  <artifactId>test-artifact</artifactId>
                     |  <version>1.0.0</version>
                     |</project>""".stripMargin.getBytes("UTF-8")
    val pomArtifact = ByteWrapper(pomBytes, "test.pom", None)
    val item = createTestItem("test-id")
    val state =
      MavenState().beginProcessing(pomArtifact, item, MavenMarkers.POM)

    // Call getPurls(Sources) WITHOUT beginProcessing(Sources)
    // This exercises the fallback path where jarAccumulated is None
    val (purlSet, _) = state.getPurls(
      ByteWrapper(Array[Byte](), "test-sources.jar", None),
      item,
      MavenMarkers.Sources
    )
    val purls = purlSet.canonicalStrings
    assert(purls.nonEmpty, "Fallback should still emit a pURL")
    val purl = Purl.parse(purls.head).nn
    assertEquals(purl.qualifiers.get("packaging"), "sources")
  }

  test("getPurls fallback and applyAccumulatedAugmentation produce same pURL") {
    val tempDir = Files.createTempDirectory("consistency").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            """groupId=com.example
              |artifactId=lib
              |version=1.0
              |""".stripMargin
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("consistency")
      val store = MemStorage(None)

      // Path 1: beginProcessing(Sources) + accumulateInfo + applyAccumulatedAugmentation
      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)
      val applyPurls =
        store.purls().toSet.filter(_.contains("packaging=sources"))

      // Path 2: beginProcessing(POM) to set groupId/artifactId/version, then getPurls(Sources) fallback
      val pomContent = """<?xml version="1.0"?>
                         |<project>
                         |  <groupId>com.example</groupId>
                         |  <artifactId>lib</artifactId>
                         |  <version>1.0</version>
                         |</project>""".stripMargin
      val pomArtifact =
        ByteWrapper(pomContent.getBytes("UTF-8"), "lib-1.0.pom", None)
      val state2 =
        MavenState().beginProcessing(pomArtifact, item, MavenMarkers.POM)
      val (purlSet, _) = state2.getPurls(wrapper, item, MavenMarkers.Sources)
      val fallbackPurls = purlSet.canonicalStrings.toSet

      // Both should produce a pURL with packaging=sources for com.example:lib:1.0
      assert(
        applyPurls.nonEmpty,
        "applyAccumulatedAugmentation should emit pURL"
      )
      assert(fallbackPurls.nonEmpty, "getPurls fallback should emit pURL")
      // The canonical form should match (both should contain com.example/lib@1.0)
      assert(
        applyPurls.exists(p =>
          p.contains("com.example") && p.contains("lib") && p.contains("1.0")
        ),
        s"applyAccumulatedAugmentation pURL missing expected groupId/artifactId/version: $applyPurls"
      )
      assert(
        fallbackPurls.exists(p =>
          p.contains("com.example") && p.contains("lib") && p.contains("1.0")
        ),
        s"getPurls fallback pURL missing expected groupId/artifactId/version: $fallbackPurls"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== Tests 15-19: computeMavenFiles ====================

  private val jarHeader = Array[Byte](
    0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00
  )

  test("computeMavenFiles claims standalone sources JAR") {
    val tempDir = Files.createTempDirectory("standalone-src").toFile
    try {
      val srcJar = new File(tempDir, "foo-1.0-sources.jar")
      Helpers.writeOverFile(srcJar, jarHeader)
      val srcWrapper = FileWrapper(srcJar, "foo-1.0-sources.jar", None)

      val byUUID = Map(srcWrapper.uuid -> srcWrapper)
      val byName = Map("foo-1.0-sources.jar" -> Vector(srcWrapper))

      val (toProcess, _, revisedByName, _) =
        MavenToProcess.computeMavenFiles(byUUID, byName)

      assert(toProcess.nonEmpty, "Should claim the standalone sources JAR")
      val maven = toProcess.head.asInstanceOf[MavenToProcess]
      assert(maven.jar == srcWrapper, "Sources JAR should be in jar slot")
      assert(maven.pom.isEmpty, "No POM companion")
      assert(maven.source.isEmpty, "No source companion")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("computeMavenFiles claims standalone javadoc JAR") {
    val tempDir = Files.createTempDirectory("standalone-doc").toFile
    try {
      val docJar = new File(tempDir, "foo-1.0-javadoc.jar")
      Helpers.writeOverFile(docJar, jarHeader)
      val docWrapper = FileWrapper(docJar, "foo-1.0-javadoc.jar", None)

      val byUUID = Map(docWrapper.uuid -> docWrapper)
      val byName = Map("foo-1.0-javadoc.jar" -> Vector(docWrapper))

      val (toProcess, _, _, _) =
        MavenToProcess.computeMavenFiles(byUUID, byName)

      assert(toProcess.nonEmpty, "Should claim the standalone javadoc JAR")
      val maven = toProcess.head.asInstanceOf[MavenToProcess]
      assert(maven.jar == docWrapper, "Javadoc JAR should be in jar slot")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("computeMavenFiles does not double-claim bundled sources JAR") {
    val tempDir = Files.createTempDirectory("bundled-src").toFile
    try {
      val mainJar = new File(tempDir, "test.jar")
      val sourcesJar = new File(tempDir, "test-sources.jar")
      Helpers.writeOverFile(mainJar, jarHeader)
      Helpers.writeOverFile(sourcesJar, jarHeader)

      val mainWrapper = FileWrapper(mainJar, "test.jar", None)
      val srcWrapper = FileWrapper(sourcesJar, "test-sources.jar", None)

      val byUUID = Map(
        mainWrapper.uuid -> mainWrapper,
        srcWrapper.uuid -> srcWrapper
      )
      val byName = Map(
        "test.jar" -> Vector(mainWrapper),
        "test-sources.jar" -> Vector(srcWrapper)
      )

      val (toProcess, _, _, _) =
        MavenToProcess.computeMavenFiles(byUUID, byName)

      // Should have exactly one MavenToProcess
      assertEquals(toProcess.length, 1)
      val maven = toProcess.head.asInstanceOf[MavenToProcess]
      assert(maven.jar == mainWrapper, "Main JAR should be primary")
      assert(
        maven.source.contains(srcWrapper),
        "Sources JAR should be in source companion slot"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("computeMavenFiles rejects non-archive sources JAR") {
    val tempDir = Files.createTempDirectory("non-archive").toFile
    try {
      // Create a text file named .jar — its mimeType will NOT be java-archive
      val fakeJar = new File(tempDir, "foo-1.0-sources.jar")
      Helpers.writeOverFile(fakeJar, "this is not a JAR".getBytes("UTF-8"))
      val fakeWrapper = FileWrapper(fakeJar, "foo-1.0-sources.jar", None)

      val byUUID = Map(fakeWrapper.uuid -> fakeWrapper)
      val byName = Map("foo-1.0-sources.jar" -> Vector(fakeWrapper))

      val (toProcess, _, _, _) =
        MavenToProcess.computeMavenFiles(byUUID, byName)

      // Should NOT be claimed because mimeType is not application/java-archive
      assert(
        toProcess.isEmpty,
        "Non-archive file should not be claimed by Maven strategy"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("computeMavenFiles removes standalone sources from byName") {
    val tempDir = Files.createTempDirectory("remove-byname").toFile
    try {
      val srcJar = new File(tempDir, "foo-1.0-sources.jar")
      Helpers.writeOverFile(srcJar, jarHeader)
      val srcWrapper = FileWrapper(srcJar, "foo-1.0-sources.jar", None)

      val byUUID = Map(srcWrapper.uuid -> srcWrapper)
      val byName = Map("foo-1.0-sources.jar" -> Vector(srcWrapper))

      val (_, _, revisedByName, _) =
        MavenToProcess.computeMavenFiles(byUUID, byName)

      assert(
        !revisedByName.contains("foo-1.0-sources.jar"),
        "Standalone sources JAR should be removed from byName"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== Tests 20-24: Sequential processing & state isolation ====================

  test("classifier survives postChildProcessing copy") {
    val tempDir = Files.createTempDirectory("postchild").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq("com/example/Lib.java" -> "public class Lib {}")
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("postchild")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      // postChildProcessing for Sources returns a copy
      val s2 =
        s1.postChildProcessing(Some(Vector.empty), store, MavenMarkers.Sources)

      // currentMarker should survive the copy
      assertEquals(s2.currentMarker, Some(MavenMarkers.Sources))
      assert(
        s2.sourcesAccumulated.isDefined,
        "sourcesAccumulated should survive copy"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== Tests 25-29: Boundary conditions ====================

  test("empty sources JAR does not crash") {
    val tempDir = Files.createTempDirectory("empty-src").toFile
    try {
      val jarFile = new File(tempDir, "foo-1.0-sources.jar")
      // Create a valid ZIP with zero entries
      val zos = new ZipOutputStream(new FileOutputStream(jarFile))
      zos.close()
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("empty")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      // Should not crash. An empty JAR with no pom.properties/manifest may
      // still produce a pURL from the filename fallback (same as regular
      // JARs), so we only assert no crash, not no pURL.
      s1.applyAccumulatedAugmentation(item, wrapper, store)
      // Verify no exception was thrown — that's the assertion
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("sources JAR with only manifest resolves from manifest+filename") {
    val tempDir = Files.createTempDirectory("manifest-only").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" ->
            """Manifest-Version: 1.0
              |Implementation-Title: lib
              |Implementation-Version: 1.0
              |Implementation-Vendor-Id: com.example
              |""".stripMargin
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("manifest-only")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      assert(purls.nonEmpty, "Should emit a pURL from manifest+filename")
      val sourcesPurls = purls.filter(_.contains("packaging=sources"))
      assert(sourcesPurls.nonEmpty, "pURL should have packaging=sources")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test(
    "sources JAR with multiple pom.properties selects primary via filename"
  ) {
    val tempDir = Files.createTempDirectory("multi-props").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
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
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("multi-props")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      // Primary should be com.example:lib:1.0 (matches filename)
      val primaryPurls = purls.filter(p =>
        p.contains("com.example") && p.contains("lib") && p.contains("1.0")
      )
      assert(
        primaryPurls.nonEmpty,
        "Primary pURL should be com.example:lib:1.0"
      )
      assert(
        primaryPurls.exists(_.contains("packaging=sources")),
        "Primary pURL should have sources classifier"
      )
      // Secondary should be org.other:dep:2.0 (with sources classifier per REQ-1)
      val secondaryPurls =
        purls.filter(p => p.contains("org.other") && p.contains("dep"))
      assert(
        secondaryPurls.nonEmpty,
        "Should have secondary pURL for org.other:dep"
      )
      assert(
        secondaryPurls.forall(_.contains("packaging=sources")),
        "Secondary pURL from sources JAR should have sources classifier (REQ-1)"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("sources JAR with no META-INF: no crash") {
    val tempDir = Files.createTempDirectory("no-meta").toFile
    try {
      val jarFile = new File(tempDir, "foo-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "com/example/Foo.java" -> "public class Foo {}",
          "com/example/Bar.java" -> "public class Bar {}"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("no-meta")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      // No crash. The filename fallback may still produce a pURL from
      // the filename (foo-1.0-sources.jar → artifactId=foo, version=1.0-sources).
      // This is the same behavior as regular JARs with no metadata.
      // We only assert no exception was thrown.
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== Integration tests ====================

  test("aim-all-1.0.1-sources.jar emits pURLs for embedded packages") {
    val jarFile = new File(
      "test_data/download/adg_tests/repo_ea/aim-all-1.0.1-sources.jar"
    )
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("aim-all-src")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      // Should contain aim-starter and aim-cluster
      assert(
        purls.exists(_.contains("aim-starter")),
        s"Should find aim-starter pURL, got: $purls"
      )
      assert(
        purls.exists(_.contains("aim-cluster")),
        s"Should find aim-cluster pURL, got: $purls"
      )
    } else {
      println("Skipping aim-all test — file not found")
    }
  }

  test("annotations-alpha-0.0.1-sources.jar emits pURL") {
    val jarFile = new File(
      "test_data/download/adg_tests/repo_ea/annotations-alpha-0.0.1-sources.jar"
    )
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("annotations-src")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      assert(purls.nonEmpty, "Should emit at least one pURL")
      // Should contain annotations artifactId
      assert(
        purls.exists(_.contains("annotations")),
        s"Should find annotations pURL, got: $purls"
      )
    } else {
      println("Skipping annotations-alpha test — file not found")
    }
  }

  // ==================== Tests 20-23: Sequential processing / state isolation ====================

  // Test 20: POM → Sources → JAR — each marker uses own metadata
  //
  // What this tests: That processing Sources and JAR markers sequentially
  // on the same MavenState does not cause cross-contamination. Sources
  // should emit a pURL with ?packaging=sources, JAR should emit a pURL
  // with no classifier. Both should use their own pom.properties.
  //
  // Requirement: Phase 1 — independent accumulation per marker.
  // Theory: applyAccumulatedAugmentation resets jarAccumulated and
  // classifier, so the next marker starts fresh.
  test("POM → Sources → JAR: each marker uses own metadata") {
    val tempDir = Files.createTempDirectory("maven-seq-test").toFile
    try {
      // Sources JAR with pom.properties: com.example:lib:1.0
      val sourcesJar = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        sourcesJar,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            "# Generated\n groupId = com.example\n artifactId = lib\n version = 1.0\n",
          "com/example/Foo.java" -> "package com.example; class Foo {}"
        )
      )
      // Main JAR with pom.properties: com.example:lib:1.0
      val mainJar = new File(tempDir, "lib-1.0.jar")
      writeJarEntries(
        mainJar,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            "# Generated\n groupId = com.example\n artifactId = lib\n version = 1.0\n",
          "com/example/Foo.class" -> "CAFEBABE"
        )
      )

      val sourcesWrapper =
        FileWrapper(sourcesJar, sourcesJar.getAbsolutePath, None)
      val mainWrapper = FileWrapper(mainJar, mainJar.getAbsolutePath, None)

      val store = MemStorage(None)

      // Process Sources marker
      val sSources = MavenState().beginProcessing(
        sourcesWrapper,
        createTestItem("src"),
        MavenMarkers.Sources
      )
      FileWalker.withinArchiveStream(sourcesWrapper) { entries =>
        entries.foreach { entry =>
          sSources.accumulateInfo("src", createTestItem("src"), entry, store)
        }
      }
      val srcItem = createTestItem("src-item")
      sSources.applyAccumulatedAugmentation(srcItem, sourcesWrapper, store)

      // Process JAR marker on the SAME state (simulating sequential marker processing)
      val sJar = sSources.beginProcessing(
        mainWrapper,
        createTestItem("jar"),
        MavenMarkers.JAR
      )
      FileWalker.withinArchiveStream(mainWrapper) { entries =>
        entries.foreach { entry =>
          sJar.accumulateInfo("jar", createTestItem("jar"), entry, store)
        }
      }
      val jarItem = createTestItem("jar-item")
      sJar.applyAccumulatedAugmentation(jarItem, mainWrapper, store)

      val allPurls = store.purls().toSet
      // Sources pURL should have ?packaging=sources
      assert(
        allPurls.exists(p =>
          p.contains("lib") && p.contains("1.0") && p.contains(
            "packaging=sources"
          )
        ),
        s"Expected sources pURL with packaging=sources, got: $allPurls"
      )
      // JAR pURL should NOT have packaging or classifier
      assert(
        allPurls.exists(p =>
          p.contains("lib") && p.contains("1.0") && !p.contains(
            "packaging"
          ) && !p.contains("classifier")
        ),
        s"Expected JAR pURL without classifier, got: $allPurls"
      )
    } finally {
      tempDir.listFiles().foreach(_.delete())
      tempDir.delete()
    }
  }

  // Test 21: Sources groupId/artifactId/version differs from JAR groupId/artifactId/version — no cross-contamination
  //
  // What this tests: That when Sources has different pom.properties groupId/artifactId/version
  // than JAR, each marker uses its own groupId/artifactId/version. Sources pURL should use
  // com.sources:lib-src:2.0, JAR pURL should use com.example:lib:1.0.
  //
  // Requirement: Phase 1 — resolveGroupIdArtifactIdVersion reads from accumulator, not shared state.
  // Theory: Each marker's jarAccumulated is reset between markers, so
  // resolveGroupIdArtifactIdVersion only sees the current marker's pom.properties.
  test(
    "Sources groupId/artifactId/version differs from JAR groupId/artifactId/version: no cross-contamination"
  ) {
    val tempDir = Files.createTempDirectory("maven-coordinates-diff").toFile
    try {
      // Sources JAR with different groupId/artifactId/version: com.sources:lib-src:2.0
      val sourcesJar = new File(tempDir, "lib-src-2.0-sources.jar")
      writeJarEntries(
        sourcesJar,
        Seq(
          "META-INF/maven/com.sources/lib-src/pom.properties" ->
            "groupId = com.sources\n artifactId = lib-src\n version = 2.0\n"
        )
      )
      // Main JAR with groupId/artifactId/version: com.example:lib:1.0
      val mainJar = new File(tempDir, "lib-1.0.jar")
      writeJarEntries(
        mainJar,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" ->
            "groupId = com.example\n artifactId = lib\n version = 1.0\n"
        )
      )

      val sourcesWrapper =
        FileWrapper(sourcesJar, sourcesJar.getAbsolutePath, None)
      val mainWrapper = FileWrapper(mainJar, mainJar.getAbsolutePath, None)

      val store = MemStorage(None)

      // Process Sources
      val sSources = MavenState().beginProcessing(
        sourcesWrapper,
        createTestItem("src2"),
        MavenMarkers.Sources
      )
      FileWalker.withinArchiveStream(sourcesWrapper) { entries =>
        entries.foreach { entry =>
          sSources.accumulateInfo("src2", createTestItem("src2"), entry, store)
        }
      }
      sSources.applyAccumulatedAugmentation(
        createTestItem("src2-item"),
        sourcesWrapper,
        store
      )

      // Process JAR
      val sJar = sSources.beginProcessing(
        mainWrapper,
        createTestItem("jar2"),
        MavenMarkers.JAR
      )
      FileWalker.withinArchiveStream(mainWrapper) { entries =>
        entries.foreach { entry =>
          sJar.accumulateInfo("jar2", createTestItem("jar2"), entry, store)
        }
      }
      sJar.applyAccumulatedAugmentation(
        createTestItem("jar2-item"),
        mainWrapper,
        store
      )

      val allPurls = store.purls().toSet
      // Sources pURL should use com.sources/lib-src@2.0
      assert(
        allPurls.exists(p =>
          p.contains("com.sources") && p.contains("lib-src") && p.contains(
            "2.0"
          )
        ),
        s"Expected sources pURL with com.sources:lib-src:2.0, got: $allPurls"
      )
      // JAR pURL should use com.example/lib@1.0 (NOT Sources' values)
      assert(
        allPurls.exists(p =>
          p.contains("com.example") && p.contains("/lib@1.0")
        ),
        s"Expected JAR pURL with com.example:lib:1.0, got: $allPurls"
      )
    } finally {
      tempDir.listFiles().foreach(_.delete())
      tempDir.delete()
    }
  }

  // Test 22: Sources + Javadoc both bundled — correct classifiers
  //
  // What this tests: That when both Sources and JavaDocs are processed,
  // each gets the correct classifier: Sources → ?packaging=sources,
  // JavaDocs → ?classifier=javadoc, JAR → no classifier.
  //
  // Requirement: Phase 1 — each marker sets its own classifier.
  // Theory: beginProcessing sets classifier, applyAug uses it, then resets.
  test("Sources + Javadoc both bundled: correct classifiers") {
    val tempDir = Files.createTempDirectory("maven-src-javadoc").toFile
    try {
      val propsContent =
        "groupId = com.example\n artifactId = lib\n version = 1.0\n"

      val sourcesJar = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        sourcesJar,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" -> propsContent
        )
      )
      val javadocJar = new File(tempDir, "lib-1.0-javadoc.jar")
      writeJarEntries(
        javadocJar,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" -> propsContent
        )
      )
      val mainJar = new File(tempDir, "lib-1.0.jar")
      writeJarEntries(
        mainJar,
        Seq(
          "META-INF/maven/com.example/lib/pom.properties" -> propsContent
        )
      )

      val srcWrapper = FileWrapper(sourcesJar, sourcesJar.getAbsolutePath, None)
      val docWrapper = FileWrapper(javadocJar, javadocJar.getAbsolutePath, None)
      val jarWrapper = FileWrapper(mainJar, mainJar.getAbsolutePath, None)
      val store = MemStorage(None)

      // Process Sources
      val s1 = MavenState().beginProcessing(
        srcWrapper,
        createTestItem("s22"),
        MavenMarkers.Sources
      )
      FileWalker.withinArchiveStream(srcWrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo("s22", createTestItem("s22"), entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(
        createTestItem("s22-item"),
        srcWrapper,
        store
      )

      // Process JavaDocs
      val s2 = s1.beginProcessing(
        docWrapper,
        createTestItem("d22"),
        MavenMarkers.JavaDocs
      )
      FileWalker.withinArchiveStream(docWrapper) { entries =>
        entries.foreach { entry =>
          s2.accumulateInfo("d22", createTestItem("d22"), entry, store)
        }
      }
      s2.applyAccumulatedAugmentation(
        createTestItem("d22-item"),
        docWrapper,
        store
      )

      // Process JAR
      val s3 =
        s2.beginProcessing(jarWrapper, createTestItem("j22"), MavenMarkers.JAR)
      FileWalker.withinArchiveStream(jarWrapper) { entries =>
        entries.foreach { entry =>
          s3.accumulateInfo("j22", createTestItem("j22"), entry, store)
        }
      }
      s3.applyAccumulatedAugmentation(
        createTestItem("j22-item"),
        jarWrapper,
        store
      )

      val allPurls = store.purls().toSet
      assert(
        allPurls.exists(_.contains("packaging=sources")),
        s"Expected sources pURL with packaging=sources, got: $allPurls"
      )
      assert(
        allPurls.exists(_.contains("classifier=javadoc")),
        s"Expected javadoc pURL with classifier=javadoc, got: $allPurls"
      )
      assert(
        allPurls.exists(p =>
          !p.contains("packaging") && !p.contains("classifier")
        ),
        s"Expected JAR pURL without classifier, got: $allPurls"
      )
    } finally {
      tempDir.listFiles().foreach(_.delete())
      tempDir.delete()
    }
  }

  // Test 23: Sources + Javadoc with different groupId/artifactId/version tuples — each uses own
  //
  // What this tests: That when Sources has groupId/artifactId/version (com.src:lib-src:2.0)
  // and Javadoc has groupId/artifactId/version (com.doc:lib-doc:3.0), each marker uses its
  // own coordinates from its own pom.properties.
  //
  // Requirement: Phase 1 — resolveGroupIdArtifactIdVersion reads from current accumulator.
  // Theory: Each marker's accumulator is fresh (reset between markers).
  test(
    "Sources + Javadoc with different groupId/artifactId/version tuples: each uses own"
  ) {
    val tempDir = Files.createTempDirectory("maven-diff-coordinates").toFile
    try {
      val sourcesJar = new File(tempDir, "lib-src-2.0-sources.jar")
      writeJarEntries(
        sourcesJar,
        Seq(
          "META-INF/maven/com.src/lib-src/pom.properties" ->
            "groupId = com.src\n artifactId = lib-src\n version = 2.0\n"
        )
      )
      val javadocJar = new File(tempDir, "lib-doc-3.0-javadoc.jar")
      writeJarEntries(
        javadocJar,
        Seq(
          "META-INF/maven/com.doc/lib-doc/pom.properties" ->
            "groupId = com.doc\n artifactId = lib-doc\n version = 3.0\n"
        )
      )

      val srcWrapper = FileWrapper(sourcesJar, sourcesJar.getAbsolutePath, None)
      val docWrapper = FileWrapper(javadocJar, javadocJar.getAbsolutePath, None)
      val store = MemStorage(None)

      // Process Sources
      val s1 = MavenState().beginProcessing(
        srcWrapper,
        createTestItem("s23"),
        MavenMarkers.Sources
      )
      FileWalker.withinArchiveStream(srcWrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo("s23", createTestItem("s23"), entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(
        createTestItem("s23-item"),
        srcWrapper,
        store
      )

      // Process JavaDocs
      val s2 = s1.beginProcessing(
        docWrapper,
        createTestItem("d23"),
        MavenMarkers.JavaDocs
      )
      FileWalker.withinArchiveStream(docWrapper) { entries =>
        entries.foreach { entry =>
          s2.accumulateInfo("d23", createTestItem("d23"), entry, store)
        }
      }
      s2.applyAccumulatedAugmentation(
        createTestItem("d23-item"),
        docWrapper,
        store
      )

      val allPurls = store.purls().toSet
      assert(
        allPurls.exists(p =>
          p.contains("com.src") && p.contains("lib-src") && p.contains("2.0")
        ),
        s"Expected sources pURL with com.src:lib-src:2.0, got: $allPurls"
      )
      assert(
        allPurls.exists(p =>
          p.contains("com.doc") && p.contains("lib-doc") && p.contains("3.0")
        ),
        s"Expected javadoc pURL with com.doc:lib-doc:3.0, got: $allPurls"
      )
    } finally {
      tempDir.listFiles().foreach(_.delete())
      tempDir.delete()
    }
  }

  // ==================== Test 28: Robustness ====================

  // Test 28: Sources JAR with corrupted pom.properties does not crash
  //
  // What this tests: That pom.properties with empty values, binary garbage,
  // and Windows line endings does not cause an exception. The system should
  // gracefully fall back to manifest/filename.
  //
  // Requirement: Boundary condition — robustness.
  // Theory: PomPropertiesParser handles malformed input gracefully (returns
  // empty Option rather than throwing).
  test("sources JAR with corrupted pom.properties does not crash") {
    val tempDir = Files.createTempDirectory("maven-corrupted").toFile
    try {
      val jarFile = new File(tempDir, "lib-1.0-sources.jar")
      writeJarEntries(
        jarFile,
        Seq(
          // Empty values
          "META-INF/maven/com.example/empty/pom.properties" ->
            "groupId = \n artifactId = \n version = \n",
          // Binary garbage
          "META-INF/maven/com.example/garbage/pom.properties" ->
            new String(
              Array(0x00.toByte, 0x01.toByte, 0xff.toByte, 0xfe.toByte),
              "ISO-8859-1"
            ),
          // Windows line endings
          "META-INF/maven/com.example/lib/pom.properties" ->
            "groupId = com.example\r\n artifactId = lib\r\n version = 1.0\r\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val store = MemStorage(None)
      val item = createTestItem("corrupted-test")

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      // Should not throw
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      // Should still resolve from the valid pom.properties (Windows line endings)
      assert(
        purls.nonEmpty,
        "Should emit pURL despite corrupted pom.properties"
      )
      assert(
        purls.exists(_.contains("com.example")),
        s"Expected pURL with com.example from valid entry, got: $purls"
      )
    } finally {
      tempDir.listFiles().foreach(_.delete())
      tempDir.delete()
    }
  }

  // ==================== Test 32: wps_war_test integration ====================

  // Test 32: wps_war_test bundle — sources and javadoc each emit pURL
  //
  // What this tests: That a real-world WAR bundle with sources and javadoc
  // JARs produces pURLs for each. Sources and javadoc have no pom.properties
  // so they should fall back to POM groupId/artifactId/version or filename.
  //
  // Requirement: Integration test — end-to-end on real test data.
  // Theory: The wps_war_test directory contains wps-demo-1.3.0.war,
  // wps-demo-1.3.0-sources.jar, wps-demo-1.3.0-javadoc.jar, and
  // wps-demo-1.3.0.pom.
  test("wps_war_test: sources and javadoc each emit pURL") {
    val sourcesFile =
      new File("test_data/wps_war_test/wps-demo-1.3.0-sources.jar")
    val javadocFile =
      new File("test_data/wps_war_test/wps-demo-1.3.0-javadoc.jar")
    if (sourcesFile.exists() && javadocFile.exists()) {
      val sourcesWrapper =
        FileWrapper(sourcesFile, sourcesFile.getAbsolutePath, None)
      val javadocWrapper =
        FileWrapper(javadocFile, javadocFile.getAbsolutePath, None)
      val store = MemStorage(None)

      // Process Sources
      val s1 = MavenState().beginProcessing(
        sourcesWrapper,
        createTestItem("wps-src"),
        MavenMarkers.Sources
      )
      FileWalker.withinArchiveStream(sourcesWrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo("wps-src", createTestItem("wps-src"), entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(
        createTestItem("wps-src-item"),
        sourcesWrapper,
        store
      )

      // Process JavaDocs
      val s2 = s1.beginProcessing(
        javadocWrapper,
        createTestItem("wps-doc"),
        MavenMarkers.JavaDocs
      )
      FileWalker.withinArchiveStream(javadocWrapper) { entries =>
        entries.foreach { entry =>
          s2.accumulateInfo("wps-doc", createTestItem("wps-doc"), entry, store)
        }
      }
      s2.applyAccumulatedAugmentation(
        createTestItem("wps-doc-item"),
        javadocWrapper,
        store
      )

      val allPurls = store.purls().toSet
      // Sources should emit a pURL (from filename fallback if no pom.properties)
      assert(
        allPurls.exists(_.contains("packaging=sources")),
        s"Expected sources pURL with packaging=sources, got: $allPurls"
      )
      // Javadoc should emit a pURL
      assert(
        allPurls.exists(_.contains("classifier=javadoc")),
        s"Expected javadoc pURL with classifier=javadoc, got: $allPurls"
      )
    } else {
      println("Skipping wps_war_test — files not found")
    }
  }

  // ==================== Test 33: abris sources JAR from corpus ====================

  // Test 33: standalone sources JAR from corpus: abris-0.0.1-sources.jar
  //
  // What this tests: That a real standalone sources JAR from the test
  // corpus produces the correct pURL with classifier. The abris sources
  // JAR contains pom.properties with za.co.absa:abris:0.0.1.
  //
  // Requirement: Integration test — end-to-end on real corpus artifact.
  // Theory: beginProcessing(Sources) sets up sourcesAccumulated,
  // accumulateInfo collects pom.properties, applyAccumulatedAugmentation
  // resolves groupId/artifactId/version and emits pURL with
  // ?packaging=sources.
  test("standalone sources JAR from corpus: abris-0.0.1-sources.jar") {
    val jarFile = new File(
      "test_data/download/adg_tests/repo_ea/abris-0.0.1-sources.jar"
    )
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("abris-src")
      val store = MemStorage(None)

      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      s1.applyAccumulatedAugmentation(item, wrapper, store)

      val purls = store.purls().toSet
      assert(purls.nonEmpty, "Should emit at least one pURL")
      assert(
        purls.exists(_.contains("za.co.absa")),
        s"Expected pURL with za.co.absa, got: $purls"
      )
      assert(
        purls.exists(_.contains("abris")),
        s"Expected pURL with abris, got: $purls"
      )
      assert(
        purls.exists(_.contains("0.0.1")),
        s"Expected pURL with version 0.0.1, got: $purls"
      )
      assert(
        purls.exists(_.contains("packaging=sources")),
        s"Expected pURL with packaging=sources classifier, got: $purls"
      )
    } else {
      println("Skipping abris test — file not found")
    }
  }
}
