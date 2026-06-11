/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MavenPhase5Suite extends FunSuite {

  private def createTestItem(id: String): Item =
    Item(id, scala.collection.immutable.TreeSet.empty, None, None)

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

  private def metadataValue(
      state: MavenState,
      artifact: FileWrapper,
      marker: MavenMarkers,
      key: String
  ): Option[String] = {
    val (meta, _) = state.getMetadata(artifact, createTestItem("test"), marker)
    meta.get(key).map(_.head.value)
  }

  // ==================== 5.1: Spring Boot Fat JAR ====================

  test("MavenState - detects Spring Boot fat JAR") {
    val tempDir = Files.createTempDirectory("spring-boot").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "BOOT-INF/classes/com/example/App.class" -> "class bytes",
          "BOOT-INF/lib/spring-core-5.3.jar" -> "jar bytes"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("sb"),
        MavenMarkers.JAR
      )
      val jarType = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JarType")
      )
      assertEquals(jarType, Some("spring-boot-fat-jar"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - extracts nested JAR list") {
    val tempDir = Files.createTempDirectory("spring-boot-nested").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "BOOT-INF/lib/slf4j-api-1.7.jar" -> "",
          "BOOT-INF/lib/logback-1.2.jar" -> ""
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("sb"),
        MavenMarkers.JAR
      )
      val nested = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("NestedJars")
      )
      assert(nested.isDefined, "NestedJars must be present")
      val json = nested.get
      assert(json.contains("slf4j-api-1.7.jar"), json)
      assert(json.contains("logback-1.2.jar"), json)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - extracts Spring Boot main class") {
    val tempDir = Files.createTempDirectory("spring-boot-main").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\nStart-Class: com.example.App\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("sb"),
        MavenMarkers.JAR
      )
      val mainClass = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("SpringBootMainClass")
      )
      assertEquals(mainClass, Some("com.example.App"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - extracts Spring Boot layers.idx") {
    val tempDir = Files.createTempDirectory("layers-idx").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "BOOT-INF/layers.idx" -> "dependencies\nspring-boot-loader\nsnapshot-dependencies\n",
          "BOOT-INF/lib/spring-core-5.3.jar" -> ""
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("sb"),
        MavenMarkers.JAR
      )
      val layers = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("LayersIdx")
      )
      assert(layers.isDefined, "LayersIdx must be present")
      val json = layers.get
      assert(json.contains("dependencies"), json)
      assert(json.contains("spring-boot-loader"), json)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - extracts Spring Boot classpath.idx") {
    val tempDir = Files.createTempDirectory("classpath-idx").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "BOOT-INF/classpath.idx" -> "BOOT-INF/lib/a.jar\nBOOT-INF/lib/b.jar\n",
          "BOOT-INF/lib/spring-core-5.3.jar" -> ""
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("sb"),
        MavenMarkers.JAR
      )
      val cp = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("ClasspathIdx")
      )
      assert(cp.isDefined, "ClasspathIdx must be present")
      val json = cp.get
      assert(json.contains("a.jar"), json)
      assert(json.contains("b.jar"), json)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.2: Maven Shade Plugin ====================

  test("MavenState - detects Shade Plugin marker") {
    val tempDir = Files.createTempDirectory("shade").toFile
    try {
      val jarFile = new File(tempDir, "shaded.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/org.apache.maven.plugins/maven-shade-plugin/plugin.xml" -> "<plugin/>"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("shade"),
        MavenMarkers.JAR
      )
      val jarType = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JarType")
      )
      assertEquals(jarType, Some("shaded-jar"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - detects shade from MANIFEST Created-By") {
    val tempDir = Files.createTempDirectory("shade-manifest").toFile
    try {
      val jarFile = new File(tempDir, "shaded.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\nCreated-By: Apache Maven Shade Plugin\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("shade"),
        MavenMarkers.JAR
      )
      val jarType = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JarType")
      )
      assertEquals(jarType, Some("shaded-jar"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.3: WAR Internal Structure ====================

  test("MavenState - detects WAR structure") {
    val tempDir = Files.createTempDirectory("war").toFile
    try {
      val jarFile = new File(tempDir, "app.war")
      writeJarEntries(
        jarFile,
        Seq(
          "WEB-INF/web.xml" -> "<web-app/>"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("war"),
        MavenMarkers.JAR
      )
      val jarType = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JarType")
      )
      assertEquals(jarType, Some("war"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - enumerates WAR lib JARs") {
    val tempDir = Files.createTempDirectory("war-lib").toFile
    try {
      val jarFile = new File(tempDir, "app.war")
      writeJarEntries(
        jarFile,
        Seq(
          "WEB-INF/lib/servlet-api.jar" -> "",
          "WEB-INF/lib/spring-web.jar" -> ""
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("war"),
        MavenMarkers.JAR
      )
      val warLibs = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("WarLibJars")
      )
      assert(warLibs.isDefined, "WarLibJars must be present")
      val json = warLibs.get
      assert(json.contains("servlet-api.jar"), json)
      assert(json.contains("spring-web.jar"), json)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.4: EAR Internal Structure ====================

  test("MavenState - detects EAR structure") {
    val tempDir = Files.createTempDirectory("ear").toFile
    try {
      val jarFile = new File(tempDir, "app.ear")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/application.xml" -> "<application/>"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("ear"),
        MavenMarkers.JAR
      )
      val jarType = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JarType")
      )
      assertEquals(jarType, Some("ear"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - enumerates EAR modules") {
    val tempDir = Files.createTempDirectory("ear-modules").toFile
    try {
      val jarFile = new File(tempDir, "app.ear")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/application.xml" ->
            """<?xml version="1.0" encoding="UTF-8"?>
            |<application xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="8">
            |  <module><web><web-uri>web.war</web-uri><context-root>/app</context-root></web></module>
            |  <module><ejb>business.jar</ejb></module>
            |</application>""".stripMargin
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("ear"),
        MavenMarkers.JAR
      )
      val modules = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("EarModules")
      )
      assert(modules.isDefined, "EarModules must be present")
      val json = modules.get
      assert(json.contains("web.war"), json)
      assert(json.contains("business.jar"), json)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.5: Multi-Release JAR ====================

  test("MavenState - detects Multi-Release true") {
    val tempDir = Files.createTempDirectory("mr").toFile
    try {
      val jarFile = new File(tempDir, "mr.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\nMulti-Release: true\n",
          "META-INF/versions/9/module-info.class" -> "class",
          "META-INF/versions/11/module-info.class" -> "class"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("mr"),
        MavenMarkers.JAR
      )
      val jarType = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JarType")
      )
      assertEquals(jarType, Some("multi-release"))
      val versions = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("MultiReleaseVersions")
      )
      assert(versions.isDefined, "MultiReleaseVersions must be present")
      val json = versions.get
      assert(json.contains("9"), json)
      assert(json.contains("11"), json)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - non-MR JAR has no multi-release key") {
    val tempDir = Files.createTempDirectory("non-mr").toFile
    try {
      val jarFile = new File(tempDir, "normal.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("normal"),
        MavenMarkers.JAR
      )
      val mrKey = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("MultiReleaseVersions")
      )
      assert(mrKey.isEmpty, "Non-MR JAR must not have MultiReleaseVersions key")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.6: JAR Signature ====================

  test("MavenState - detects signed JAR") {
    val tempDir = Files.createTempDirectory("signed").toFile
    try {
      val jarFile = new File(tempDir, "signed.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/EXAMPLE.SF" -> "Signature",
          "META-INF/EXAMPLE.RSA" -> "key"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("signed"),
        MavenMarkers.JAR
      )
      val signed = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JarSigned")
      )
      assertEquals(signed, Some("true"))
      val sigFiles = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("SignatureFiles")
      )
      assert(sigFiles.isDefined, "SignatureFiles must be present")
      val json = sigFiles.get
      assert(json.contains("EXAMPLE.SF"), json)
      assert(json.contains("EXAMPLE.RSA"), json)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - unsigned JAR has no signature key") {
    val tempDir = Files.createTempDirectory("unsigned").toFile
    try {
      val jarFile = new File(tempDir, "unsigned.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("unsigned"),
        MavenMarkers.JAR
      )
      val signed = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JarSigned")
      )
      assert(signed.isEmpty, "Unsigned JAR must not have JarSigned key")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.7: ServiceLoader ====================

  test("MavenState - extracts ServiceLoader metadata") {
    val tempDir = Files.createTempDirectory("services").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/services/java.sql.Driver" -> "org.example.Driver\norg.example.OtherDriver",
          "META-INF/services/javax.ws.rs.ext.Provider" -> "org.example.Provider"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("svc"),
        MavenMarkers.JAR
      )
      val services = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("ServiceProviders")
      )
      assert(services.isDefined, "ServiceProviders must be present")
      val json = services.get
      assert(json.contains("java.sql.Driver"), json)
      assert(json.contains("org.example.Driver"), json)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - no ServiceProviders key when no services") {
    val tempDir = Files.createTempDirectory("no-services").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("no-svc"),
        MavenMarkers.JAR
      )
      val services = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("ServiceProviders")
      )
      assert(services.isEmpty, "No ServiceProviders key expected")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.8: Automatic-Module-Name ====================

  test("MavenState - extracts Automatic-Module-Name") {
    val tempDir = Files.createTempDirectory("module").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\nAutomatic-Module-Name: com.example.module\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("mod"),
        MavenMarkers.JAR
      )
      val modName = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("AutomaticModuleName")
      )
      assertEquals(modName, Some("com.example.module"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.9: GraalVM native-image.properties ====================

  test("MavenState - extracts GraalVM native-image.properties") {
    val tempDir = Files.createTempDirectory("graal").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/native-image/com.example/app/native-image.properties" -> "Args=-H:+ReportExceptionStackTraces\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("graal"),
        MavenMarkers.JAR
      )
      val graal = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("GraalNativeImage")
      )
      assert(graal.isDefined, "GraalNativeImage must be present")
      assert(graal.get.contains("Args"), graal.get)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - no GraalNativeImage key when absent") {
    val tempDir = Files.createTempDirectory("no-graal").toFile
    try {
      val jarFile = new File(tempDir, "app.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("no-graal"),
        MavenMarkers.JAR
      )
      val graal = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("GraalNativeImage")
      )
      assert(graal.isEmpty, "No GraalNativeImage key expected")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.10: Jenkins Plugin Detection ====================

  test("MavenState - detects Jenkins plugin by Group-Id") {
    val tempDir = Files.createTempDirectory("jenkins").toFile
    try {
      val jarFile = new File(tempDir, "plugin.hpi")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\nGroup-Id: io.jenkins.plugins.example\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("jenkins"),
        MavenMarkers.JAR
      )
      val jenkins = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JenkinsPlugin")
      )
      assertEquals(jenkins, Some("true"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - detects by file extension jpi/hpi") {
    val tempDir = Files.createTempDirectory("jenkins-ext").toFile
    try {
      val jarFile = new File(tempDir, "plugin.jpi")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("jenkins"),
        MavenMarkers.JAR
      )
      val jenkins = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("maven")("JenkinsPlugin")
      )
      assertEquals(jenkins, Some("true"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  // ==================== 5.11: OSGi Bundle Metadata ====================

  test("MavenState - extracts OSGi bundle metadata") {
    val tempDir = Files.createTempDirectory("osgi").toFile
    try {
      val jarFile = new File(tempDir, "bundle.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> """Manifest-Version: 1.0
Bundle-Name: MyBundle
Bundle-Description: A test bundle
Bundle-Vendor: Example Inc
Bundle-DocURL: https://example.com
"""
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("osgi"),
        MavenMarkers.JAR
      )
      val bundleName = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("osgi")("BundleName")
      )
      assertEquals(bundleName, Some("MyBundle"))
      val bundleVendor = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("osgi")("BundleVendor")
      )
      assertEquals(bundleVendor, Some("Example Inc"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - no OSGi keys for non-bundle JAR") {
    val tempDir = Files.createTempDirectory("non-osgi").toFile
    try {
      val jarFile = new File(tempDir, "plain.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n"
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("plain"),
        MavenMarkers.JAR
      )
      val bundleName = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("osgi")("BundleName")
      )
      assert(bundleName.isEmpty, "Non-bundle JAR should not emit OSGi keys")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }

  test("MavenState - extracts full OSGi headers including Export-Package") {
    val tempDir = Files.createTempDirectory("osgi-full").toFile
    try {
      val jarFile = new File(tempDir, "bundle.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> """Manifest-Version: 1.0
Bundle-Name: FullBundle
Export-Package: org.example.api;version="1.0",org.example.internal
Import-Package: org.example.base;version="2.0"
Require-Capability: osgi.ee;filter:="(&(osgi.ee=JavaSE)(version=11))"
Provide-Capability: osgi.service;objectClass:List<String>="com.example.Service"
Fragment-Host: org.example.host
"""
        )
      )
      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val state = MavenState().beginProcessing(
        wrapper,
        createTestItem("osgi-full"),
        MavenMarkers.JAR
      )
      val exportPkg = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("osgi")("ExportPackage")
      )
      assert(exportPkg.isDefined, "ExportPackage must be present")
      val exportJson = exportPkg.get
      assert(exportJson.contains("org.example.api"), exportJson)
      assert(exportJson.contains("\"version\":\"1.0\""), exportJson)
      assert(exportJson.contains("org.example.internal"), exportJson)
      val importPkg = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("osgi")("ImportPackage")
      )
      assert(importPkg.isDefined, "ImportPackage must be present")
      assert(importPkg.get.contains("org.example.base"), importPkg.get)
      val reqCap = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("osgi")("RequireCapability")
      )
      assert(reqCap.isDefined, "RequireCapability must be present")
      val provCap = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("osgi")("ProvideCapability")
      )
      assert(provCap.isDefined, "ProvideCapability must be present")
      val fragment = metadataValue(
        state,
        wrapper,
        MavenMarkers.JAR,
        MetadataKeyConstants.adHoc("osgi")("FragmentHost")
      )
      assert(fragment.isDefined, "FragmentHost must be present")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }
}
