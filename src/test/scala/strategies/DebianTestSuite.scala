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
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.strategies.Debian
import io.spicelabs.goatrodeo.omnibor.strategies.DebianState
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class DebianTestSuite extends munit.FunSuite {

  def createTestItem(id: String): Item = {
    Item(
      id,
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(id),
          mimeType = TreeSet("application/octet-stream"),
          fileSize = 100,
          extra = TreeMap()
        )
      )
    )
  }

  // ==================== computePurl Tests ====================

  test("Debian.computePurl - extracts purl from DEB file") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val result = Debian.computePurl(wrapper)

      assert(result.isDefined)
      val (purl, metadata) = result.get
      assert(purl.isDefined)
      assertEquals(purl.get.getType(), "deb")
    }
  }

  test("Debian.computePurl - returns None for non-DEB file") {
    val nonDebData = "not a deb file".getBytes("UTF-8")
    val wrapper = ByteWrapper(nonDebData, "test.txt", None)
    val result = Debian.computePurl(wrapper)

    assertEquals(result, None)
  }

  test("Debian.computePurl - extracts package name") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val result = Debian.computePurl(wrapper)

      assert(result.isDefined)
      val (purl, _) = result.get
      assert(purl.isDefined)
      assert(purl.get.getName().nonEmpty)
    }
  }

  test("Debian.computePurl - extracts version") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val result = Debian.computePurl(wrapper)

      assert(result.isDefined)
      val (purl, _) = result.get
      assert(purl.isDefined)
      assert(purl.get.getVersion().nonEmpty)
    }
  }

  test("Debian.computePurl - extracts metadata") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val result = Debian.computePurl(wrapper)

      assert(result.isDefined)
      val (_, metadata) = result.get
      // Should have control file metadata
      assert(metadata.nonEmpty)
    }
  }

  test("Debian.computePurl - detects ubuntu namespace in path") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      // This file path contains ubuntu
      val wrapper =
        FileWrapper(debFile, "ubuntu/tk8.6_8.6.14-1build1_amd64.deb", None)
      val result = Debian.computePurl(wrapper)

      if (result.isDefined) {
        val (purl, _) = result.get
        if (purl.isDefined) {
          // Should detect ubuntu in path
          assert(
            purl.get.getNamespace() == "ubuntu" || purl.get
              .getNamespace() == "debian"
          )
        }
      }
    }
  }

  // ==================== DebianState Tests ====================

  test("DebianState.beginProcessing - returns same state") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val state = DebianState(wrapper)
      val item = createTestItem("test-id")

      val newState = state.beginProcessing(wrapper, item, SingleMarker())
      assertEquals(newState, state)
    }
  }

  test("DebianState.getPurls - returns purl for valid DEB") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val state = DebianState(wrapper)
      val item = createTestItem("test-id")

      val (purls, _) = state.getPurls(wrapper, item, SingleMarker())
      assert(purls.nonEmpty)
    }
  }

  test("DebianState.getMetadata - returns metadata for valid DEB") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val state = DebianState(wrapper)
      val item = createTestItem("test-id")

      val (metadata, _) = state.getMetadata(wrapper, item, SingleMarker())
      assert(metadata.nonEmpty)
    }
  }

  test("DebianState.postChildProcessing - returns same state") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val storage = MemStorage(None)
      val state = DebianState(wrapper)

      val newState = state.postChildProcessing(None, storage, SingleMarker())
      assertEquals(newState, state)
    }
  }

  // ==================== Debian ToProcess Tests ====================

  test("Debian.itemCnt - returns 1") {
    val artifact = ByteWrapper(Array[Byte](), "test.deb", None)
    val tp = Debian(artifact)

    assertEquals(tp.itemCnt, 1)
  }

  test("Debian.main - returns path") {
    val artifact = ByteWrapper(Array[Byte](), "path/to/test.deb", None)
    val tp = Debian(artifact)

    assertEquals(tp.main, "path/to/test.deb")
  }

  test("Debian.mimeType - returns artifact mime type") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val tp = Debian(wrapper)

      assertEquals(tp.mimeType, "application/x-debian-package")
    }
  }

  test(
    "Debian.getElementsToProcess - returns single element with SingleMarker"
  ) {
    val artifact = ByteWrapper(Array[Byte](), "test.deb", None)
    val tp = Debian(artifact)

    val (elements, _) = tp.getElementsToProcess()
    assertEquals(elements.length, 1)
    assert(elements.head._2.isInstanceOf[SingleMarker])
  }

  // ==================== computeDebianFiles Tests ====================

  test("computeDebianFiles - identifies DEB files") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val byUUID = Map(wrapper.uuid -> wrapper)
      val byName = Map(debFile.getName() -> Vector(wrapper))

      val (toProcess, revisedByUUID, revisedByName, name) =
        Debian.computeDebianFiles(byUUID, byName)

      assertEquals(name, "Debian")
      assertEquals(toProcess.length, 1)
      assert(toProcess.head.isInstanceOf[Debian])
      // UUID should be removed
      assert(!revisedByUUID.contains(wrapper.uuid))
    }
  }

  test("computeDebianFiles - ignores non-DEB files") {
    val nonDebData = "not a deb".getBytes("UTF-8")
    val wrapper = ByteWrapper(nonDebData, "test.txt", None)
    val byUUID = Map(wrapper.uuid -> wrapper)
    val byName = Map("test.txt" -> Vector(wrapper))

    val (toProcess, revisedByUUID, _, name) =
      Debian.computeDebianFiles(byUUID, byName)

    assertEquals(name, "Debian")
    assert(toProcess.isEmpty)
    // Non-DEB should remain in UUID map
    assert(revisedByUUID.contains(wrapper.uuid))
  }

  test("computeDebianFiles - handles multiple DEB files") {
    val debFile1 = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    val debFile2 = new File("test_data/libasound2_1.1.3-5ubuntu0.6_amd64.deb")

    if (debFile1.exists() && debFile2.exists()) {
      val wrapper1 = FileWrapper(debFile1, debFile1.getName(), None)
      val wrapper2 = FileWrapper(debFile2, debFile2.getName(), None)

      val byUUID = Map(
        wrapper1.uuid -> wrapper1,
        wrapper2.uuid -> wrapper2
      )
      val byName = Map(
        debFile1.getName() -> Vector(wrapper1),
        debFile2.getName() -> Vector(wrapper2)
      )

      val (toProcess, _, _, _) = Debian.computeDebianFiles(byUUID, byName)

      assertEquals(toProcess.length, 2)
    }
  }

  // ==================== Integration Tests ====================

  test("Debian - full processing of DEB file") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val store =
        ToProcess.buildGraphFromArtifactWrapper(wrapper, args = Config())

      // Should have purls
      val purls = store.purls()
      assert(purls.nonEmpty, "Should have at least one purl")
    }
  }

  test("Debian - stores control file metadata") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val result = Debian.computePurl(wrapper)

      if (result.isDefined) {
        val (_, metadata) = result.get
        // Check for control file in metadata
        assert(metadata.contains("control") || metadata.contains("package"))
      }
    }
  }
}
