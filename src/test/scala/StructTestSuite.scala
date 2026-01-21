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

import io.bullet.borer.Cbor
import io.bullet.borer.Dom
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.ItemTagData
import io.spicelabs.goatrodeo.omnibor.PairOf
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class StructTestSuite extends munit.FunSuite {

  // ==================== EdgeType Predicate Tests ====================

  test("EdgeType.isAliasFrom - returns true for aliasFrom") {
    assert(EdgeType.isAliasFrom(EdgeType.aliasFrom))
  }

  test("EdgeType.isAliasFrom - returns false for aliasTo") {
    assert(!EdgeType.isAliasFrom(EdgeType.aliasTo))
  }

  test("EdgeType.isAliasTo - returns true for aliasTo") {
    assert(EdgeType.isAliasTo(EdgeType.aliasTo))
  }

  test("EdgeType.isAliasTo - returns false for aliasFrom") {
    assert(!EdgeType.isAliasTo(EdgeType.aliasFrom))
  }

  test("EdgeType.isContains - returns true for contains") {
    assert(EdgeType.isContains(EdgeType.contains))
  }

  test("EdgeType.isContains - returns false for containedBy") {
    assert(!EdgeType.isContains(EdgeType.containedBy))
  }

  test("EdgeType.isContainedByUp - returns true for containedBy") {
    assert(EdgeType.isContainedByUp(EdgeType.containedBy))
  }

  test("EdgeType.isContainedByUp - returns false for contains") {
    assert(!EdgeType.isContainedByUp(EdgeType.contains))
  }

  test("EdgeType.isBuiltFrom - returns true for builtFrom") {
    assert(EdgeType.isBuiltFrom(EdgeType.builtFrom))
  }

  test("EdgeType.isBuiltFrom - returns false for buildsTo") {
    assert(!EdgeType.isBuiltFrom(EdgeType.buildsTo))
  }

  test("EdgeType.isBuildsTo - returns true for buildsTo") {
    assert(EdgeType.isBuildsTo(EdgeType.buildsTo))
  }

  test("EdgeType.isBuildsTo - returns false for builtFrom") {
    assert(!EdgeType.isBuildsTo(EdgeType.builtFrom))
  }

  test("EdgeType.isDown - returns true for down edges") {
    assert(EdgeType.isDown(EdgeType.contains))
    assert(EdgeType.isDown(EdgeType.builtFrom))
  }

  test("EdgeType.isDown - returns false for up edges") {
    assert(!EdgeType.isDown(EdgeType.containedBy))
    assert(!EdgeType.isDown(EdgeType.buildsTo))
  }

  // ==================== EdgeType Constants Tests ====================

  test("EdgeType constants - have correct values") {
    assertEquals(EdgeType.to, ":to")
    assertEquals(EdgeType.from, ":from")
    assertEquals(EdgeType.down, ":down")
    assertEquals(EdgeType.up, ":up")
  }

  test("EdgeType constants - containedBy and contains are complementary") {
    assertEquals(EdgeType.containedBy, "contained:up")
    assertEquals(EdgeType.contains, "contained:down")
  }

  test("EdgeType constants - aliasTo and aliasFrom use :to/:from") {
    assertEquals(EdgeType.aliasTo, "alias:to")
    assertEquals(EdgeType.aliasFrom, "alias:from")
  }

  test("EdgeType constants - buildsTo and builtFrom use :up/:down") {
    assertEquals(EdgeType.buildsTo, "build:up")
    assertEquals(EdgeType.builtFrom, "build:down")
  }

  test("EdgeType constants - tag edges exist") {
    assertEquals(EdgeType.tagFrom, "tag:from")
    assertEquals(EdgeType.tagTo, "tag:to")
  }

  // ==================== StringOrPair Variants Tests ====================

  test("StringOf - value returns string") {
    val s = StringOf("test")
    assertEquals(s.value, "test")
  }

  test("StringOf - mimeType returns None") {
    val s = StringOf("test")
    assertEquals(s.mimeType, None)
  }

  test("PairOf - value returns second string") {
    val p = PairOf("mime/type", "content")
    assertEquals(p.value, "content")
  }

  test("PairOf - mimeType returns Some with first string") {
    val p = PairOf("mime/type", "content")
    assertEquals(p.mimeType, Some("mime/type"))
  }

  test("StringOrPair.apply(String) - creates StringOf") {
    val result: StringOrPair = StringOrPair("test")
    assert(result.isInstanceOf[StringOf])
  }

  test("StringOrPair.apply(String, String) - creates PairOf") {
    val result: StringOrPair = StringOrPair("mime", "content")
    assert(result.isInstanceOf[PairOf])
  }

  test("StringOrPair.apply((String, String)) - creates PairOf from tuple") {
    val result: StringOrPair = StringOrPair("mime" -> "content")
    assert(result.isInstanceOf[PairOf])
    assertEquals(result.value, "content")
    assertEquals(result.mimeType, Some("mime"))
  }

  // ==================== StringOrPair CBOR Tests ====================

  test("StringOf - CBOR round-trip") {
    val original = StringOf("test value")
    val bytes = Cbor.encode(original: StringOrPair).toByteArray
    val decoded = Cbor.decode(bytes).to[StringOrPair].value
    assertEquals(decoded, original)
  }

  test("PairOf - CBOR round-trip") {
    val original = PairOf("text/plain", "content")
    val bytes = Cbor.encode(original: StringOrPair).toByteArray
    val decoded = Cbor.decode(bytes).to[StringOrPair].value
    assert(decoded.isInstanceOf[PairOf])
    assertEquals(decoded.value, "content")
    assertEquals(decoded.mimeType, Some("text/plain"))
  }

  test("StringOrPair - ordering is correct") {
    val items = TreeSet(
      StringOrPair("zebra"),
      StringOrPair("alpha"),
      StringOrPair("beta")
    )
    assertEquals(items.toVector.map(_.value), Vector("alpha", "beta", "zebra"))
  }

  test("StringOrPair - implicit from string") {
    val s: StringOrPair = "test"
    assertEquals(s.value, "test")
  }

  test("StringOrPair - implicit from tuple") {
    val p: StringOrPair = ("mime", "content")
    assertEquals(p.value, "content")
  }

  // ==================== ItemMetaData Tests ====================

  test("ItemMetaData.mimeType constant - is correct") {
    assertEquals(ItemMetaData.mimeType, "application/vnd.cc.goatrodeo")
  }

  test("ItemMetaData - CBOR round-trip") {
    val original = ItemMetaData(
      fileNames = TreeSet("file1.txt", "file2.txt"),
      mimeType = TreeSet("text/plain"),
      fileSize = 1234,
      extra = TreeMap("key" -> TreeSet(StringOrPair("value")))
    )
    val bytes = original.encodeCBOR()
    val decoded = Cbor.decode(bytes).to[ItemMetaData].value

    assertEquals(decoded.fileNames, original.fileNames)
    assertEquals(decoded.mimeType, original.mimeType)
    assertEquals(decoded.fileSize, original.fileSize)
    assertEquals(decoded.extra.size, original.extra.size)
  }

  test("ItemMetaData.merge - merges filenames") {
    val a = ItemMetaData(TreeSet("file1"), TreeSet(), 100, TreeMap())
    val b = ItemMetaData(TreeSet("file2"), TreeSet(), 100, TreeMap())

    val merged = a.merge(b, () => Vector(), () => Vector())
    assert(merged.fileNames.contains("file1"))
    assert(merged.fileNames.contains("file2"))
  }

  test("ItemMetaData.merge - merges mimeTypes") {
    // Must have non-empty fileNames for merge to work
    val a = ItemMetaData(TreeSet("file.txt"), TreeSet("mime1"), 100, TreeMap())
    val b = ItemMetaData(TreeSet("file.txt"), TreeSet("mime2"), 100, TreeMap())

    val merged = a.merge(b, () => Vector(), () => Vector())
    assert(merged.mimeType.contains("mime1"))
    assert(merged.mimeType.contains("mime2"))
  }

  test("ItemMetaData.merge - preserves fileSize from first") {
    // Must have non-empty fileNames for merge to work
    val a = ItemMetaData(TreeSet("file.txt"), TreeSet(), 100, TreeMap())
    val b = ItemMetaData(TreeSet("file.txt"), TreeSet(), 200, TreeMap())

    val merged = a.merge(b, () => Vector(), () => Vector())
    assertEquals(merged.fileSize, 100L)
  }

  test("ItemMetaData.merge - merges extra") {
    // Must have non-empty fileNames for merge to work
    val a = ItemMetaData(
      TreeSet("file.txt"),
      TreeSet(),
      100,
      TreeMap("key1" -> TreeSet(StringOrPair("val1")))
    )
    val b = ItemMetaData(
      TreeSet("file.txt"),
      TreeSet(),
      100,
      TreeMap("key2" -> TreeSet(StringOrPair("val2")))
    )

    val merged = a.merge(b, () => Vector(), () => Vector())
    assert(merged.extra.contains("key1"))
    assert(merged.extra.contains("key2"))
  }

  test(
    "ItemMetaData.merge - creates gitoid-qualified filenames for duplicates"
  ) {
    val a = ItemMetaData(TreeSet("file.txt"), TreeSet(), 100, TreeMap())
    val b = ItemMetaData(TreeSet("other.txt"), TreeSet(), 100, TreeMap())

    val aContains = () => Vector("gitoid1", "gitoid2")
    val bContains = () => Vector("gitoid3", "gitoid4")

    val merged = a.merge(b, aContains, bContains)
    // When there are multiple different filenames, they should be gitoid-qualified
    assert(merged.fileNames.size > 2)
  }

  test("ItemMetaData.merge - single filename stays simple") {
    val a = ItemMetaData(TreeSet("same.txt"), TreeSet(), 100, TreeMap())
    val b = ItemMetaData(TreeSet("same.txt"), TreeSet(), 100, TreeMap())

    val merged = a.merge(b, () => Vector(), () => Vector())
    assertEquals(merged.fileNames.size, 1)
    assertEquals(merged.fileNames.head, "same.txt")
  }

  // ==================== ItemTagData Tests ====================

  test("ItemTagData.mimeType constant - is correct") {
    assertEquals(ItemTagData.mimeType, "application/vnd.cc.goatrodeo.tag")
  }

  test("ItemTagData.merge - merges map elements") {
    val map1 = Dom.MapElem.Unsized(("key1", Dom.StringElem("val1")))
    val map2 = Dom.MapElem.Unsized(("key2", Dom.StringElem("val2")))

    val tag1 = ItemTagData(map1)
    val tag2 = ItemTagData(map2)

    val merged = tag1.merge(tag2)
    merged.tag match {
      case m: Dom.MapElem =>
        assertEquals(m.members.size, 2)
      case _ => fail("Expected MapElem")
    }
  }

  test("ItemTagData.merge - merges array elements") {
    val arr1 = Dom.ArrayElem.Unsized(Dom.StringElem("a"), Dom.StringElem("b"))
    val arr2 = Dom.ArrayElem.Unsized(Dom.StringElem("c"), Dom.StringElem("d"))

    val tag1 = ItemTagData(arr1)
    val tag2 = ItemTagData(arr2)

    val merged = tag1.merge(tag2)
    merged.tag match {
      case a: Dom.ArrayElem =>
        assertEquals(a.elems.size, 4)
      case _ => fail("Expected ArrayElem")
    }
  }

  test("ItemTagData.merge - array wins over non-array for second") {
    val arr = Dom.ArrayElem.Unsized(Dom.StringElem("a"))
    val str = Dom.StringElem("test")

    val tag1 = ItemTagData(arr)
    val tag2 = ItemTagData(str)

    val merged = tag1.merge(tag2)
    assert(merged.tag.isInstanceOf[Dom.ArrayElem])
  }

  test("ItemTagData.merge - first wins for non-collection types") {
    val str1 = Dom.StringElem("first")
    val str2 = Dom.StringElem("second")

    val tag1 = ItemTagData(str1)
    val tag2 = ItemTagData(str2)

    val merged = tag1.merge(tag2)
    assertEquals(merged.tag, str1)
  }

  // ==================== Edge Type Alias Tests ====================

  test("Edge type - is tuple alias") {
    val edge: (String, String) = EdgeType.aliasFrom -> "sha256:abc"
    assertEquals(edge._1, EdgeType.aliasFrom)
    assertEquals(edge._2, "sha256:abc")
  }

  // ==================== LocationReference Tests ====================

  test("LocationReference - is tuple alias") {
    import io.spicelabs.goatrodeo.omnibor.LocationReference
    val loc: LocationReference = (100L, 200L)
    assertEquals(loc._1, 100L)
    assertEquals(loc._2, 200L)
  }

  // ==================== IndexLoc Tests ====================

  test("IndexLoc.Loc - contains offset and fileHash") {
    import io.spicelabs.goatrodeo.omnibor.IndexLoc
    val loc = IndexLoc.Loc(100L, 200L)
    loc match {
      case IndexLoc.Loc(offset, fileHash) =>
        assertEquals(offset, 100L)
        assertEquals(fileHash, 200L)
      case _ => fail("Expected IndexLoc.Loc")
    }
  }

  test("IndexLoc.Chain - contains vector of IndexLocs") {
    import io.spicelabs.goatrodeo.omnibor.IndexLoc
    val chainLoc = IndexLoc.Chain(
      Vector(
        IndexLoc.Loc(1L, 2L),
        IndexLoc.Loc(3L, 4L)
      )
    )
    chainLoc match {
      case IndexLoc.Chain(chain) => assertEquals(chain.length, 2)
      case _                     => fail("Expected IndexLoc.Chain")
    }
  }

  // ==================== ItemOffset Tests ====================

  test("ItemOffset - contains hash and loc") {
    import io.spicelabs.goatrodeo.omnibor.IndexLoc
    import io.spicelabs.goatrodeo.omnibor.ItemOffset
    val offset = ItemOffset(
      hashHi = 0x123456789abcdef0L,
      hashLow = 0x0fedcba987654321L,
      loc = IndexLoc.Loc(100L, 200L)
    )
    assertEquals(offset.hashHi, 0x123456789abcdef0L)
    assertEquals(offset.hashLow, 0x0fedcba987654321L)
  }
}
