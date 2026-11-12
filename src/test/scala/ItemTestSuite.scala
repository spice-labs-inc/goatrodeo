import io.spicelabs.goatrodeo.omnibor.TestEdges
import io.bullet.borer.Cbor
import io.bullet.borer.Dom
import io.bullet.borer.Dom.ArrayElem
import io.bullet.borer.Dom.MapElem
import io.bullet.borer.Dom.StringElem
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.PURLHelpers

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class ItemTestSuite extends GoatRodeoFunSuite {

  def createBasicItem(id: String): Item = {
    Item(
      id,
      TreeMap(),
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

  def createItemWithConnections(
      id: String,
      connections: TreeMap[String, TreeSet[String]]
  ): Item = {
    Item(
      id,
      connections,
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

  // ==================== CBOR Serialization Tests ====================

  test("Item.decode - map with wrong key names fails as a value") {
    val wrong = MapElem.Sized(
      StringElem("rubbish") -> Dom.NullElem,
      StringElem("body_mime_type") -> Dom.NullElem,
      StringElem("connections") -> ArrayElem.Sized(),
      StringElem("identifier") -> StringElem("x")
    )
    val bytes = Cbor.encode(wrong).toByteArray
    val result = Item.decode(bytes)
    assert(
      result.isFailure,
      "a map without the expected keys must decode to Failure, not throw"
    )
  }

  test("Item.decode - reordered map fails as a value") {
    // The CBOR form is positional with order validated by name in `decode`;
    // a reordered map is rejected as a Failure, matching the strictness of
    // the historical decoder while remaining throw-free.
    val reordered = MapElem.Sized(
      StringElem("identifier") -> StringElem("x"),
      StringElem("connections") -> ArrayElem.Sized(),
      StringElem("body_mime_type") -> StringElem(ItemMetaData.mimeType),
      StringElem("body") -> Dom.NullElem
    )
    val bytes = Cbor.encode(reordered).toByteArray
    val result = Item.decode(bytes)
    assert(result.isFailure, s"reordered map must decode to Failure: $result")
  }

  test("Item.decode - junk bytes fail as a value") {
    val result = Item.decode("this is not cbor".getBytes("UTF-8"))
    assert(result.isFailure, "junk bytes must decode to Failure, not throw")
  }

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
    val connections = TestEdges.edges(
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
    assert(item.edges.nonEmpty)
    assert(item.edges.exists(_._1 == EdgeType.aliasFrom))
  }

  test("Item.itemFrom - includes container if provided") {
    val data = "test content".getBytes("UTF-8")
    val wrapper = ByteWrapper(data, "test.txt", None)
    val container = Some("gitoid:blob:sha256:parent123")
    val item = Item.itemFrom(wrapper, container)

    assert(
      item.edges.exists( e =>
        e._1 == EdgeType.containedBy && e._2 == container.get
      )
    )
  }

  test("Item.itemFrom - includes all hash aliases") {
    val data = "test".getBytes("UTF-8")
    val wrapper = ByteWrapper(data, "test.txt", None)
    val item = Item.itemFrom(wrapper, None)

    val aliases = item.edges.filter(_._1 == EdgeType.aliasFrom).map(_._2)
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

    assert(updated.targetsOf(EdgeType.aliasFrom).contains("sha256:newhash"))
  }

  test("Item.withConnection - preserves existing connections") {
    val connections = TreeMap(EdgeType.aliasFrom -> TreeSet("existing"))
    val item = createItemWithConnections("test-id", connections)
    val updated = item.withConnection(EdgeType.containedBy, "parent")

    assert(updated.targetsOf(EdgeType.aliasFrom).contains("existing"))
    assert(updated.targetsOf(EdgeType.containedBy).contains("parent"))
  }

  // ==================== isRoot Tests ====================

  test("Item.isRoot - returns true for root item") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")
    assert(item.isRoot())
  }

  test("Item.isRoot - returns false for item with aliasTo") {
    val connections = TreeMap(EdgeType.aliasTo -> TreeSet("gitoid:blob:sha256:target"))
    val item = createItemWithConnections("sha256:abc123", connections)
    assert(!item.isRoot())
  }

  test("Item.isRoot - returns false for item with containedBy") {
    val connections =
      TreeMap(EdgeType.containedBy -> TreeSet("gitoid:blob:sha256:parent"))
    val item =
      createItemWithConnections("gitoid:blob:sha256:abc123", connections)
    assert(!item.isRoot())
  }

  test("Item.isRoot - returns false for tags identifier") {
    val item = Item(
      "tags",
      TreeMap(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet(), TreeSet(), 0, TreeMap()))
    )
    assert(!item.isRoot())
  }

  test("Item.isRoot - returns false for non-metadata body type") {
    val item = Item("test-id", TreeMap(), None, None)
    assert(!item.isRoot())
  }

  // ==================== buildListOfReferencesForAliasFromBuiltFromContainedBy Tests ====================

  test("buildListOfReferences - returns aliasTo for aliasFrom") {
    val connections = TreeMap(EdgeType.aliasFrom -> TreeSet("sha256:abc123"))
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assert(refs.contains(EdgeType.aliasTo -> "sha256:abc123"))
  }

  test("buildListOfReferences - returns buildsTo for builtFrom") {
    val connections = TreeMap(EdgeType.builtFrom -> TreeSet("gitoid:blob:sha256:source"))
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assert(refs.contains(EdgeType.buildsTo -> "gitoid:blob:sha256:source"))
  }

  test("buildListOfReferences - returns contains for containedBy") {
    val connections =
      TreeMap(EdgeType.containedBy -> TreeSet("gitoid:blob:sha256:parent"))
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assert(refs.contains(EdgeType.contains -> "gitoid:blob:sha256:parent"))
  }

  test("buildListOfReferences - returns tagTo for tagFrom") {
    val connections = TreeMap(EdgeType.tagFrom -> TreeSet("gitoid:blob:sha256:tag"))
    val item = createItemWithConnections("gitoid:blob:sha256:main", connections)

    val refs = item.buildListOfReferencesForAliasFromBuiltFromContainedBy()
    assert(refs.contains(EdgeType.tagTo -> "gitoid:blob:sha256:tag"))
  }

  test("buildListOfReferences - handles multiple connections") {
    val connections = TestEdges.edges(
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
    val item1 =
      createItemWithConnections("id", TreeMap(EdgeType.aliasFrom -> TreeSet("a")))
    val item2 =
      createItemWithConnections("id", TreeMap(EdgeType.aliasFrom -> TreeSet("b")))

    val merged = item1.merge(item2)
    assert(merged.targetsOf(EdgeType.aliasFrom).contains("a"))
    assert(merged.targetsOf(EdgeType.aliasFrom).contains("b"))
  }

  test("Item.merge - merges metadata bodies") {
    val item1 = Item(
      "id",
      TreeMap(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file1"), TreeSet("mime1"), 100, TreeMap()))
    )
    val item2 = Item(
      "id",
      TreeMap(),
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
    val item2 = Item("id", TreeMap(), None, None)

    val merged = item1.merge(item2)
    assert(merged.bodyAsItemMetaData.isDefined)
  }

  test("Item.merge - preserves extra metadata") {
    val extra1 = TreeMap("key1" -> TreeSet(StringOrPair("val1")))
    val extra2 = TreeMap("key2" -> TreeSet(StringOrPair("val2")))
    // ItemMetaData.merge requires non-empty fileNames
    val item1 = Item(
      "id",
      TreeMap(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file.txt"), TreeSet(), 100, extra1))
    )
    val item2 = Item(
      "id",
      TreeMap(),
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
    val purl = PURLHelpers.purl(
      `type` = "deb",
      name = "artifact",
      namespace = Some("debian"),
      version = Some("1.0")
    )

    val enhanced = item.enhanceItemWithPurls(Seq(purl.toCanonical()))
    val purlConnections = enhanced.edges.filter(_._2.startsWith("pkg:"))
    assert(purlConnections.nonEmpty)
  }

  test("enhanceItemWithPurls - adds purl to filenames") {
    val item = createBasicItem("gitoid:blob:sha256:abc123")
    val purl = PURLHelpers.purl(
      `type` = "deb",
      name = "artifact",
      namespace = Some("debian"),
      version = Some("1.0")
    )

    val enhanced = item.enhanceItemWithPurls(Seq(purl.toCanonical()))
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
    val purl1 = PURLHelpers.purl(
      `type` = "deb",
      name = "artifact1",
      namespace = Some("ubuntu"),
      version = Some("1.0")
    )
    val purl2 = PURLHelpers.purl(
      `type` = "npm",
      name = "package",
      version = Some("2.0")
    )

    val enhanced =
      item.enhanceItemWithPurls(
        Seq(purl1.toCanonical(), purl2.toCanonical())
      )
    val purlConnections = enhanced.edges.filter(_._2.startsWith("pkg:"))
    assertEquals(purlConnections.size, 2)
  }

  test("enhanceItemWithPurls - creates body if none exists") {
    val item = Item("id", TreeMap(), None, None)
    val purl = PURLHelpers.purl(
      `type` = "deb",
      name = "artifact",
      namespace = Some("debian"),
      version = Some("1.0")
    )

    val enhanced = item.enhanceItemWithPurls(Seq(purl.toCanonical()))
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
    val item = Item("id", TreeMap(), None, None)

    val enhanced = item.enhanceWithMetadata(filenames = Seq("file.txt"))
    assert(enhanced.bodyAsItemMetaData.isDefined)
    assertEquals(enhanced.bodyMimeType, Some(ItemMetaData.mimeType))
  }

  test("enhanceWithMetadata - includes parent gitoid for duplicate filenames") {
    val item = Item(
      "id",
      TreeMap(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("existing.txt"), TreeSet(), 100, TreeMap()))
    )

    val parent = Some("gitoid:blob:sha256:parent123")
    val enhanced = item.enhanceWithMetadata(
      maybeParent = parent,
      filenames = Seq("newfile.txt")
    )
    val fileNames = enhanced.bodyAsItemMetaData.get.fileNames
    // Should include parent-qualified filename
    assert(fileNames.exists(_.contains("parent123")))
  }

  // ==================== itemsToFilenameGitOIDMap Tests ====================

  test("itemsToFilenameGitOIDMap - creates filename to gitoid map") {
    val item1 = Item(
      "gitoid:blob:sha256:abc123",
      TreeMap(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(TreeSet("file1.txt"), TreeSet(), 100, TreeMap()))
    )
    val item2 = Item(
      "gitoid:blob:sha256:def456",
      TreeMap(),
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
      TreeMap(),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          TreeSet[String]("include.java", "exclude.txt"),
          TreeSet[String]("text/plain"),
          100,
          TreeMap[String, TreeSet[StringOrPair]]()
        )
      )
    )

    val map = Item.itemsToFilenameGitOIDMap(Seq(item), _.endsWith(".java"))
    assert(map.contains("include.java"))
    assert(!map.contains("exclude.txt"))
  }

  test("itemsToFilenameGitOIDMap - applies mime filter") {
    val item1 = Item(
      "gitoid:blob:sha256:abc123",
      TreeMap(),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          TreeSet("file.java"),
          TreeSet("text/x-java-source"),
          100,
          TreeMap()
        )
      )
    )
    val item2 = Item(
      "gitoid:blob:sha256:def456",
      TreeMap(),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(TreeSet("file.txt"), TreeSet("text/plain"), 100, TreeMap())
      )
    )

    val map = Item.itemsToFilenameGitOIDMap(
      Seq(item1, item2),
      mimeFilter = _.contains("text/x-java-source")
    )
    assert(map.contains("file.java"))
    assert(!map.contains("file.txt"))
  }

  test("itemsToFilenameGitOIDMap - handles items without body") {
    val item = Item("id", TreeMap(), None, None)
    val map = Item.itemsToFilenameGitOIDMap(Seq(item))
    assert(map.isEmpty)
  }

  // ==================== Utilities Tests ====================

  test("Item.bodyAsItemMetaData - returns Some for ItemMetaData body") {
    val item = createBasicItem("id")
    assert(item.bodyAsItemMetaData.isDefined)
  }

  test("Item.bodyAsItemMetaData - returns None for no body") {
    val item = Item("id", TreeMap(), None, None)
    assertEquals(item.bodyAsItemMetaData, None)
  }

  test("Item index key - is 16 bytes and stable for an identifier") {
    val item = createBasicItem("test-identifier")
    val key = io.spicelabs.goatrodeo.envelopes.Blake3IndexKey.key(item.identifier)
    assertEquals(key.length, 16)
    assertEquals(
      key.toVector,
      io.spicelabs.goatrodeo.envelopes.Blake3IndexKey.key("test-identifier").toVector
    )
  }

  test("Item.cmpKey - orders items by their index key") {
    val item1 = createBasicItem("aaa")
    val item2 = createBasicItem("zzz")

    // Exactly one of the two orders holds
    assert(item1.cmpKey(item2) != item2.cmpKey(item1))
  }

  test("Item.listContains - returns contained items") {
    val connections = TestEdges.edges(
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
