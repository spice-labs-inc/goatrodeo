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

import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers
import scala.util.Try
import io.bullet.borer.Json
import io.bullet.borer.Codec
import io.bullet.borer.Encoder
import io.bullet.borer.Decoder
import io.bullet.borer.Cbor
import io.bullet.borer.derivation.key
import java.time.Instant
import io.spicelabs.goatrodeo.util.FileType
import io.spicelabs.goatrodeo.util.PackageIdentifier
import io.bullet.borer.Writer
import io.spicelabs.goatrodeo.util.Helpers.filesForParent
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

// enum EdgeType extends Comparable[EdgeType] {
//   case AliasTo
//   case AliasFrom
//   case Contains
//   case ContainedBy
//   case BuildsTo
//   case BuiltFrom

//   override def compareTo(other: EdgeType): Int =
//     this.toString().compareTo(other.toString())

//   def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray
// }

// object EdgeType {
//   given Encoder[EdgeType] = {
//     import io.bullet.borer.derivation.MapBasedCodecs.*
//     deriveEncoder[EdgeType]
//   }

//   given Decoder[EdgeType] = {
//     import io.bullet.borer.derivation.MapBasedCodecs.*
//     deriveDecoder[EdgeType]
//   }
// }

object EdgeType {
  def isAliasFrom(s: String): Boolean = {
    s == aliasFrom
  }

  def isAliasTo(s: String): Boolean = {
    s == aliasTo
  }

  def isFromLeft(s: String): Boolean = {
    s.endsWith(fromLeft)
  }

  def isToRight(s: String): Boolean = {
    s.endsWith(toRight)
  }

  def isContainsDown(s: String): Boolean = {
    s.endsWith(containsDown)
  }

  def isContainedByUp(s: String): Boolean = {
    s.endsWith(containedByUp)
  }

  val toRight = ":->";
  val fromLeft = ":<-";
  val containsDown = ":||";
  val containedByUp = ":^";

  val containedBy = "ContainedBy:^";
  val contains = "Contains:||";
  val aliasTo = "AliasTo:->";
  val aliasFrom = "AliasFrom:<-";
  val buildsTo = "BuildsTo:^"
  val builtFrom = "BuiltFrom:||"

}

type Edge = (String, String)

case class ItemMetaData(
    @key("file_names") fileNames: TreeSet[String],
    @key("file_type") fileType: TreeSet[String],
    @key("file_sub_type") fileSubType: TreeSet[String],
    @key("file_size") fileSize: Long,
    extra: TreeMap[String, TreeSet[String]]
) {
  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  def merge(other: ItemMetaData): ItemMetaData = {
    ItemMetaData(
      fileNames = this.fileNames ++ other.fileNames,
      fileType = this.fileType ++ other.fileType,
      fileSubType = this.fileSubType ++ other.fileSubType,
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
      fileType: FileType,
      packageIdentifier: Option[PackageIdentifier],
      fileSize: Long
  ): ItemMetaData = {
    packageIdentifier match {
      case Some(
            pid
          ) =>
        ItemMetaData(
          fileNames = TreeSet(fileName),
          fileType = TreeSet("package"),
          fileSubType = TreeSet(pid.protocol.name),
          fileSize = fileSize,
          extra = fileType.toStringMap() ++
            TreeMap("purl" -> TreeSet(pid.purl()*)) ++
            pid.toStringMap()
        )
      case None =>
        ItemMetaData(
          fileNames = TreeSet(fileName),
          fileType = fileType.typeName().map(TreeSet(_)).getOrElse(TreeSet()),
          fileSubType = fileType.subType().map(TreeSet(_)).getOrElse(TreeSet()),
          fileSize = fileSize,
          extra = fileType.toStringMap()
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
    @key("body_mime_type") bodyMimeType: Option[String],
    body: Option[ItemMetaData]
) {

  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  def fixReferencePosition(hash: Long, offset: Long): Item = {
    val hasCur = reference != Item.noopLocationReference
    this.copy(
      reference = (hash, offset)
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
        case Edge(EdgeType.aliasFrom, connection) => {

          store.write(
            connection,
            maybeAlias => {
              val alias = maybeAlias.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  connections = TreeSet(),
                  bodyMimeType = None,
                  body = None
                )
              )
              val toAdd = (EdgeType.aliasTo, this.identifier)
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
        case Edge(EdgeType.builtFrom, connection) => {

          store.write(
            connection,
            maybeSource => {
              val source = maybeSource.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  connections = TreeSet(),
                  bodyMimeType = None,
                  body = None
                )
              )
              val toAdd = (EdgeType.buildsTo, this.identifier)
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
        case Edge(EdgeType.containedBy, connection) => {

          store.write(
            connection,
            maybeContainer => {
              val container = maybeContainer.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  connections = TreeSet(),
                  bodyMimeType = None,
                  body = None
                )
              )
              val toAdd = (EdgeType.contains, this.identifier)
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

    val (body, mime) = (this.body, other.body, this.bodyMimeType == other.bodyMimeType) match {
        case (Some(a), Some(b), true) => Some(a.merge(b)) -> this.bodyMimeType
        case (Some(a), _, _)       => Some(a) -> this.bodyMimeType
        case (_, Some(b), _)       => Some(b) -> other.bodyMimeType
        case _                  => None -> None
      }

    Item(
      identifier = this.identifier,
      reference = this.reference,
      connections = this.connections ++ other.connections,
      bodyMimeType = mime,
      body = body,
    )

  }
}

object Item {
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
