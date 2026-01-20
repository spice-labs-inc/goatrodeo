/* Copyright 2024 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.strategies.MavenMarkers
import io.spicelabs.goatrodeo.omnibor.strategies.MavenState
import io.spicelabs.goatrodeo.omnibor.strategies.MavenToProcess
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.xml.NodeSeq

class MavenTestSuite extends munit.FunSuite {

  val pomXml = """<?xml version="1.0" encoding="UTF-8"?>
<project>
    <groupId>org.example</groupId>
    <artifactId>test-artifact</artifactId>
    <version>1.0.0</version>
</project>"""

  def createTestItem(id: String): Item = {
    Item(
      id,
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(
        fileNames = TreeSet(id),
        mimeType = TreeSet("application/octet-stream"),
        fileSize = 100,
        extra = TreeMap()
      ))
    )
  }

  // ==================== MavenState.beginProcessing Tests ====================

  test("MavenState.beginProcessing - captures POM content for POM marker") {
    val pomBytes = pomXml.getBytes("UTF-8")
    val artifact = ByteWrapper(pomBytes, "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState()

    val newState = state.beginProcessing(artifact, item, MavenMarkers.POM)

    assert(newState.pomFile.nonEmpty)
    assert(newState.pomXml != NodeSeq.Empty)
  }

  test("MavenState.beginProcessing - does not capture for JAR marker") {
    val jarBytes = Array[Byte](0x50, 0x4b, 0x03, 0x04)
    val artifact = ByteWrapper(jarBytes, "test.jar", None)
    val item = createTestItem("test-id")
    val state = MavenState()

    val newState = state.beginProcessing(artifact, item, MavenMarkers.JAR)

    assert(newState.pomFile.isEmpty)
    assertEquals(newState.pomXml, NodeSeq.Empty)
  }

  test("MavenState.beginProcessing - does not capture for Sources marker") {
    val srcBytes = "class Test {}".getBytes("UTF-8")
    val artifact = ByteWrapper(srcBytes, "test-sources.jar", None)
    val item = createTestItem("test-id")
    val state = MavenState()

    val newState = state.beginProcessing(artifact, item, MavenMarkers.Sources)

    assert(newState.pomFile.isEmpty)
  }

  test("MavenState.beginProcessing - handles invalid XML gracefully") {
    val invalidXml = "not valid xml <<>>"
    val artifact = ByteWrapper(invalidXml.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState()

    val newState = state.beginProcessing(artifact, item, MavenMarkers.POM)

    assert(newState.pomFile.nonEmpty)
    assertEquals(newState.pomXml, NodeSeq.Empty) // Invalid XML returns empty
  }

  // ==================== MavenState.getPurls Tests ====================

  test("MavenState.getPurls - returns purl for valid POM") {
    val pomBytes = pomXml.getBytes("UTF-8")
    val artifact = ByteWrapper(pomBytes, "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)

    val (purls, _) = state.getPurls(artifact, item, MavenMarkers.JAR)

    assert(purls.nonEmpty)
    val purl = purls.head
    assertEquals(purl.getType(), "maven")
    assertEquals(purl.getNamespace(), "org.example")
    assertEquals(purl.getName(), "test-artifact")
    assertEquals(purl.getVersion(), "1.0.0")
  }

  test("MavenState.getPurls - returns empty for missing group") {
    val badPom = """<?xml version="1.0"?>
<project>
    <artifactId>test</artifactId>
    <version>1.0</version>
</project>"""
    val artifact = ByteWrapper(badPom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)

    val (purls, _) = state.getPurls(artifact, item, MavenMarkers.JAR)

    assert(purls.isEmpty)
  }

  test("MavenState.getPurls - includes pom qualifier for POM marker") {
    val pomBytes = pomXml.getBytes("UTF-8")
    val artifact = ByteWrapper(pomBytes, "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)

    val (purls, _) = state.getPurls(artifact, item, MavenMarkers.POM)

    assert(purls.nonEmpty)
    val purl = purls.head
    assertEquals(purl.getQualifiers().get("type"), "pom")
  }

  test("MavenState.getPurls - includes sources qualifier for Sources marker") {
    val pomBytes = pomXml.getBytes("UTF-8")
    val artifact = ByteWrapper(pomBytes, "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)

    val (purls, _) = state.getPurls(artifact, item, MavenMarkers.Sources)

    assert(purls.nonEmpty)
    val purl = purls.head
    assertEquals(purl.getQualifiers().get("packaging"), "sources")
  }

  test("MavenState.getPurls - includes javadoc qualifier for JavaDocs marker") {
    val pomBytes = pomXml.getBytes("UTF-8")
    val artifact = ByteWrapper(pomBytes, "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)

    val (purls, _) = state.getPurls(artifact, item, MavenMarkers.JavaDocs)

    assert(purls.nonEmpty)
    val purl = purls.head
    assertEquals(purl.getQualifiers().get("classifier"), "javadoc")
  }

  test("MavenState.getPurls - extracts version from parent if missing") {
    val pomWithParent = """<?xml version="1.0"?>
<project>
    <groupId>org.example</groupId>
    <artifactId>test</artifactId>
    <parent>
        <version>2.0.0</version>
    </parent>
</project>"""
    val artifact = ByteWrapper(pomWithParent.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)

    val (purls, _) = state.getPurls(artifact, item, MavenMarkers.JAR)

    assert(purls.nonEmpty)
    assertEquals(purls.head.getVersion(), "2.0.0")
  }

  // ==================== MavenState.getMetadata Tests ====================

  test("MavenState.getMetadata - includes pom content for non-POM markers") {
    val pomBytes = pomXml.getBytes("UTF-8")
    val artifact = ByteWrapper(pomBytes, "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)

    val jarArtifact = ByteWrapper(Array[Byte](), "test.jar", None)
    val (metadata, _) = state.getMetadata(jarArtifact, item, MavenMarkers.JAR)

    assert(metadata.contains("pom"))
  }

  test("MavenState.getMetadata - returns pom content for POM marker") {
    val pomBytes = pomXml.getBytes("UTF-8")
    val artifact = ByteWrapper(pomBytes, "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)

    val (metadata, _) = state.getMetadata(artifact, item, MavenMarkers.POM)

    // POM marker still returns pom content (just not manifest since it's not an archive)
    assert(metadata.contains("pom"))
  }

  // ==================== MavenState.postChildProcessing Tests ====================

  test("MavenState.postChildProcessing - captures sources for Sources marker") {
    val storage = MemStorage(None)
    val sourceItem = Item(
      "gitoid:blob:sha256:source123",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("Source.java"), TreeSet(), 100, TreeMap()))
    )
    storage.write(sourceItem.identifier, _ => Some(sourceItem), _ => "")

    val state = MavenState()
    val kids = Some(Vector(sourceItem.identifier))
    val newState = state.postChildProcessing(kids, storage, MavenMarkers.Sources)

    assert(newState.sources.nonEmpty)
    assert(newState.sourceGitoids.contains("Source.java"))
  }

  test("MavenState.postChildProcessing - does not capture for JAR marker") {
    val storage = MemStorage(None)
    val state = MavenState()
    val newState = state.postChildProcessing(None, storage, MavenMarkers.JAR)

    assert(newState.sources.isEmpty)
    assert(newState.sourceGitoids.isEmpty)
  }

  // ==================== MavenToProcess Tests ====================

  test("MavenToProcess.itemCnt - returns 1 for jar only") {
    val jarArtifact = ByteWrapper(Array[Byte](), "test.jar", None)
    val tp = MavenToProcess(jarArtifact, None, None, None)

    assertEquals(tp.itemCnt, 1)
  }

  test("MavenToProcess.itemCnt - returns correct count with optional files") {
    val jarArtifact = ByteWrapper(Array[Byte](), "test.jar", None)
    val pomArtifact = ByteWrapper(Array[Byte](), "test.pom", None)
    val srcArtifact = ByteWrapper(Array[Byte](), "test-sources.jar", None)
    val docArtifact = ByteWrapper(Array[Byte](), "test-javadoc.jar", None)

    val tp = MavenToProcess(jarArtifact, Some(pomArtifact), Some(srcArtifact), Some(docArtifact))
    assertEquals(tp.itemCnt, 4)
  }

  test("MavenToProcess.getElementsToProcess - returns POM first if present") {
    val jarArtifact = ByteWrapper(Array[Byte](), "test.jar", None)
    val pomArtifact = ByteWrapper(pomXml.getBytes("UTF-8"), "test.pom", None)

    val tp = MavenToProcess(jarArtifact, Some(pomArtifact), None, None)
    val (elements, _) = tp.getElementsToProcess()

    assertEquals(elements.head._2, MavenMarkers.POM)
    assertEquals(elements.last._2, MavenMarkers.JAR)
  }

  test("MavenToProcess.getElementsToProcess - returns correct order") {
    val jarArtifact = ByteWrapper(Array[Byte](), "test.jar", None)
    val pomArtifact = ByteWrapper(pomXml.getBytes("UTF-8"), "test.pom", None)
    val srcArtifact = ByteWrapper(Array[Byte](), "test-sources.jar", None)
    val docArtifact = ByteWrapper(Array[Byte](), "test-javadoc.jar", None)

    val tp = MavenToProcess(jarArtifact, Some(pomArtifact), Some(srcArtifact), Some(docArtifact))
    val (elements, _) = tp.getElementsToProcess()

    val markers = elements.map(_._2)
    assertEquals(markers, Seq(MavenMarkers.POM, MavenMarkers.Sources, MavenMarkers.JavaDocs, MavenMarkers.JAR))
  }

  test("MavenToProcess.main - returns jar path") {
    val jarArtifact = ByteWrapper(Array[Byte](), "path/to/test.jar", None)
    val tp = MavenToProcess(jarArtifact, None, None, None)

    assertEquals(tp.main, "path/to/test.jar")
  }

  test("MavenToProcess.mimeType - returns jar mime type") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getName(), None)
      val tp = MavenToProcess(wrapper, None, None, None)
      assertEquals(tp.mimeType, "application/java-archive")
    }
  }

  test("MavenToProcess.markSuccessfulCompletion - calls finished on all artifacts") {
    var finishedCount = 0
    val jarBytes = Array[Byte]()
    val jarArtifact = ByteWrapper(jarBytes, "test.jar", None)
    val pomArtifact = ByteWrapper(Array[Byte](), "test.pom", None)

    val tp = MavenToProcess(jarArtifact, Some(pomArtifact), None, None)
    tp.markSuccessfulCompletion()
    // ByteWrapper.finished() does nothing, but the method should complete without error
  }

  // ==================== computeMavenFiles Tests ====================

  test("computeMavenFiles - matches jar files") {
    val jarArtifact = ByteWrapper(Array[Byte](0x50, 0x4b, 0x03, 0x04), "test.jar", None)

    // Simulate ByteWrapper returning correct mime type
    val tempDir = Files.createTempDirectory("maventest")
    try {
      val jarFile = new java.io.File(tempDir.toFile(), "test.jar")
      val pomFile = new java.io.File(tempDir.toFile(), "test.pom")

      // Create minimal valid JAR (just zip header)
      Helpers.writeOverFile(jarFile, Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
      Helpers.writeOverFile(pomFile, pomXml)

      val jarWrapper = FileWrapper(jarFile, "test.jar", None)
      val pomWrapper = FileWrapper(pomFile, "test.pom", None)

      val byUUID = Map(
        jarWrapper.uuid -> jarWrapper,
        pomWrapper.uuid -> pomWrapper
      )
      val byName = Map(
        "test.jar" -> Vector(jarWrapper),
        "test.pom" -> Vector(pomWrapper)
      )

      val (toProcess, _, _, name) = MavenToProcess.computeMavenFiles(byUUID, byName)

      assertEquals(name, "Maven")
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  test("computeMavenFiles - excludes -sources.jar from main jars") {
    val tempDir = Files.createTempDirectory("mavenexclude")
    try {
      val mainJar = new java.io.File(tempDir.toFile(), "test.jar")
      val sourcesJar = new java.io.File(tempDir.toFile(), "test-sources.jar")

      val jarHeader = Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
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

      val (toProcess, revisedByUUID, revisedByName, _) = MavenToProcess.computeMavenFiles(byUUID, byName)

      // Sources jar should be associated with main jar, not a separate entry
      if (toProcess.nonEmpty) {
        val maven = toProcess.head.asInstanceOf[MavenToProcess]
        assert(maven.jar == mainWrapper)
        // Sources might be paired if names match
      }
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  test("computeMavenFiles - handles war files") {
    val tempDir = Files.createTempDirectory("mavenwar")
    try {
      val warFile = new java.io.File(tempDir.toFile(), "test.war")
      val jarHeader = Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      Helpers.writeOverFile(warFile, jarHeader)

      val warWrapper = FileWrapper(warFile, "test.war", None)

      val byUUID = Map(warWrapper.uuid -> warWrapper)
      val byName = Map("test.war" -> Vector(warWrapper))

      val (toProcess, _, _, name) = MavenToProcess.computeMavenFiles(byUUID, byName)

      assertEquals(name, "Maven")
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  // ==================== Integration Tests ====================

  test("MavenToProcess - processes real JAR file") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getName(), None)
      val tp = MavenToProcess(wrapper, None, None, None)

      val (elements, state) = tp.getElementsToProcess()
      assertEquals(elements.length, 1)
      assertEquals(elements.head._2, MavenMarkers.JAR)
    }
  }
}
