package io.spicelabs.goatrodeo.omnibor

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
import io.bullet.borer.Decoder
import io.bullet.borer.Encoder
import io.bullet.borer.Writer
import io.bullet.borer.derivation.key
import io.spicelabs.goatrodeo.util.Helpers

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.language.implicitConversions

/** Constants and predicates for edge types in the Artifact Dependency Graph.
  *
  * Edge types define the relationships between Items:
  *   - `aliasFrom`/`aliasTo`: Alternate identifiers (e.g., sha1, sha512 hashes)
  *   - `containedBy`/`contains`: Container relationships (e.g., JAR contains
  *     class files)
  *   - `builtFrom`/`buildsTo`: Build relationships (e.g., class file built from
  *     source)
  *   - `tagFrom`/`tagTo`: Tag relationships for metadata tagging
  *
  * The `:down` and `:up` suffixes indicate direction:
  *   - `:down` - from container to contained
  *   - `:up` - from contained to container
  *   - `:from` - source of alias/build
  *   - `:to` - target of alias/build
  */
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

  /** Test if the edge type is "contains" (container to contained).
    *
    * @param s
    *   the edge type string
    * @return
    *   true if this is a "contains" edge
    */
  def isContains(s: String): Boolean = s == contains

  /** Test if the edge type is "containedBy" (contained to container).
    *
    * @param s
    *   the edge type string
    * @return
    *   true if this is a "containedBy" edge
    */
  def isContainedByUp(s: String): Boolean = s == containedBy

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

  /** Test if the edge type represents a downward direction (to contained
    * items).
    *
    * @param s
    *   the edge type string
    * @return
    *   true if the edge ends with ":down"
    */
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

  /** Edge suffix for "to" direction. */
  val to = ":to"

  /** Edge suffix for "from" direction. */
  val from = ":from"

  /** Edge suffix for downward direction (to contained). */
  val down = ":down"

  /** Edge suffix for upward direction (to container). */
  val up = ":up"

  /** Edge type for "contained by" relationship (points to container). */
  val containedBy = "contained:up";

  /** Edge type for "contains" relationship (points to contained). */
  val contains = "contained:down";

  /** Edge type for "alias to" relationship. */
  val aliasTo = "alias:to";

  /** Edge type for "alias from" relationship. */
  val aliasFrom = "alias:from";

  /** Edge type for "builds to" relationship (points to built artifact). */
  val buildsTo = "build:up"

  /** Edge type for "built from" relationship (points to source). */
  val builtFrom = "build:down"

  /** Edge type for "tag from" relationship. */
  val tagFrom = "tag:from"

  /** Edge type for "tag to" relationship. */
  val tagTo = "tag:to"
}

/** Type alias for an edge in the graph: (edge type, target GitOID). */
type Edge = (String, String)

/** A value that can be either a simple String or a (mime type, value) pair.
  *
  * Used in ItemMetaData.extra to store additional information that may have
  * associated MIME types (e.g., manifest content with "text/maven-manifest").
  */
sealed trait StringOrPair {

  /** Get the main value (the string for StringOf, the second string for
    * PairOf).
    */
  def value: String

  /** Get the MIME type if this is a PairOf. */
  def mimeType: Option[String] = None
}

/** A StringOrPair containing just a simple string value.
  *
  * @param s
  *   the string value
  */
final case class StringOf(s: String) extends StringOrPair {
  def value = s
}

/** A StringOrPair containing a MIME type and a value.
  *
  * @param s1
  *   the MIME type
  * @param s2
  *   the value
  */
final case class PairOf(s1: String, s2: String) extends StringOrPair {
  def value = s2
  override def mimeType: Option[String] = Some(s1)
}

/** Factory methods and codecs for StringOrPair. */
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

  given Decoder[StringOrPair] = { reader =>
    if (reader.hasArrayStart || reader.hasArrayHeader(2)) {
      val unbounded = reader.readArrayOpen(2)
      val item = PairOf(
        reader.readString(),
        reader.readString()
      )
      reader.readArrayClose(unbounded, item)
    } else if (reader.hasString) {
      StringOf(reader.readString())
    } else {
      reader.unexpectedDataItem(
        f"Looking for 'String' or Array of String got ${reader.dataItem()}"
      )
    }

  }
}

/** Metadata associated with an Item in the Artifact Dependency Graph.
  *
  * Contains information about the artifact such as filenames, MIME types, file
  * size, and additional metadata (extra).
  *
  * @param fileNames
  *   the filenames associated with this artifact (may include paths or pURLs)
  * @param mimeType
  *   the MIME types of this artifact
  * @param fileSize
  *   the size of the artifact in bytes
  * @param extra
  *   additional metadata as a map from keys to sets of values
  */
case class ItemMetaData(
    @key("file_names") fileNames: TreeSet[String],
    @key("mime_type") mimeType: TreeSet[String],
    @key("file_size") fileSize: Long,
    extra: TreeMap[String, TreeSet[StringOrPair]]
) {

  /** Encode this metadata to CBOR format. */
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

/** Companion object for ItemMetaData with MIME type constant and codecs. */
object ItemMetaData {

  /** The MIME type for ItemMetaData bodies. */
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

/** Companion object for ItemTagData with MIME type constant and codecs. */
object ItemTagData {

  /** The MIME type for ItemTagData bodies. */
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

/** Tag data associated with an Item for tagging and tracking purposes.
  *
  * @param tag
  *   the tag data as a CBOR DOM element
  */
case class ItemTagData(tag: io.bullet.borer.Dom.Element) {

  /** Merge this tag data with another ItemTagData.
    *
    * @param other
    *   the other tag data to merge
    * @return
    *   a new ItemTagData with merged content
    */
  def merge(other: ItemTagData): ItemTagData = {

    // Merge logic for Dom.Element
    val mergedTag = (this.tag, other.tag) match {
      case (f1: io.bullet.borer.Dom.MapElem, f2: io.bullet.borer.Dom.MapElem) =>
        io.bullet.borer.Dom.MapElem.Unsized(f1.members ++ f2.members)
      case (
            a1: io.bullet.borer.Dom.ArrayElem,
            a2: io.bullet.borer.Dom.ArrayElem
          ) =>
        io.bullet.borer.Dom.ArrayElem.Unsized(a1.elems ++ a2.elems)
      case (a1: io.bullet.borer.Dom.ArrayElem, _) =>
        a1
      case (_, a2: io.bullet.borer.Dom.ArrayElem) =>
        a2
      case (a, _) =>
        a
    }
    ItemTagData(mergedTag)
  }
}

/** Type alias for a location reference: (offset, file position). */
type LocationReference = (Long, Long)

/** An index location in the GRI (index) file.
  *
  * Used to locate Items within the binary GRD (data) files.
  */
enum IndexLoc {

  /** A direct location with an offset within a specific data file.
    *
    * @param offset
    *   the byte offset within the file
    * @param fileHash
    *   the hash of the data file
    */
  case Loc(offset: Long, fileHash: Long)

  /** A chain of locations for items that span multiple files.
    *
    * @param chain
    *   the vector of locations
    */
  case Chain(chain: Vector[IndexLoc])
}

/** Offset information for locating an Item by its identifier hash.
  *
  * @param hashHi
  *   high 64 bits of the identifier MD5 hash
  * @param hashLow
  *   low 64 bits of the identifier MD5 hash
  * @param loc
  *   the location where the Item data can be found
  */
case class ItemOffset(hashHi: Long, hashLow: Long, loc: IndexLoc)
