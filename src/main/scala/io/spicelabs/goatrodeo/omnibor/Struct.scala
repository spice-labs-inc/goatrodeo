package io.spicelabs.goatrodeo.omnibor

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

import io.spicelabs.goatrodeo.util.Helpers
import io.bullet.borer.Cbor
import io.bullet.borer.Decoder
import io.bullet.borer.Encoder
import io.bullet.borer.Writer
import io.bullet.borer.derivation.key

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.language.implicitConversions

object EdgeType {

  /** Is the type `AliasFrom`
    *
    * @param s
    *   the EdgeType to check
    * @return
    *   the EdgeType is `AliasFrom`
    */
  def isAliasFrom(s: String): Boolean = {
    s == aliasFrom
  }

  def isContains(s: String): Boolean = s == contains

  /** Is the type `AliasTo`
    *
    * @param s
    *   the EdgeType to check
    * @return
    *   the EdgeType is `AliasTo`
    */
  def isAliasTo(s: String): Boolean = {
    s == aliasTo
  }

  def isDown(s: String): Boolean = {
    s.endsWith(down)
  }

  /** Is the type "BuiltFrom"
    *
    * @param s
    *   the EdgeType to check
    * @return
    *   the EdgeType is BuiltFrom
    */
  def isBuiltFrom(s: String): Boolean = {
    s == builtFrom
  }

  /** Is the type "BuildTo"
    *
    * @param s
    *   the EdgeType to check
    * @return
    *   the EdgeType is BuildsTo
    */
  def isBuildsTo(s: String): Boolean = {
    s == buildsTo
  }

  val to = ":to"
  val from = ":from"
  val down = ":down"
  val up = ":up"

  val containedBy = "contained:up";
  val contains = "contained:down";
  val aliasTo = "alias:to";
  val aliasFrom = "alias:from";
  val buildsTo = "build:up"
  val builtFrom = "build:down"
  val tagFrom = "tag:from"
  val tagTo = "tag:to"
}

type Edge = (String, String)

sealed trait StringOrPair {
  def value: String
  def mimeType: Option[String] = None
}
final case class StringOf(s: String) extends StringOrPair {
  def value = s
}
final case class PairOf(s1: String, s2: String) extends StringOrPair {
  def value = s2
  override def mimeType: Option[String] = Some(s1)
}

object StringOrPair {
  def apply(s: String): StringOrPair = StringOf(s)
  def apply(s1: String, s2: String): StringOrPair =
    PairOf(s1, s2)
  def apply(s: (String, String)): StringOrPair =
    PairOf(s._1, s._2)

  implicit def fromString(s: String): StringOrPair = StringOf(s)
  implicit def fromPair(p: (String, String)): StringOrPair = PairOf(p._1, p._2)

  given Ordering[StringOrPair] = {
    Ordering.by[StringOrPair, String](e =>
      e match {
        case StringOf(s)    => s
        case PairOf(s1, s2) => f"${s1}${s2}"
      }
    )
  }

  given Encoder[StringOrPair] = { (writer, item) =>
    item match {
      case StringOf(s) => writer.writeString(s)
      case PairOf(a, b) =>
        writer
          .writeArrayOpen(2)
          .writeString(a)
          .writeString(b)
          .writeArrayClose()
    }

  }

  given Decoder[StringOrPair] = Decoder { reader =>
    if (reader.hasArrayStart) {
      val unbounded = reader.readArrayOpen(2)
      val item = PairOf(
        reader.readString(),
        reader.readString()
      )
      reader.readArrayClose(unbounded, item)
    } else if (reader.hasString) {
      StringOf(reader.readString())
    } else {
      reader.unexpectedDataItem("Looking for 'String' or Array of String")
    }

  }
}
case class ItemMetaData(
    @key("file_names") fileNames: TreeSet[String],
    @key("mime_type") mimeType: TreeSet[String],
    @key("file_size") fileSize: Long,
    extra: TreeMap[String, TreeSet[StringOrPair]]
) {
  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  /** Merge two metadata instances. If the filenames will only contain one name
    * after merge, then that's the filename. If the filename will diverge,
    * attach "contains" to each of the names
    */
  def merge(
      other: ItemMetaData,
      thisContains: () => Vector[String],
      otherContains: () => Vector[String]
  ): ItemMetaData = {

    def fix(filename: String, gitoids: Vector[String]): TreeSet[String] = {
      TreeSet(gitoids.map(go => f"${go}!${filename}") :+ filename*)
    }

    val mergedFilenames = this.fileNames ++ other.fileNames

    val resolvedFilenames =
      (mergedFilenames.size, this.fileNames.size, other.fileNames.size) match {
        // if there's only one filename, then it's a clean merge
        case (1, _, _) => mergedFilenames

        // if both have already done the filename to gitoid mapping, then it's safe to merge
        case (_, tsize, osize) if tsize > 1 && osize > 1 => mergedFilenames

        // create the mapping
        case (_, tsize, osize) =>
          val thisWithMapping =
            if (tsize > 1) this.fileNames
            else fix(this.fileNames.head, thisContains())
          val otherWithMapping =
            if (osize > 1) other.fileNames
            else fix(other.fileNames.head, otherContains())
          thisWithMapping ++ otherWithMapping
      }

    val ret = ItemMetaData(
      fileNames = resolvedFilenames,
      mimeType = this.mimeType ++ other.mimeType,
      fileSize = this.fileSize,
      extra = Helpers.mergeTreeMaps(this.extra, other.extra)
    )

    ret
  }
}

object ItemMetaData {
  val mimeType = "application/vnd.cc.goatrodeo"

  given Encoder[ItemMetaData] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveEncoder[ItemMetaData]
  }

  given Decoder[ItemMetaData] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveDecoder[ItemMetaData]
  }
}

object TagMetaData {
  val mimeType = "application/vnd.cc.goatrodeo.tag"

    given Encoder[ItemTagData] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveEncoder[ItemTagData]
  }

  given Decoder[ItemTagData] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveDecoder[ItemTagData]
  }
}

case class ItemTagData(tag: JValue)

type LocationReference = (Long, Long)

enum IndexLoc {
  case Loc(offset: Long, fileHash: Long)
  case Chain(chain: Vector[IndexLoc])
}

case class ItemOffset(hashHi: Long, hashLow: Long, loc: IndexLoc)
