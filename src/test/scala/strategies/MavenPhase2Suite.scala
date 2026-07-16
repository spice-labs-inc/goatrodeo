/* Phase 2 Tests: PomParser Integration & Extended POM Metadata
   Maven Phase 2 tests §2.1–2.5

   Phase 2 integrates PomParser into MavenState so that:
   - POM processing uses PomParser for property-interpolated groupId/artifactId/version extraction
   - Extended POM metadata (name, description, url, organization, scm) is
     stored in MavenState and emitted via getMetadata
   - The existing resolveGroupIdArtifactIdVersion (used by JAR marker) continues to work
 */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Edge
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.PomParser
import munit.FunSuite

import scala.collection.immutable.TreeSet

class MavenPhase2Suite extends FunSuite {

  private def createTestItem(id: String): Item = Item(
    id,
    TreeSet.empty[Edge],
    None,
    None
  )

  // ==================== 2.1: PomParser integration ====================

  test("beginProcessing POM uses PomParser for property-interpolated version") {
    val pom = """<project>
      <groupId>com.example</groupId>
      <artifactId>test-art</artifactId>
      <version>${rev}</version>
      <properties><rev>2.0.0</rev></properties>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("interp-ver")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(
      state.parsedPom.flatMap(_.version),
      Some("2.0.0"),
      "Version should be interpolated via PomParser"
    )
  }

  test("beginProcessing POM resolves custom property in groupId") {
    val pom = """<project>
      <groupId>${base.group}</groupId>
      <artifactId>test-art</artifactId>
      <version>1.0</version>
      <properties><base.group>com.resolved</base.group></properties>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("interp-gid")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(
      state.parsedPom.flatMap(_.groupId),
      Some("com.resolved"),
      "GroupId should be interpolated via PomParser"
    )
  }

  test("beginProcessing POM resolves ${pom.version} legacy property") {
    val pom = """<project>
      <groupId>com.example</groupId>
      <artifactId>test-art</artifactId>
      <version>3.1.0</version>
      <properties><derived>${pom.version}</derived></properties>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("pom-ver-prop")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(
      state.parsedPom.flatMap(_.version),
      Some("3.1.0"),
      "Explicit version should be used; pom.version is available for other fields"
    )
  }

  test(
    "beginProcessing POM handles unresolvable property — PomParser returns None, MavenState falls back"
  ) {
    val pom = """<project>
      <groupId>com.example</groupId>
      <artifactId>test-art</artifactId>
      <version>${unknown.prop}</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "mylib-2.0.pom", None)
    val item = createTestItem("unresolved-prop")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assert(
      state.parsedPom.flatMap(_.version).isEmpty,
      "PomParser should return None for unresolvable property"
    )
    // With the new design, filename fallback happens in getPurls,
    // not beginProcessing. Verify that getPurls resolves the version
    // from the filename.
    val (purlSet, _) = state.getPurls(artifact, item, MavenMarkers.POM)
    val purls = purlSet.canonicalStrings
    assert(
      purls.nonEmpty,
      "getPurls should produce a pURL from filename fallback"
    )
    assert(
      purls.head.contains("2.0"),
      s"pURL should contain version 2.0 from filename, got: $purls"
    )
  }

  test(
    "beginProcessing POM extracts version from parent when missing in child"
  ) {
    val pom = """<project>
      <parent>
        <groupId>com.parent</groupId>
        <artifactId>parent-art</artifactId>
        <version>5.0.0</version>
      </parent>
      <artifactId>child-art</artifactId>
    </project>"""
    val artifact =
      ByteWrapper(pom.getBytes("UTF-8"), "child-art-5.0.0.pom", None)
    val item = createTestItem("parent-ver")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assertEquals(
      state.parsedPom.flatMap(_.version),
      Some("5.0.0"),
      "Version should come from parent when missing in child"
    )
  }

  // ==================== 2.5: Extended POM metadata ====================

  test("PomParser extracts SCM URL") {
    val pom = """<project>
      <scm><url>https://github.com/example/repo</url></scm>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.scmUrl, Some("https://github.com/example/repo"))
  }

  test("PomParser missing SCM produces None") {
    val pom =
      "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>"
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.scmUrl, None)
  }

  test("MavenState getMetadata includes POM name as NAME key") {
    val pom = """<project>
      <name>My Library</name>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("name-meta")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    val (metadata, _) = state.getMetadata(artifact, item, MavenMarkers.POM)
    val nameValues = metadata.get(MetadataKeyConstants.NAME)
    assert(nameValues.isDefined, "NAME key should be present in metadata")
    assert(
      nameValues.get.exists(_.value.contains("My Library")),
      "NAME value should contain 'My Library'"
    )
  }

  test("MavenState getMetadata includes POM description as DESCRIPTION key") {
    val pom = """<project>
      <description>A useful library</description>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("desc-meta")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    val (metadata, _) = state.getMetadata(artifact, item, MavenMarkers.POM)
    val descValues = metadata.get(MetadataKeyConstants.DESCRIPTION)
    assert(
      descValues.isDefined,
      "DESCRIPTION key should be present in metadata"
    )
    assert(
      descValues.get.exists(_.value.contains("A useful library")),
      "DESCRIPTION value should contain the library description"
    )
  }

  test("MavenState getMetadata includes POM URL as URL key") {
    val pom = """<project>
      <url>https://example.com/project</url>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("url-meta")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    val (metadata, _) = state.getMetadata(artifact, item, MavenMarkers.POM)
    val urlValues = metadata.get(MetadataKeyConstants.URL)
    assert(urlValues.isDefined, "URL key should be present in metadata")
    assert(
      urlValues.get.exists(_.value.contains("example.com")),
      "URL value should contain the project URL"
    )
  }

  test("MavenState getMetadata includes organization as PUBLISHER key") {
    val pom = """<project>
      <organization><name>Acme Corp</name></organization>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("pub-meta")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    val (metadata, _) = state.getMetadata(artifact, item, MavenMarkers.POM)
    val pubValues = metadata.get(MetadataKeyConstants.PUBLISHER)
    assert(pubValues.isDefined, "PUBLISHER key should be present in metadata")
    assert(
      pubValues.get.exists(_.value.contains("Acme Corp")),
      "PUBLISHER value should contain organization name"
    )
  }

  test("MavenState getMetadata includes SCM URL as adHoc key") {
    val pom = """<project>
      <scm><url>https://github.com/example/repo</url></scm>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("scm-meta")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    val (metadata, _) = state.getMetadata(artifact, item, MavenMarkers.POM)
    val scmKey = MetadataKeyConstants.adHoc("maven")("SCM_URL")
    val scmValues = metadata.get(scmKey)
    assert(scmValues.isDefined, "maven:SCM_URL key should be present")
    assert(
      scmValues.get.exists(_.value.contains("github.com")),
      "SCM_URL value should contain the SCM URL"
    )
  }

  test("MavenState getMetadata omits keys when POM fields are missing") {
    val pom =
      "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>"
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("no-extra-meta")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    val (metadata, _) = state.getMetadata(artifact, item, MavenMarkers.POM)
    assertEquals(metadata.get(MetadataKeyConstants.NAME), None)
    assertEquals(metadata.get(MetadataKeyConstants.DESCRIPTION), None)
    assertEquals(metadata.get(MetadataKeyConstants.URL), None)
    assertEquals(metadata.get(MetadataKeyConstants.PUBLISHER), None)
    assertEquals(
      metadata.get(MetadataKeyConstants.adHoc("maven")("SCM_URL")),
      None
    )
  }

  // ==================== 2.5: PomParser public resolveProperty ====================

  test("PomParser.resolveProperty resolves known property") {
    val props = Map("spring.version" -> "5.3.0")
    assertEquals(
      PomParser.resolveProperty("spring.version", props),
      Some("5.3.0")
    )
  }

  test("PomParser.resolveProperty returns None for unknown property") {
    assertEquals(PomParser.resolveProperty("unknown", Map.empty), None)
  }

  test("PomParser.resolveProperty resolves chained property") {
    val props = Map("a" -> "1.0", "b" -> "${a}")
    assertEquals(PomParser.resolveProperty("b", props), Some("1.0"))
  }

  test("PomParser.resolveProperty detects circular reference") {
    val props = Map("a" -> "${b}", "b" -> "${a}")
    assertEquals(PomParser.resolveProperty("a", props), None)
  }

  // ==================== Property interpolation in build dates ====================

  test("beginProcessing POM extracts build date from interpolated property") {
    val pom = """<project>
      <properties>
        <buildDate>${maven.timestamp}</buildDate>
        <maven.timestamp>2024-06-15T10:30:00Z</maven.timestamp>
      </properties>
      <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("interp-date")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    assert(
      state.buildDate.isDefined,
      "Build date should be extracted from interpolated property"
    )
  }

  // ==================== Gap: Bundle-License flows to metadata ====================

  test("Bundle-License from JAR manifest appears in metadata") {
    val manifest =
      "Bundle-License: https://www.apache.org/licenses/LICENSE-2.0\n"
    val baos = new java.io.ByteArrayOutputStream
    val zos = new java.util.zip.ZipOutputStream(baos)
    zos.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
    zos.write(manifest.getBytes("UTF-8"))
    zos.closeEntry()
    zos.putNextEntry(
      new java.util.zip.ZipEntry(
        "META-INF/maven/com.example/test/pom.properties"
      )
    )
    zos.write(
      "groupId=com.example\nartifactId=test\nversion=1.0".getBytes("UTF-8")
    )
    zos.closeEntry()
    zos.close()
    val jarBytes = baos.toByteArray
    val artifact = ByteWrapper(jarBytes, "test.jar", None)
    val item = createTestItem("bundle-license-test")
    val store = MemStorage(None)
    val s1 = MavenState().beginProcessing(artifact, item, MavenMarkers.JAR)
    FileWalker.withinArchiveStream(artifact) { entries =>
      entries.foreach { entry =>
        s1.accumulateInfo(item.identifier, item, entry, store)
      }
    }
    val (metadata, _) = s1.getMetadata(artifact, item, MavenMarkers.JAR)
    val licenseKey = MetadataKeyConstants.LICENSE
    assert(
      metadata.get(licenseKey).isDefined,
      "Bundle-License should appear in metadata"
    )
  }

  // ==================== Gap: Dependency JSON ====================

  test("getMetadata includes dependency JSON when dependencies exist") {
    val pom = """<project>
      <groupId>com.test</groupId>
      <artifactId>dep-test</artifactId>
      <version>1.0</version>
      <dependencies>
        <dependency>
          <groupId>org.example</groupId>
          <artifactId>lib</artifactId>
          <version>2.0</version>
        </dependency>
      </dependencies>
    </project>"""
    val artifact = ByteWrapper(pom.getBytes("UTF-8"), "test.pom", None)
    val item = createTestItem("dep-json-test")
    val state = MavenState().beginProcessing(artifact, item, MavenMarkers.POM)
    val (metadata, _) = state.getMetadata(artifact, item, MavenMarkers.POM)
    val depKey = MetadataKeyConstants.adHoc("maven")("DEPENDENCIES")
    val depValues = metadata.get(depKey)
    assert(depValues.isDefined, "Dependencies metadata key should be present")
    val json = depValues.get.head.value
    assert(
      json.contains("org.example") || json.contains("lib"),
      "Dependency JSON should contain dependency info"
    )
  }

  // ==================== Gap: PomParser depth boundary ====================

  test("PomParser: chain of depth 9 resolves") {
    val props =
      (0 until 9).map(i => s"p$i" -> s"$${p${i + 1}").toMap + ("p9" -> "done")
    val result = PomParser.resolveProperty("p0", props)
    assert(result.isDefined, "Chain of depth 9 should resolve")
  }

  test("PomParser: depth 10 boundary returns None") {
    // 11 links, so depth 11; MaxDepth is 10, so this should return None
    val props = (0 until 11)
      .map(i => s"p$i" -> s"$${p${i + 1}}")
      .toMap + ("p11" -> "done")
    val result = PomParser.resolveProperty("p0", props)
    assertEquals(result, None, "Exceeding MaxDepth should return None")
  }

  test("PomParser: circular reference returns None") {
    val result =
      PomParser.resolveProperty("a", Map("a" -> "${b}", "b" -> "${a}"))
    assertEquals(result, None)
  }

  // ==================== Gap: Parent vs Project groupId/artifactId/version ====================

  /* Bug: PomParser.tagText uses getElementsByTagName which returns elements in
   * document order. When a POM has a <parent> block (which always comes first
   * in document order), the parent's groupId/artifactId/version are returned
   * instead of the project's own.
   *
   * Example POM that triggers the bug:
   *   <project>
   *     <parent>
   *       <groupId>za.co.absa.spline</groupId>
   *       <artifactId>parent</artifactId>
   *       <version>0.4.0</version>
   *     </parent>
   *     <artifactId>admin</artifactId>
   *   </project>
   *
   * Expected: artifactId = "admin" (project's own)
   * Actual (buggy): artifactId = "parent" (from <parent> block)
   *
   * This test verifies that PomParser correctly distinguishes between
   * project-level and parent-level elements, returning the project's own
   * values when they exist, and only falling back to parent values when
   * the project doesn't specify its own.
   *
   * Requirement: REQ-2 (canonical pURL priority) — the external POM must
   * provide the correct project-level groupId/artifactId/version, not the
   * parent's values.
   */

  test("PomParser: project artifactId not shadowed by parent artifactId") {
    val pom = """<?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <parent>
          <groupId>za.co.absa.spline</groupId>
          <artifactId>parent</artifactId>
          <version>0.4.0</version>
          <relativePath>../parent/pom.xml</relativePath>
        </parent>
        <artifactId>admin</artifactId>
        <packaging>jar</packaging>
      </project>"""

    val parsed = PomParser.parse(pom)
    assert(parsed.isDefined, "PomParser should successfully parse this POM")
    assertEquals(
      parsed.get.artifactId,
      Some("admin"),
      "Project artifactId must be 'admin', not 'parent' from the <parent> block"
    )
  }

  test("PomParser: project groupId not shadowed by parent groupId") {
    val pom = """<?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <parent>
          <groupId>com.example.parent</groupId>
          <artifactId>parent</artifactId>
          <version>1.0</version>
        </parent>
        <groupId>com.example.project</groupId>
        <artifactId>my-artifact</artifactId>
        <version>2.0</version>
      </project>"""

    val parsed = PomParser.parse(pom)
    assert(parsed.isDefined, "PomParser should successfully parse this POM")
    assertEquals(
      parsed.get.groupId,
      Some("com.example.project"),
      "Project groupId must be 'com.example.project', not 'com.example.parent' from <parent>"
    )
    assertEquals(
      parsed.get.artifactId,
      Some("my-artifact"),
      "Project artifactId must be 'my-artifact'"
    )
    assertEquals(
      parsed.get.version,
      Some("2.0"),
      "Project version must be '2.0', not '1.0' from <parent>"
    )
  }

  test(
    "PomParser: falls back to parent groupId/version when project omits them"
  ) {
    /* When the project doesn't specify its own groupId or version (which is
     * common — they inherit from parent), PomParser should fall back to the
     * parent's values. But the project's artifactId should always be its own.
     */
    val pom = """<?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <parent>
          <groupId>za.co.absa.spline</groupId>
          <artifactId>parent</artifactId>
          <version>0.4.0</version>
        </parent>
        <artifactId>admin</artifactId>
        <packaging>jar</packaging>
      </project>"""

    val parsed = PomParser.parse(pom)
    assert(parsed.isDefined, "PomParser should successfully parse this POM")
    assertEquals(
      parsed.get.groupId,
      Some("za.co.absa.spline"),
      "GroupId should be inherited from parent: 'za.co.absa.spline'"
    )
    assertEquals(
      parsed.get.artifactId,
      Some("admin"),
      "ArtifactId must be project's own: 'admin'"
    )
    assertEquals(
      parsed.get.version,
      Some("0.4.0"),
      "Version should be inherited from parent: '0.4.0'"
    )
  }

  test("PomParser: parent fields are separately accessible") {
    /* The parent's groupId/artifactId/version should still be accessible via
     * the parentGroupId/parentArtifactId/parentVersion fields for metadata.
     */
    val pom = """<?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <parent>
          <groupId>com.example.parent</groupId>
          <artifactId>parent-art</artifactId>
          <version>1.0</version>
        </parent>
        <groupId>com.example.project</groupId>
        <artifactId>my-artifact</artifactId>
        <version>2.0</version>
      </project>"""

    val parsed = PomParser.parse(pom)
    assert(parsed.isDefined, "PomParser should successfully parse this POM")
    assertEquals(parsed.get.parentGroupId, Some("com.example.parent"))
    assertEquals(parsed.get.parentArtifactId, Some("parent-art"))
    assertEquals(parsed.get.parentVersion, Some("1.0"))
  }
}
