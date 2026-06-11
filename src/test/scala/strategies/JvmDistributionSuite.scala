/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0 */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.TreeSet

/** Phase 6 — JVM Distribution Strategy test suite.
  *
  * Tests JvmDistribution.release file parsing, vendor detection, pURL
  * generation, metadata emission, and package tagging.
  */
class JvmDistributionSuite extends FunSuite {

  private def createTestItem(id: String): Item =
    Item(id, TreeSet.empty, None, None)

  private def loadRelease(dir: String): String = {
    val path = s"test_data/jvm/$dir/release"
    val f = new File(path)
    if (!f.exists()) fail(s"Missing test corpus: $path")
    Files.readString(f.toPath)
  }

  // ==================== 6.2: Parse release file ====================

  test("JvmState - parses release file with all fields") {
    val content = loadRelease("adoptium-jdk21")
    val data = JvmDistribution.parseReleaseFile(content)

    assertEquals(data.javaVersion, Some("21.0.4"))
    assertEquals(data.javaRuntimeVersion, Some("21.0.4+7"))
    assertEquals(data.implementor, Some("Eclipse Adoptium"))
    assertEquals(data.imageType, Some("JDK"))
    assertEquals(data.osArch, Some("x86_64"))
    assertEquals(data.osName, Some("linux"))
    assertEquals(data.libc, Some("glibc"))
    assertEquals(data.semanticVersion, Some("21.0.4+7"))
    assertEquals(data.fullVersion, Some("21.0.4+7-adoptium"))
    assertEquals(data.jvmVariant, Some("Hotspot"))
    assertEquals(
      data.sourceRepo,
      Some("https://github.com/adoptium/temurin21-binaries")
    )
    assertEquals(data.javaVersionDate, Some("2024-07-16"))
  }

  test("JvmState - rejects file without JAVA_VERSION or JAVA_RUNTIME_VERSION") {
    val randomRelease =
      "OS_NAME=linux\nOS_ARCH=x86_64\nIMPLEMENTOR=Unknown\n"
    val data = JvmDistribution.parseReleaseFile(randomRelease)
    assertEquals(data.javaVersion, None)
    assertEquals(data.javaRuntimeVersion, None)

    // computeJvmFiles should not claim it
    val wrapper = ByteWrapper(
      randomRelease.getBytes("UTF-8"),
      "release",
      None
    )
    val byUUID = Map(wrapper.uuid -> wrapper)
    val byName = Map("release" -> Vector(wrapper))
    val (toProcess, _, _, _) =
      JvmDistribution.computeJvmFiles(byUUID, byName)
    assertEquals(
      toProcess.size,
      0,
      "Random release file without JAVA_VERSION should not be claimed"
    )
  }

  test("JvmState - handles pre-JEP 223 version (1.8.0_411)") {
    val content = loadRelease("oracle-jdk8")
    val data = JvmDistribution.parseReleaseFile(content)
    assertEquals(data.javaVersion, Some("1.8.0_411"))
    assertEquals(data.javaRuntimeVersion, Some("1.8.0_411-b25"))
  }

  test("JvmState - handles post-JEP 223 version (21.0.4+7)") {
    val content = loadRelease("adoptium-jdk21")
    val data = JvmDistribution.parseReleaseFile(content)
    assertEquals(data.javaVersion, Some("21.0.4"))
    assertEquals(data.javaRuntimeVersion, Some("21.0.4+7"))
  }

  test("JvmState - detects JDK vs JRE from IMAGE_TYPE") {
    val jdkRelease = loadRelease("adoptium-jdk21")
    val jdkData = JvmDistribution.parseReleaseFile(jdkRelease)
    val jreRelease = loadRelease("zulu-jdk11")
    val jreData = JvmDistribution.parseReleaseFile(jreRelease)

    val jdkWrapper = FileWrapper(
      new File("test_data/jvm/adoptium-jdk21/release"),
      "release",
      None
    )
    val jreWrapper = FileWrapper(
      new File("test_data/jvm/zulu-jdk11/release"),
      "release",
      None
    )

    assert(
      JvmDistribution.isJDK(jdkData, jdkWrapper),
      "Adoptium with IMAGE_TYPE=JDK should be JDK"
    )
    assert(
      !JvmDistribution.isJDK(jreData, jreWrapper),
      "Zulu with IMAGE_TYPE=JRE should be JRE"
    )
  }

  test("JvmState - detects JDK via bin/javac presence") {
    val tempDir = Files.createTempDirectory("jdk-bin-test").toFile
    try {
      val releaseFile = new File(tempDir, "release")
      Files.writeString(
        releaseFile.toPath,
        "JAVA_VERSION=\"17.0.0\"\nIMPLEMENTOR=\"Test\"\n"
      )

      val binDir = new File(tempDir, "bin")
      binDir.mkdirs()
      val javacFile = new File(binDir, "javac")
      javacFile.createNewFile()

      val wrapper = FileWrapper(releaseFile, releaseFile.getAbsolutePath, None)
      val data = JvmDistribution.parseReleaseFile(
        Files.readString(releaseFile.toPath)
      )
      assert(
        JvmDistribution.isJDK(data, wrapper),
        "bin/javac presence should classify as JDK when IMAGE_TYPE absent"
      )
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 6.3: Vendor detection ====================

  test("JvmState - identifies Eclipse Adoptium") {
    val (ns, prod) = JvmDistribution.detectVendor(
      Some("Eclipse Adoptium"),
      ""
    )
    assertEquals(ns, "eclipse")
    assertEquals(prod, "temurin")
  }

  test("JvmState - identifies Oracle") {
    val (ns, prod) = JvmDistribution.detectVendor(
      Some("Oracle Corporation"),
      ""
    )
    assertEquals(ns, "oracle")
    assertEquals(prod, "jdk")
  }

  test("JvmState - identifies Azul Zulu") {
    val (ns, prod) =
      JvmDistribution.detectVendor(Some("Azul Systems, Inc."), "")
    assertEquals(ns, "azul")
    assertEquals(prod, "zulu")
  }

  test("JvmState - identifies Amazon Corretto") {
    val (ns, prod) =
      JvmDistribution.detectVendor(Some("Amazon.com Inc."), "")
    assertEquals(ns, "amazon")
    assertEquals(prod, "corretto")
  }

  test("JvmState - defaults to OpenJDK") {
    val (ns, prod) =
      JvmDistribution.detectVendor(Some("Unknown Vendor"), "")
    assertEquals(ns, "openjdk")
    assertEquals(prod, "jdk")
  }

  // ==================== 6.4: pURL generation ====================

  test("JvmState - generates pURL for JDK") {
    val content = loadRelease("adoptium-jdk21")
    val wrapper = FileWrapper(
      new File("test_data/jvm/adoptium-jdk21/release"),
      "release",
      None
    )
    val data = JvmDistribution.parseReleaseFile(content)
    val state = new JvmState(wrapper, data)

    val (purls, _) = state.getPurls(
      wrapper,
      createTestItem("test"),
      SingleMarker()
    )
    assert(purls.nonEmpty, "Should produce at least one pURL")
    val purl = purls.head
    assert(
      purl.contains("eclipse"),
      s"pURL should contain vendor namespace 'eclipse': $purl"
    )
    assert(
      purl.contains("temurin"),
      s"pURL should contain product 'temurin': $purl"
    )
    assert(
      purl.contains("21.0.4"),
      s"pURL should contain version '21.0.4': $purl"
    )
  }

  test("JvmState - includes repository_url qualifier") {
    val content = loadRelease("adoptium-jdk21")
    val wrapper = FileWrapper(
      new File("test_data/jvm/adoptium-jdk21/release"),
      "release",
      None
    )
    val data = JvmDistribution.parseReleaseFile(content)
    val state = new JvmState(wrapper, data)

    val (purls, _) = state.getPurls(
      wrapper,
      createTestItem("test"),
      SingleMarker()
    )
    assert(purls.nonEmpty)
    val purl = purls.head
    assert(
      purl.contains("repository_url"),
      s"pURL should contain repository_url qualifier: $purl"
    )
    assert(
      purl.contains("adoptium"),
      s"pURL should reference adoptium repo: $purl"
    )
  }

  // ==================== 6.5: Package tag ====================

  test("JvmState - generates package tag") {
    val content = loadRelease("adoptium-jdk21")
    val wrapper = FileWrapper(
      new File("test_data/jvm/adoptium-jdk21/release"),
      "release",
      None
    )
    val data = JvmDistribution.parseReleaseFile(content)
    val state = new JvmState(wrapper, data)

    val tagOpt = state.maybePackageTag(SingleMarker())
    assert(tagOpt.isDefined, "Should produce a package tag")
    val tag = tagOpt.get
    assertEquals(tag.name, "eclipse/temurin")
    assertEquals(tag.version, Some("21.0.4+7"))
  }

  test("JvmState - tag date from JAVA_VERSION_DATE") {
    val content = loadRelease("adoptium-jdk21")
    val wrapper = FileWrapper(
      new File("test_data/jvm/adoptium-jdk21/release"),
      "release",
      None
    )
    val data = JvmDistribution.parseReleaseFile(content)
    val state = new JvmState(wrapper, data)

    val tagOpt = state.maybePackageTag(SingleMarker())
    assert(tagOpt.isDefined)
    assert(
      tagOpt.get.date.isDefined,
      "Tag date should be parsed from JAVA_VERSION_DATE"
    )
  }

  // ==================== 6.6: Corpus ====================

  test("corpus adoptium-jdk21 produces pURL and metadata") {
    val wrapper = FileWrapper(
      new File("test_data/jvm/adoptium-jdk21/release"),
      "test_data/jvm/adoptium-jdk21/release",
      None
    )
    val data = JvmDistribution.parseReleaseFile(
      wrapper.withStream(Helpers.slurpInputToString(_))
    )
    val state = new JvmState(wrapper, data)

    val (purls, _) =
      state.getPurls(wrapper, createTestItem("t"), SingleMarker())
    assert(purls.nonEmpty, "Adoptium corpus should produce pURL")

    val (meta, _) =
      state.getMetadata(wrapper, createTestItem("t"), SingleMarker())
    assert(
      meta.contains(MetadataKeyConstants.NAME),
      "Metadata should have Name"
    )
    assert(
      meta.contains(MetadataKeyConstants.VERSION),
      "Metadata should have Version"
    )
  }

  test("corpus oracle-jdk8 produces pURL") {
    val wrapper = FileWrapper(
      new File("test_data/jvm/oracle-jdk8/release"),
      "test_data/jvm/oracle-jdk8/release",
      None
    )
    val data = JvmDistribution.parseReleaseFile(
      wrapper.withStream(Helpers.slurpInputToString(_))
    )
    val state = new JvmState(wrapper, data)

    val (purls, _) =
      state.getPurls(wrapper, createTestItem("t"), SingleMarker())
    assert(purls.nonEmpty, "Oracle JDK 8 corpus should produce pURL")
    assert(purls.head.contains("oracle"), "pURL should reference oracle")
  }

  test("corpus corretto-jdk17 identified as Amazon") {
    val wrapper = FileWrapper(
      new File("test_data/jvm/corretto-jdk17/release"),
      "test_data/jvm/corretto-jdk17/release",
      None
    )
    val data = JvmDistribution.parseReleaseFile(
      wrapper.withStream(Helpers.slurpInputToString(_))
    )
    val state = new JvmState(wrapper, data)

    val (purls, _) =
      state.getPurls(wrapper, createTestItem("t"), SingleMarker())
    assert(purls.head.contains("amazon"), "Corretto should map to amazon")
  }

  test("corpus zulu-jdk11 identified as JRE") {
    val wrapper = FileWrapper(
      new File("test_data/jvm/zulu-jdk11/release"),
      "test_data/jvm/zulu-jdk11/release",
      None
    )
    val data = JvmDistribution.parseReleaseFile(
      wrapper.withStream(Helpers.slurpInputToString(_))
    )
    val state = new JvmState(wrapper, data)

    val (meta, _) =
      state.getMetadata(wrapper, createTestItem("t"), SingleMarker())
    val isJDK = meta.get(MetadataKeyConstants.adHoc("jvm")("IsJDK"))
    assert(isJDK.isDefined, "IsJDK key should be present")
    assertEquals(isJDK.get.head.value, "false", "Zulu corpus is a JRE")
  }
}
