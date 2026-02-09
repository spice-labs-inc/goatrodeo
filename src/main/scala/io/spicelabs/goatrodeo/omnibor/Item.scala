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
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.GitOIDUtils
import io.spicelabs.goatrodeo.util.Helpers

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try
import io.spicelabs.rodeocomponents.APIS.artifacts.WorkItem
import java.{util => ju}
import io.spicelabs.rodeocomponents.APIS.artifacts.BackendStorage
import scala.jdk.OptionConverters._
import scala.jdk.CollectionConverters.*
import io.spicelabs.rodeocomponents.APIS.purls.Purl
import io.spicelabs.goatrodeo.components.PurlAdapter
import io.spicelabs.goatrodeo.util.TreeMapExtensions.JUListExtensions.asVectorMap
import io.spicelabs.goatrodeo.util.TreeMapExtensions.JUListExtensions.asVector
import io.spicelabs.rodeocomponents.APIS.artifacts.StringPair
import io.spicelabs.rodeocomponents.APIS.artifacts.ParentFrame
import io.spicelabs.rodeocomponents.APIS.artifacts.StringPairOptional
import scala.annotation.tailrec
import io.spicelabs.goatrodeo.omnibor.Item.toTreeMap

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
    identifier: String,
    // reference: LocationReference,
    connections: TreeSet[Edge],
    @key("body_mime_type") bodyMimeType: Option[String],
    body: Option[ItemMetaData | ItemTagData]
) extends WorkItem {

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

  private lazy val md5 = Helpers.computeMD5(identifier)

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
    val myHash = Helpers.md5hashHex(identifier)
    val thatHash = Helpers.md5hashHex(that.identifier)
    myHash < thatHash
  }

  /** Is the item a root item
    *
    * @return
    */
  def isRoot(): Boolean = {
    if (this.bodyMimeType != Some(ItemMetaData.mimeType)) {
      false
    } else if (this.identifier == "tags") {
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
        identifier,
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
                  item.connections + (aliasType -> this.identifier)
                )
              )
            case None =>
              Some(
                Item(
                  itemNeedingAlias,
                  // noopLocationReference,
                  TreeSet(aliasType -> this.identifier),
                  None,
                  None
                )
              )
          },
          item =>
            f"Updating alias reference ${itemNeedingAlias} ${item.bodyAsItemMetaData match {
                case None       => ""
                case Some(body) => f"files ${body.fileNames}"
              }} alias name ${aliasType} for item ${this.identifier}${this.bodyAsItemMetaData match {
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
      maybeParent: Option[GitOID] = None,
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
              Vector(name, f"${parent}/${name}")
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

  override def compareMd5(other: WorkItem): Boolean = cmpMd5(
    other.asInstanceOf[Item]
  )
  override def containedGitoids(): (ju.List[String]) = {
    listContains().foldLeft(java.util.ArrayList[String]())((list, item) => {
      list.add(item)
      list
    })
  }
  override def createOrUpdateInStorage(
      store: BackendStorage,
      context: java.util.function.Function[WorkItem, String]
  ): WorkItem = {
    createOrUpdateInStore(store.asInstanceOf[Storage], item => context(item))
  }
  override def enhanceWithMetadata(
      parent: ju.Optional[String],
      extra: ju.Map[String, ju.Set[StringPairOptional]],
      filenames: ju.List[String],
      mimeTypes: ju.List[String]
  ): WorkItem = {
    var par = parent.toScala
    var meta = toTreeMap(extra)
    val files = filenames.asVector
    val mimes = mimeTypes.asVector
    enhanceWithMetadata(par, meta, files, mimes)
  }
  override def enhanceWithPurls(purls: ju.List[Purl]): WorkItem = {
    val purlVec = purls.asVectorMap(p => {
      p.asInstanceOf[PurlAdapter].purl
    })
    enhanceItemWithPurls(purlVec)
  }
  override def getMd5(): Array[Byte] = this.md5
  override def merge(other: WorkItem): WorkItem = merge(
    other.asInstanceOf[Item]
  )
  override def getIdentifier(): String = identifier
  override def getConnections(): ju.Set[StringPair] = {
    connections.foldLeft(ju.TreeSet[StringPair]())((set, elem) => {
      set.add(StringPair(elem._1, elem._2))
      set
    })
  }
  override def withNewConnection(edgeType: String, id: String): WorkItem =
    withConnection(edgeType, id)
  override def isRootWorkItem(): Boolean = isRoot()
  override def referencedFromAliasOrBuildOrContained(): ju.List[StringPair] = {
    val vec = buildListOfReferencesForAliasFromBuiltFromContainedBy()
    vec.foldLeft(ju.ArrayList())((list, pair) => {
      list.add(StringPair(pair._1, pair._2))
      list
    })
  }
  override def updateTheBackReferences(
      store: BackendStorage,
      frame: ParentFrame
  ): WorkItem = {
    updateBackReferences(
      store.asInstanceOf[Storage],
      frame.asInstanceOf[ParentScope]
    )
  }
}

/** Companion object for Item providing factory methods, CBOR codecs, and
  * utilities.
  */
object Item {
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
  def itemFrom(artifact: ArtifactWrapper, container: Option[GitOID]): Item = {
    val (id, hashes) = GitOIDUtils.computeAllHashes(artifact)
    Item(
      id,
      // Item.noopLocationReference,
      TreeSet(
        hashes.map(hash => EdgeType.aliasFrom -> hash)*
      ) ++ container.toSeq.map(c => EdgeType.containedBy -> c),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(),
          mimeType = TreeSet(artifact.mimeType),
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
  ): Map[String, GitOID] = {
    val mapping = for {
      item <- items
      metadata <- item.body match {
        case Some(body: ItemMetaData) if mimeFilter(body.mimeType) =>
          Some(body).toSeq
        case _ => None.toSeq
      }
      filename <- metadata.fileNames.toSeq if nameFilter(filename)
    } yield filename -> item.identifier

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

  /** CBOR encoder for Item. */
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
        w.writeMapMember("identifier", item.identifier)
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
        val identifier = r.readString()

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

  private def toTreeMap(
      meta: ju.Map[String, ju.Set[StringPairOptional]]
  ): TreeMap[String, TreeSet[StringOrPair]] = {

    toTreeMapTail(
      meta.keySet().iterator(),
      meta,
      TreeMap[String, TreeSet[StringOrPair]]()
    )
  }

  @tailrec
  private def toTreeMapTail(
      iterator: ju.Iterator[String],
      meta: ju.Map[String, ju.Set[StringPairOptional]],
      result: TreeMap[String, TreeSet[StringOrPair]]
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    if (!iterator.hasNext()) {
      result
    } else {
      val key = iterator.next()
      val value = meta.get(key).asScala
      val newEntry =
        value.foldLeft(TreeSet[StringOrPair]())((set, stringPair) => {
          val first = stringPair.first()
          val second = stringPair.second()
          val sop =
            if second.isPresent then StringOrPair(first, second.get())
            else StringOrPair(first)
          set + sop
        })
      toTreeMapTail(iterator, meta, result + (key -> newEntry))
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
