package goatrodeo.omnibor

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

import goatrodeo.util.{GitOID, PackageIdentifier}
import goatrodeo.util.Helpers
import scala.util.Try
import io.bullet.borer.Json
import io.bullet.borer.Codec
import io.bullet.borer.Encoder
import io.bullet.borer.Decoder
import io.bullet.borer.Cbor
import io.bullet.borer.derivation.key
import java.time.Instant
import goatrodeo.util.FileType
import io.bullet.borer.Writer
import goatrodeo.util.Helpers.filesForParent
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

enum EdgeType extends Comparable[EdgeType] {
  case AliasTo
  case AliasFrom
  case Contains
  case ContainedBy
  case BuildsTo
  case BuiltFrom

  override def compareTo(other: EdgeType): Int =
    this.toString().compareTo(other.toString())

  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray
}

object EdgeType {
  given Encoder[EdgeType] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveEncoder[EdgeType]
  }

  given Decoder[EdgeType] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveDecoder[EdgeType]
  }
}

type Edge = (EdgeType, String)

sealed trait StringOrPair {}


final case class StringOf(s: String) extends StringOrPair {}

object StringOf {
  given Encoder[StringOf] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveEncoder[StringOf]
  }

  given Decoder[StringOf] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveDecoder[StringOf]
  }
}

final case class PairOf(s1: String, s2: String) extends StringOrPair {}

object PairOf {
  given Encoder[PairOf] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveEncoder[PairOf]
  }

  given Decoder[PairOf] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveDecoder[PairOf]
  }
}

object StringOrPair {
  implicit def fromString(s: String): StringOrPair = StringOf(s)
  implicit def fromPair(p: (String, String)): StringOrPair = PairOf(p._1, p._2)

  implicit def yak[StringOrPair]: Ordering[StringOrPair] = {
    val ord: Ordering[String] = implicitly
    Ordering.by[StringOrPair, String](e =>
      e match {
        case StringOf(s) => s
        case PairOf(s1, s2)   => f"${s1}${s2}"
      }
    )(ord)
  }

  given Encoder[StringOrPair] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveEncoder[StringOrPair]
  }

  given Decoder[StringOrPair] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveDecoder[StringOrPair]
  }
}


case class ItemMetaData(
    @key("file_names") fileNames: TreeSet[String],
    @key("mime_type") mimeType: TreeSet[String],
    @key("file_size") fileSize: Long,
    extra: TreeMap[String, TreeSet[StringOrPair]]
) {
  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  def merge(other: ItemMetaData): ItemMetaData = {
    ItemMetaData(
      fileNames = this.fileNames ++ other.fileNames,
      mimeType = this.mimeType ++ other.mimeType,
      fileSize = this.fileSize,
      extra = {
        var ret = this.extra;
        for { (k, v) <- other.extra } {
          val nv = ret.get(k) match {
            case None     => v
            case Some(mv) => v ++ mv
          }
          ret = ret + (k -> nv)
        }

        ret
      }
    )
  }
}

object ItemMetaData {
  def from(
      fileName: String,
      mimeType: String,
      packageIdentifier: Option[PackageIdentifier],
      fileSize: Long
  ): ItemMetaData = {
    packageIdentifier match {
      case Some(
            pid
          ) =>
        ItemMetaData(
          fileNames = TreeSet(fileName),
          mimeType = TreeSet(mimeType),
          fileSize = fileSize,
          extra = TreeMap[String, TreeSet[StringOrPair]](
            "purl" -> TreeSet(pid.purl().map(a => StringOf(a)): _*)
          ) ++
            pid.toStringMap()
        )
      case None =>
        ItemMetaData(
          fileNames = TreeSet(fileName),
          mimeType = TreeSet(mimeType),
          fileSize = fileSize,
          extra = TreeMap.empty
        )
    }

  }
  given Encoder[ItemMetaData] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveEncoder[ItemMetaData]
  }

  given Decoder[ItemMetaData] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveDecoder[ItemMetaData]
  }
}

type LocationReference = (Long, Long)

case class Item(
    identifier: String,
    reference: LocationReference,
    connections: TreeSet[Edge],
    metadata: Option[ItemMetaData],
    @key("merged_from") mergedFrom: TreeSet[LocationReference]
) {

  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  def fixReferencePosition(hash: Long, offset: Long): Item = {
    val hasCur = reference != Item.noopLocationReference
    this.copy(
      reference = (hash, offset),
      mergedFrom = (if (hasCur) TreeSet(this.reference) else TreeSet())
    )
  }

  private lazy val md5 = Helpers.computeMD5(identifier)

  def identifierMD5(): Array[Byte] = md5

  def cmpMd5(that: Item): Boolean = {
    val myHash = Helpers.md5hashHex(identifier)
    val thatHash = Helpers.md5hashHex(that.identifier)
    myHash < thatHash
  }

  def fixReferences(store: Storage): Item = {
    for { edge <- this.connections } {
      edge match {
        case Edge(EdgeType.AliasFrom, connection) => {

          store.write(
            connection,
            maybeAlias => {
              val alias = maybeAlias.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  connections = TreeSet(),
                  metadata = None,
                  mergedFrom = TreeSet()
                )
              )
              val toAdd = (EdgeType.AliasTo, this.identifier)
              val updatedAlias =
                if (alias.connections.contains(toAdd)) { alias }
                else {
                  alias.copy(
                    connections = (alias.connections + toAdd)
                  )
                }
              updatedAlias
            }
          )
        }
        case Edge(EdgeType.BuiltFrom, connection) => {

          store.write(
            connection,
            maybeSource => {
              val source = maybeSource.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  connections = TreeSet(),
                  metadata = None,
                  mergedFrom = TreeSet()
                )
              )
              val toAdd = (EdgeType.BuildsTo, this.identifier)
              val updatedSource =
                if (source.connections.contains(toAdd)) { source }
                else {
                  source.copy(
                    connections = (source.connections + toAdd)
                  )
                }
              updatedSource
            }
          )
        }
        case Edge(EdgeType.ContainedBy, connection) => {

          store.write(
            connection,
            maybeContainer => {
              val container = maybeContainer.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  connections = TreeSet(),
                  metadata = None,
                  mergedFrom = TreeSet()
                )
              )
              val toAdd = (EdgeType.Contains, this.identifier)
              val updatedSource = if (container.connections.contains(toAdd)) {
                container
              } else {
                container.copy(
                  connections = (container.connections + toAdd)
                )
              }
              updatedSource
            }
          )
        }
        case _ =>
      }
    }
    this

  }

  def merge(other: Item): Item = {
    Item(
      identifier = this.identifier,
      reference = this.reference,
      connections = this.connections ++ other.connections,
      metadata = (this.metadata, other.metadata) match {
        case (Some(a), Some(b)) => Some(a.merge(b))
        case (Some(a), _)       => Some(a)
        case (_, Some(b))       => Some(b)
        case _                  => None
      },
      mergedFrom = this.mergedFrom ++ other.mergedFrom
    )

  }
}

object Item {
  // given itemCmp: java.util.Comparator[Item] = {

  // }

  given forOption[T: Encoder]: Encoder.DefaultValueAware[Option[T]] =
    new Encoder.DefaultValueAware[Option[T]] {

      def write(w: Writer, value: Option[T]) =
        value match
          case Some(x) => w.write(x)
          case None    => w.writeNull()

      def withDefaultValue(defaultValue: Option[T]): Encoder[Option[T]] =
        if (defaultValue eq None)
          new Encoder.PossiblyWithoutOutput[Option[T]] {
            def producesOutputFor(value: Option[T]) = value ne None
            def write(w: Writer, value: Option[T]) =
              value match
                case Some(x) => w.write(x)
                case None    => w
          }
        else this
    }

  val noopLocationReference: LocationReference = (0L, 0L)
  given Encoder[Item] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveEncoder[Item]
  }

  given Decoder[Item] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveDecoder[Item]
  }

  def decode(bytes: Array[Byte]): Try[Item] = {
    Cbor.decode(bytes).to[Item].valueTry
  }
}

enum IndexLoc {
  case Loc(offset: Long, fileHash: Long)
  case Chain(chain: Vector[IndexLoc])
}

case class ItemOffset(hashHi: Long, hashLow: Long, loc: IndexLoc)
