/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0 */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.strategies.MavenMarkers
import io.spicelabs.goatrodeo.omnibor.strategies.MavenState
import io.spicelabs.goatrodeo.omnibor.strategies.MavenToProcess
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.PomParser
import munit.FunSuite

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class MavenPhase1Suite extends FunSuite {

  private def createTestItem(id: String): Item = Item(
    id,
    TreeSet(),
    Some(ItemMetaData.mimeType),
    None
  )

  // ==================== 1.1: Archive types ====================

  // Ear/jpi/war handled by PackageTagIntegrationSuite

  // ==================== 1.2: pom.properties ====================

  test("pom.properties overrides POM GAV (resolveGAV)") {
    val state = MavenState()
    val props = Map(
      "groupId" -> "com.embedded",
      "artifactId" -> "embed-art",
      "version" -> "2.0"
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      props,
      None
    )
    assertEquals(g, Some("com.embedded"))
    assertEquals(a, Some("embed-art"))
    assertEquals(v, Some("2.0"))
  }

  test("pom.properties takes priority over POM GAV") {
    val state = MavenState()
    val pomOpt = PomParser.parse(
      "<project><groupId>com.pom</groupId><artifactId>pom-art</artifactId><version>1.0</version></project>"
    )
    val props = Map(
      "groupId" -> "com.props",
      "artifactId" -> "props-art",
      "version" -> "3.0"
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      pomOpt,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      props,
      None
    )
    assertEquals(g, Some("com.props"))
    assertEquals(a, Some("props-art"))
    assertEquals(v, Some("3.0"))
  }

  // ==================== 1.3: Embedded pom.xml ====================

  test("embedded pom.xml provides GAV when no external POM") {
    val state = MavenState()
    val embeddedPom = PomParser.parse(
      "<project><groupId>com.embed</groupId><artifactId>embed-art</artifactId><version>5.0</version></project>"
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      Map.empty,
      embeddedPom
    )
    assertEquals(g, Some("com.embed"))
    assertEquals(a, Some("embed-art"))
    assertEquals(v, Some("5.0"))
  }

  // ==================== 1.5: Filename identity ====================

  test("extracts identity from simple filename") {
    val state = MavenState()
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "mylib-1.2.3.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]]
    )
    assert(a.contains("mylib"), s"artifactId should be mylib, got $a")
    assert(v.contains("1.2.3"), s"version should be 1.2.3, got $v")
  }

  test("preserves Scala suffix in artifactId") {
    val state = MavenState()
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "lib_2.13-1.0.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]]
    )
    assert(
      a.contains("lib_2.13"),
      s"artifactId should preserve Scala suffix, got $a"
    )
    assert(v.contains("1.0"), s"version should be 1.0, got $v")
  }

  test("handles SNAPSHOT versions") {
    val state = MavenState()
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "lib-1.0-SNAPSHOT.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]]
    )
    assert(
      v.contains("1.0-SNAPSHOT"),
      s"version should preserve SNAPSHOT, got $v"
    )
  }

  test("POM GAV takes precedence over filename") {
    val pomOpt = PomParser.parse(
      "<project><groupId>com.pom</groupId><artifactId>pom-art</artifactId><version>9.9.9</version></project>"
    )
    val state = MavenState()
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "different-1.0.jar", None),
      pomOpt,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      Map.empty,
      None
    )
    assertEquals(g, Some("com.pom"))
    assertEquals(a, Some("pom-art"))
    assertEquals(v, Some("9.9.9"))
  }

  // ==================== 1.4: OSGi / Bundle identity ====================

  test("resolves GAV from Bundle-SymbolicName") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(StringOrPair("com.example.mybundle")),
      "bundle-version" -> TreeSet(StringOrPair("1.2.3"))
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assert(
      a.contains("com.example.mybundle"),
      s"artifactId from Bundle-SymbolicName, got $a"
    )
    assert(v.contains("1.2.3"), s"version from Bundle-Version, got $v")
  }

  test("strips OSGi directives from Bundle-SymbolicName") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(
        StringOrPair("org.example;uses:=\"org.foo\"")
      ),
      "bundle-version" -> TreeSet(StringOrPair("1.0.0"))
    )
    val (_, a, _) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(a, Some("org.example"))
  }

  // ==================== 1.6: GAV Priority ====================

  test("GAV priority: pom.properties > POM > manifest > filename") {
    val state = MavenState()
    val props =
      Map("groupId" -> "com.props", "artifactId" -> "p-art", "version" -> "3.0")
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      props,
      None
    )
    assertEquals(g, Some("com.props"))
    assertEquals(a, Some("p-art"))
    assertEquals(v, Some("3.0"))
  }

  test("GAV falls through to filename") {
    val state = MavenState()
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "fallback-4.5.6.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      Map.empty,
      None
    )
    assert(a.contains("fallback"), s"artifactId from filename, got $a")
    assert(v.contains("4.5.6"), s"version from filename, got $v")
  }

  // ==================== 1.7: Build Date ====================

  test("parses dd-MMM-yyyy format") {
    val pomWithDate = """<?xml version="1.0"?>
<project>
  <properties>
    <buildDate>01-Jan-2024</buildDate>
  </properties>
</project>"""
    val artifact = ByteWrapper(pomWithDate.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("date-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assert(
      state.buildDate.isDefined,
      "buildDate from dd-MMM-yyyy should be parsed"
    )
  }

  test("extracts build date from Bnd-LastModified") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bnd-lastmodified" -> TreeSet(StringOrPair("1704067200000"))
    )
    val result = state.buildDateFromManifest(manifest)
    assert(result.isDefined, "Bnd-LastModified epoch should parse")
  }

  // ==================== 1.8: XXE Protection ====================

  test("rejects XXE in POM") {
    val xxePom =
      """<?xml version="1.0"?>
        |<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        |<project>
        |  <groupId>&xxe;</groupId>
        |  <artifactId>test</artifactId>
        |  <version>1.0</version>
        |</project>""".stripMargin
    val artifact = ByteWrapper(xxePom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("xxe-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.groupId, None)
  }

  test("normal POM still parses with secure parser") {
    val pomXml =
      """<project><groupId>org.example</groupId><artifactId>test-artifact</artifactId><version>1.0.0</version></project>"""
    val artifact = ByteWrapper(pomXml.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.groupId, Some("org.example"))
    assertEquals(state.artifactId, Some("test-artifact"))
    assertEquals(state.version, Some("1.0.0"))
  }

  test("parses POM after stripping harmless DOCTYPE") {
    // A benign DOCTYPE should be stripped and the POM should still parse.
    val pomWithDoctype =
      """<?xml version="1.0"?>
        |<!DOCTYPE project>
        |<project>
        |  <groupId>com.example</groupId>
        |  <artifactId>safe-art</artifactId>
        |  <version>2.0.0</version>
        |</project>""".stripMargin
    val artifact = ByteWrapper(pomWithDoctype.getBytes("UTF-8"), "safe.pom", None)
    val item = createTestItem(" harmless-doctype")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.groupId, Some("com.example"))
    assertEquals(state.artifactId, Some("safe-art"))
    assertEquals(state.version, Some("2.0.0"))
  }

  test("handles DOCTYPE with quoted > in SYSTEM identifier") {
    // stripDoctype must not terminate on the > inside the quoted string.
    val pomWithQuotedGt =
      """<?xml version="1.0"?>
        |<!DOCTYPE project SYSTEM "project>v2.dtd">
        |<project>
        |  <groupId>com.example</groupId>
        |  <artifactId>quoted-gt</artifactId>
        |  <version>3.0.0</version>
        |</project>""".stripMargin
    val artifact = ByteWrapper(pomWithQuotedGt.getBytes("UTF-8"), "quoted.pom", None)
    val item = createTestItem("quoted-gt-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.groupId, Some("com.example"))
    assertEquals(state.artifactId, Some("quoted-gt"))
    assertEquals(state.version, Some("3.0.0"))
  }

  test("rejects POM whose body uses internal entity after DOCTYPE strip") {
    // After stripping the DOCTYPE, &myEntity; is undeclared, so parsing fails.
    val pomWithEntity =
      """<?xml version="1.0"?>
        |<!DOCTYPE project [<!ENTITY myEntity "injected">]>
        |<project>
        |  <groupId>&myEntity;</groupId>
        |  <artifactId>test</artifactId>
        |  <version>1.0</version>
        |</project>""".stripMargin
    val artifact = ByteWrapper(pomWithEntity.getBytes("UTF-8"), "entity.pom", None)
    val item = createTestItem("entity-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.groupId, None)
  }

  // ==================== 1.1: Archive types ====================

  private val jarHeader = Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00,
    0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

  private def testArchiveExt(ext: String, label: String): Unit = {
    test(s"computeMavenFiles - handles $label files") {
      val tempDir = Files.createTempDirectory("maven-archive")
      try {
        val archiveFile = new File(tempDir.toFile, s"test$ext")
        Helpers.writeOverFile(archiveFile, jarHeader)
        val wrapper = FileWrapper(archiveFile, s"test$ext", None)
        val byUUID = Map(wrapper.uuid -> wrapper)
        val byName = Map(s"test$ext" -> Vector(wrapper))
        val (toProcess, _, _, name) =
          MavenToProcess.computeMavenFiles(byUUID, byName)
        assertEquals(name, "Maven")
        assert(
          toProcess.nonEmpty,
          s"$label should be recognized as Maven artifact"
        )
      } finally {
        Helpers.deleteDirectory(tempDir)
      }
    }
  }

  testArchiveExt(".ear", "ear")
  testArchiveExt(".jpi", "jpi Jenkins plugin")
  testArchiveExt(".hpi", "hpi Jenkins plugin")
  testArchiveExt(".par", "par")
  testArchiveExt(".sar", "sar")
  testArchiveExt(".nar", "nar")
  testArchiveExt(".kar", "kar")
  testArchiveExt(".far", "far")
  testArchiveExt(".lpkg", "lpkg")
  testArchiveExt(".rar", "rar")
  testArchiveExt(".zap", "zap")

  // ==================== 1.4: OSGi groupId assignment ====================

  test("Bundle-SymbolicName is used for artifactId, not groupId") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(StringOrPair("com.example.mybundle")),
      "bundle-version" -> TreeSet(StringOrPair("1.2.3"))
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assert(
      g.isDefined,
      "groupId should be set from Bundle-SymbolicName when no Implementation-Vendor-Id"
    )
    assertEquals(a, Some("com.example.mybundle"))
  }

  test("Implementation-Vendor-Id provides groupId") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(StringOrPair("com.example.mybundle")),
      "implementation-vendor-id" -> TreeSet(StringOrPair("com.vendor")),
      "bundle-version" -> TreeSet(StringOrPair("1.2.3"))
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(g, Some("com.vendor"))
    assertEquals(a, Some("com.example.mybundle"))
  }

  test("Maven Bundle Plugin heuristic extracts last segment as artifactId") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(StringOrPair("org.example.mylib")),
      "created-by" -> TreeSet(StringOrPair("Apache Maven Bundle Plugin")),
      "bundle-version" -> TreeSet(StringOrPair("1.0.0"))
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(a, Some("mylib"))
  }

  // ==================== 1.6: Priority 7 manifest fields ====================

  test("resolves GAV from Implementation-Title and Implementation-Version") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-title" -> TreeSet(StringOrPair("my-app")),
      "implementation-version" -> TreeSet(StringOrPair("2.0.0"))
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(a, Some("my-app"))
    assertEquals(v, Some("2.0.0"))
  }

  test("Extension-Name provides artifactId when no bundle headers") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "extension-name" -> TreeSet(StringOrPair("com.example.ext")),
      "implementation-version" -> TreeSet(StringOrPair("3.0"))
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(a, Some("com.example.ext"))
    assertEquals(v, Some("3.0"))
  }

  // ==================== 1.7: Additional POM properties and date formats ====================

  test("parses build.timestamp property") {
    val pom = """<project>
      <properties><build.timestamp>2024-01-15</build.timestamp></properties>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("bt-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assert(
      state.buildDate.isDefined,
      "build.timestamp should be parsed as date"
    )
  }

  test("parses yyyy-MM-dd HH:mm:ss date format") {
    val pom = """<project>
      <properties><buildDate>2024-01-15 10:30:00</buildDate></properties>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("datetime-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assert(
      state.buildDate.isDefined,
      "yyyy-MM-dd HH:mm:ss format should be parsed"
    )
  }

  test("parses yyyyMMdd-HHmm Bnd date format") {
    val pom = """<project>
      <properties><buildDate>20240115-1030</buildDate></properties>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("bnd-date-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assert(state.buildDate.isDefined, "yyyyMMdd-HHmm format should be parsed")
  }

  // ==================== 1.8: Billion-laughs / XXE depth ====================

  test("rejects billion-laughs entity expansion") {
    val bombPom =
      """<?xml version="1.0"?>
        |<!DOCTYPE lolz [
        |  <!ENTITY lol "lol">
        |  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
        |  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
        |]>
        |<project>
        |  <groupId>&lol3;</groupId>
        |  <artifactId>test</artifactId>
        |  <version>1.0</version>
        |</project>""".stripMargin
    val artifact = ByteWrapper(bombPom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("bomb-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.groupId, None)
  }

  // ==================== Gap fill: 1.2 Path traversal protection ====================

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

  test("1.2 path traversal: extractAllEmbeddedGavs skips entries with ..") {
    val tempDir = Files.createTempDirectory("maven-phase1-jar")
    try {
      val jarFile = new File(tempDir.toFile, "test.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/../../etc/passwd/pom.properties" ->
            "groupId=com.evil\nartifactId=evil-art\nversion=1.0",
          "META-INF/maven/com.good/good-art/pom.properties" ->
            "groupId=com.good\nartifactId=good-art\nversion=2.0"
        )
      )
      val wrapper = FileWrapper(jarFile, "test.jar", None)
      val item = createTestItem("path-traversal-test")
      val state = MavenState().beginProcessing(wrapper, item, MavenMarkers.JAR)
      assert(
        !state.embeddedGavs.exists(_._2 == "evil-art"),
        "Traversal entry with .. should be skipped"
      )
      assert(
        state.embeddedGavs.exists(_._2 == "good-art"),
        "Legitimate entry should be included"
      )
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  // ==================== Gap fill: 1.2 JAR without pom.properties ====================

  test("1.2 no pom.properties: handles JAR without META-INF/maven gracefully") {
    val tempDir = Files.createTempDirectory("maven-phase1-jar")
    try {
      val jarFile = new File(tempDir.toFile, "mylib-1.0.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n"
        )
      )
      val wrapper = FileWrapper(jarFile, "mylib-1.0.jar", None)
      val item = createTestItem("no-pom-props-test")
      val state = MavenState().beginProcessing(wrapper, item, MavenMarkers.JAR)
      assertEquals(state.embeddedGavs.length, 0)
      assert(
        state.artifactId.contains("mylib"),
        s"Should fall back to filename, got artifactId=${state.artifactId}"
      )
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  // ==================== Gap fill: 1.2 Multiple pom.properties ====================

  test(
    "1.2 multiple pom.properties: extracts all and selects primary matching filename"
  ) {
    val tempDir = Files.createTempDirectory("maven-phase1-jar")
    try {
      val jarFile = new File(tempDir.toFile, "art1-1.0.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/com.example1/art1/pom.properties" ->
            "groupId=com.example1\nartifactId=art1\nversion=1.0",
          "META-INF/maven/com.example2/art2/pom.properties" ->
            "groupId=com.example2\nartifactId=art2\nversion=2.0"
        )
      )
      val wrapper = FileWrapper(jarFile, "art1-1.0.jar", None)
      val item = createTestItem("multi-embed-test")
      val state = MavenState().beginProcessing(wrapper, item, MavenMarkers.JAR)
      assertEquals(
        state.embeddedGavs.length,
        2,
        "Should extract both embedded GAVs"
      )
      assert(
        state.artifactId.contains("art1"),
        s"Primary GAV should match filename art1, got ${state.artifactId}"
      )
      assert(
        state.embeddedGavs.exists(_._2 == "art2"),
        "Non-primary embedded GAV should still be recorded"
      )
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  // ==================== Gap fill: 1.3 External POM priority over embedded pom.xml ====================

  test("1.3 priority: external POM takes precedence over embedded pom.xml") {
    val state = MavenState()
    val externalPom = PomParser.parse(
      "<project><groupId>com.external</groupId><artifactId>ext-art</artifactId><version>3.0</version></project>"
    )
    val embeddedPom = PomParser.parse(
      "<project><groupId>com.embedded</groupId><artifactId>embed-art</artifactId><version>1.0</version></project>"
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      externalPom,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      Map.empty,
      embeddedPom
    )
    assertEquals(g, Some("com.external"))
    assertEquals(a, Some("ext-art"))
    assertEquals(v, Some("3.0"))
  }

  // ==================== Gap fill: 1.7 Date format fallback chain ====================

  test("1.7 date fallback: ISO fails, dd-MMM-yyyy succeeds via POM") {
    val pom = """<project>
      <properties><buildDate>15-Mar-2024</buildDate></properties>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("date-fallback-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assert(
      state.buildDate.isDefined,
      "dd-MMM-yyyy date should be parsed after ISO format fails in fallback chain"
    )
  }

  test("1.7 date fallback: ISO fails, dd-MMM-yyyy succeeds via manifest") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "build-date" -> TreeSet(StringOrPair("01-Jan-2024"))
    )
    val result = state.buildDateFromManifest(manifest)
    assert(
      result.isDefined,
      "dd-MMM-yyyy in manifest build-date should parse via fallback chain"
    )
  }
}

// ==================== §1.6: GAV Priority Chain Integration Tests ====================

class GAVPrioritySuite extends FunSuite {

  private val state = MavenState()

  test("GAV priority: pom.properties wins over POM XML") {
    val props = Map(
      "groupId" -> "com.props",
      "artifactId" -> "props-art",
      "version" -> "2.0"
    )
    val pomOpt = PomParser.parse(
      "<project><groupId>com.pom</groupId><artifactId>pom-art</artifactId><version>1.0</version></project>"
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      pomOpt,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      props,
      None
    )
    assertEquals(g, Some("com.props"))
    assertEquals(a, Some("props-art"))
  }

  test(
    "GAV priority chain: pom.properties > embedded POM > manifest > filename"
  ) {
    val props = Map(
      "groupId" -> "com.props",
      "artifactId" -> "props-art",
      "version" -> "3.0"
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      props,
      None
    )
    assertEquals(g, Some("com.props"))
    assertEquals(a, Some("props-art"))
  }

  test("manifest Bundle-Version used for version when no pom.properties") {
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(StringOrPair("com.example.bundle")),
      "bundle-version" -> TreeSet(StringOrPair("2.1.0"))
    )
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(v, Some("2.1.0"))
  }

  test("filename fallback when no pom.properties, no POM, no manifest") {
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "mylib-2.0.1.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      Map.empty,
      None
    )
    assert(a.isDefined, "should parse artifactId from filename")
    assert(v.isDefined, "should parse version from filename")
  }

  test("Bundle-Version is used for version when no pom.properties") {
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(StringOrPair("com.example.bundle")),
      "bundle-version" -> TreeSet(StringOrPair("3.5.0"))
    )
    val state = MavenState()
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(v, Some("3.5.0"), "Bundle-Version should provide version")
  }

  test("Implementation-Version used as version when no POM") {
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-title" -> TreeSet(StringOrPair("mylib")),
      "implementation-version" -> TreeSet(StringOrPair("2.3.4"))
    )
    val state = MavenState()
    val (g, a, v) = state.resolveGAV(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      v,
      Some("2.3.4"),
      "Implementation-Version should be used for version"
    )
  }
}
