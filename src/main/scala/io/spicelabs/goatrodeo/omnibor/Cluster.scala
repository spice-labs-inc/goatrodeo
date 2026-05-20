package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.envelopes.ClusterFileEnvelope
import io.spicelabs.goatrodeo.envelopes.DataFileEnvelope
import io.spicelabs.goatrodeo.envelopes.IndexFileEnvelope
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.nio.channels.FileChannel
import scala.collection.immutable.TreeSet
import scala.util.Using

case class IndexFile(
    envelope: IndexFileEnvelope,
    file: File,
    dataOffset: Long
)

object IndexFile {
  def open(dir: File, hash: Long): IndexFile = {
    val file = GoatRodeoCluster.findFile(dir, hash, "gri");
    val testedHash = Helpers.byteArrayToLong63Bits(Helpers.computeSHA256(file));
    if (testedHash != hash) {
      throw Exception(
        f"Index file for ${file.getName()} does not match ${String.format("%016x", testedHash)}"
      )
    }

    Using.resource(FileInputStream(file)) { inputStream =>
      val ipf = inputStream.getChannel()
      val magic = Helpers.readInt(ipf)
      if (magic != GraphManager.Consts.IndexFileMagicNumber) {
        throw Exception(
          f"Unexpected magic number ${magic} expecting ${GraphManager.Consts.IndexFileMagicNumber} for ${file.getName()}"
        )
      }

      // Envelope length is written as a 2-byte short by `writeABlock`, so
      // we must read it the same way here. (Helpers.readLenAndCBOR reads
      // a 4-byte int, which doesn't match the wire format.)
      val envLen = Helpers.readShort(ipf)
      val indexEnv: IndexFileEnvelope = Helpers.readCBOR(ipf, envLen)
      val indexPos = ipf.position()
      IndexFile(indexEnv, file, indexPos)
    }
  }

}

case class DataFile(
    envelope: DataFileEnvelope,
    private val file: FileChannel,
    dataOffset: Long
) {
  def readItemAt(pos: Long): Item =
    this.synchronized {
      file.position(pos)
      val itemLen = Helpers.readInt(file)
      val item: Item = Helpers.readCBOR[Item](file, itemLen)

      item
    }
}

object DataFile {
  def open(dir: File, hash: Long): DataFile = {
    val file = GoatRodeoCluster.findFile(dir, hash, "grd");
    val testedHash = Helpers.byteArrayToLong63Bits(Helpers.computeSHA256(file));
    if (testedHash != hash) {
      throw Exception(
        f"Index file for ${file.getName()} does not match ${String.format("%016x", testedHash)}"
      )
    }

    Using.resource(FileInputStream(file)) { inputStream =>
      val ipf = inputStream.getChannel()
      val magic = Helpers.readInt(ipf)
      if (magic != GraphManager.Consts.DataFileMagicNumber) {
        throw Exception(
          f"Unexpected magic number ${magic} expecting ${GraphManager.Consts.DataFileMagicNumber} for ${file.getName()}"
        )
      }

      val envLen = Helpers.readShort(ipf)
      val dataEnv: DataFileEnvelope = Helpers.readCBOR(ipf, envLen)
      val indexPos = ipf.position()
      DataFile(dataEnv, FileInputStream(file).getChannel(), indexPos)
    }
  }

}

case class GoatRodeoCluster(
    envelope: ClusterFileEnvelope,
    path: File,
    dataFiles: Map[Long, DataFile],
    indexFiles: Map[Long, IndexFile],
    /** Cluster-wide alias map, loaded from the `<hash>.gra` sidecar
      * file referenced by `envelope.aliasMapFile`. Empty when the
      * cluster has no aliases (e.g. v1 clusters, or v4 clusters with
      * `aliasMapFile = None`). */
    aliasMap: scala.collection.immutable.TreeMap[String, scala.collection.immutable.TreeSet[String]] =
      scala.collection.immutable.TreeMap.empty
) {

  /** Look up the alternates registered for a canonical identifier, if
    * any. Useful for v4 readers that want to surface the `alias:from`
    * edges that were stripped at write time. */
  def aliasesFor(canonical: String): scala.collection.immutable.TreeSet[String] =
    aliasMap.getOrElse(canonical, scala.collection.immutable.TreeSet.empty)

  /** Inverse of [[aliasMap]] — `alternateIdentifier → canonicalIdentifier`.
    * Built lazily on first access. */
  lazy val aliasInverseMap: Map[String, String] = {
    val b = scala.collection.mutable.Map.empty[String, String]
    for {
      (canon, aliases) <- aliasMap
      alias <- aliases
    } b.update(alias, canon)
    b.toMap
  }

  /** Resolve any alternate identifier to its canonical form. Returns the
    * input unchanged if it's already canonical (or not known to the
    * cluster). */
  def canonicalise(id: String): String = aliasInverseMap.getOrElse(id, id)

  /** Forward-edge index — `(edgeType, target) → set of source identifiers`.
    *
    * Built lazily by streaming every Item in every DataFile via
    * `GRDWalker`. Because v3 GRDWalker synthesises the dropped
    * `alias:from` edges back from `DataFileEnvelope.aliasMap`, this index
    * also captures alias relationships, not just content edges.
    *
    * Cost: one full sequential scan of the cluster on first use, plus
    * O(total-edges) memory for the index. */
  private lazy val forwardEdgeIndex: Map[(String, String), Set[String]] = {
    val b =
      scala.collection.mutable.Map
        .empty[(String, String), scala.collection.mutable.Set[String]]
    for ((hash, _) <- dataFiles) {
      val dataFileFile = GoatRodeoCluster.findFile(path, hash, "grd")
      val fc = new FileInputStream(dataFileFile).getChannel()
      try {
        val walker = new GRDWalker(fc)
        walker.open()
        var next = walker.readNext()
        while (next.isDefined) {
          val item = next.get
          val src = item.identifierString
          for ((et, target) <- item.connections.iterator) {
            b.getOrElseUpdate((et, target), scala.collection.mutable.Set.empty) += src
          }
          next = walker.readNext()
        }
      } finally fc.close()
    }
    b.iterator.map { case (k, v) => k -> v.toSet }.toMap
  }

  /** Answer "what items point at me via `edgeType`?"
    *
    * The on-disk format drops the inverse half of every content-edge
    * pair (`contains` and `buildsTo`) and folds alias edges into the
    * envelope's `aliasMap`. This helper synthesises the answer to the
    * inverse-direction question by inverting the surviving forward
    * edges, plus consulting the alias map for the alias direction.
    *
    * Examples:
    *   - `cluster.reverseEdges(X, EdgeType.contains)` — items whose
    *     `containedBy` points at X (X's children, in the legacy
    *     "X contains Y" sense).
    *   - `cluster.reverseEdges(X, EdgeType.buildsTo)` — items that X was
    *     built from.
    *   - `cluster.reverseEdges(X, EdgeType.aliasTo)` — the canonical id
    *     of X if X is an alias, otherwise empty.
    *
    * The `id` argument is canonicalised first, so callers may pass any
    * alias and get the same answer. */
  def reverseEdges(id: String, edgeType: String): Set[String] = {
    val canon = canonicalise(id)
    edgeType match {
      case EdgeType.aliasTo =>
        // X.aliasTo points at canonical. If we're asked "what points at me
        // via aliasTo", the answer is: nothing if I'm canonical (aliases
        // don't exist as items post-Slice-2); my own canonical otherwise.
        if (canon != id) Set(canon) else Set.empty
      case other =>
        // Map inverse-of-content/tag/alias to its forward counterpart.
        val forwardEt = other match {
          case EdgeType.contains => EdgeType.containedBy
          case EdgeType.buildsTo => EdgeType.builtFrom
          case EdgeType.tagTo    => EdgeType.tagFrom
          case x                 => x
        }
        forwardEdgeIndex.getOrElse((forwardEt, canon), Set.empty)
    }
  }
}

object GoatRodeoCluster {
  def findFile(dir: File, hash: Long, suffix: String): File = {
    File(dir, f"${String.format("%016x", hash)}.${suffix}")
  }

  /** Read and decode a `<hash>.gra` alias-map sidecar file.
    *
    * The file's layout matches [[GraphManager.writeAliasMapFile]]:
    *
    *   - 4 bytes — [[GraphManager.Consts.AliasMapFileMagicNumber]].
    *   - Rest of the file — a single CBOR-encoded
    *     `TreeMap[String, TreeSet[String]]` (no length prefix; the map
    *     is self-delimiting and consumes the remainder of the file).
    *
    * This is the read companion to the sidecar emission that replaces
    * the v3 embedded-alias-map design (which silently truncated for
    * any cluster whose alias map exceeded ~64 KiB).
    */
  private def loadAliasMapFile(
      dir: File,
      hash: Long
  ): scala.collection.immutable.TreeMap[String, scala.collection.immutable.TreeSet[String]] = {
    val file = findFile(dir, hash, "gra")
    Using.resource(FileInputStream(file)) { fis =>
      val fc = fis.getChannel()
      val magic = Helpers.readInt(fc)
      if (magic != GraphManager.Consts.AliasMapFileMagicNumber) {
        throw Exception(
          f"Unexpected magic number ${magic} expecting ${GraphManager.Consts.AliasMapFileMagicNumber} for alias-map file ${file.getName()}"
        )
      }
      // Read the remainder of the file as a single CBOR-encoded map.
      val remaining = (fc.size() - fc.position()).toInt
      val buf = java.nio.ByteBuffer.allocate(remaining)
      while (buf.hasRemaining()) {
        if (fc.read(buf) < 0) {
          throw Exception(
            f"Unexpected EOF while reading alias-map file ${file.getName()}"
          )
        }
      }
      buf.position(0)
      import io.bullet.borer.Cbor
      Cbor
        .decode(buf)
        .to[
          scala.collection.immutable.TreeMap[String, scala.collection.immutable.TreeSet[String]]
        ]
        .value
    }
  }
  def open(path: File): GoatRodeoCluster = {

    val fileName = path.getName()
    val fileNameLen = fileName.length()
    val hash = BigInteger
      .apply(fileName.substring(fileNameLen - 20, fileNameLen - 4), 16)
      .longValue()

    val testedHash = Helpers.byteArrayToLong63Bits(Helpers.computeSHA256(path))

    if (testedHash != hash) {
      throw Exception(
        f"Cluster file file for '${fileName}' does not match actual hash ${String
            .format("%016x", testedHash)}"
      );
    }
    val theDir = path.getAbsoluteFile().getParentFile()

    Using.resource(FileInputStream(path)) { clusterFile =>
      val dfp = clusterFile.getChannel()
      val magic = Helpers.readInt(dfp)
      if (magic != GraphManager.Consts.ClusterFileMagicNumber) {
        throw Exception(
          f"Unexpected magic number ${magic}, expecting ${GraphManager.Consts.ClusterFileMagicNumber} for data file ${fileName}"
        )
      }

      val envLen = Helpers.readShort(dfp)
      val env: ClusterFileEnvelope = Helpers.readCBOR(dfp, envLen)

      if (env.magic != GraphManager.Consts.ClusterFileMagicNumber) {
        throw Exception(
          f"Loaded a cluster with an invalid magic number: ${env}"
        );
      }

      val indexFiles = Map((for { indexFile <- env.indexFiles } yield {

        (indexFile -> IndexFile.open(theDir, indexFile))
      })*)

      val dataFileHashes = TreeSet((for {
        idx <- indexFiles.values; dataFile <- idx.envelope.dataFiles
      } yield dataFile).toSeq*)

      val dataFiles = Map((for {
        dataFileHash <- dataFileHashes.toSeq
      } yield (dataFileHash -> DataFile.open(theDir, dataFileHash)))*)

      val aliasMap = env.aliasMapFile match {
        case Some(hash) => loadAliasMapFile(theDir, hash)
        case None       =>
          scala.collection.immutable.TreeMap
            .empty[String, scala.collection.immutable.TreeSet[String]]
      }

      GoatRodeoCluster(
        envelope = env,
        path = theDir,
        dataFiles = dataFiles,
        indexFiles = indexFiles,
        aliasMap = aliasMap
      )
    }

  }
}
