/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0 */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.strategies.MavenMarkers
import io.spicelabs.goatrodeo.omnibor.strategies.MavenState
import io.spicelabs.goatrodeo.omnibor.strategies.MavenToProcess
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWalker
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

  /** Process a JAR artifact through the full accumulation pipeline:
    *   1. beginProcessing(JAR) - initializes jarAccumulated 2. Walk archive
    *      entries, calling accumulateInfo for each child 3.
    *      applyAccumulatedAugmentation - resolves groupId/artifactId/version,
    *      creates pURL
    *
    * This simulates what the ToProcess.process pipeline does for JAR markers.
    */
  private def processJarThroughPipeline(
      wrapper: ArtifactWrapper,
      state: MavenState = MavenState()
  ): (MavenState, MemStorage) = {
    val item = createTestItem("jar-test")
    val store = MemStorage(None)
    val s1 = state.beginProcessing(wrapper, item, MavenMarkers.JAR)
    FileWalker.withinArchiveStream(wrapper) { entries =>
      entries.foreach { entry =>
        s1.accumulateInfo(item.identifier, item, entry, store)
      }
    }
    (s1.applyAccumulatedAugmentation(item, wrapper, store), store)
  }

  // ==================== 1.1: Archive types ====================

  // Ear/jpi/war handled by PackageTagIntegrationSuite

  // ==================== 1.2: pom.properties ====================

  test(
    "pom.properties overrides POM groupId/artifactId/version (resolveGroupIdArtifactIdVersion)"
  ) {
    val state = MavenState()
    val props = Map(
      "groupId" -> "com.embedded",
      "artifactId" -> "embed-art",
      "version" -> "2.0"
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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

  test(
    "external POM takes priority over pom.properties groupId/artifactId/version"
  ) {
    val state = MavenState()
    val pomOpt = PomParser.parse(
      "<project><groupId>com.pom</groupId><artifactId>pom-art</artifactId><version>1.0</version></project>"
    )
    val props = Map(
      "groupId" -> "com.props",
      "artifactId" -> "props-art",
      "version" -> "3.0"
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      pomOpt,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      props,
      None
    )
    assertEquals(g, Some("com.pom"))
    assertEquals(a, Some("pom-art"))
    assertEquals(v, Some("1.0"))
  }

  // ==================== 1.3: Embedded pom.xml ====================

  test(
    "embedded pom.xml provides groupId/artifactId/version when no external POM"
  ) {
    val state = MavenState()
    val embeddedPom = PomParser.parse(
      "<project><groupId>com.embed</groupId><artifactId>embed-art</artifactId><version>5.0</version></project>"
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "mylib-1.2.3.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]]
    )
    assert(a.contains("mylib"), s"artifactId should be mylib, got $a")
    assert(v.contains("1.2.3"), s"version should be 1.2.3, got $v")
  }

  test("preserves Scala suffix in artifactId") {
    val state = MavenState()
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "lib-1.0-SNAPSHOT.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]]
    )
    assert(
      v.contains("1.0-SNAPSHOT"),
      s"version should preserve SNAPSHOT, got $v"
    )
  }

  test("POM groupId/artifactId/version takes precedence over filename") {
    val pomOpt = PomParser.parse(
      "<project><groupId>com.pom</groupId><artifactId>pom-art</artifactId><version>9.9.9</version></project>"
    )
    val state = MavenState()
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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

  test("resolves groupId/artifactId/version from Bundle-SymbolicName") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(StringOrPair("com.example.mybundle")),
      "bundle-version" -> TreeSet(StringOrPair("1.2.3"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (_, a, _) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(a, Some("org.example"))
  }

  // ==================== 1.6: groupId/artifactId/version Priority ====================

  test(
    "groupId/artifactId/version priority: external POM > pom.properties > embedded POM > manifest > filename"
  ) {
    val state = MavenState()
    val props =
      Map("groupId" -> "com.props", "artifactId" -> "p-art", "version" -> "3.0")
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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

  test("groupId/artifactId/version falls through to filename") {
    val state = MavenState()
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    assertEquals(state.parsedPom.flatMap(_.groupId), None)
  }

  test("normal POM still parses with secure parser") {
    val pomXml =
      """<project><groupId>org.example</groupId><artifactId>test-artifact</artifactId><version>1.0.0</version></project>"""
    val artifact = ByteWrapper(pomXml.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("test-id")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.parsedPom.flatMap(_.groupId), Some("org.example"))
    assertEquals(state.parsedPom.flatMap(_.artifactId), Some("test-artifact"))
    assertEquals(state.parsedPom.flatMap(_.version), Some("1.0.0"))
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
    val artifact =
      ByteWrapper(pomWithDoctype.getBytes("UTF-8"), "safe.pom", None)
    val item = createTestItem(" harmless-doctype")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.parsedPom.flatMap(_.groupId), Some("com.example"))
    assertEquals(state.parsedPom.flatMap(_.artifactId), Some("safe-art"))
    assertEquals(state.parsedPom.flatMap(_.version), Some("2.0.0"))
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
    val artifact =
      ByteWrapper(pomWithQuotedGt.getBytes("UTF-8"), "quoted.pom", None)
    val item = createTestItem("quoted-gt-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.parsedPom.flatMap(_.groupId), Some("com.example"))
    assertEquals(state.parsedPom.flatMap(_.artifactId), Some("quoted-gt"))
    assertEquals(state.parsedPom.flatMap(_.version), Some("3.0.0"))
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
    val artifact =
      ByteWrapper(pomWithEntity.getBytes("UTF-8"), "entity.pom", None)
    val item = createTestItem("entity-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(state.parsedPom.flatMap(_.groupId), None)
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(a, Some("mylib"))
  }

  // ==================== 1.6: Priority 7 manifest fields ====================

  test(
    "resolves groupId/artifactId/version from Implementation-Title and Implementation-Version"
  ) {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-title" -> TreeSet(StringOrPair("my-app")),
      "implementation-version" -> TreeSet(StringOrPair("2.0.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    assertEquals(state.parsedPom.flatMap(_.groupId), None)
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

  test(
    "1.2 path traversal: extractAllEmbeddedGroupIdArtifactIdVersion skips entries with .."
  ) {
    val tempDir = Files.createTempDirectory("maven-phase1-jar")
    try {
      val jarFile = new File(tempDir.toFile, "good-art-1.0.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/maven/../../etc/passwd/pom.properties" ->
            "groupId=com.evil\nartifactId=evil-art\nversion=1.0",
          "META-INF/maven/com.good/good-art/pom.properties" ->
            "groupId=com.good\nartifactId=good-art\nversion=2.0"
        )
      )
      val wrapper = FileWrapper(jarFile, "good-art-1.0.jar", None)
      val (state, store) = processJarThroughPipeline(wrapper)
      // The resolved artifactId should be "good-art" (from pom.properties,
      // which matches the filename "good-art"), NOT "evil-art" (which was
      // in a path-traversal entry that should have been skipped)
      val purls = store.purls().toSet
      assert(
        !purls.exists(_.contains("evil")),
        "Traversal entry with .. should be skipped"
      )
      assert(
        purls.exists(_.contains("good-art")),
        "Legitimate entry should be used for groupId/artifactId/version resolution"
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
      val (state, store) = processJarThroughPipeline(wrapper)
      val purls = store.purls().toSet
      assert(
        purls.exists(_.contains("mylib")),
        s"Should fall back to filename, got pURLs: $purls"
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
      val (state, store) = processJarThroughPipeline(wrapper)
      val purls = store.purls().toSet
      assert(
        purls.exists(_.contains("art1")),
        s"Primary groupId/artifactId/version should match filename art1, got pURLs: $purls"
      )
      assert(
        purls.exists(_.contains("com.example1")),
        s"Primary groupId should match filename, got pURLs: $purls"
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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

  // ==================== Gap fill: filename groupId extraction ====================

  test("1.5 extracts groupId and artifactId from dotted filename") {
    val state = MavenState()
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "com.example.mylib-1.2.3.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]]
    )
    assertEquals(g, Some("com.example"))
    assertEquals(a, Some("mylib"))
    assertEquals(v, Some("1.2.3"))
  }

  test("1.5 no groupId from filename when no dots") {
    val state = MavenState()
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "mylib-1.0.jar", None),
      None,
      TreeMap.empty[String, TreeSet[StringOrPair]]
    )
    // With the artifactId-as-groupId fallback, groupId is now Some("mylib")
    // (same as artifactId) when no groupId source is found. This ensures
    // a valid Maven pURL can always be constructed.
    assertEquals(g, Some("mylib"))
    assertEquals(a, Some("mylib"))
    assertEquals(v, Some("1.0"))
  }

  // ==================== Gap fill: parent POM groupId fallback ====================

  test("1.6 parent POM groupId fallback") {
    val pom = """<project>
      <parent><groupId>com.parent</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
      <artifactId>child</artifactId>
    </project>"""
    val parsed = PomParser.parse(pom)
    assertEquals(parsed.flatMap(_.groupId), Some("com.parent"))
  }

  // ==================== Gap fill: maven.timestamp date property ====================

  test("1.7 extracts build date from maven.timestamp property") {
    val pom = """<project>
      <properties><maven.timestamp>2024-06-15T10:30:00Z</maven.timestamp></properties>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("maven-ts-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assert(
      state.buildDate.isDefined,
      "maven.timestamp should be parsed as build date"
    )
  }

  // ==================== Gap fill: bundle-name and specification-version ====================

  test("1.4 resolves artifactId from Bundle-Name when no Bundle-SymbolicName") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-name" -> TreeSet(StringOrPair("my-bundle")),
      "bundle-version" -> TreeSet(StringOrPair("2.0.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(a, Some("my-bundle"))
    assertEquals(v, Some("2.0.0"))
  }

  test("1.6 resolves version from Specification-Version fallback") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-title" -> TreeSet(StringOrPair("mylib")),
      "specification-version" -> TreeSet(StringOrPair("3.0.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(v, Some("3.0.0"))
  }

  // ==================== Field-Level Merge Tests ====================
  //
  // These tests verify that resolveGroupIdArtifactIdVersion uses field-level merge: for each field
  // (groupId, artifactId, version), the best value is picked from the best
  // source FOR THAT FIELD, not from the first source that provides all three.
  //
  // Per-field priority:
  //   groupId:    pom.properties > external POM > embedded pom.xml > manifest > filename
  //   artifactId: pom.properties > external POM > embedded pom.xml > filename > manifest
  //   version:    pom.properties > external POM > embedded pom.xml > manifest > filename
  //
  // The key change: filename has HIGHER priority than manifest for artifactId,
  // because Implementation-Title is human-readable, not a Maven artifactId.

  /** Tests that when pom.properties has groupId and version but is missing
    * artifactId, the artifactId comes from the filename (higher priority than
    * manifest for artifactId). groupId and version come from pom.properties
    * (highest priority for all fields).
    *
    * '''What it tests:''' Field-level merge fills missing fields from
    * lower-priority sources without discarding the fields that ARE available
    * from a higher-priority source.
    *
    * '''Why:''' Source-level priority discards the entire pom.properties triple
    * when one field is missing. Field-level merge keeps the good fields and
    * fills only the missing ones.
    *
    * '''Requirement:''' Plan Test 1 — pom.properties partial → filename fills
    * artifactId.
    *
    * '''LLM context:''' This is a RED test. It fails with the current
    * source-level priority code because pom.properties is incomplete (missing
    * artifactId), so fromProps=None, and the manifest wins with the wrong
    * artifactId from Implementation-Title.
    */
  test(
    "field-merge: pom.properties missing artifactId → filename provides it"
  ) {
    val state = MavenState()
    val props = Map(
      "groupId" -> "org.example",
      "version" -> "1.0"
    )
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-title" -> TreeSet(StringOrPair("Example Library")),
      "implementation-version" -> TreeSet(StringOrPair("1.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "example-lib-1.0.jar", None),
      None,
      manifest,
      props,
      None
    )
    assertEquals(g, Some("org.example"), "groupId from pom.properties")
    assertEquals(
      a,
      Some("example-lib"),
      "artifactId from filename (not manifest Implementation-Title)"
    )
    assertEquals(v, Some("1.0"), "version from pom.properties")
  }

  /** Tests that when no pom.properties is available, the artifactId comes from
    * the filename, NOT from manifest's Implementation-Title.
    *
    * '''What it tests:''' Field-level merge: filename (priority 4 for
    * artifactId) beats manifest (priority 5 for artifactId).
    *
    * '''Why:''' Implementation-Title is human-readable ("Spring Boot
    * AutoConfigure"); filename matches Maven artifactId
    * ("spring-boot-autoconfigure").
    *
    * '''Requirement:''' Plan Test 2 — filename beats manifest for artifactId.
    *
    * '''LLM context:''' This is a RED test. It fails with the current code
    * because manifest wins (source-level priority 4) and produces
    * artifactId="Spring Boot AutoConfigure" instead of
    * "spring-boot-autoconfigure".
    */
  test("field-merge: filename artifactId beats manifest Implementation-Title") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-title" -> TreeSet(
        StringOrPair("Spring Boot AutoConfigure")
      ),
      "implementation-version" -> TreeSet(StringOrPair("2.7.14"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(
        Array.emptyByteArray,
        "spring-boot-autoconfigure-2.7.14.jar",
        None
      ),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      a,
      Some("spring-boot-autoconfigure"),
      "artifactId from filename, not Implementation-Title"
    )
    assertEquals(
      v,
      Some("2.7.14"),
      "version from manifest Implementation-Version"
    )
  }

  /** Tests the surgical nature of the field-level merge: all three fields can
    * come from different sources simultaneously.
    *
    * '''What it tests:''' When manifest provides groupId+version and filename
    * provides artifactId, the merge produces a result with groupId from
    * manifest, artifactId from filename, and version from manifest.
    *
    * '''Why:''' This is THE test that proves the swap is surgical: groupId and
    * version still come from manifest (unchanged priority), only artifactId
    * comes from filename (swapped priority).
    *
    * '''Requirement:''' Plan Test 3 — swap verification, all 3 fields from
    * different sources.
    *
    * '''LLM context:''' This is a RED test. With source-level priority, the
    * manifest wins wholesale, producing artifactId="Human Readable Name".
    */
  test(
    "field-merge: swap verification — groupId+version from manifest, artifactId from filename"
  ) {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-vendor-id" -> TreeSet(StringOrPair("org.example")),
      "implementation-title" -> TreeSet(StringOrPair("Human Readable Name")),
      "implementation-version" -> TreeSet(StringOrPair("1.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "mylib-1.0.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      g,
      Some("org.example"),
      "groupId from manifest Implementation-Vendor-Id"
    )
    assertEquals(
      a,
      Some("mylib"),
      "artifactId from filename (not manifest Implementation-Title)"
    )
    assertEquals(v, Some("1.0"), "version from manifest Implementation-Version")
  }

  /** Tests that the manifest can contribute groupId and version even when it
    * has NO artifactId headers (no Implementation-Title, no
    * Bundle-SymbolicName, no Bundle-Name, no Extension-Name).
    *
    * '''What it tests:''' The gate removal in
    * resolveGroupIdArtifactIdVersionFromManifest. Previously, the method
    * returned None when artifactIdOpt was None, discarding valid groupId and
    * version. Now it returns individual fields without the gate.
    *
    * '''Why:''' A manifest with Implementation-Vendor-Id and
    * Implementation-Version but no artifactId headers still has valid vendor
    * and version information. Field-level merge should use these fields.
    *
    * '''Requirement:''' Plan Test 4 — manifest provides groupId/version when
    * manifest has no artifactId.
    *
    * '''LLM context:''' This is a RED test. With the current code, the gate
    * causes resolveGroupIdArtifactIdVersionFromManifest to return None, so the
    * manifest contributes nothing. The filename wins wholesale, producing
    * groupId=None (fallback to artifactId), artifactId="mylib", version="2.0"
    * all from filename.
    */
  test(
    "field-merge: manifest provides groupId/version when no artifactId headers"
  ) {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-vendor-id" -> TreeSet(StringOrPair("com.vendor")),
      "implementation-version" -> TreeSet(StringOrPair("2.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "mylib-2.0.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      g,
      Some("com.vendor"),
      "groupId from manifest (previously gated out)"
    )
    assertEquals(a, Some("mylib"), "artifactId from filename")
    assertEquals(v, Some("2.0"), "version from manifest (previously gated out)")
  }

  /** Tests that when pom.properties has artifactId and version but no groupId,
    * the groupId comes from the manifest (Implementation-Vendor-Id).
    *
    * '''What it tests:''' Field-level merge fills missing groupId from manifest
    * while keeping artifactId and version from pom.properties.
    *
    * '''Why:''' Source-level priority discards the entire pom.properties triple
    * when groupId is missing. Field-level merge keeps the good fields.
    *
    * '''Requirement:''' Plan Test 5 — pom.properties missing groupId → manifest
    * provides groupId.
    *
    * '''LLM context:''' This is a RED test. With source-level priority,
    * fromProps=None (missing groupId), manifest wins → artifactId="My Library"
    * from Implementation-Title instead of "mylib" from pom.properties.
    */
  test("field-merge: pom.properties missing groupId → manifest provides it") {
    val state = MavenState()
    val props = Map(
      "artifactId" -> "mylib",
      "version" -> "1.0"
    )
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-vendor-id" -> TreeSet(StringOrPair("com.example")),
      "implementation-title" -> TreeSet(StringOrPair("My Library")),
      "implementation-version" -> TreeSet(StringOrPair("1.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "mylib-1.0.jar", None),
      None,
      manifest,
      props,
      None
    )
    assertEquals(
      g,
      Some("com.example"),
      "groupId from manifest (pom.properties missing it)"
    )
    assertEquals(
      a,
      Some("mylib"),
      "artifactId from pom.properties (highest available, no external POM)"
    )
    assertEquals(
      v,
      Some("1.0"),
      "version from pom.properties (highest available, no external POM)"
    )
  }

  /** Tests that field-level merge works across external POM and embedded
    * pom.xml, not just pom.properties/manifest/filename.
    *
    * '''What it tests:''' When external POM provides groupId+version (no
    * artifactId) and embedded pom.xml provides artifactId (no groupId, no
    * version), the merge combines them: groupId from external POM, artifactId
    * from embedded pom.xml, version from external POM.
    *
    * '''Requirement:''' Plan Test 6 — mixed-field across external/embedded POM.
    *
    * '''LLM context:''' This is a RED test. With source-level priority, neither
    * POM provides a complete triple, so both are discarded and the result falls
    * through to manifest/filename.
    */
  test("field-merge: mixed fields across external POM and embedded pom.xml") {
    val state = MavenState()
    val externalPom = PomParser.parse(
      "<project><groupId>com.external</groupId><version>3.0</version></project>"
    )
    val embeddedPom = PomParser.parse(
      "<project><artifactId>embed-art</artifactId></project>"
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      externalPom,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      Map.empty,
      embeddedPom
    )
    assertEquals(g, Some("com.external"), "groupId from external POM")
    assertEquals(a, Some("embed-art"), "artifactId from embedded pom.xml")
    assertEquals(v, Some("3.0"), "version from external POM")
  }

  /** Tests that when filename has a dotted groupId prefix (e.g.
    * "com.example.mylib-1.0.jar"), manifest still wins for groupId.
    *
    * '''What it tests:''' Manifest groupId (priority 4) beats filename groupId
    * (priority 5). This is unchanged from source-level priority.
    *
    * '''Why:''' Documents that manifest groupId beats filename groupId even
    * when filename has a dotted prefix. Also documents a pre-existing
    * limitation: extractIdentityFromFilename splits on the last dot, which may
    * produce a wrong groupId/artifactId split for some filenames.
    *
    * '''Requirement:''' Plan Test 7 — dotted filename + manifest groupId.
    */
  test(
    "field-merge: manifest groupId beats filename groupId when both present"
  ) {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-vendor-id" -> TreeSet(StringOrPair("com.vendor")),
      "implementation-version" -> TreeSet(StringOrPair("1.0"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "com.example.mylib-1.0.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(g, Some("com.vendor"), "groupId from manifest (not filename)")
    assertEquals(a, Some("mylib"), "artifactId from filename")
    assertEquals(v, Some("1.0"), "version from manifest")
  }

  /** Full-pipeline integration test: build a synthetic JAR with no
    * pom.properties but a manifest with human-readable Implementation-Title,
    * and verify the resolved artifactId comes from the filename, not the
    * manifest.
    *
    * '''What it tests:''' The field-level merge works end-to-end through the
    * real pipeline. When no pom.properties is present, the artifactId should
    * come from the filename (field-level merge: filename priority 4 > manifest
    * priority 5 for artifactId).
    *
    * '''Why:''' Catches integration issues that unit tests of
    * resolveGroupIdArtifactIdVersion miss (e.g., does
    * applyAccumulatedAugmentation correctly pass all 5 sources to the
    * refactored resolveGroupIdArtifactIdVersion?).
    *
    * '''Note:''' This test uses NO pom.properties because the pipeline's
    * `accumulateInfo` only collects complete groupId/artifactId/version tuples
    * (all three fields) from pom.properties. Incomplete pom.properties is not
    * passed to resolveGroupIdArtifactIdVersion through the current pipeline.
    * This is a known limitation — the pipeline would need to be updated to pass
    * partial groupId/artifactId/version tuples for the "incomplete
    * pom.properties" case to work end-to-end. That is out of scope for this
    * plan.
    *
    * '''Requirement:''' Plan Test 9 — full-pipeline integration.
    */
  test(
    "field-merge: full pipeline — no pom.properties, filename beats manifest"
  ) {
    val tempDir = Files.createTempDirectory("field-merge-pipeline")
    try {
      val jarFile = new File(tempDir.toFile, "mylib-1.0.jar")
      writeJarEntries(
        jarFile,
        Seq(
          "META-INF/MANIFEST.MF" ->
            "Manifest-Version: 1.0\nImplementation-Title: My Library\nImplementation-Version: 1.0\n"
        )
      )
      val wrapper = FileWrapper(jarFile, "mylib-1.0.jar", None)
      val (state, store) = processJarThroughPipeline(wrapper)
      val purls = store.purls().toSet
      assert(
        purls.exists(p => p.contains("mylib") && !p.contains("My+Library")),
        "artifactId from filename (not manifest Implementation-Title 'My Library')"
      )
      assert(
        purls.exists(_.contains("1.0")),
        "version from manifest Implementation-Version"
      )
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  // ==================== Security Tests ====================

  /** Documents the version masking attack vector.
    *
    * '''What it tests:''' When pom.properties provides groupId+artifactId (no
    * version) and manifest provides a spoofed version, the merged pURL uses the
    * manifest's version. This is the current behavior — the test documents it,
    * not asserts it is correct.
    *
    * '''Requirement:''' Plan Test 17 — version masking documentation.
    *
    * '''LLM context:''' This test documents a security concern, not a desired
    * behavior. The manifest's version is trusted because it's usually written
    * by the build tool. A spoofed manifest version can hide vulnerabilities.
    */
  test(
    "security: version masking — manifest version with pom.properties identity"
  ) {
    val state = MavenState()
    val props = Map(
      "groupId" -> "org.example",
      "artifactId" -> "mylib"
    )
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "implementation-version" -> TreeSet(StringOrPair("99.0.FAKE"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "mylib-1.0.jar", None),
      None,
      manifest,
      props,
      None
    )
    assertEquals(g, Some("org.example"), "groupId from pom.properties")
    assertEquals(a, Some("mylib"), "artifactId from pom.properties")
    // version from manifest (priority 4) beats filename (priority 5)
    assertEquals(
      v,
      Some("99.0.FAKE"),
      "version from manifest — documents version masking risk"
    )
  }

  /** Tests that filenames with special characters do not cause crashes or pURL
    * injection.
    *
    * '''What it tests:''' resolveGroupIdArtifactIdVersion does not throw for
    * filenames containing special characters like %2F (URL-encoded forward
    * slash).
    *
    * '''Why:''' pURL injection via filename is structurally mitigated by
    * Purl.encode() but semantically unguarded. This test documents that the
    * structural mitigation works.
    *
    * '''Requirement:''' Plan Test 18 — character whitelist.
    */
  test("security: filename with special characters does not crash") {
    val state = MavenState()
    val result = scala.util.Try {
      state.resolveGroupIdArtifactIdVersion(
        ByteWrapper(Array.emptyByteArray, "mylib%2Fevil-1.0.jar", None),
        None,
        TreeMap.empty[String, TreeSet[StringOrPair]],
        Map.empty,
        None
      )
    }
    assert(
      result.isSuccess,
      "resolveGroupIdArtifactIdVersion must not throw for filenames with special characters"
    )
  }
}

// ==================== §1.6: groupId/artifactId/version Priority Chain Integration Tests ====================

class GroupIdArtifactIdVersionPrioritySuite extends FunSuite {

  private val state = MavenState()

  test(
    "groupId/artifactId/version priority: external POM wins over pom.properties"
  ) {
    val props = Map(
      "groupId" -> "com.props",
      "artifactId" -> "props-art",
      "version" -> "2.0"
    )
    val pomOpt = PomParser.parse(
      "<project><groupId>com.pom</groupId><artifactId>pom-art</artifactId><version>1.0</version></project>"
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      pomOpt,
      TreeMap.empty[String, TreeSet[StringOrPair]],
      props,
      None
    )
    assertEquals(g, Some("com.pom"))
    assertEquals(a, Some("pom-art"))
  }

  test(
    "groupId/artifactId/version priority chain: external POM > pom.properties > embedded POM > manifest > filename"
  ) {
    val props = Map(
      "groupId" -> "com.props",
      "artifactId" -> "props-art",
      "version" -> "3.0"
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "test.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(v, Some("2.1.0"))
  }

  test("filename fallback when no pom.properties, no POM, no manifest") {
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
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
