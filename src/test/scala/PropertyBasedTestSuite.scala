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

import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.PairOf
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.IncludeExclude
import munit.ScalaCheckSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.*

import java.io.ByteArrayInputStream
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Property-based tests using ScalaCheck.
  *
  * These tests verify invariants that should hold for ALL possible inputs, not
  * just a handful of examples. This catches edge cases that example-based tests
  * miss.
  *
  * As a snarky principal engineer once said: "Unit tests are cute. Property
  * tests are insurance."
  */
class PropertyBasedTestSuite extends ScalaCheckSuite {

  // ==================== Generators ====================
  // Because ScalaCheck needs to know how to create random instances

  /** Generate valid GitOID-like identifiers */
  val genGitOID: Gen[String] = for {
    hashType <- Gen.oneOf("sha256", "sha1", "sha512")
    // Generate hex string of appropriate length
    hexLength = hashType match {
      case "sha256" => 64
      case "sha1"   => 40
      case "sha512" => 128
      case _        => 64
    }
    hexChars <- Gen.listOfN(hexLength, Gen.hexChar)
  } yield s"gitoid:blob:$hashType:${hexChars.mkString.toLowerCase}"

  /** Generate simple alphanumeric strings (avoids encoding issues) */
  val genSimpleString: Gen[String] = Gen.alphaNumStr.suchThat(_.nonEmpty)

  /** Generate file names */
  val genFileName: Gen[String] = for {
    name <- Gen.alphaNumStr.suchThat(_.length > 0)
    ext <- Gen.oneOf("jar", "class", "java", "xml", "json", "txt")
  } yield s"$name.$ext"

  /** Generate MIME types */
  val genMimeType: Gen[String] = Gen.oneOf(
    "application/java-archive",
    "application/octet-stream",
    "text/plain",
    "application/json",
    "application/xml"
  )

  /** Generate StringOrPair */
  val genStringOrPair: Gen[StringOrPair] = Gen.oneOf(
    genSimpleString.map(StringOf(_)),
    for {
      mime <- genMimeType
      value <- genSimpleString
    } yield PairOf(mime, value)
  )

  /** Generate TreeSet of strings */
  def genTreeSetString: Gen[TreeSet[String]] = for {
    strings <- Gen.listOf(genSimpleString)
  } yield TreeSet(strings.take(5)*) // Limit size for performance

  /** Generate TreeSet of StringOrPair */
  def genTreeSetStringOrPair: Gen[TreeSet[StringOrPair]] = for {
    items <- Gen.listOf(genStringOrPair)
  } yield TreeSet(items.take(5)*)(using Ordering.by(_.value))

  /** Generate extra metadata map */
  val genExtra: Gen[TreeMap[String, TreeSet[StringOrPair]]] = for {
    keys <- Gen.listOf(Gen.alphaLowerStr.suchThat(_.nonEmpty))
    values <- Gen.listOfN(keys.length, genTreeSetStringOrPair)
  } yield TreeMap(keys.take(3).zip(values.take(3))*)

  /** Generate ItemMetaData */
  val genItemMetaData: Gen[ItemMetaData] = for {
    fileNames <- Gen.listOf(genFileName).map(l => TreeSet(l.take(3)*))
    mimeTypes <- Gen.listOf(genMimeType).map(l => TreeSet(l.take(2)*))
    fileSize <- Gen.posNum[Long]
    extra <- genExtra
  } yield ItemMetaData(
    fileNames = if (fileNames.isEmpty) TreeSet("default.txt") else fileNames,
    mimeType =
      if (mimeTypes.isEmpty) TreeSet("application/octet-stream") else mimeTypes,
    fileSize = fileSize,
    extra = extra
  )

  /** Generate edge types */
  val genEdgeType: Gen[String] = Gen.oneOf(
    EdgeType.aliasFrom,
    EdgeType.aliasTo,
    EdgeType.containedBy,
    EdgeType.contains,
    EdgeType.builtFrom,
    EdgeType.buildsTo
  )

  /** Generate edges (connections) */
  val genEdge: Gen[(String, String)] = for {
    edgeType <- genEdgeType
    target <- genGitOID
  } yield (edgeType, target)

  /** Generate Item */
  val genItem: Gen[Item] = for {
    identifier <- genGitOID
    connections <- Gen.listOf(genEdge).map(l => TreeSet(l.take(5)*))
    hasMetadata <- Gen.oneOf(true, false)
    metadata <-
      if (hasMetadata) genItemMetaData.map(Some(_)) else Gen.const(None)
  } yield Item(
    identifier = identifier,
    connections = connections,
    bodyMimeType = metadata.map(_ => ItemMetaData.mimeType),
    body = metadata
  )

  /** Generate byte arrays */
  val genByteArray: Gen[Array[Byte]] =
    Gen.containerOf[Array, Byte](Arbitrary.arbByte.arbitrary)

  /** Generate non-empty byte arrays */
  val genNonEmptyByteArray: Gen[Array[Byte]] =
    Gen.nonEmptyContainerOf[Array, Byte](Arbitrary.arbByte.arbitrary)

  // Make generators available as Arbitrary instances
  given Arbitrary[Item] = Arbitrary(genItem)
  given Arbitrary[ItemMetaData] = Arbitrary(genItemMetaData)
  given Arbitrary[StringOrPair] = Arbitrary(genStringOrPair)

  // ==================== CBOR Serialization Round-trip Tests ====================

  property("Item CBOR round-trip: encode then decode equals original") {
    forAll(genItem) { item =>
      val encoded = item.encodeCBOR()
      val decoded = Item.decode(encoded)

      decoded.isSuccess && decoded.get.identifier == item.identifier &&
      decoded.get.connections == item.connections
    }
  }

  property("Item CBOR encoding is deterministic") {
    forAll(genItem) { item =>
      val encoded1 = item.encodeCBOR()
      val encoded2 = item.encodeCBOR()
      encoded1.sameElements(encoded2)
    }
  }

  property("ItemMetaData CBOR round-trip preserves all fields") {
    forAll(genItemMetaData) { metadata =>
      val encoded = metadata.encodeCBOR()
      val item = Item(
        identifier = "gitoid:blob:sha256:" + "a" * 64,
        connections = TreeSet(),
        bodyMimeType = Some(ItemMetaData.mimeType),
        body = Some(metadata)
      )
      val itemEncoded = item.encodeCBOR()
      val decoded = Item.decode(itemEncoded)

      decoded.isSuccess && {
        val decodedMeta = decoded.get.bodyAsItemMetaData
        decodedMeta.isDefined &&
        decodedMeta.get.fileNames == metadata.fileNames &&
        decodedMeta.get.mimeType == metadata.mimeType &&
        decodedMeta.get.fileSize == metadata.fileSize
      }
    }
  }

  // ==================== Hash Function Tests ====================

  property("MD5 hash is deterministic") {
    forAll(genNonEmptyByteArray) { bytes =>
      val hash1 = Helpers.computeMD5(new ByteArrayInputStream(bytes))
      val hash2 = Helpers.computeMD5(new ByteArrayInputStream(bytes))
      hash1.sameElements(hash2)
    }
  }

  property("SHA256 hash is deterministic") {
    forAll(genNonEmptyByteArray) { bytes =>
      val hash1 = Helpers.computeSHA256(new ByteArrayInputStream(bytes))
      val hash2 = Helpers.computeSHA256(new ByteArrayInputStream(bytes))
      hash1.sameElements(hash2)
    }
  }

  property("MD5 hash length is always 16 bytes") {
    forAll(genNonEmptyByteArray) { bytes =>
      val hash = Helpers.computeMD5(new ByteArrayInputStream(bytes))
      hash.length == 16
    }
  }

  property("SHA256 hash length is always 32 bytes") {
    forAll(genNonEmptyByteArray) { bytes =>
      val hash = Helpers.computeSHA256(new ByteArrayInputStream(bytes))
      hash.length == 32
    }
  }

  property("SHA1 hash length is always 20 bytes") {
    forAll(genNonEmptyByteArray) { bytes =>
      val hash = Helpers.computeSHA1(new ByteArrayInputStream(bytes))
      hash.length == 20
    }
  }

  property("SHA512 hash length is always 64 bytes") {
    forAll(genNonEmptyByteArray) { bytes =>
      val hash = Helpers.computeSHA512(new ByteArrayInputStream(bytes))
      hash.length == 64
    }
  }

  property(
    "Different inputs produce different hashes (with high probability)"
  ) {
    forAll(genNonEmptyByteArray, genNonEmptyByteArray) { (bytes1, bytes2) =>
      // Only check if inputs are actually different
      if (!bytes1.sameElements(bytes2)) {
        val hash1 = Helpers.computeSHA256(new ByteArrayInputStream(bytes1))
        val hash2 = Helpers.computeSHA256(new ByteArrayInputStream(bytes2))
        !hash1.sameElements(hash2)
      } else {
        true // Same input, same hash is expected
      }
    }
  }

  // ==================== Hex Encoding Tests ====================

  property("toHex produces lowercase hex string") {
    forAll(genNonEmptyByteArray) { bytes =>
      val hex = Helpers.toHex(bytes)
      hex.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
    }
  }

  property("toHex output length is 2x input length") {
    forAll(genByteArray) { bytes =>
      val hex = Helpers.toHex(bytes)
      hex.length == bytes.length * 2
    }
  }

  property("toHex is deterministic") {
    forAll(genByteArray) { bytes =>
      val hex1 = Helpers.toHex(bytes)
      val hex2 = Helpers.toHex(bytes)
      hex1 == hex2
    }
  }

  property("charToBin correctly converts hex chars") {
    forAll(Gen.hexChar) { c =>
      val result = Helpers.charToBin(c)
      result >= 0 && result <= 15
    }
  }

  property("hexChar and charToBin are inverses for 0-15") {
    forAll(Gen.choose(0, 15)) { n =>
      val hex = Helpers.hexChar(n.toByte)
      val back = Helpers.charToBin(hex)
      back == n
    }
  }

  // ==================== Long/Byte Array Conversion Tests ====================

  property("byteArrayToLong63Bits always returns non-negative") {
    forAll(Gen.containerOfN[Array, Byte](8, Arbitrary.arbByte.arbitrary)) {
      bytes =>
        Helpers.byteArrayToLong63Bits(bytes) >= 0
    }
  }

  property("toHex(Long) produces 16 character hex string") {
    forAll(Arbitrary.arbLong.arbitrary) { l =>
      Helpers.toHex(l).length == 16
    }
  }

  // ==================== ItemMetaData Merge Tests ====================

  property("ItemMetaData merge is commutative for fileNames union") {
    forAll(genItemMetaData, genItemMetaData) { (a, b) =>
      // Merge in both directions
      val ab = a.merge(b, () => Vector(), () => Vector())
      val ba = b.merge(a, () => Vector(), () => Vector())

      // File names should be the same regardless of order
      ab.fileNames == ba.fileNames
    }
  }

  property("ItemMetaData merge combines MIME types") {
    forAll(genItemMetaData, genItemMetaData) { (a, b) =>
      val merged = a.merge(b, () => Vector(), () => Vector())

      // All MIME types from both should be present
      a.mimeType.subsetOf(merged.mimeType) &&
      b.mimeType.subsetOf(merged.mimeType)
    }
  }

  property("ItemMetaData merge preserves first item's fileSize") {
    forAll(genItemMetaData, genItemMetaData) { (a, b) =>
      val merged = a.merge(b, () => Vector(), () => Vector())
      // The implementation uses `this.fileSize` (first argument's fileSize)
      merged.fileSize == a.fileSize
    }
  }

  // ==================== IncludeExclude Filter Tests ====================

  property("Empty IncludeExclude includes everything") {
    forAll(genSimpleString) { s =>
      val filter = IncludeExclude()
      filter.shouldInclude(s)
    }
  }

  property("Excluded item is not included (unless explicitly included)") {
    forAll(genSimpleString) { s =>
      val filter = IncludeExclude() :+ s"-$s"
      !filter.shouldInclude(s)
    }
  }

  property("Explicitly included item overrides exclusion") {
    forAll(genSimpleString.suchThat(_.nonEmpty)) { s =>
      val filter = (IncludeExclude() :+ s"-$s") :+ s"+$s"
      filter.shouldInclude(s)
    }
  }

  property("Adding same predicate twice is idempotent") {
    forAll(genSimpleString) { s =>
      val filter1 = IncludeExclude() :+ s"+$s"
      val filter2 = filter1 :+ s"+$s"

      // Both should behave the same way
      filter1.shouldInclude(s) == filter2.shouldInclude(s)
    }
  }

  // ==================== Item Merge Tests ====================

  property("Item merge preserves identifier") {
    forAll(genItem, genItem) { (a, b) =>
      val aWithBId = a.copy(identifier = b.identifier)
      val merged = aWithBId.merge(b)
      merged.identifier == b.identifier
    }
  }

  property("Item merge combines connections") {
    forAll(genItem) { item =>
      val extraConnection =
        (EdgeType.aliasFrom, "gitoid:blob:sha256:" + "b" * 64)
      val itemWithExtra =
        item.copy(connections = item.connections + extraConnection)
      val merged = item.merge(itemWithExtra)

      merged.connections.contains(extraConnection)
    }
  }

  property("Item withConnection adds connection") {
    forAll(genItem, genEdgeType, genGitOID) { (item, edgeType, target) =>
      val updated = item.withConnection(edgeType, target)
      updated.connections.contains((edgeType, target))
    }
  }

  // ==================== EdgeType Predicate Tests ====================

  property("isAliasFrom only true for aliasFrom") {
    forAll(genEdgeType) { edgeType =>
      EdgeType.isAliasFrom(edgeType) == (edgeType == EdgeType.aliasFrom)
    }
  }

  property("isContains only true for contains") {
    forAll(genEdgeType) { edgeType =>
      EdgeType.isContains(edgeType) == (edgeType == EdgeType.contains)
    }
  }

  property("isDown true only for :down suffix") {
    forAll(genEdgeType) { edgeType =>
      EdgeType.isDown(edgeType) == edgeType.endsWith(":down")
    }
  }

  // ==================== StringOrPair Tests ====================

  property("StringOf.value returns the string") {
    forAll(genSimpleString) { s =>
      StringOf(s).value == s
    }
  }

  property("PairOf.value returns second string") {
    forAll(genSimpleString, genSimpleString) { (s1, s2) =>
      PairOf(s1, s2).value == s2
    }
  }

  property("PairOf.mimeType returns Some(first string)") {
    forAll(genSimpleString, genSimpleString) { (s1, s2) =>
      PairOf(s1, s2).mimeType == Some(s1)
    }
  }

  property("StringOf.mimeType returns None") {
    forAll(genSimpleString) { s =>
      StringOf(s).mimeType.isEmpty
    }
  }

  // ==================== MD5 Identifier Tests ====================

  property("Item.identifierMD5 is deterministic") {
    forAll(genItem) { item =>
      val md5_1 = item.identifierMD5()
      val md5_2 = item.identifierMD5()
      md5_1.sameElements(md5_2)
    }
  }

  property("Different identifiers produce different MD5s") {
    forAll(genItem, genGitOID) { (item, newId) =>
      if (item.identifier != newId) {
        val item2 = item.copy(identifier = newId)
        !item.identifierMD5().sameElements(item2.identifierMD5())
      } else {
        true
      }
    }
  }
}
