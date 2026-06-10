/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File
import scala.collection.immutable.TreeSet

class MavenPhase5CorpusSuite extends FunSuite {

  private def createTestItem(id: String): Item =
    Item(id, TreeSet.empty, None, None)

  private def metadataValue(
      state: MavenState,
      artifact: FileWrapper,
      key: String
  ): Option[String] = {
    val (meta, _) =
      state.getMetadata(artifact, createTestItem("test"), MavenMarkers.JAR)
    meta.get(key).map(_.head.value)
  }

  private def loadArtifact(dir: String, name: String): FileWrapper = {
    val file = new File(s"test_data/$dir/$name")
    FileWrapper(file, file.getAbsolutePath, None)
  }

  test("corpus spring-boot-fat-jar produces spring-boot-fat-jar type") {
    val artifact = loadArtifact("spring-boot-fat-jar", "app.jar")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("sb"),
      MavenMarkers.JAR
    )
    val jarType = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("JarType")
    )
    assertEquals(jarType, Some("spring-boot-fat-jar"))
    val nested = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("NestedJars")
    )
    assert(nested.isDefined, "NestedJars must be present")
    assert(nested.get.contains("nested-library.jar"), nested.get)
  }

  test("corpus war-test produces war type") {
    val artifact = loadArtifact("war-test", "app.war")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("war"),
      MavenMarkers.JAR
    )
    val jarType = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("JarType")
    )
    assertEquals(jarType, Some("war"))
    val libs = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("WarLibJars")
    )
    assert(libs.isDefined, "WarLibJars must be present")
    assert(libs.get.contains("library.jar"), libs.get)
  }

  test("corpus ear-test produces ear type") {
    val artifact = loadArtifact("ear-test", "app.ear")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("ear"),
      MavenMarkers.JAR
    )
    val jarType = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("JarType")
    )
    assertEquals(jarType, Some("ear"))
    val modules = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("EarModules")
    )
    assert(modules.isDefined, "EarModules must be present")
    assert(modules.get.contains("web.war"), modules.get)
  }

  test("corpus multi-release-jar produces multi-release type") {
    val artifact = loadArtifact("multi-release-jar", "mr.jar")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("mr"),
      MavenMarkers.JAR
    )
    val jarType = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("JarType")
    )
    assertEquals(jarType, Some("multi-release"))
    val versions = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("MultiReleaseVersions")
    )
    assert(versions.isDefined, "MultiReleaseVersions must be present")
    assert(versions.get.contains("9"), versions.get)
  }

  test("corpus shaded-jar produces shaded-jar type") {
    val artifact = loadArtifact("shaded-jar", "shaded.jar")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("shade"),
      MavenMarkers.JAR
    )
    val jarType = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("JarType")
    )
    assertEquals(jarType, Some("shaded-jar"))
  }

  test("corpus signed-jar detects signatures") {
    val artifact = loadArtifact("signed-jar", "signed.jar")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("signed"),
      MavenMarkers.JAR
    )
    val signed = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("JarSigned")
    )
    assertEquals(signed, Some("true"))
    val sigFiles = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("SignatureFiles")
    )
    assert(sigFiles.isDefined, "SignatureFiles must be present")
    assert(sigFiles.get.contains("TEST.SF"), sigFiles.get)
    assert(sigFiles.get.contains("TEST.RSA"), sigFiles.get)
  }

  test("corpus osgi-bundle emits full OSGi headers") {
    val artifact = loadArtifact("osgi-bundle", "bundle.jar")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("osgi"),
      MavenMarkers.JAR
    )
    val bundleName = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("osgi")("BundleName")
    )
    assert(bundleName.isDefined, "BundleName must be present")
    val exportPkg = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("osgi")("ExportPackage")
    )
    assert(exportPkg.isDefined, "ExportPackage must be present")
    val importPkg = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("osgi")("ImportPackage")
    )
    assert(importPkg.isDefined, "ImportPackage must be present")
  }

  test("corpus graalvm-jar extracts native-image.properties") {
    val artifact = loadArtifact("graalvm-jar", "graal.jar")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("graal"),
      MavenMarkers.JAR
    )
    val graal = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("GraalNativeImage")
    )
    assert(graal.isDefined, "GraalNativeImage must be present")
  }

  test("corpus jenkins-plugin detects JenkinsPlugin") {
    val artifact = loadArtifact("jenkins-plugin", "plugin.hpi")
    val state = MavenState().beginProcessing(
      artifact,
      createTestItem("jenkins"),
      MavenMarkers.JAR
    )
    val jenkins = metadataValue(
      state,
      artifact,
      MetadataKeyConstants.adHoc("maven")("JenkinsPlugin")
    )
    assertEquals(jenkins, Some("true"))
  }

  test("corpus newrelic-weave JAR is not claimed by Maven") {
    val artifact = loadArtifact("newrelic-weave", "weave.jar")
    val byUUID = Map(artifact.uuid -> artifact)
    val byName = Map("weave.jar" -> Vector(artifact))
    val (toProcess, _, _, _) = MavenToProcess.computeMavenFiles(byUUID, byName)
    // Weave-Classes JARs should be excluded from Maven processing
    assertEquals(
      toProcess.size,
      0,
      "Weave-Classes JAR must not be claimed by MavenToProcess"
    )
  }
}
