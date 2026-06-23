/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MavenPhase5ModuleInfoSuite extends FunSuite {

  private def createTestItem(id: String): Item =
    Item(id, scala.collection.immutable.TreeSet.empty, None, None)

  private def writeJarEntriesBytes(
      jarFile: File,
      entries: Seq[(String, Array[Byte])]
  ): Unit = {
    val zos = new ZipOutputStream(new FileOutputStream(jarFile))
    try {
      for ((path, content) <- entries) {
        zos.putNextEntry(new ZipEntry(path))
        zos.write(content)
        zos.closeEntry()
      }
    } finally {
      zos.close()
    }
  }

  test("MavenState - extracts module-info.class metadata via BCEL") {
    val tempDir = Files.createTempDirectory("module-info-test").toFile
    try {
      val modDir = new File(tempDir, "mod")
      modDir.mkdirs()

      // Write dummy source files so javac can compile the module-info
      val driverDir = new File(tempDir, "org/example")
      driverDir.mkdirs()
      Files.writeString(
        new File(driverDir, "MyDriver.java").toPath,
        """package org.example;
          |import java.sql.Driver;
          |import java.sql.Connection;
          |import java.util.Properties;
          |import java.sql.DriverPropertyInfo;
          |import java.util.logging.Logger;
          |public class MyDriver implements Driver {
          |  public Connection connect(String url, Properties info) { return null; }
          |  public boolean acceptsURL(String url) { return false; }
          |  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return null; }
          |  public int getMajorVersion() { return 1; }
          |  public int getMinorVersion() { return 0; }
          |  public boolean jdbcCompliant() { return false; }
          |  public Logger getParentLogger() { return null; }
          |}
          |""".stripMargin
      )

      val moduleInfoJava = new File(tempDir, "module-info.java")
      Files.writeString(
        moduleInfoJava.toPath,
        """module com.example.mod {
          |    requires java.base;
          |    requires java.sql;
          |    exports com.example.api;
          |    provides java.sql.Driver with org.example.MyDriver;
          |    uses java.nio.file.spi.FileSystemProvider;
          |}
          |""".stripMargin
      )

      val dummyJava = new File(tempDir, "Dummy.java")
      Files.writeString(
        dummyJava.toPath,
        "package com.example.api;\npublic class Dummy {}\n"
      )

      val javacResult = new ProcessBuilder(
        "javac",
        "-d",
        modDir.getAbsolutePath,
        dummyJava.getAbsolutePath,
        new File(driverDir, "MyDriver.java").getAbsolutePath,
        moduleInfoJava.getAbsolutePath
      ).inheritIO().start().waitFor()
      assertEquals(javacResult, 0, "javac should succeed")

      val moduleInfoClass = new File(modDir, "module-info.class")
      assert(
        moduleInfoClass.exists(),
        "module-info.class should exist after javac"
      )
      val classBytes = Files.readAllBytes(moduleInfoClass.toPath)

      val jarFile = new File(tempDir, "module.jar")
      writeJarEntriesBytes(jarFile, Seq("module-info.class" -> classBytes))

      val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
      val item = createTestItem("mod")
      val store = MemStorage(None)
      val s1 = MavenState().beginProcessing(wrapper, item, MavenMarkers.JAR)
      FileWalker.withinArchiveStream(wrapper) { entries =>
        entries.foreach { entry =>
          s1.accumulateInfo(item.identifier, item, entry, store)
        }
      }
      val (meta, _) =
        s1.getMetadata(wrapper, createTestItem("mod"), MavenMarkers.JAR)

      val modNameKey =
        MetadataKeyConstants.adHoc("maven")("AutomaticModuleName")
      val modName = meta.get(modNameKey).map(_.head.value)
      assertEquals(modName, Some("com.example.mod"))

      val reqKey = MetadataKeyConstants.adHoc("maven")("ModuleRequires")
      val req = meta.get(reqKey).map(_.head.value)
      assert(req.isDefined, "ModuleRequires should be present")
      val reqJson = req.get
      assert(reqJson.contains("java.base"), reqJson)
      assert(reqJson.contains("java.sql"), reqJson)

      val expKey = MetadataKeyConstants.adHoc("maven")("ModuleExports")
      val exp = meta.get(expKey).map(_.head.value)
      assert(exp.isDefined, "ModuleExports should be present")
      assert(exp.get.contains("com.example.api"), exp.get)

      val provKey = MetadataKeyConstants.adHoc("maven")("ModuleProvides")
      val prov = meta.get(provKey).map(_.head.value)
      assert(prov.isDefined, "ModuleProvides should be present")
      val provJson = prov.get
      assert(provJson.contains("java.sql.Driver"), provJson)
      assert(provJson.contains("org.example.MyDriver"), provJson)

      val usesKey = MetadataKeyConstants.adHoc("maven")("ModuleUses")
      val uses = meta.get(usesKey).map(_.head.value)
      assert(uses.isDefined, "ModuleUses should be present")
      assert(
        uses.get.contains("java.nio.file.spi.FileSystemProvider"),
        uses.get
      )

      val opensKey = MetadataKeyConstants.adHoc("maven")("ModuleOpens")
      assertEquals(meta.get(opensKey), None, "No opens entries expected")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath)
    }
  }
}
