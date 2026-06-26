/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import munit.FunSuite

import java.io.File

class MavenPhase4Suite extends FunSuite {

  private def createTestItem(id: String): Item =
    Item(id, scala.collection.immutable.TreeSet.empty, None, None)

  // ==================== 4.1: Parent POM Metadata ====================

  test("MavenState - stores parent POM GAV") {
    val pomXml = """<?xml version="1.0" encoding="UTF-8"?>
      |<project>
      |  <parent>
      |    <groupId>com.example</groupId>
      |    <artifactId>parent-artifact</artifactId>
      |    <version>2.0.0</version>
      |  </parent>
      |  <artifactId>child-artifact</artifactId>
      |</project>""".stripMargin

    val state = MavenState()
    val artifact = ByteWrapper(pomXml.getBytes("UTF-8"), "pom.xml", None)
    val item = createTestItem("test")
    val newState = state.beginProcessing(artifact, item, MavenMarkers.POM)

    val (meta, _) = newState.getMetadata(artifact, item, MavenMarkers.POM)
    val parentMeta = meta.get(MetadataKeyConstants.adHoc("maven")("ParentPOM"))
    assert(parentMeta.isDefined, "ParentPOM metadata key must be present")
    val json = parentMeta.get.head.value
    assert(
      json.contains("com.example"),
      "Parent groupId must appear in ParentPOM JSON"
    )
    assert(json.contains("parent-artifact"), "Parent artifactId must appear")
    assert(json.contains("2.0.0"), "Parent version must appear")
  }

  test("MavenState - no ParentPOM when no parent") {
    val pomXml = """<?xml version="1.0" encoding="UTF-8"?>
      |<project>
      |  <groupId>com.example</groupId>
      |  <artifactId>standalone</artifactId>
      |  <version>1.0.0</version>
      |</project>""".stripMargin

    val state = MavenState()
    val artifact = ByteWrapper(pomXml.getBytes("UTF-8"), "pom.xml", None)
    val item = createTestItem("test")
    val newState = state.beginProcessing(artifact, item, MavenMarkers.POM)

    val (meta, _) = newState.getMetadata(artifact, item, MavenMarkers.POM)
    val parentMeta = meta.get(MetadataKeyConstants.adHoc("maven")("ParentPOM"))
    assert(parentMeta.isEmpty, "ParentPOM key must be absent when no <parent>")
  }

  // ==================== 4.2: maven-metadata.xml ====================

  private def makeMetadataXml(): String =
    """<?xml version="1.0" encoding="UTF-8"?>
    |<metadata>
    |  <groupId>com.example</groupId>
    |  <artifactId>my-artifact</artifactId>
    |  <versioning>
    |    <latest>3.0.0</latest>
    |    <release>2.0.0</release>
    |    <versions>
    |      <version>1.0.0</version>
    |      <version>2.0.0</version>
    |      <version>3.0.0</version>
    |    </versions>
    |  </versioning>
    |</metadata>""".stripMargin

  private def createMinimalJar(path: File): Unit = {
    import java.util.jar.{JarOutputStream, Manifest, Attributes}
    val manifest = new Manifest()
    manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    val jos = new JarOutputStream(new java.io.FileOutputStream(path), manifest)
    jos.close()
  }

  test("computeMavenFiles - matches maven-metadata.xml") {
    val tmpDir = File.createTempFile("maven-test", "").getParentFile
    val jarFile = new File(tmpDir, "my-artifact-1.0.0.jar")
    val pomFile = new File(tmpDir, "my-artifact-1.0.0.pom")
    val metaFile = new File(tmpDir, "maven-metadata.xml")

    // Create a valid minimal JAR so Tika detects java-archive MIME
    createMinimalJar(jarFile)
    java.nio.file.Files.write(
      pomFile.toPath,
      """<project><groupId>com.example</groupId><artifactId>my-artifact</artifactId><version>1.0.0</version></project>"""
        .getBytes("UTF-8")
    )
    java.nio.file.Files
      .write(metaFile.toPath, makeMetadataXml().getBytes("UTF-8"))

    try {
      import io.spicelabs.goatrodeo.util.FileWrapper
      val jarWrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val pomWrapper = FileWrapper(pomFile, pomFile.getAbsolutePath, None)
      val metaWrapper = FileWrapper(metaFile, metaFile.getAbsolutePath, None)

      val byName: ToProcess.ByName = Map(
        jarWrapper.path() -> Vector(jarWrapper),
        pomWrapper.path() -> Vector(pomWrapper),
        metaWrapper.path() -> Vector(metaWrapper)
      )
      val byUUID = Map(
        jarWrapper.uuid -> jarWrapper,
        pomWrapper.uuid -> pomWrapper,
        metaWrapper.uuid -> metaWrapper
      )

      val (toProcess, _, revisedByName, _) =
        MavenToProcess.computeMavenFiles(byUUID, byName)
      assertEquals(toProcess.size, 1)
      val mtp = toProcess.head.asInstanceOf[MavenToProcess]
      assert(mtp.metadataXml.isDefined, "maven-metadata.xml should be matched")

      // Verify revisedByName no longer contains the consumed maven-metadata.xml
      assert(!revisedByName.contains(metaWrapper.path()))
    } finally {
      jarFile.delete()
      pomFile.delete()
      metaFile.delete()
    }
  }

  test("MavenState - extracts version list") {
    val metaXml = makeMetadataXml()
    val state = MavenState()
    val artifact =
      ByteWrapper(metaXml.getBytes("UTF-8"), "maven-metadata.xml", None)
    val item = createTestItem("test")
    val newState = state.beginProcessing(artifact, item, MavenMarkers.Metadata)

    val (meta, _) = newState.getMetadata(artifact, item, MavenMarkers.Metadata)
    val versionsMeta = meta.get(MetadataKeyConstants.adHoc("maven")("Versions"))
    assert(versionsMeta.isDefined, "Versions key must be present")
    val versionsJson = versionsMeta.get.head.value
    assert(versionsJson.contains("1.0.0"), "Versions must include 1.0.0")
    assert(versionsJson.contains("2.0.0"), "Versions must include 2.0.0")
    assert(versionsJson.contains("3.0.0"), "Versions must include 3.0.0")
  }

  test("MavenState - extracts latest/release") {
    val metaXml = makeMetadataXml()
    val state = MavenState()
    val artifact =
      ByteWrapper(metaXml.getBytes("UTF-8"), "maven-metadata.xml", None)
    val item = createTestItem("test")
    val newState = state.beginProcessing(artifact, item, MavenMarkers.Metadata)

    val (meta, _) = newState.getMetadata(artifact, item, MavenMarkers.Metadata)
    val latestMeta = meta.get(MetadataKeyConstants.adHoc("maven")("Latest"))
    assert(latestMeta.isDefined, "Latest key must be present")
    assertEquals(latestMeta.get.head.value, "3.0.0")

    val releaseMeta = meta.get(MetadataKeyConstants.adHoc("maven")("Release"))
    assert(releaseMeta.isDefined, "Release key must be present")
    assertEquals(releaseMeta.get.head.value, "2.0.0")
  }

  // ==================== 4.3: pqc_jars Corpus ====================

  test("MavenToProcess - pqc_jars generates 4 distinct pURLs") {
    assume(new File("test_data/pqc_jars").exists(), "pqc_jars test data exists")

    val source = new File("test_data/pqc_jars")
    val strategies = ToProcess.strategyForDirectory(source, false, None)

    // Filter to Maven strategies
    val mavenStrategies = strategies.toVector.collect {
      case mtp: MavenToProcess => mtp
    }

    assertEquals(mavenStrategies.size, 4, "Should find 4 Maven JARs")

    val purls = mavenStrategies.flatMap { mtp =>
      val store = MemStorage(None)
      val item = createTestItem("jar")
      // Process the POM first to get GAV data
      val state = MavenState()
      val s1 =
        state.beginProcessing(mtp.jar, item, MavenMarkers.JAR)
      val s2 = mtp.pom
        .map { pom =>
          s1.beginProcessing(pom, createTestItem("pom"), MavenMarkers.POM)
        }
        .getOrElse(s1)
      // Accumulate JAR child entries
      FileWalker.withinArchiveStream(mtp.jar) { entries =>
        entries.foreach { entry =>
          s2.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      // Apply accumulated augmentation which resolves GAV and creates pURLs
      val s3 = s2.applyAccumulatedAugmentation(item, mtp.jar, store)
      // After applyAccumulatedAugmentation, pURLs are in the store's purl index
      store.purls().toVector
    }

    // Should have 4 distinct pURLs (one per version)
    assertEquals(purls.size, 4, "Should generate 4 pURLs")
    val distinctPurls = purls.map(_.toString).distinct
    assertEquals(distinctPurls.size, 4, "All 4 pURLs must be distinct")
  }

  test("MavenToProcess - pqc_jars pairs each JAR with its POM") {
    assume(new File("test_data/pqc_jars").exists(), "pqc_jars test data exists")

    val source = new File("test_data/pqc_jars")
    val strategies = ToProcess.strategyForDirectory(source, false, None)

    val mavenStrategies = strategies.toVector.collect {
      case mtp: MavenToProcess => mtp
    }

    assertEquals(mavenStrategies.size, 4)

    mavenStrategies.foreach { mtp =>
      assert(mtp.pom.isDefined, s"JAR ${mtp.jar.path()} must have paired POM")
      val jarName = mtp.jar.path()
      val pomPath = mtp.pom.get.path()
      // POM filename should match JAR filename base (same version)
      val jarBase = jarName.takeWhile(_ != '.')
      val pomBase = pomPath.takeWhile(_ != '.')
      assertEquals(pomBase, jarBase, "POM base name must match JAR base name")
    }
  }
}
