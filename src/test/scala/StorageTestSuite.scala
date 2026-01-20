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

import com.github.packageurl.PackageURLBuilder
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.util.Helpers

import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class StorageTestSuite extends munit.FunSuite {

  def createTestItem(id: String, fileNames: Set[String] = Set()): Item = {
    Item(
      id,
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(fileNames.toSeq*),
          mimeType = TreeSet("application/octet-stream"),
          fileSize = 100,
          extra = TreeMap()
        )
      )
    )
  }

  // ==================== Basic Operations Tests ====================

  test("MemStorage - exists returns false for non-existent key") {
    val storage = MemStorage(None)
    assert(!storage.exists("nonexistent"))
  }

  test("MemStorage - exists returns true after write") {
    val storage = MemStorage(None)
    val item = createTestItem("test-id")
    storage.write("test-id", _ => Some(item), _ => "")
    assert(storage.exists("test-id"))
  }

  test("MemStorage - read returns None for non-existent key") {
    val storage = MemStorage(None)
    assertEquals(storage.read("nonexistent"), None)
  }

  test("MemStorage - read returns item after write") {
    val storage = MemStorage(None)
    val item = createTestItem("test-id")
    storage.write("test-id", _ => Some(item), _ => "")
    val result = storage.read("test-id")
    assert(result.isDefined)
    assertEquals(result.get.identifier, "test-id")
  }

  test("MemStorage - write creates new item") {
    val storage = MemStorage(None)
    val item = createTestItem("new-id")
    val result = storage.write("new-id", _ => Some(item), _ => "")
    assert(result.isDefined)
    assertEquals(result.get.identifier, "new-id")
  }

  test("MemStorage - write updates existing item") {
    val storage = MemStorage(None)
    val item1 = createTestItem("id", Set("file1"))
    val item2 = createTestItem("id", Set("file2"))

    storage.write("id", _ => Some(item1), _ => "")
    storage.write(
      "id",
      {
        case Some(existing) => Some(existing.merge(item2))
        case None           => Some(item2)
      },
      _ => ""
    )

    val result = storage.read("id")
    assert(result.isDefined)
    val fileNames = result.get.bodyAsItemMetaData.get.fileNames
    assert(fileNames.contains("file1"))
    assert(fileNames.contains("file2"))
  }

  test("MemStorage - write with None operation") {
    val storage = MemStorage(None)
    val result = storage.write("id", _ => None, _ => "")
    assertEquals(result, None)
  }

  test("MemStorage - size returns correct count") {
    val storage = MemStorage(None)
    assertEquals(storage.size(), 0)

    storage.write("id1", _ => Some(createTestItem("id1")), _ => "")
    assertEquals(storage.size(), 1)

    storage.write("id2", _ => Some(createTestItem("id2")), _ => "")
    assertEquals(storage.size(), 2)
  }

  test("MemStorage - keys returns all keys") {
    val storage = MemStorage(None)
    storage.write("key1", _ => Some(createTestItem("key1")), _ => "")
    storage.write("key2", _ => Some(createTestItem("key2")), _ => "")
    storage.write("key3", _ => Some(createTestItem("key3")), _ => "")

    val keys = storage.keys()
    assertEquals(keys.size, 3)
    assert(keys.contains("key1"))
    assert(keys.contains("key2"))
    assert(keys.contains("key3"))
  }

  test("MemStorage - contains returns true for existing key") {
    val storage = MemStorage(None)
    storage.write("exists", _ => Some(createTestItem("exists")), _ => "")
    assert(storage.contains("exists"))
  }

  test("MemStorage - contains returns false for non-existing key") {
    val storage = MemStorage(None)
    assert(!storage.contains("nonexistent"))
  }

  test("MemStorage - release clears all data") {
    val storage = MemStorage(None)
    storage.write("id1", _ => Some(createTestItem("id1")), _ => "")
    storage.write("id2", _ => Some(createTestItem("id2")), _ => "")

    storage.release()

    assertEquals(storage.size(), 0)
    assertEquals(storage.keys().size, 0)
  }

  // ==================== GitOID Filtering Tests ====================

  test("MemStorage - gitoidKeys returns only gitoid keys") {
    val storage = MemStorage(None)
    storage.write(
      "gitoid:blob:sha256:abc123",
      _ => Some(createTestItem("gitoid:blob:sha256:abc123")),
      _ => ""
    )
    storage.write(
      "sha256:def456",
      _ => Some(createTestItem("sha256:def456")),
      _ => ""
    )
    storage.write(
      "pkg:maven/group/artifact@1.0",
      _ => Some(createTestItem("pkg:maven/group/artifact@1.0")),
      _ => ""
    )

    val gitoids = storage.gitoidKeys()
    assertEquals(gitoids.size, 1)
    assert(gitoids.contains("gitoid:blob:sha256:abc123"))
    assert(!gitoids.contains("sha256:def456"))
    assert(!gitoids.contains("pkg:maven/group/artifact@1.0"))
  }

  test("MemStorage - gitoidKeys returns empty for no gitoids") {
    val storage = MemStorage(None)
    storage.write(
      "sha256:abc123",
      _ => Some(createTestItem("sha256:abc123")),
      _ => ""
    )
    val gitoids = storage.gitoidKeys()
    assertEquals(gitoids.size, 0)
  }

  // ==================== Purl Management Tests ====================

  test("MemStorage - addPurl adds package URL") {
    val storage = MemStorage(None)
    val purl = PackageURLBuilder
      .aPackageURL()
      .withType("maven")
      .withNamespace("org.example")
      .withName("artifact")
      .withVersion("1.0.0")
      .build()

    storage.addPurl(purl)

    val purls = storage.purls()
    assertEquals(purls.size, 1)
    assert(purls.contains(purl.canonicalize()))
  }

  test("MemStorage - purls returns all added purls") {
    val storage = MemStorage(None)
    val purl1 = PackageURLBuilder
      .aPackageURL()
      .withType("deb")
      .withNamespace("ubuntu")
      .withName("artifact1")
      .withVersion("1.0")
      .build()
    val purl2 = PackageURLBuilder
      .aPackageURL()
      .withType("npm")
      .withName("package")
      .withVersion("2.0")
      .build()

    storage.addPurl(purl1)
    storage.addPurl(purl2)

    val purls = storage.purls()
    assertEquals(purls.size, 2)
  }

  test("MemStorage - purls deduplicates identical purls") {
    val storage = MemStorage(None)
    val purl = PackageURLBuilder
      .aPackageURL()
      .withType("deb")
      .withNamespace("debian")
      .withName("artifact")
      .withVersion("1.0")
      .build()

    storage.addPurl(purl)
    storage.addPurl(purl)

    val purls = storage.purls()
    assertEquals(purls.size, 1)
  }

  // ==================== ListFileNames Trait Tests ====================

  test("MemStorage - sortedPaths returns sorted keys") {
    val storage = MemStorage(None)
    storage.write("c-key", _ => Some(createTestItem("c-key")), _ => "")
    storage.write("a-key", _ => Some(createTestItem("a-key")), _ => "")
    storage.write("b-key", _ => Some(createTestItem("b-key")), _ => "")

    val sorted = storage.sortedPaths()
    assertEquals(sorted, Vector("a-key", "b-key", "c-key"))
  }

  test("MemStorage - pathsSortedWithMD5 returns MD5 sorted pairs") {
    val storage = MemStorage(None)
    storage.write("key1", _ => Some(createTestItem("key1")), _ => "")
    storage.write("key2", _ => Some(createTestItem("key2")), _ => "")

    val result = storage.pathsSortedWithMD5()
    assertEquals(result.length, 2)
    // Verify each pair has MD5 hash and original key
    for ((md5, key) <- result) {
      assertEquals(md5.length, 32)
      assert(key == "key1" || key == "key2")
    }
  }

  test("MemStorage - target returns configured directory") {
    val tempDir = Files.createTempDirectory("targettest").toFile()
    try {
      val storage = MemStorage(Some(tempDir))
      assertEquals(storage.target(), Some(tempDir))
    } finally {
      tempDir.delete()
    }
  }

  test("MemStorage - target returns None when not configured") {
    val storage = MemStorage(None)
    assertEquals(storage.target(), None)
  }

  test("MemStorage - destDirectory returns configured directory") {
    val tempDir = Files.createTempDirectory("desttest").toFile()
    try {
      val storage = MemStorage(Some(tempDir))
      assertEquals(storage.destDirectory(), Some(tempDir))
    } finally {
      tempDir.delete()
    }
  }

  // ==================== Concurrent Operations Tests ====================

  test("MemStorage - handles concurrent writes to same key") {
    val storage = MemStorage(None)
    val threads = (1 to 10).map { i =>
      new Thread(() => {
        for (_ <- 1 to 100) {
          storage.write(
            "concurrent-key",
            {
              case Some(existing) =>
                val meta = existing.bodyAsItemMetaData.get
                Some(
                  existing.copy(body =
                    Some(meta.copy(fileSize = meta.fileSize + 1))
                  )
                )
              case None => Some(createTestItem("concurrent-key"))
            },
            _ => ""
          )
        }
      })
    }

    threads.foreach(_.start())
    threads.foreach(_.join())

    val result = storage.read("concurrent-key")
    assert(result.isDefined)
  }

  test("MemStorage - handles concurrent writes to different keys") {
    val storage = MemStorage(None)
    val threads = (1 to 10).map { i =>
      new Thread(() => {
        for (j <- 1 to 100) {
          val key = s"key-$i-$j"
          storage.write(key, _ => Some(createTestItem(key)), _ => "")
        }
      })
    }

    threads.foreach(_.start())
    threads.foreach(_.join())

    assertEquals(storage.size(), 1000)
  }

  test("MemStorage - handles concurrent read and write") {
    val storage = MemStorage(None)
    storage.write("rw-key", _ => Some(createTestItem("rw-key")), _ => "")

    val writeThread = new Thread(() => {
      for (_ <- 1 to 100) {
        storage.write(
          "rw-key",
          {
            case Some(existing) => Some(existing)
            case None           => Some(createTestItem("rw-key"))
          },
          _ => ""
        )
      }
    })

    val readThread = new Thread(() => {
      for (_ <- 1 to 100) {
        storage.read("rw-key")
      }
    })

    writeThread.start()
    readThread.start()
    writeThread.join()
    readThread.join()

    assert(storage.exists("rw-key"))
  }

  // ==================== Factory Method Tests ====================

  test("Storage.getStorage - returns MemStorage with None") {
    val storage = Storage.getStorage(None)
    assert(storage.isInstanceOf[MemStorage])
  }

  test("Storage.getStorage - returns MemStorage with directory") {
    val tempDir = Files.createTempDirectory("getstoragetest").toFile()
    try {
      val storage = Storage.getStorage(Some(tempDir))
      assert(storage.isInstanceOf[MemStorage])
      assertEquals(storage.destDirectory(), Some(tempDir))
    } finally {
      tempDir.delete()
    }
  }

  test("MemStorage.getStorage - returns MemStorage") {
    val storage = MemStorage.getStorage(None)
    assert(storage.isInstanceOf[MemStorage])
  }

  // ==================== Emit Methods Tests ====================

  test("MemStorage - emitRootsToDir creates file with roots") {
    val tempDir = Files.createTempDirectory("emitroots").toFile()
    try {
      val storage = MemStorage(None)
      // Create a root item (no aliasTo or containedBy edges)
      val rootItem = Item(
        "gitoid:blob:sha256:root123",
        TreeSet(),
        Some(ItemMetaData.mimeType),
        Some(ItemMetaData(TreeSet(), TreeSet(), 100, TreeMap()))
      )
      storage.write("gitoid:blob:sha256:root123", _ => Some(rootItem), _ => "")

      storage.emitRootsToDir(tempDir)

      val files = tempDir.listFiles()
      assert(files.length > 0)
      assert(files.exists(_.getName().startsWith("roots_")))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("MemStorage - emitAllItemsToDir creates file with all items") {
    val tempDir = Files.createTempDirectory("emititems").toFile()
    try {
      val storage = MemStorage(None)
      storage.write("item1", _ => Some(createTestItem("item1")), _ => "")
      storage.write("item2", _ => Some(createTestItem("item2")), _ => "")

      storage.emitAllItemsToDir(tempDir)

      val files = tempDir.listFiles()
      assert(files.length > 0)
      assert(files.exists(_.getName().startsWith("items_")))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  // ==================== Edge Cases ====================

  test("MemStorage - handles empty storage operations") {
    val storage = MemStorage(None)
    assertEquals(storage.size(), 0)
    assertEquals(storage.keys().size, 0)
    assertEquals(storage.gitoidKeys().size, 0)
    assertEquals(storage.purls().size, 0)
    assertEquals(storage.sortedPaths().size, 0)
  }

  test("MemStorage - handles special characters in keys") {
    val storage = MemStorage(None)
    val specialKey = "key/with:special@chars#and?query=param"
    storage.write(specialKey, _ => Some(createTestItem(specialKey)), _ => "")
    assert(storage.exists(specialKey))
    assert(storage.read(specialKey).isDefined)
  }

  test("MemStorage - handles very long keys") {
    val storage = MemStorage(None)
    val longKey = "a" * 10000
    storage.write(longKey, _ => Some(createTestItem(longKey)), _ => "")
    assert(storage.exists(longKey))
  }
}
