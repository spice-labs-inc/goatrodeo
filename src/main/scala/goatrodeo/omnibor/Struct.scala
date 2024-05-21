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
import goatrodeo.envelopes.PayloadFormat
import io.bullet.borer.derivation.key
import java.time.Instant
import goatrodeo.util.FileType
import goatrodeo.util.PackageIdentifier
import io.bullet.borer.Writer

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

type Edge = (String, EdgeType)
// case class Edge(connection: String, @key("edge_type") edgeType: EdgeType) extends Ordered[Edge] {

//   override def compare(that: Edge): Int = {
//     val strDiff = this.connection.compare(this.connection)
//     if (strDiff != 0) {
//       strDiff
//     } else {
//       this.edgeType.toString().compare(that.edgeType.toString())
//     }
//   }

//   def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray
// }

// object Edge {
//   given Encoder[Edge] = {
//     import io.bullet.borer.derivation.MapBasedCodecs.*
//     deriveEncoder[Edge]
//   }

//   given Decoder[Edge] = {
//     import io.bullet.borer.derivation.MapBasedCodecs.*
//     deriveDecoder[Edge]
//   }

// }

case class ItemMetaData(
    @key("file_names") fileNames: Vector[String],
    @key("file_type") fileType: String,
    @key("file_sub_type") fileSubType: String,
    extra: Map[String, String]
) {
  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  def merge(other: ItemMetaData): ItemMetaData = {
    val myNames = Set(this.fileNames: _*)
    val whatsLeft = other.fileNames.filterNot(s => myNames.contains(s))
    val extraLeft =
      other.extra.filterNot((k, v) => this.extra.get(k) == Some(v))

    if (whatsLeft.isEmpty && extraLeft.isEmpty) {
      this
    } else {
      ItemMetaData(
        fileNames = (myNames ++ whatsLeft).toVector.sorted,
        fileType = this.fileType,
        fileSubType = this.fileSubType,
        extra = this.extra ++ extraLeft
      )
    }
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
            pid @ PackageIdentifier(protocol, groupId, artifactId, version)
          ) =>
        ItemMetaData(
          fileNames = Vector(fileName),
          fileType = "package",
          fileSubType = protocol.name,
          extra = fileType.toStringMap() ++ Map("purl" -> pid.purl()) ++ pid
            .toStringMap()
        )
      case None =>
        ItemMetaData(
          fileNames = Vector(fileName),
          fileType = fileType.typeName().getOrElse("Unknown"),
          fileSubType = fileType.subType().getOrElse(""),
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

// case class ItemReference(@key("file_hash") file_hash: Long, offset: Long) {
//   def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray
// }

// object ItemReference {
//   given Encoder[ItemReference] = {
//     import io.bullet.borer.derivation.MapBasedCodecs.*
//     deriveEncoder[ItemReference]
//   }

//   given Decoder[ItemReference] = {
//     import io.bullet.borer.derivation.MapBasedCodecs.*
//     deriveDecoder[ItemReference]
//   }

//   def noop = ItemReference(0, 0)
// }

type LocationReference = (Long, Long)

case class Item(
    identifier: String,
    reference: LocationReference,
    @key("alt_identifiers") altIdentifiers: Vector[String],
    connections: Vector[Edge],
    @key("previous_reference") previousReference: Option[LocationReference],
    metadata: Option[ItemMetaData],
    @key("merged_from") mergedFrom: Vector[Item],
    _timestamp: Long ,
    _version: Int ,
    _type: String 
) {
  def encodeCBOR(): Array[Byte] = Cbor.encode(this).toByteArray

  def fixReferencePosition(hash: Long, offset: Long): Item = {
    val hasCur = reference != Item.noopLocationReference
    this.copy(reference = (hash, offset), previousReference = (if (hasCur) Some(this.reference) else None))
  }

  def identifierMD5(): Array[Byte] = Helpers.computeMD5(identifier)

  def cmpMd5(that: Item): Boolean = {
    val myHash = Helpers.md5hashHex(identifier)
    val thatHash = Helpers.md5hashHex(that.identifier)
    myHash < thatHash
  }

  def fixReferences(store: Storage): Item = {
    for { edge <- this.connections } {
      edge match {
        case Edge(connection, EdgeType.AliasFrom) => {
          val alias = store
            .read(connection)
            .getOrElse(
              Item(
                identifier = connection,
                reference = Item.noopLocationReference,
                altIdentifiers = Vector(),
                connections = Vector(),
                previousReference = None,
                metadata = None,
                mergedFrom = Vector(),
                _timestamp = System.currentTimeMillis(),
                _version = 1,
                _type = "Item"
              )
            )
          val toAdd = (this.identifier, EdgeType.AliasTo)
          val updatedAlias = if (alias.connections.contains(toAdd)) { alias }
          else {
            alias.copy(
              connections = (alias.connections :+ toAdd).sortBy(_._1),
              _timestamp = System.currentTimeMillis()
            )
          }
          store.write(connection, updatedAlias)
        }
        case Edge(connection, EdgeType.BuiltFrom) => {
          val source = store
            .read(connection)
            .getOrElse(
              Item(
                identifier = connection,
                reference = Item.noopLocationReference,
                altIdentifiers = Vector(),
                connections = Vector(),
                previousReference = None,
                metadata = None,
                mergedFrom = Vector(),
                _timestamp = System.currentTimeMillis(),
                _version = 1,
                _type = "Item"
              )
            )
          val toAdd = (this.identifier, EdgeType.BuildsTo)
          val updatedSource = if (source.connections.contains(toAdd)) { source }
          else {
            source.copy(
              connections = (source.connections :+ toAdd).sortBy(_._1),
              _timestamp = System.currentTimeMillis()
            )
          }
          store.write(connection, updatedSource)
        }
        case Edge(connection, EdgeType.ContainedBy) => {
          val container = store
            .read(connection)
            .getOrElse(
              Item(
                identifier = connection,
                reference = Item.noopLocationReference,
                altIdentifiers = Vector(),
                connections = Vector(),
                previousReference = None,
                metadata = None,
                mergedFrom = Vector(),
                _timestamp = System.currentTimeMillis(),
                _version = 1,
                _type = "Item"
              )
            )
          val toAdd = (this.identifier, EdgeType.Contains)
          val updatedSource = if (container.connections.contains(toAdd)) {
            container
          } else {
            container.copy(
              connections = (container.connections :+ toAdd).sortBy(_._1),
              _timestamp = System.currentTimeMillis()
            )
          }
          store.write(connection, updatedSource)
        }
        case _ =>
      }
    }
    this

  }

  def merge(others: Vector[Item]): Item = {

    val finalMetaData = others.foldLeft(this.metadata) { (a, b) =>
      (a, b.metadata) match {
        case (Some(me), Some(them)) => Some(me.merge(them))
        case (None, Some(them))     => Some(them)
        case (Some(me), None)       => Some(me)
        case _                      => None
      }
    }

    val myAlts = Set(this.altIdentifiers: _*)
    val finalAlts = others.foldLeft(myAlts) { (me, them) =>
      val theirAlts = Set(them.altIdentifiers: _*)
      me ++ theirAlts
    }

    val myConnections = Set(this.connections: _*)
    val finalConnections = others.foldLeft(myConnections) { (me, them) =>
      val theirCons = Set(them.connections: _*)
      me ++ theirCons
    }
    // nothing new
    if (
      myAlts == finalAlts && this.metadata == finalMetaData && myConnections == finalConnections
    ) {
      this
    } else {
      val notStored = this.reference == Item.noopLocationReference
      this.copy(
        altIdentifiers = finalAlts.toVector.sorted,
        connections = finalConnections.toVector.sortBy(_._1),
        previousReference = if (notStored) None else Some(this.reference),
        metadata = finalMetaData,
        mergedFrom = if (notStored) Vector() else others,
        _timestamp = System.currentTimeMillis(),
        _version = 1
      )
    }

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

  def decode(bytes: Array[Byte], format: PayloadFormat): Try[Item] = {
    format match {
      case PayloadFormat.CBOR => Cbor.decode(bytes).to[Item].valueTry
      case PayloadFormat.JSON => Json.decode(bytes).to[Item].valueTry
    }
  }
}
