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
import io.spicelabs.goatrodeo.omnibor.strategies.GenericFile
import io.spicelabs.goatrodeo.omnibor.strategies.GenericFileState
import io.spicelabs.goatrodeo.util.ByteWrapper

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class GenericTestSuite extends munit.FunSuite {

  def createTestItem(id: String): Item = {
    Item(
      id,
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(
        fileNames = TreeSet(id),
        mimeType = TreeSet("application/octet-stream"),
        fileSize = 100,
        extra = TreeMap()
      ))
    )
  }

  // ==================== GenericFileState Tests ====================

  test("GenericFileState.beginProcessing - returns same state") {
    val artifact = ByteWrapper("test".getBytes("UTF-8"), "test.txt", None)
    val item = createTestItem("test-id")
    val state = GenericFileState()

    val newState = state.beginProcessing(artifact, item, SingleMarker())
    assertEquals(newState, state)
  }

  test("GenericFileState.getPurls - returns empty") {
    val artifact = ByteWrapper("test".getBytes("UTF-8"), "test.txt", None)
    val item = createTestItem("test-id")
    val state = GenericFileState()

    val (purls, _) = state.getPurls(artifact, item, SingleMarker())
    assert(purls.isEmpty)
  }

  test("GenericFileState.getMetadata - returns empty") {
    val artifact = ByteWrapper("test".getBytes("UTF-8"), "test.txt", None)
    val item = createTestItem("test-id")
    val state = GenericFileState()

    val (metadata, _) = state.getMetadata(artifact, item, SingleMarker())
    assert(metadata.isEmpty)
  }

  test("GenericFileState.postChildProcessing - returns same state") {
    val storage = MemStorage(None)
    val state = GenericFileState()

    val newState = state.postChildProcessing(None, storage, SingleMarker())
    assertEquals(newState, state)
  }

  // ==================== GenericFile ToProcess Tests ====================

  test("GenericFile.itemCnt - returns 1") {
    val artifact = ByteWrapper(Array[Byte](), "test.txt", None)
    val tp = GenericFile(artifact)

    assertEquals(tp.itemCnt, 1)
  }

  test("GenericFile.main - returns path") {
    val artifact = ByteWrapper(Array[Byte](), "path/to/test.txt", None)
    val tp = GenericFile(artifact)

    assertEquals(tp.main, "path/to/test.txt")
  }

  test("GenericFile.mimeType - returns artifact mime type") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val tp = GenericFile(artifact)

    assertEquals(tp.mimeType, "text/plain")
  }

  test("GenericFile.getElementsToProcess - returns single element with SingleMarker") {
    val artifact = ByteWrapper(Array[Byte](), "test.txt", None)
    val tp = GenericFile(artifact)

    val (elements, _) = tp.getElementsToProcess()
    assertEquals(elements.length, 1)
    assert(elements.head._2.isInstanceOf[SingleMarker])
  }

  test("GenericFile.markSuccessfulCompletion - completes without error") {
    val artifact = ByteWrapper(Array[Byte](), "test.txt", None)
    val tp = GenericFile(artifact)

    tp.markSuccessfulCompletion() // Should not throw
  }

  // ==================== computeGenericFiles Tests ====================

  test("computeGenericFiles - converts all remaining files") {
    val artifact1 = ByteWrapper("a".getBytes("UTF-8"), "file1.txt", None)
    val artifact2 = ByteWrapper("b".getBytes("UTF-8"), "file2.txt", None)

    val byUUID = Map(
      artifact1.uuid -> artifact1,
      artifact2.uuid -> artifact2
    )
    val byName = Map(
      "file1.txt" -> Vector(artifact1),
      "file2.txt" -> Vector(artifact2)
    )

    val (toProcess, revisedByUUID, revisedByName, name) = GenericFile.computeGenericFiles(byUUID, byName)

    assertEquals(name, "Generic")
    assertEquals(toProcess.length, 2)
    assert(revisedByUUID.isEmpty)
    assert(revisedByName.isEmpty)
  }

  test("computeGenericFiles - returns Generic name") {
    val byUUID = Map[String, ByteWrapper]()
    val byName = Map[String, Vector[ByteWrapper]]()

    val (_, _, _, name) = GenericFile.computeGenericFiles(byUUID, byName)

    assertEquals(name, "Generic")
  }

  test("computeGenericFiles - handles empty maps") {
    val byUUID = Map[String, ByteWrapper]()
    val byName = Map[String, Vector[ByteWrapper]]()

    val (toProcess, revisedByUUID, revisedByName, _) = GenericFile.computeGenericFiles(byUUID, byName)

    assert(toProcess.isEmpty)
    assert(revisedByUUID.isEmpty)
    assert(revisedByName.isEmpty)
  }
}
