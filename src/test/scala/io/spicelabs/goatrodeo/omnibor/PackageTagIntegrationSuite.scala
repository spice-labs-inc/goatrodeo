/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File

/** Phase 7 Integration Tests: Per-Package Tagging with Real Test Data
  *
  * These tests verify that the per-package tagging feature works correctly with
  * actual test corpus files:
  *   - Maven JARs from test_data/pqc_jars
  *   - Linux packages (.deb, .rpm) from test_data/
  *   - Docker images from test_data/download/docker_tests
  *
  * Each test verifies:
  *   - Package tags are created with correct name, version, and date
  *   - Tags have proper edge connections (tag:from -> tags, tag:to -> artifact)
  *   - Packages index is created and populated
  *   - Tag JSON structure follows the specification
  *
  * Requirement Traceability:
  *   - R1: --package-tags CLI option generates tags
  *   - R2: --package-tags-short-name generates short names
  *   - R3: Tag JSON has correct structure (tag, version, date)
  *   - R4: Maven strategy extracts groupId/artifactId/version and build date
  *   - R5: Baharat strategy extracts name/version from .deb
  *   - R6: Docker strategy extracts repository:tag and created date
  */
class PackageTagIntegrationSuite extends munit.FunSuite {

  // Walks multi-hundred-MB docker tarballs, which takes well over munit's
  // 30-second default — more so when other test classes run concurrently.
  override val munitTimeout = scala.concurrent.duration.Duration(30, "minutes")

  // Helper to check if test files exist
  def checkTestFile(path: String): Boolean = new File(path).exists()

  /** Helper to find package tags in storage Returns all items with
    * body_mime_type = application/vnd.cc.goatrodeo.tag that are linked from the
    * tags index and have package_tag field
    */
  def findPackageTags(storage: Storage): Vector[Item] = {
    storage.read("tags") match {
      case Some(tagsItem) =>
        val items = for {
          (edgeType, target) <- tagsItem.connections.toVector
          if edgeType == EdgeType.tagTo
          item <- storage.read(target)
          content <- extractTagContent(item)
          if isBooleanTrue(content, "package_tag")
        } yield item
        items
      case None => Vector.empty
    }
  }

  /** Helper to extract tag content from item as Map[String, Dom.Element] */
  def extractTagContent(
      item: Item
  ): Option[Map[String, io.bullet.borer.Dom.Element]] = {
    item.body.flatMap {
      case tagData: ItemTagData =>
        import io.bullet.borer.Dom
        tagData.tag match {
          case mapElem: Dom.MapElem =>
            val fields = mapElem.members.collect {
              case (Dom.StringElem(key), value) => key -> value
            }
            Some(fields.toMap)
          case _ => None
        }
      case _ => None
    }
  }

  /** Helper to extract a string field from tag content */
  def stringField(
      content: Map[String, io.bullet.borer.Dom.Element],
      key: String
  ): Option[String] =
    content.get(key).collect { case io.bullet.borer.Dom.StringElem(value) =>
      value
    }

  /** Helper to check if a boolean field is true */
  def isBooleanTrue(
      content: Map[String, io.bullet.borer.Dom.Element],
      key: String
  ): Boolean =
    content.get(key) match {
      case Some(io.bullet.borer.Dom.BooleanElem(value)) => value
      case _                                            => false
    }

  // ==================== Maven JAR Tests ====================

  test("Maven JARs - package tags created for pqc_jars") {
    assume(checkTestFile("test_data/pqc_jars"), "pqc_jars test data exists")

    val config = Configuration(packageTags = true, packageTagsShortName = false)
    val source = new File("test_data/pqc_jars")
    val strategies = ToProcess.strategyForDirectory(source, false, None)
    val storage = ToProcess.buildGraphForToProcess(strategies)(using config)

    val packageTags = findPackageTags(storage)

    // Should have tags for the 4 JAR versions (POMs are not main artifacts)
    assert(packageTags.nonEmpty, "Should create package tags")

    // Verify tag structure
    packageTags.foreach { tagItem =>
      val content = extractTagContent(tagItem)
      assert(
        content.isDefined,
        s"Tag should have content: ${tagItem.identifier}"
      )

      val fields = content.get
      assert(fields.contains("tag"), s"Tag should have 'tag' field")
      assert(fields.contains("date"), s"Tag should have 'date' field")
      assert(fields.contains("version"), s"Tag should have 'version' field")

      // Verify full qualified name format
      val tagValue = stringField(fields, "tag").get
      assert(
        tagValue.contains(":"),
        s"Full name should contain colon: $tagValue"
      )
      assert(
        tagValue.startsWith("io.spicelabs.pepperstorm:"),
        s"Should have groupId prefix: $tagValue"
      )
    }
  }

  test("Maven JARs - short names use artifactId only") {
    assume(checkTestFile("test_data/pqc_jars"), "pqc_jars test data exists")

    val config = Configuration(packageTags = true, packageTagsShortName = true)
    val source = new File("test_data/pqc_jars")
    val strategies = ToProcess.strategyForDirectory(source, false, None)
    val storage = ToProcess.buildGraphForToProcess(strategies)(using config)

    val packageTags = findPackageTags(storage)

    packageTags.foreach { tagItem =>
      val content = extractTagContent(tagItem)
      val tagValue = stringField(content.get, "tag").get

      // Should NOT contain groupId prefix with short names
      assert(
        !tagValue.contains(":"),
        s"Short name should not contain colon: $tagValue"
      )
      assertEquals(tagValue, "ps-059-patient-matching-service")
    }
  }

  test("Maven JARs - tag edges point to correct artifacts") {
    assume(checkTestFile("test_data/pqc_jars"), "pqc_jars test data exists")

    val config = Configuration(packageTags = true)
    val source = new File("test_data/pqc_jars")
    val strategies = ToProcess.strategyForDirectory(source, false, None)
    val storage = ToProcess.buildGraphForToProcess(strategies)(using config)

    val packageTags = findPackageTags(storage)

    // Each tag should have tag:from -> tags and tag:to -> some artifact
    packageTags.foreach { tagItem =>
      val edges = tagItem.connections

      val hasTagFromTags = edges.exists { case (edgeType, target) =>
        edgeType == EdgeType.tagFrom && target == "tags"
      }
      assert(hasTagFromTags, s"Tag should have tag:from -> tags edge")

      val hasTagToArtifact = edges.exists { case (edgeType, _) =>
        edgeType == EdgeType.tagTo
      }
      assert(hasTagToArtifact, s"Tag should have tag:to -> artifact edge")
    }
  }

  // ==================== Linux Package Tests (.deb) ====================

  test("Debian packages - tags created with name and version") {
    assume(
      checkTestFile("test_data/libasound2_1.1.3-5ubuntu0.6_amd64.deb"),
      "Debian test data exists"
    )

    val config = Configuration(packageTags = true)
    val source = FileWrapper(
      new File("test_data/libasound2_1.1.3-5ubuntu0.6_amd64.deb"),
      "libasound2_1.1.3-5ubuntu0.6_amd64.deb",
      None
    )
    val storage = ToProcess.buildGraphFromArtifactWrapper(source)(using config)

    val packageTags = findPackageTags(storage)

    // Baharat strategy should create tags for .deb files
    assert(packageTags.nonEmpty, "Should create package tags for .deb")

    packageTags.foreach { tagItem =>
      val content = extractTagContent(tagItem)
      assert(content.isDefined, "Tag should have content")

      val fields = content.get
      assert(fields.contains("tag"), "Should have tag field")
      assert(fields.contains("version"), "Should have version field")
    }
  }

  test("Debian with metadata - extracts build date") {
    assume(
      checkTestFile("test_data/debwithmetadata.deb"),
      "Debian with metadata test data exists"
    )

    val config = Configuration(packageTags = true)
    val source = FileWrapper(
      new File("test_data/debwithmetadata.deb"),
      "debwithmetadata.deb",
      None
    )
    val storage = ToProcess.buildGraphFromArtifactWrapper(source)(using config)

    val packageTags = findPackageTags(storage)

    packageTags.foreach { tagItem =>
      val content = extractTagContent(tagItem)
      val dateOpt = stringField(content.get, "date")

      // Date should be present and valid ISO 8601
      assert(dateOpt.isDefined, "Should extract build date")
      val dateStr = dateOpt.get
      assert(dateStr.contains("T"), "Date should have T separator")
      assert(dateStr.endsWith("Z"), "Date should end with Z")
    }
  }

  // ==================== Linux Package Tests (.rpm) ====================

  test("RPM packages - tags created with name and version") {
    assume(
      checkTestFile("test_data/busybox-1.37.0-160099.8.2.aarch64.rpm"),
      "RPM test data exists"
    )

    val config = Configuration(packageTags = true)
    val source = FileWrapper(
      new File("test_data/busybox-1.37.0-160099.8.2.aarch64.rpm"),
      "busybox-1.37.0-160099.8.2.aarch64.rpm",
      None
    )
    val storage = ToProcess.buildGraphFromArtifactWrapper(source)(using config)

    val packageTags = findPackageTags(storage)

    assert(packageTags.nonEmpty, "Should create package tags for .rpm")

    packageTags.foreach { tagItem =>
      val content = extractTagContent(tagItem)
      val fields = content.get
      assert(fields.contains("tag"), "Should have tag field")
      assert(fields.contains("version"), "Should have version field")
    }
  }

  // ==================== Docker Tests ====================

  test("Docker images - package tags created with repository, tag, and date") {
    assume(
      DockerTestFixtures.checkTestFile(
        "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar"
      ),
      "Docker test data exists"
    )

    val storage = DockerTestFixtures.bigtentStorage
    val packageTags = findPackageTags(storage)

    assert(packageTags.nonEmpty, "Should create package tags for Docker images")

    val bigtentTag = packageTags.find { tagItem =>
      stringField(extractTagContent(tagItem).get, "tag")
        .exists(_.contains("bigtent"))
    }
    assert(bigtentTag.isDefined, "Should have bigtent tag")

    val content = extractTagContent(bigtentTag.get)
    val tagValue = stringField(content.get, "tag").get
    assert(tagValue.contains(":"), s"Should have colon separator: $tagValue")
    assert(
      tagValue.contains("bigtent"),
      s"Should contain repository name: $tagValue"
    )

    packageTags.foreach { tagItem =>
      val tagContent = extractTagContent(tagItem)
      val dateOpt = stringField(tagContent.get, "date")
      assert(dateOpt.isDefined, "Should have date from Docker config")
      val dateStr = dateOpt.get
      assert(dateStr.contains("T"), "Date should have T separator")
      assert(dateStr.endsWith("Z"), "Date should end with Z")
    }
  }

  test("Docker complex image - multiple tags created") {
    assume(
      DockerTestFixtures.checkTestFile(
        "test_data/download/docker_tests/grinder_bt_pg_docker.tar"
      ),
      "Complex Docker test data exists"
    )

    val storage = DockerTestFixtures.grinderStorage
    val packageTags = findPackageTags(storage)

    val tagNames = packageTags.flatMap { tagItem =>
      stringField(extractTagContent(tagItem).get, "tag")
    }

    assert(tagNames.exists(_.contains("postgres")), "Should have postgres tag")
    assert(tagNames.exists(_.contains("bigtent")), "Should have bigtent tag")
    assert(tagNames.exists(_.contains("grinder")), "Should have grinder tag")
  }

  // ==================== Cross-Strategy Validation ====================

  test("All strategies - tag JSON has consistent structure") {
    // Test Maven from directory and individual files for others
    val testCases = Seq(
      ("test_data/pqc_jars", "Maven", true),
      ("test_data/libasound2_1.1.3-5ubuntu0.6_amd64.deb", "Debian", false),
      (
        "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar",
        "Docker",
        false
      )
    ).filter { case (path, _, _) => checkTestFile(path) }

    assume(testCases.nonEmpty, "At least one test file exists")

    val config = Configuration(packageTags = true)

    testCases.foreach { case (path, strategyName, isDirectory) =>
      val storage = (strategyName, isDirectory) match {
        case ("Docker", _) if path.contains("bigtent") =>
          DockerTestFixtures.bigtentStorage
        case ("Docker", _) if path.contains("grinder") =>
          DockerTestFixtures.grinderStorage
        case (mavenDir, true) =>
          val strategies =
            ToProcess.strategyForDirectory(new File(path), false, None)
          ToProcess.buildGraphForToProcess(strategies)(using config)
        case _ =>
          val source = FileWrapper(new File(path), path, None)
          ToProcess.buildGraphFromArtifactWrapper(source)(using config)
      }

      val packageTags = findPackageTags(storage)

      packageTags.foreach { tagItem =>
        val content = extractTagContent(tagItem)
        assert(content.isDefined, s"$strategyName: Tag should have content")

        val fields = content.get
        assert(fields.contains("tag"), s"$strategyName: Should have tag field")
        assert(
          fields.contains("date"),
          s"$strategyName: Should have date field"
        )

        // Version is optional - may or may not be present
        stringField(fields, "version").foreach { version =>
          assert(
            version.nonEmpty,
            s"$strategyName: Version should not be empty if present"
          )
        }
      }
    }
  }

  test("Tags index - contains package tags when package-tags enabled") {
    assume(checkTestFile("test_data/pqc_jars"), "pqc_jars test data exists")

    val config = Configuration(packageTags = true)
    val source = new File("test_data/pqc_jars")
    val strategies = ToProcess.strategyForDirectory(source, false, None)
    val storage = ToProcess.buildGraphForToProcess(strategies)(using config)

    val tagsItem = storage.read("tags")
    assert(tagsItem.isDefined, "Should create tags index")

    // Should have tag:to edges to package tags
    val tagEdges = tagsItem.get.connections.filter(_._1 == EdgeType.tagTo)
    assert(
      tagEdges.nonEmpty,
      "Tags index should have tag:to edges to package tags"
    )
  }

  test("Tags index - no package tag edges when package-tags disabled") {
    assume(checkTestFile("test_data/pqc_jars"), "pqc_jars test data exists")

    val config = Configuration(packageTags = false)
    val source = new File("test_data/pqc_jars")
    val strategies = ToProcess.strategyForDirectory(source, false, None)
    val storage = ToProcess.buildGraphForToProcess(strategies)(using config)

    // Without --tag or --package-tags, tags index should not exist
    val tagsItem = storage.read("tags")
    assert(
      tagsItem.isEmpty,
      "Should NOT create tags index when neither --tag nor --package-tags enabled"
    )
  }
}
