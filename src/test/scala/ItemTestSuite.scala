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
import io.bullet.borer.Cbor
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class ItemTestSuite extends munit.FunSuite {

  def createBasicItem(id: String): Item = {
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

  def createItemWithConnections(id: String, connections: TreeSet[(String, String)]): Item = {
    Item(
      id,
      connections,
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(
        fileNames = TreeSet(id),
        mimeType = TreeSet("application/octet-stream"),
        fileSize = 100,
        extra = TreeMap()
      ))
    )
  }

  // ==================== CBOR Serialization Tests ====================

  test("Item - encodeCBOR produces bytes") {
    val item = createBasicItem("test-id")
    val bytes = item.encodeCBOR()
    assert(bytes.length > 0)
  }

  test("Item.decode - decodes encoded item") {
    val item = createBasicItem("test-id")
    val bytes = item.encodeCBOR()
    val decoded = Item.decode(bytes)
    assert(decoded.isSuccess)
    assertEquals(decoded.get.identifier, "test-id")
  }

  test("Item - round-trip CBOR preserves identifier") {
    val item = createBasicItem("round-trip-id")
    val bytes = item.encodeCBOR()
    val decoded = Item.decode(bytes).get
    assertEquals(decoded.identifier, item.identifier)
  }

  test("Item - round-trip CBOR preserves connections") {
    val connections = TreeSet(
      EdgeType.aliasFrom -> "sha256:abc123",
      EdgeType.containedBy -> "gitoid:blob:sha256:parent"
    )
    val item = createItemWithConnections("test-id", connections)
    val bytes = item.encodeCBOR()
    val decoded = Item.decode(bytes).get
    assertEquals(decoded.connections, connections)
  }

  test("Item - round-trip CBOR preserves body") {
    val item = createBasicItem("body-test")
    val bytes = item.encodeCBOR()
    val decoded = Item.decode(bytes).get
    assert(decoded.bodyAsItemMetaData.isDefined)
    assertEquals(decoded.bodyAsItemMetaData.get.fileSize, 100L)
  }

  test("Item.decode - handles invalid bytes") {
    val invalidBytes = Array[Byte](0, 1, 2, 3)
    val decoded = Item.decode(invalidBytes)
    assert(decoded.isFailure)
  }

  // ==================== Construction Tests ====================

  test("Item.itemFrom - creates item from artifact") {
    val data = "test content".getBytes("UTF-8")
    val wrapper = ByteWrapper(data, "test.txt", None)
    val item = Item.itemFrom(wrapper, None)

    assert(item.identifier.startsWith("gitoid:blob:sha256:"))
    assert(item.connections.nonEmpty)
    assert(item.connections.exists(_._1 == EdgeType.aliasFrom))
  }

  test("Item.itemFrom - includes container if provided") {
    val data = "test content".getBytes("UTF-8")
    val wrapper = ByteWrapper(data, "test.txt", None)
    val container = Some("gitoid:blob:sha256:parent123")
    val item = Item.itemFrom(wrapper, container)

    assert(item.connections.exists(e => e._1 == EdgeType.containedBy && e._2 == container.get))
  }

  test("Item.itemFrom - includes all hash aliases") {
    val data = "test".getBytes("UTF-8")
    val wrapper = ByteWrapper(data, "test.txt", None)
    val item = Item.itemFrom(wrapper, None)

    val aliases = item.connections.filter(_._1 == EdgeType.aliasFrom).map(_._2)
    assert(aliases.exists(_.startsWith("gitoid:blob:sha1:")))
    assert(aliases.exists(_.startsWith("sha1:")))
    assert(aliases.exists(_.startsWith("sha256:")))
    assert(aliases.exists(_.startsWith("sha512:")))
    assert(aliases.exists(_.startsWith("md5:")))
  }

  // ==================== withConnection Tests ====================

  test("Item.withConnection - adds new connection") {
    val item = createBasicItem("test-id")
    val updated = item.withConnection(EdgeType.aliasFrom, "sha256:newhash")

    assert(updated.connections.contains(EdgeType.aliasFrom -> "sha256:newhash"))
  }

  test("Item.withConnection - preserves existing connections") {
    val connections = TreeSet(EdgeType.aliasFrom -> "existing")
    val item = createItemWithConnections("test-id", connections)
    val updated = item.withConnection(EdgeType.containedBy, "parent")

    assert(updated.connections.contains(EdgeType.aliasFrom -> "existing"))
    assert(updated.connections.contains(EdgeType.containedBy -> "parent"))
  }

  // ==================== isRoot Tests ====================

  test("Item.isRoot - returns true for root item") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")
    assert(item.isRoot())
  }

  test("Item.isRoot - returns false for item with aliasTo") {
    val connections = TreeSet(EdgeType.aliasTo -> "gitoid:blob:sha256:target")
    val item = createItemWithConnections("sha256:abc123", connections)
    assert(!item.isRoot())
  }

  test("Item.isRoot - returns false for item with containedBy") {
    val connections = TreeSet(EdgeType.containedBy -> "gitoid:blob:sha256:parent")
    val item = createItemWithConnections("gitoid:blob:sha256:abc123", connections)
    assert(!item.isRoot())
  }

  test("Item.isRoot - returns false for tags identifier") {
    val item = Item(
      "tags",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet(), TreeSet(), 0, TreeMap()))
    )
    assert(!item.isRoot())
  }

  test("Item.isRoot - returns false for non-metadata body type") {
    val item = Item("test-id", TreeSet(), None, None)
    assert(!item.isRoot())
  }

  // ==================== buildListOfReferencesForAliasFromBuiltFromContainedBy Tests ====================

  test("buildListOfReferences - returns aliasTo for aliasFrom") {
    val connections = TreeSet(EdgeType.aliasFrom -> "sha256:abc123")
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assert(refs.contains(EdgeType.aliasTo -> "sha256:abc123"))
  }

  test("buildListOfReferences - returns buildsTo for builtFrom") {
    val connections = TreeSet(EdgeType.builtFrom -> "gitoid:blob:sha256:source")
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assert(refs.contains(EdgeType.buildsTo -> "gitoid:blob:sha256:source"))
  }

  test("buildListOfReferences - returns contains for containedBy") {
    val connections = TreeSet(EdgeType.containedBy -> "gitoid:blob:sha256:parent")
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assert(refs.contains(EdgeType.contains -> "gitoid:blob:sha256:parent"))
  }

  test("buildListOfReferences - returns tagTo for tagFrom") {
    val connections = TreeSet(EdgeType.tagFrom -> "gitoid:blob:sha256:tag")
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assert(refs.contains(EdgeType.tagTo -> "gitoid:blob:sha256:tag"))
  }

  test("buildListOfReferences - handles multiple connections") {
    val connections = TreeSet(
      EdgeType.aliasFrom -> "sha256:abc",
      EdgeType.aliasFrom -> "sha1:def",
      EdgeType.containedBy -> "parent"
    )
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assertEquals(refs.length, 3)
  }

  // ==================== merge Tests ====================

  test("Item.merge - merges connections") {
    val item1 = createItemWithConnections("id", TreeSet(EdgeType.aliasFrom -> "a"))
    val item2 = createItemWithConnections("id", TreeSet(EdgeType.aliasFrom -> "b"))

    val merged = item1.merge(item2)
    assert(merged.connections.contains(EdgeType.aliasFrom -> "a"))
    assert(merged.connections.contains(EdgeType.aliasFrom -> "b"))
  }

  test("Item.merge - merges metadata bodies") {
    val item1 = Item(
      "id",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file1"), TreeSet("mime1"), 100, TreeMap()))
    )
    val item2 = Item(
      "id",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file2"), TreeSet("mime2"), 100, TreeMap()))
    )

    val merged = item1.merge(item2)
    val body = merged.bodyAsItemMetaData.get
    assert(body.mimeType.contains("mime1"))
    assert(body.mimeType.contains("mime2"))
  }

  test("Item.merge - preserves identifier from this") {
    val item1 = createBasicItem("id-1")
    val item2 = createBasicItem("id-2")

    val merged = item1.merge(item2)
    assertEquals(merged.identifier, "id-1")
  }

  test("Item.merge - handles None body") {
    val item1 = createBasicItem("id")
    val item2 = Item("id", TreeSet(), None, None)

    val merged = item1.merge(item2)
    assert(merged.bodyAsItemMetaData.isDefined)
  }

  test("Item.merge - preserves extra metadata") {
    val extra1 = TreeMap("key1" -> TreeSet(StringOrPair("val1")))
    val extra2 = TreeMap("key2" -> TreeSet(StringOrPair("val2")))
    // ItemMetaData.merge requires non-empty fileNames
    val item1 = Item(
      "id",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file.txt"), TreeSet(), 100, extra1))
    )
    val item2 = Item(
      "id",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file.txt"), TreeSet(), 100, extra2))
    )

    val merged = item1.merge(item2)
    val body = merged.bodyAsItemMetaData.get
    assert(body.extra.contains("key1"))
    assert(body.extra.contains("key2"))
  }

  // ==================== enhanceItemWithPurls Tests ====================

  test("enhanceItemWithPurls - adds purl connections") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")
    val purl = PackageURLBuilder.aPackageURL()
      .withType("deb")
      .withNamespace("debian")
      .withName("artifact")
      .withVersion("1.0")
      .build()

    val enhanced = item.enhanceItemWithPurls(Seq(purl))
    val purlConnections = enhanced.connections.filter(_._2.startsWith("pkg:"))
    assert(purlConnections.nonEmpty)
  }

  test("enhanceItemWithPurls - adds purl to filenames") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")
    val purl = PackageURLBuilder.aPackageURL()
      .withType("deb")
      .withNamespace("debian")
      .withName("artifact")
      .withVersion("1.0")
      .build()

    val enhanced = item.enhanceItemWithPurls(Seq(purl))
    val body = enhanced.bodyAsItemMetaData.get
    assert(body.fileNames.exists(_.startsWith("pkg:")))
  }

  test("enhanceItemWithPurls - handles empty purls") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")
    val enhanced = item.enhanceItemWithPurls(Seq())
    assertEquals(enhanced, item)
  }

  test("enhanceItemWithPurls - handles multiple purls") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")
    val purl1 = PackageURLBuilder.aPackageURL()
      .withType("deb")
      .withNamespace("ubuntu")
      .withName("artifact1")
      .withVersion("1.0")
      .build()
    val purl2 = PackageURLBuilder.aPackageURL()
      .withType("npm")
      .withName("package")
      .withVersion("2.0")
      .build()

    val enhanced = item.enhanceItemWithPurls(Seq(purl1, purl2))
    val purlConnections = enhanced.connections.filter(_._2.startsWith("pkg:"))
    assertEquals(purlConnections.size, 2)
  }

  test("enhanceItemWithPurls - creates body if none exists") {
    val item = Item("id", TreeSet(), None, None)
    val purl = PackageURLBuilder.aPackageURL()
      .withType("deb")
      .withNamespace("debian")
      .withName("artifact")
      .withVersion("1.0")
      .build()

    val enhanced = item.enhanceItemWithPurls(Seq(purl))
    assert(enhanced.bodyAsItemMetaData.isDefined)
  }

  // ==================== enhanceWithMetadata Tests ====================

  test("enhanceWithMetadata - adds extra metadata") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")
    val extra = TreeMap("key" -> TreeSet(StringOrPair("value")))

    val enhanced = item.enhanceWithMetadata(extra = extra)
    assert(enhanced.bodyAsItemMetaData.get.extra.contains("key"))
  }

  test("enhanceWithMetadata - adds filenames") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")

    val enhanced = item.enhanceWithMetadata(filenames = Seq("newfile.txt"))
    assert(enhanced.bodyAsItemMetaData.get.fileNames.contains("newfile.txt"))
  }

  test("enhanceWithMetadata - adds mime types") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")

    val enhanced = item.enhanceWithMetadata(mimeTypes = Seq("text/plain"))
    assert(enhanced.bodyAsItemMetaData.get.mimeType.contains("text/plain"))
  }

  test("enhanceWithMetadata - creates body if none exists") {
    val item = Item("id", TreeSet(), None, None)

    val enhanced = item.enhanceWithMetadata(filenames = Seq("file.txt"))
    assert(enhanced.bodyAsItemMetaData.isDefined)
    assertEquals(enhanced.bodyMimeType, Some(ItemMetaData.mimeType))
  }

  test("enhanceWithMetadata - includes parent gitoid for duplicate filenames") {
    val item = Item(
      "id",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("existing.txt"), TreeSet(), 100, TreeMap()))
    )

    val parent = Some("gitoid:blob:sha256:parent123")
    val enhanced = item.enhanceWithMetadata(maybeParent = parent, filenames = Seq("newfile.txt"))
    val fileNames = enhanced.bodyAsItemMetaData.get.fileNames
    // Should include parent-qualified filename
    assert(fileNames.exists(_.contains("parent123")))
  }

  // ==================== itemsToFilenameGitOIDMap Tests ====================

  test("itemsToFilenameGitOIDMap - creates filename to gitoid map") {
    val item1 = Item(
      "gitoid:blob:sha256:abc123",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file1.txt"), TreeSet(), 100, TreeMap()))
    )
    val item2 = Item(
      "gitoid:blob:sha256:def456",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file2.txt"), TreeSet(), 100, TreeMap()))
    )

    val map = Item.itemsToFilenameGitOIDMap(Seq(item1, item2))
    assertEquals(map.get("file1.txt"), Some("gitoid:blob:sha256:abc123"))
    assertEquals(map.get("file2.txt"), Some("gitoid:blob:sha256:def456"))
  }

  test("itemsToFilenameGitOIDMap - applies name filter") {
    val item = Item(
      "gitoid:blob:sha256:abc123",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("include.java", "exclude.txt"), TreeSet(), 100, TreeMap()))
    )

    val map = Item.itemsToFilenameGitOIDMap(Seq(item), _.endsWith(".java"))
    assert(map.contains("include.java"))
    assert(!map.contains("exclude.txt"))
  }

  test("itemsToFilenameGitOIDMap - applies mime filter") {
    val item1 = Item(
      "gitoid:blob:sha256:abc123",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file.java"), TreeSet("text/x-java-source"), 100, TreeMap()))
    )
    val item2 = Item(
      "gitoid:blob:sha256:def456",
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file.txt"), TreeSet("text/plain"), 100, TreeMap()))
    )

    val map = Item.itemsToFilenameGitOIDMap(
      Seq(item1, item2),
      mimeFilter = _.contains("text/x-java-source")
    )
    assert(map.contains("file.java"))
    assert(!map.contains("file.txt"))
  }

  test("itemsToFilenameGitOIDMap - handles items without body") {
    val item = Item("id", TreeSet(), None, None)
    val map = Item.itemsToFilenameGitOIDMap(Seq(item))
    assert(map.isEmpty)
  }

  // ==================== Utilities Tests ====================

  test("Item.bodyAsItemMetaData - returns Some for ItemMetaData body") {
    val item = createBasicItem("id")
    assert(item.bodyAsItemMetaData.isDefined)
  }

  test("Item.bodyAsItemMetaData - returns None for no body") {
    val item = Item("id", TreeSet(), None, None)
    assertEquals(item.bodyAsItemMetaData, None)
  }

  test("Item.identifierMD5 - returns MD5 of identifier") {
    val item = createBasicItem("test-identifier")
    val md5 = item.identifierMD5()
    assertEquals(md5.length, 16)
  }

  test("Item.cmpMd5 - compares items by MD5") {
    val item1 = createBasicItem("aaa")
    val item2 = createBasicItem("zzz")

    // One should be less than the other
    assert(item1.cmpMd5(item2) != item2.cmpMd5(item1))
  }

  test("Item.listContains - returns contained items") {
    val connections = TreeSet(
      EdgeType.contains -> "child1",
      EdgeType.contains -> "child2",
      EdgeType.aliasFrom -> "alias1"
    )
    val item = createItemWithConnections("parent", connections)

    val contains = item.listContains()
    assertEquals(contains.length, 2)
    assert(contains.contains("child1"))
    assert(contains.contains("child2"))
    assert(!contains.contains("alias1"))
  }

  test("Item - cachedCBOR returns same bytes each time") {
    val item = createBasicItem("id")
    val bytes1 = item.cachedCBOR
    val bytes2 = item.cachedCBOR
    assertEquals(bytes1.toSeq, bytes2.toSeq)
  }
}
