package goatrodeo.omnibor

/* Copyright 2024 David Pollak & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

import scala.collection.SortedSet
import goatrodeo.util.GitOID
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
import goatrodeo.util.PackageIdentifier
import io.bullet.borer.Writer
import goatrodeo.util.Helpers.filesForParent

enum EdgeType {
  case AliasTo
  case AliasFrom
  case Contains
  case ContainedBy
  case BuildsTo
  case BuiltFrom

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

type Edge = (String, EdgeType, Option[String])

case class ItemMetaData(
    @key("file_names") fileNames: Set[String],
    @key("file_type") fileType: Set[String],
    @key("file_sub_type") fileSubType: Set[String],
    extra: Map[String, Set[String]]
) {
  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  def merge(other: ItemMetaData): ItemMetaData = {
    ItemMetaData(
      fileNames = this.fileNames ++ other.fileNames,
      fileType = this.fileType ++ other.fileType,
      fileSubType = this.fileSubType ++ other.fileSubType,
      extra = {
        var ret = this.extra;
        for {(k, v) <- other.extra} {
          val nv = ret.get(k) match {
            case None => v
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
      packageIdentifier: Option[PackageIdentifier]
  ): ItemMetaData = {
    packageIdentifier match {
      case Some(
            pid
          ) =>
        ItemMetaData(
          fileNames = Set(fileName),
          fileType = Set("package"),
          fileSubType = Set(pid.protocol.name),
          extra = fileType.toStringMap() ++ 
          Map("purl" -> Set(pid.purl())) ++ 
          pid.toStringMap()
        )
      case None =>
        ItemMetaData(
          fileNames = Set(fileName),
          fileType = fileType.typeName().map(Set(_)).getOrElse(Set()),
          fileSubType = fileType.subType().map(Set(_)).getOrElse(Set()),
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
    connections: Set[Edge],
    metadata: Option[ItemMetaData],
    @key("merged_from") mergedFrom: Vector[LocationReference],
    @key("file_size") fileSize: Long,
    _timestamp: Long,
    _version: Int,
    _type: String
) {
  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  def fixReferencePosition(hash: Long, offset: Long): Item = {
    val hasCur = reference != Item.noopLocationReference
    this.copy(
      reference = (hash, offset),
      mergedFrom = (if (hasCur) Vector(this.reference) else Vector())
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
        case Edge(connection, EdgeType.AliasFrom, data) => {

          store.write(
            connection,
            maybeAlias => {
              val alias = maybeAlias.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  connections = Set(),
                  metadata = None,
                  fileSize = this.fileSize,
                  mergedFrom = Vector(),
                  _timestamp = System.currentTimeMillis(),
                  _version = 1,
                  _type = "Item"
                )
              )
              val toAdd = (this.identifier, EdgeType.AliasTo, data)
              val updatedAlias =
                if (alias.connections.contains(toAdd)) { alias }
                else {
                  alias.copy(
                    connections = (alias.connections + toAdd),
                    _timestamp = System.currentTimeMillis()
                  )
                }
              updatedAlias
            }
          )
        }
        case Edge(connection, EdgeType.BuiltFrom, data) => {

          store.write(
            connection,
            maybeSource => {
              val source = maybeSource.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  connections = Set(),
                  metadata = None,
                  fileSize = -1,
                  mergedFrom = Vector(),
                  _timestamp = System.currentTimeMillis(),
                  _version = 1,
                  _type = "Item"
                )
              )
              val toAdd = (this.identifier, EdgeType.BuildsTo, data)
              val updatedSource =
                if (source.connections.contains(toAdd)) { source }
                else {
                  source.copy(
                    connections = (source.connections + toAdd),
                    _timestamp = System.currentTimeMillis()
                  )
                }
              updatedSource
            }
          )
        }
        case Edge(connection, EdgeType.ContainedBy, data) => {

          store.write(
            connection,
            maybeContainer => {
              val container = maybeContainer.getOrElse(
                Item(
                  identifier = connection,
                  reference = Item.noopLocationReference,
                  // altIdentifiers = Vector(),
                  connections = Set(),
                  metadata = None,
                  fileSize = -1,
                  mergedFrom = Vector(),
                  _timestamp = System.currentTimeMillis(),
                  _version = 1,
                  _type = "Item"
                )
              )
              val toAdd = (this.identifier, EdgeType.Contains, data)
              val updatedSource = if (container.connections.contains(toAdd)) {
                container
              } else {
                container.copy(
                  connections = (container.connections + toAdd),
                  _timestamp = System.currentTimeMillis()
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
      mergedFrom = this.mergedFrom ++ other.mergedFrom,
      fileSize = if (this.fileSize == -1) other.fileSize else this.fileSize,
      _timestamp = System.currentTimeMillis(),
      _version = 1,
      _type = this._type
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
