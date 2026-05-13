package io.spicelabs.goatrodeo.omnibor

import com.github.packageurl.PackageURL
import com.typesafe.scalalogging.Logger
import io.bullet.borer.Cbor
import io.bullet.borer.Decoder
import io.bullet.borer.Encoder
import io.bullet.borer.Reader
import io.bullet.borer.Writer
import io.bullet.borer.derivation.key
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.EdgeTypeId
import io.spicelabs.goatrodeo.util.Gitoid
import io.spicelabs.goatrodeo.util.GitOIDUtils
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.Identifier

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Represents a node in the Artifact Dependency Graph (ADG).
  *
  * An Item is the core data structure in OmniBOR/Goat Rodeo, representing a
  * single artifact identified by its GitOID. Items can have connections to
  * other Items (edges in the graph) and can carry metadata about the artifact.
  *
  * @param identifier
  *   the GitOID that uniquely identifies this Item (e.g.,
  *   "gitoid:blob:sha256:...")
  * @param connections
  *   edges to other Items, represented as (edge type, target GitOID) pairs
  * @param bodyMimeType
  *   the MIME type of the body, typically "application/vnd.cc.goatrodeo"
  * @param body
  *   optional metadata about this Item (either ItemMetaData or ItemTagData)
  */
case class Item(
    identifier: Identifier,
    // reference: LocationReference,
    connections: TreeSet[Edge],
    @key("body_mime_type") bodyMimeType: Option[String],
    body: Option[ItemMetaData | ItemTagData]
) {

  /** Encode this Item to CBOR format.
    *
    * @return
    *   the CBOR-encoded bytes
    */
  def encodeCBOR(): Array[Byte] = cachedCBOR

  /** Get the body as ItemMetaData if it is of that type.
    *
    * @return
    *   Some(ItemMetaData) if the body is ItemMetaData, None otherwise
    */
  def bodyAsItemMetaData: Option[ItemMetaData] = body match {
    case Some(b: ItemMetaData) => Some(b)
    case _                     => None
  }

  /** add a connection
    *
    * @param edgeType
    *   the type of connection
    * @param id
    *   the id of the connection
    * @return
    *   the revised Item
    */
  def withConnection(edgeType: String, id: String): Item =
    this.copy(connections = this.connections + (edgeType -> id))

  /** Canonical String form of `identifier`. Materialised lazily on first
    * access and cached, so the cost of decoding bytes back into a String is
    * paid at most once per Item lifetime regardless of how many storage-key,
    * edge, or logging consumers ask for it. */
  lazy val identifierString: String = identifier.apply()

  /** MD5 of the identifier's raw bytes — no UTF-8 round-trip through the
    * canonical String form. */
  private lazy val md5: Array[Byte] = identifier.md5

  /** Cache the auto-derived hashCode. Without this, `Item.hashCode` recomputes
    * the identifier's byte-array hash on every call (because `ArraySeq.ofByte`
    * doesn't cache its hashCode the way `String` does). Items frequently
    * appear as `HashMap` / `HashSet` elements, where each `put` / `get` /
    * `contains` invokes hashCode at least once. */
  override lazy val hashCode: Int = scala.runtime.ScalaRunTime._hashCode(this)

  /** Lazily cached CBOR-encoded representation of this Item. */
  lazy val cachedCBOR: Array[Byte] = Cbor.encode(this).toByteArray

  /** Get the MD5 hash of this Item's identifier.
    *
    * @return
    *   the 16-byte MD5 hash
    */
  def identifierMD5(): Array[Byte] = md5

  /** Compare this Item's MD5 hash with another Item's for ordering.
    *
    * @param that
    *   the other Item to compare
    * @return
    *   true if this Item's hash is lexicographically less than the other's
    */
  def cmpMd5(that: Item): Boolean = {
    val myHash = Helpers.toHex(this.md5)
    val thatHash = Helpers.toHex(that.md5)
    myHash < thatHash
  }

  /** Is the item a root item
    *
    * @return
    */
  def isRoot(): Boolean = {
    if (this.bodyMimeType != Some(ItemMetaData.mimeType)) {
      false
    } else if (this.identifier == Item.tagsRootIdentifier) {
      false
    } else if (
      this.connections
        .find(e => EdgeType.isAliasTo(e._1) || EdgeType.isContainedByUp(e._1))
        .isDefined
    ) { false }
    else {

      true
    }

  }

  /** Builds a list of items that are referenced from this item. The references
    * are of types `AliasFrom`, `BuiltFrom`, and `ContainedBy`
    *
    * The resulting `Item`s should be updated in the store
    */
  def buildListOfReferencesForAliasFromBuiltFromContainedBy()
      : Vector[(String, String)] = {
    for {
      edge <- this.connections.toVector
      toUpdate <- edge match {
        case Edge(EdgeType.aliasFrom, connection) => {
          Vector(
            (EdgeType.aliasTo -> connection)
          )

        }

        case Edge(EdgeType.builtFrom, connection) => {

          Vector(EdgeType.buildsTo -> connection)

        }
        case Edge(EdgeType.containedBy, connection) => {

          Vector(
            EdgeType.contains -> connection
          )
        }
        case Edge(EdgeType.tagFrom, connection) => {
          Vector(EdgeType.tagTo -> connection)
        }
        case _ => Vector.empty
      }

    } yield {
      toUpdate
    }
  }

  /** Create or update (merge) this `Item` in the store.
    *
    * The resulting item will be returned. The resulting `Item` may be `this` or
    * `this` merged with the item in the store
    *
    * @param store
    *   the `Storage` instance
    *
    * @return
    *   the updated item
    */
  def createOrUpdateInStore(store: Storage, context: Item => String): Item = {
    store
      .write(
        identifierString,
        {
          case None        => Some(this)
          case Some(other) => Some(this.merge(other))
        },
        context
      )
      .get // we know we just created an item
  }

  def updateBackReferences(store: Storage, parentScope: ParentScope): Item = {
    this
      .buildListOfReferencesForAliasFromBuiltFromContainedBy()
      .foreach { case (aliasType, itemNeedingAlias) =>
        store.write(
          itemNeedingAlias,
          {
            case Some(item) =>
              Some(
                item.copy(connections =
                  item.connections + (aliasType -> this.identifierString)
                )
              )
            case None =>
              Some(
                Item(
                  Identifier(itemNeedingAlias),
                  // noopLocationReference,
                  TreeSet(aliasType -> this.identifierString),
                  None,
                  None
                )
              )
          },
          item =>
            f"Updating alias reference ${itemNeedingAlias} ${item.bodyAsItemMetaData match {
                case None       => ""
                case Some(body) => f"files ${body.fileNames}"
              }} alias name ${aliasType} for item ${this.identifierString}${this.bodyAsItemMetaData match {
                case None       => ""
                case Some(body) => f" files ${body.fileNames}"
              }}, parent scope ${parentScope.parentScopeInformation()}"
        )

      }
    this
  }

  /** Get a list of all GitOIDs that this Item contains.
    *
    * @return
    *   a Vector of GitOIDs from "contains" edges
    */
  def listContains(): Vector[String] = {
    this.connections.toVector.filter(v => EdgeType.isContains(v._1)).map(_._2)
  }

  /** Merge this `Item` with another `Item`
    *
    * @param other
    *   the item to merge with
    *
    * @return
    *   the merged items
    */
  def merge(other: Item): Item = {

    val (body, mime) =
      (this.body, other.body, this.bodyMimeType == other.bodyMimeType) match {
        case (Some(a: ItemMetaData), Some(b: ItemMetaData), true) =>
          Some(
            a.merge(b, () => this.listContains(), () => other.listContains())
          ) -> this.bodyMimeType
        case (Some(a: ItemTagData), Some(b: ItemTagData), true) =>
          Some(
            a.merge(b)
          ) -> this.bodyMimeType

        case (Some(a), _, _) => Some(a) -> this.bodyMimeType
        case (_, Some(b), _) => Some(b) -> other.bodyMimeType
        case _               => None -> None
      }

    Item(
      identifier = this.identifier,
      connections = this.connections ++ other.connections,
      bodyMimeType = mime,
      body = body
    )

  }

  /** Given an `Item`, enhance it with PackageURLs
    *
    * @param purls
    *   the PackageURLs
    * @param fileNames
    *   the filenames associated with this Item
    *
    * @return
    *   the enhanced `Item`
    */
  def enhanceItemWithPurls(purls: Seq[PackageURL]): Item = {
    if (purls.isEmpty) this
    else {

      val textPurls = purls.map(p => p.canonicalize())
      val ret = this.copy(
        connections = this.connections ++ TreeSet(
          textPurls.map(purl => EdgeType.aliasFrom -> purl)*
        ),
        body = this.body match {
          case None =>
            Some(
              ItemMetaData(
                fileNames = TreeSet(textPurls*),
                mimeType = TreeSet(),
                fileSize = 0,
                extra = TreeMap()
              )
            )
          case Some(body: ItemMetaData) =>
            Some(body.copy(fileNames = body.fileNames ++ textPurls))
          case Some(body: ItemTagData) =>
            Some(body)
        }
      )
      ret
    }
  }

  /** Enhance an `Item` with metadata including filenames and extra
    *
    * @param extra
    *   the extra metadata to add
    * @param filenames
    *   the extra filenames to add
    *
    * @return
    *   the enhanced `Item`
    */
  def enhanceWithMetadata(
      maybeParent: Option[Gitoid] = None,
      extra: TreeMap[String, TreeSet[StringOrPair]] = TreeMap(),
      filenames: Seq[String] = Vector(),
      mimeTypes: Seq[String] = Vector()
  ): Item = {
    this.copy(
      bodyMimeType = this.bodyMimeType match {
        case Some(v) => Some(v)
        case None    => Some(ItemMetaData.mimeType)
      },
      body = Some({
        val base = this.body match {
          case Some(b: ItemMetaData) => b
          case _ =>
            ItemMetaData(
              fileNames = TreeSet(),
              mimeType = TreeSet(),
              fileSize = 0,
              extra = TreeMap()
            )
        }
        val baseFileNames = base.fileNames

        // If the filename we found is different from any existing filenames,
        // include the gitoid of the thing containing the filename
        // this will support detection of docker image file overlays
        // if there's just one filename,
        val augmentedFileNames = filenames.flatMap(name =>
          maybeParent match {
            case Some(parent)
                if baseFileNames.size > 1 || (baseFileNames.size == 1 && !baseFileNames
                  .contains(name)) =>
              Vector(name, f"${parent()}/${name}")
            case _ => Vector(name)
          }
        )

        base.copy(
          fileNames = base.fileNames ++ augmentedFileNames,
          extra = base.extra ++ extra,
          mimeType = base.mimeType ++ TreeSet(mimeTypes*)
        )

      })
    )
  }

  def getMd5(): Array[Byte] = this.md5
  def getIdentifier(): String = identifierString
  def isRootWorkItem(): Boolean = isRoot()
}

/** Companion object for Item providing factory methods, CBOR codecs, and
  * utilities.
  */
object Item {

  /** The sentinel identifier used for the "tags" root Item — the Item under
    * which per-package tag references are anchored. Not a real gitoid URL. */
  val tagsRootIdentifier: Identifier = Identifier.sentinel("tags")

  /** Canonical String form of `tagsRootIdentifier` for use where a String
    * storage key is required. */
  val tagsRootIdentifierString: String = tagsRootIdentifier.apply()
  protected val logger: Logger = Logger(getClass())

  /** Given an ArtifactWrapper, create an `Item` based on the hashes/gitoids for
    * the artifact
    *
    * @param artifact
    *   the artifact to compute the Item for
    * @param container
    *   if this item is contained by another item, include that
    *
    * @return
    *   the created item
    */
  def itemFrom(artifact: ArtifactWrapper, container: Option[Gitoid]): Item = {
    val (id, hashes) = GitOIDUtils.computeAllHashes(artifact)
    Item(
      Identifier.fromGitoid(id),
      // Item.noopLocationReference,
      TreeSet(
        hashes.map(hash => EdgeType.aliasFrom -> hash)*
      ) ++ container.toSeq.map(c => EdgeType.containedBy -> c()),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(),
          mimeType = TreeSet.from(artifact.mimeType),
          fileSize = artifact.size(),
          extra = TreeMap()
        )
      )
    )
  }

  /** Given `Item`s, create a mapping between the filenames and the GitOIDs for
    * the item. Used as part of the Maven/JVM source mapping features.
    *
    * @param items
    *   the items to map
    * @param nameFilter
    *   allows filtering of the name (e.g., remove pURL, etc.)
    * @param mimeFilter
    *   allows filtering of mime type to ensure it's a mapping to the mime types
    *   that are interesting
    *
    * @return
    *   a map from filenames to gitoids
    */
  def itemsToFilenameGitOIDMap(
      items: Seq[Item],
      nameFilter: String => Boolean = s => true,
      mimeFilter: Set[String] => Boolean = s => true
  ): Map[String, Gitoid] = {
    val mapping = for {
      item <- items
      metadata <- item.body match {
        case Some(body: ItemMetaData) if mimeFilter(body.mimeType) =>
          Some(body).toSeq
        case _ => None.toSeq
      }
      filename <- metadata.fileNames.toSeq if nameFilter(filename)
    } yield filename -> item.identifier.asGitoid.getOrElse(
      Gitoid(item.identifierString)
    )

    Map(mapping*)
  }

  given forOption[T: Encoder]: Encoder.DefaultValueAware[Option[T]] =
    new Encoder.DefaultValueAware[Option[T]] {

      def write(w: Writer, value: Option[T]): Writer =
        value match {
          case Some(x) => w.write(x)
          case None    => w.writeNull()
        }

      def withDefaultValue(defaultValue: Option[T]): Encoder[Option[T]] =
        if (defaultValue eq None)
          new Encoder.PossiblyWithoutOutput[Option[T]] {
            def producesOutputFor(value: Option[T]): Boolean = value ne None
            def write(w: Writer, value: Option[T]): Writer =
              value match {
                case Some(x) => w.write(x)
                case None    => w
              }
          }
        else this
    }

  /** A no-op location reference (0, 0) used as a placeholder. */
  val noopLocationReference: LocationReference = (0L, 0L)

  /** CBOR encoder for Item. The `identifier` is written as a CBOR byte string
    * for CBOR output (compact, no String materialisation) and as the canonical
    * text form for JSON output (which can't represent byte strings). */
  given Encoder[Item] = {

    import io.bullet.borer.Dom
    new Encoder[Item] {
      def write(w: Writer, item: Item): w.type = {
        w.writeMapOpen(4)
        item.body match {
          case None                  => w.writeMapMember("body", Dom.NullElem)
          case Some(v: ItemMetaData) => w.writeMapMember("body", v)
          case Some(v: ItemTagData)  => w.writeMapMember("body", v.tag)
        }

        item.bodyMimeType match {
          case None => w.writeMapMember("body_mime_type", Dom.NullElem)
          case Some(mimeType) => w.writeMapMember("body_mime_type", mimeType)

        }

        w.writeMapMember("connections", item.connections)
        // Default: write the identifier as the canonical text form. The
        // decoder (below) still accepts CBOR byte-string input, so a future
        // encoder can be switched to binary by replacing this line with
        //   if (w.writingCbor) w.writeMapMember("identifier", item.identifier)
        //   else                w.writeMapMember("identifier", item.identifierString)
        // The focused `GraphBenchmark` shows binary saves ~8% on-disk and is
        // 11-28% faster on decode/read but ~14% slower on disk write; the
        // full sbt test wall-clock is too noisy to distinguish the two.
        w.writeMapMember("identifier", item.identifierString)
        w.writeMapClose()

      }
    }
  }

  /** CBOR decoder for Item. */
  given Decoder[Item] = {
    new Decoder[Item] {
      import io.bullet.borer.Dom
      def read(r: Reader): Item = {
        val unbounded = r.readMapOpen(4)
        assert(r.readString() == "body")
        val bodyOpt: Option[Dom.Element] = if (r.hasNull) {
          r.readNull()
          None
        } else {
          Some(r.read[Dom.Element]())
        }
        assert(r.readString() == "body_mime_type")
        val bodyMimeType: Option[String] = if (r.hasNull) {
          r.readNull()
          None
        } else { Some(r.readString()) }
        assert(r.readString() == "connections")
        val connections: TreeSet[Edge] = r.read[TreeSet[Edge]]()
        assert(r.readString() == "identifier")
        // Identifier may arrive as a CBOR byte string (current wire format) or
        // — for legacy data / JSON input — as a text string. Dispatch on the
        // data-item type.
        val identifier: Identifier =
          if (r.hasByteArray) r.read[Identifier]()
          else Identifier(r.readString())

        val ret = Item(
          identifier,
          connections,
          bodyMimeType,
          (bodyMimeType, bodyOpt) match {
            case (None, _) => None
            case (Some(ItemMetaData.mimeType), Some(body)) =>
              Some(Cbor.transEncode(body).transDecode.to[ItemMetaData].value)
            case (Some(ItemTagData.mimeType), Some(body)) =>
              Some(ItemTagData(body))

            case _ =>
              throw new IllegalArgumentException(
                s"Unexpected bodyMimeType/bodyOpt combination: bodyMimeType=$bodyMimeType, bodyOpt=$bodyOpt"
              )
          }
        )
        r.readMapClose(unbounded = unbounded, ret)
      }
    }
  }

  /** Decode an Item from CBOR bytes.
    *
    * @param bytes
    *   the CBOR-encoded bytes
    * @return
    *   a Try containing the decoded Item or an error
    */
  def decode(bytes: Array[Byte]): Try[Item] = {
    Cbor.decode(bytes).to[Item].valueTry
  }

  /** Encode an Item using the v2 streamed CBOR format.
    *
    * The on-disk shape is the same map as the legacy encoder (`body`,
    * `body_mime_type`, `connections`, `identifier`), but each entry of
    * `connections` is a `WireEdge` rather than a `Tuple2[String, String]`.
    *
    * For each edge `(edgeTypeStr, targetGitoidStr)`:
    *   - if the target is already recorded in `ctx.gitoidToLoc`, the edge
    *     becomes a `WireEdge.SameFile` or `WireEdge.CrossFile` backref
    *   - if the edge-type string is one of the known constants in
    *     `EdgeTypeId`, the edge becomes `WireEdge.External`
    *   - otherwise the edge becomes `WireEdge.UnknownType` and round-trips
    *     verbatim.
    *
    * The encoder does **not** mutate `ctx`; the caller is responsible for
    * calling `ctx.record(identifierString, offset)` after the bytes are
    * actually appended to the DataFile.
    */
  def encodeStreamed(item: Item, ctx: WriteContext): Array[Byte] = {
    import io.bullet.borer.Dom
    // Custom one-off encoder so we stream directly through the writer
    // (no intermediate Vector[WireEdge]). Mirrors the legacy `given Encoder`
    // exactly except for the `connections` field.
    val encoder: Encoder[Item] = new Encoder[Item] {
      def write(w: Writer, it: Item): w.type = {
        w.writeMapOpen(4)
        it.body match {
          case None                  => w.writeMapMember("body", Dom.NullElem)
          case Some(v: ItemMetaData) => w.writeMapMember("body", v)
          case Some(v: ItemTagData)  => w.writeMapMember("body", v.tag)
        }
        it.bodyMimeType match {
          case None           => w.writeMapMember("body_mime_type", Dom.NullElem)
          case Some(mimeType) => w.writeMapMember("body_mime_type", mimeType)
        }
        // connections: write directly as a sized CBOR array of WireEdges
        // without materialising the intermediate Vector.
        w.writeString("connections")
        val size = it.connections.size
        w.writeArrayOpen(size)
        val cur = ctx.currentFileOrdinal
        it.connections.foreach { case (edgeTypeStr, targetGitoid) =>
          val etId = EdgeTypeId.fromString(edgeTypeStr)
          val we: WireEdge =
            if (etId == EdgeTypeId.Unknown) {
              WireEdge.UnknownType(edgeTypeStr, targetGitoid)
            } else {
              ctx.gitoidToLoc.get(targetGitoid) match {
                case Some((fo, off)) =>
                  if (fo == cur) WireEdge.SameFile(etId, off)
                  else WireEdge.CrossFile(etId, fo, off)
                case None => WireEdge.External(etId, targetGitoid)
              }
            }
          w.write(we)
        }
        w.writeArrayClose()
        w.writeMapMember("identifier", it.identifierString)
        w.writeMapClose()
        w
      }
    }
    Cbor.encode(item)(using encoder).toByteArray
  }

  /** Decode an Item from v2 streamed CBOR bytes.
    *
    * `WireEdge.SameFile` and `WireEdge.CrossFile` backref edges are resolved
    * against `ctx.locToGitoid`; if a backref points at an unknown offset,
    * decoding fails with an `IllegalStateException`. */
  def decodeStreamed(bytes: Array[Byte], ctx: ReadContext): Try[Item] = {
    val decoder: Decoder[Item] = new Decoder[Item] {
      import io.bullet.borer.Dom
      def read(r: Reader): Item = {
        val unbounded = r.readMapOpen(4)
        assert(r.readString() == "body")
        val bodyOpt: Option[Dom.Element] = if (r.hasNull) {
          r.readNull()
          None
        } else {
          Some(r.read[Dom.Element]())
        }
        assert(r.readString() == "body_mime_type")
        val bodyMimeType: Option[String] = if (r.hasNull) {
          r.readNull()
          None
        } else { Some(r.readString()) }
        assert(r.readString() == "connections")
        // Connections are written as a sized CBOR array of WireEdges. Let
        // Borer's collection decoder pull them in one go, then resolve each
        // to a legacy `Edge` against the per-DataFile context.
        val cur = ctx.currentFileOrdinal
        val wireEdges = r.read[Vector[WireEdge]]()
        val connectionsBuilder = TreeSet.newBuilder[Edge]
        wireEdges.foreach { we =>
          connectionsBuilder += resolveWireEdge(we, ctx, cur)
        }
        val connections: TreeSet[Edge] = connectionsBuilder.result()

        assert(r.readString() == "identifier")
        val identifier: Identifier =
          if (r.hasByteArray) r.read[Identifier]()
          else Identifier(r.readString())

        val ret = Item(
          identifier,
          connections,
          bodyMimeType,
          (bodyMimeType, bodyOpt) match {
            case (None, _) => None
            case (Some(ItemMetaData.mimeType), Some(body)) =>
              Some(Cbor.transEncode(body).transDecode.to[ItemMetaData].value)
            case (Some(ItemTagData.mimeType), Some(body)) =>
              Some(ItemTagData(body))
            case _ =>
              throw new IllegalArgumentException(
                s"Unexpected bodyMimeType/bodyOpt combination: bodyMimeType=$bodyMimeType, bodyOpt=$bodyOpt"
              )
          }
        )
        r.readMapClose(unbounded = unbounded, ret)
      }
    }
    Cbor.decode(bytes).to[Item](using decoder).valueTry
  }

  /** Resolve a `WireEdge` to the in-memory `Edge` `(String, String)` form
    * using the supplied `ReadContext`. */
  private def resolveWireEdge(
      we: WireEdge,
      ctx: ReadContext,
      currentFileOrdinal: Byte
  ): Edge = {
    we match {
      case WireEdge.SameFile(et, off) =>
        val target = ctx.resolve(currentFileOrdinal, off).getOrElse(
          throw new IllegalStateException(
            s"v2 SameFile edge points at unknown offset $off in file $currentFileOrdinal"
          )
        )
        (
          EdgeTypeId
            .toStringOpt(et)
            .getOrElse(
              throw new IllegalStateException(
                s"Unknown EdgeTypeId $et in SameFile backref"
              )
            ),
          target
        )
      case WireEdge.CrossFile(et, fo, off) =>
        val target = ctx.resolve(fo, off).getOrElse(
          throw new IllegalStateException(
            s"v2 CrossFile edge points at unknown offset $off in file $fo"
          )
        )
        (
          EdgeTypeId
            .toStringOpt(et)
            .getOrElse(
              throw new IllegalStateException(
                s"Unknown EdgeTypeId $et in CrossFile backref"
              )
            ),
          target
        )
      case WireEdge.External(et, gid) =>
        (
          EdgeTypeId
            .toStringOpt(et)
            .getOrElse(
              throw new IllegalStateException(
                s"Unknown EdgeTypeId $et in External edge"
              )
            ),
          gid
        )
      case WireEdge.UnknownType(et, gid) => (et, gid)
    }
  }

}

/** Information about how to tag an ADG.
  *
  * @param name
  *   the tag name
  * @param extra
  *   optional additional JSON/CBOR data to include with the tag
  */
case class TagInfo(name: String, extra: Option[io.bullet.borer.Dom.Element])

/** Information for per-package tagging.
  *
  * @param name
  *   the package name (e.g., "org.example:artifact" or "artifact")
  * @param version
  *   optional package version (omitted from JSON if None)
  * @param date
  *   optional build/publish date (uses current date if None when creating tag)
  */
case class PackageTagInfo(
    name: String,
    version: Option[String],
    date: Option[java.util.Date]
)

object PackageTagInfo {
  import org.json4s._
  import org.json4s.native.JsonMethods._
  import org.json4s.native.Serialization
  import org.json4s.JsonDSL._
  import java.text.SimpleDateFormat
  import java.util.TimeZone

  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  /** Convert PackageTagInfo to JSON string.
    *
    * Omits version field if None. Formats date as ISO 8601 UTC.
    */
  def toJson(info: PackageTagInfo): String = {
    val dateStr = info.date
      .map(formatDateISO8601)
      .getOrElse(formatDateISO8601(new java.util.Date()))

    val json = info.version match {
      case Some(ver) =>
        ("tag" -> info.name) ~
          ("version" -> ver) ~
          ("date" -> dateStr)
      case None =>
        ("tag" -> info.name) ~
          ("date" -> dateStr)
    }

    compact(render(json))
  }

  /** Format a Date as ISO 8601 string (public version).
    */
  def toIso8601(date: java.util.Date): String = formatDateISO8601(date)

  private def formatDateISO8601(date: java.util.Date): String = {
    val tz = TimeZone.getTimeZone("UTC")
    val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    df.setTimeZone(tz)
    df.format(date)
  }
}
