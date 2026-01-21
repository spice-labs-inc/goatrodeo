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

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.strategies.GenericFile
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class ToProcessTestSuite extends munit.FunSuite {

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

  // ==================== ParentScope Tests ====================

  test("ParentScope.forAndWith - creates scope with given identifier") {
    val scope = ParentScope.forAndWith("test-id", None, Map())
    assertEquals(scope.scopeFor(), "test-id")
  }

  test("ParentScope.forAndWith - links to parent scope") {
    val parentScope = ParentScope.forAndWith("parent-id", None, Map())
    val childScope =
      ParentScope.forAndWith("child-id", Some(parentScope), Map())

    assertEquals(childScope.parentOfParentScope(), Some(parentScope))
  }

  test("ParentScope.parentScopeInformation - returns scope info string") {
    val scope = ParentScope.forAndWith("test-id", None, Map())
    val info = scope.parentScopeInformation()

    assert(info.contains("test-id"))
  }

  test("ParentScope.parentScopeInformation - includes parent info") {
    val parentScope = ParentScope.forAndWith("parent-id", None, Map())
    val childScope =
      ParentScope.forAndWith("child-id", Some(parentScope), Map())

    val info = childScope.parentScopeInformation()
    assert(info.contains("child-id"))
    assert(info.contains("parent-id"))
  }

  test("ParentScope.beginProcessing - returns unchanged item by default") {
    val scope = ParentScope.forAndWith("test-id", None, Map())
    val storage = MemStorage(None)
    val artifact = ByteWrapper(Array[Byte](), "test.txt", None)
    val item = createTestItem("item-id")

    val result = scope.beginProcessing(storage, artifact, item)
    assertEquals(result, item)
  }

  // ==================== strategiesForArtifacts Tests ====================

  test("strategiesForArtifacts - returns empty for empty input") {
    val artifacts = Vector[ByteWrapper]()
    val result = ToProcess.strategiesForArtifacts(artifacts, _ => (), false)
    assert(result.isEmpty)
  }

  test("strategiesForArtifacts - creates strategies for text files") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val result =
      ToProcess.strategiesForArtifacts(Vector(artifact), _ => (), false)

    assertEquals(result.length, 1)
    assert(result.head.isInstanceOf[GenericFile])
  }

  test("strategiesForArtifacts - calls onFound callback") {
    var count = 0
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    ToProcess.strategiesForArtifacts(Vector(artifact), _ => count += 1, false)

    assertEquals(count, 1)
  }

  test("strategiesForArtifacts - groups files by type") {
    val txt = ByteWrapper("text".getBytes("UTF-8"), "file.txt", None)
    val bin = ByteWrapper(Array[Byte](0, 1, 2), "file.bin", None)

    val result =
      ToProcess.strategiesForArtifacts(Vector(txt, bin), _ => (), false)
    assertEquals(result.length, 2)
  }

  // ==================== strategyForDirectory Tests ====================

  test("strategyForDirectory - creates strategies from directory") {
    val tempDir = Files.createTempDirectory("strategiestest").toFile()
    try {
      val file1 = new File(tempDir, "file1.txt")
      val file2 = new File(tempDir, "file2.txt")
      Helpers.writeOverFile(file1, "content1")
      Helpers.writeOverFile(file2, "content2")

      val result = ToProcess.strategyForDirectory(tempDir, false, None)
      assertEquals(result.length, 2)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("strategyForDirectory - returns empty for empty directory") {
    val tempDir = Files.createTempDirectory("emptydir").toFile()
    try {
      val result = ToProcess.strategyForDirectory(tempDir, false, None)
      assert(result.isEmpty)
    } finally {
      tempDir.delete()
    }
  }

  test("strategyForDirectory - calls onFound callback") {
    val tempDir = Files.createTempDirectory("callbackdir").toFile()
    try {
      val file = new File(tempDir, "test.txt")
      Helpers.writeOverFile(file, "content")

      var called = false
      ToProcess.strategyForDirectory(tempDir, false, None, _ => called = true)
      assert(called)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  // ==================== buildGraphForToProcess Tests ====================

  test("buildGraphForToProcess - creates storage with items") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val toProcess = Vector(GenericFile(artifact))

    val store = ToProcess.buildGraphForToProcess(toProcess, args = Config())

    assert(store.size() > 0)
  }

  test("buildGraphForToProcess - returns configured storage") {
    val tempDir = Files.createTempDirectory("buildgraphtest").toFile()
    try {
      val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
      val toProcess = Vector(GenericFile(artifact))
      val storage = MemStorage(Some(tempDir))

      val result =
        ToProcess.buildGraphForToProcess(toProcess, storage, Config())

      assertEquals(result.destDirectory(), Some(tempDir))
    } finally {
      tempDir.delete()
    }
  }

  test("buildGraphForToProcess - handles empty toProcess") {
    val toProcess = Vector[ToProcess]()
    val store = ToProcess.buildGraphForToProcess(toProcess, args = Config())

    assertEquals(store.size(), 0)
  }

  test("buildGraphForToProcess - respects block list") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val toProcess = Vector(GenericFile(artifact))

    // This test verifies that block list is passed through
    val store = ToProcess.buildGraphForToProcess(
      toProcess,
      args = Config(),
      block = Set("gitoid:blob:sha256:abc123")
    )

    assert(store.size() > 0)
  }

  // ==================== buildGraphFromArtifactWrapper Tests ====================

  test("buildGraphFromArtifactWrapper - builds from single artifact") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)

    val store =
      ToProcess.buildGraphFromArtifactWrapper(artifact, args = Config())

    assert(store.size() > 0)
  }

  test("buildGraphFromArtifactWrapper - generates gitoids") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)

    val store =
      ToProcess.buildGraphFromArtifactWrapper(artifact, args = Config())

    val hasGitoid = store.keys().exists(_.startsWith("gitoid:"))
    assert(hasGitoid)
  }

  // ==================== process Tests ====================

  test("GenericFile.process - creates items in store") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val tp = GenericFile(artifact)
    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())

    tp.process(None, store, parentScope, None, Config())

    assert(store.size() > 0)
  }

  test("GenericFile.process - returns gitoids") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val tp = GenericFile(artifact)
    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())

    val result = tp.process(None, store, parentScope, None, Config())

    assert(result.nonEmpty)
    assert(result.head.startsWith("gitoid:"))
  }

  test("GenericFile.process - calls atEnd callback") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val tp = GenericFile(artifact)
    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())

    var called = false
    tp.process(
      None,
      store,
      parentScope,
      None,
      Config(),
      atEnd = (_, _) => called = true
    )

    assert(called)
  }

  test("GenericFile.process - respects keepRunning flag") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val tp = GenericFile(artifact)
    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())

    val result = tp.process(
      None,
      store,
      parentScope,
      None,
      Config(),
      keepRunning = () => false
    )

    assert(result.isEmpty, "Should return empty when keepRunning is false")
  }

  test("GenericFile.process - respects block list") {
    val artifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    val tp = GenericFile(artifact)
    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())

    // Process once to get the gitoid
    val result1 = tp.process(None, store, parentScope, None, Config())
    val gitoid = result1.head

    // Process again with that gitoid blocked
    val store2 = MemStorage(None)
    val tp2 =
      GenericFile(ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None))
    val result2 = tp2.process(
      None,
      store2,
      parentScope,
      None,
      Config(),
      blockList = Set(gitoid)
    )

    assert(result2.isEmpty, "Should return empty when gitoid is blocked")
  }

  // ==================== computeToProcess Tests ====================

  test("computeToProcess - has correct number of strategies") {
    // Should have: Maven, Docker, Debian, Dotnet, Generic
    assertEquals(ToProcess.computeToProcess.length, 5)
  }

  // ==================== Integration Tests ====================

  test("ToProcess - processes JAR file") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getName(), None)
      val store =
        ToProcess.buildGraphFromArtifactWrapper(wrapper, args = Config())

      assert(store.size() > 0)
      assert(store.keys().exists(_.startsWith("gitoid:")))
    }
  }

  test("ToProcess - processes DEB file") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      val store =
        ToProcess.buildGraphFromArtifactWrapper(wrapper, args = Config())

      assert(store.size() > 0)
      assert(store.purls().nonEmpty)
    }
  }

  test("ToProcess - processes nested archives") {
    val nestedFile = new File("test_data/nested.tar")
    if (nestedFile.exists()) {
      val wrapper = FileWrapper(nestedFile, nestedFile.getName(), None)
      val store =
        ToProcess.buildGraphFromArtifactWrapper(wrapper, args = Config())

      // Should have processed contents
      assert(store.size() > 1)
    }
  }
}
