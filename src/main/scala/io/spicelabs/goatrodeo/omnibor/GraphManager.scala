package io.spicelabs.goatrodeo.omnibor

import com.typesafe.scalalogging.Logger
import io.bullet.borer.Cbor
import io.spicelabs.goatrodeo.envelopes.ClusterFileEnvelope
import io.spicelabs.goatrodeo.envelopes.DataFileEnvelope
import io.spicelabs.goatrodeo.envelopes.IndexFileEnvelope
import io.spicelabs.goatrodeo.envelopes.Position
import io.spicelabs.goatrodeo.util.Helpers
import org.json4s.JsonDSL
import org.json4s.JsonDSL.*

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.util.Try

/** Manages persistence and retrieval of Artifact Dependency Graph (ADG) data.
  *
  * The graph data is stored in three types of files:
  *   - GRD (Goat Rodeo Data): Contains CBOR-encoded Item data
  *   - GRI (Goat Rodeo Index): Contains an index for looking up Items by hash
  *   - GRC (Goat Rodeo Cluster): Metadata about a set of GRD/GRI files
  *
  * Files are named by their SHA256 hash (as a hex string) with the appropriate
  * extension.
  */
object GraphManager {
  private val logger: Logger = Logger(getClass())

  /** Constants for file format magic numbers and limits. */
  object Consts {

    /** Magic number at the start of GRD (data) files: 0x00BE1100 ("Bell"). */
    val DataFileMagicNumber: Int = 0x00be1100 // Bell

    /** Magic number at the start of GRI (index) files: 0x54154170 ("Shishitō").
      */
    val IndexFileMagicNumber: Int = 0x54154170 // Shishitō

    /** Magic number at the start of GRC (cluster) files: 0xBA4A4A ("Banana").
      */
    val ClusterFileMagicNumber: Int = 0xba4a4a // Banana

    /** Magic number at the start of GRA (alias-map sidecar) files:
      * 0x0A11A5ED ("Aliased"). Written by the cluster's writeEntries
      * pass when the alias collapse produces a non-empty map. The
      * `.gra` file is referenced by hash from
      * [[io.spicelabs.goatrodeo.envelopes.ClusterFileEnvelope.aliasMapFile]].
      */
    val AliasMapFileMagicNumber: Int = 0x0a11a5ed // Aliased

    /** Target maximum file size (15 GB) before starting a new data file. */
    val TargetMaxFileSize: Long = 15L * 1024L * 1024L * 1024L // 15G
  }

  /** Holds the SHA256 hashes (as Long) of a paired data and index file.
    *
    * @param dataFile
    *   the hash of the GRD data file
    * @param indexFile
    *   the hash of the GRI index file
    */
  case class DataAndIndexFiles(dataFile: Long, indexFile: Long)
  private def writeABlock(
      targetDirectory: File,
      items: Iterator[Item],
      previous: Long,
      afterWrite: Item => Unit,
      writeCtx: WriteContext
  ): DataAndIndexFiles = {
    val start = Instant.now()
    // create temporary file
    val tempFile =
      Files.createTempFile(targetDirectory.toPath(), "goat_rodeo_data_", ".grd")

    val fileWriter = new FileOutputStream(tempFile.toFile())
    val writer = fileWriter.getChannel()
    var previousPosition: Long = 0
    Helpers.writeInt(writer, Consts.DataFileMagicNumber)
    val dataFileEnvelope =
      DataFileEnvelope.build(
        previous = previous,
        builtFromMerge = false
      )
    val envelopeBytes = dataFileEnvelope.encode()
    // write the DataFileEnvelope length
    Helpers.writeShort(writer, envelopeBytes.length)

    // write DataFileEnvelope
    val writtenLen = writer.write(ByteBuffer.wrap(envelopeBytes))
    var loopCnt = 0

    var pairs: Vector[(String, Array[Byte], Position)] = Vector()

    // Batched write buffer: a single reusable 4 MiB heap ByteBuffer that
    // accumulates length-prefixed item frames and is flushed to the
    // channel only when full (or when an oversized item arrives, or at
    // end-of-stream). Replaces the previous per-item
    // `ByteBuffer.allocate(256 + entryBytes.length)` + `writer.write` —
    // ~300k Items on the ADGTests corpus → ~300k allocations and
    // ~300k syscalls otherwise.
    //
    // `logicalPosition` is what the channel position *will be* once the
    // buffer is flushed, i.e. `writer.position() + buffer.position()`.
    // It is the offset recorded into `pairs` and into
    // `writeCtx.record(...)` for back-references, and it is also what
    // the `TargetMaxFileSize` check uses (writer.position() lags the
    // true byte stream by up to BUFFER_CAPACITY here).
    val BufferCapacity = 4 * 1024 * 1024
    val batch = ByteBuffer.allocate(BufferCapacity)
    var logicalPosition: Long = writer.position()

    def flushBatch(): Unit = {
      if (batch.position() > 0) {
        batch.flip()
        while (batch.hasRemaining()) writer.write(batch)
        batch.clear()
      }
    }

    // loop writing entries until empty or the file is >= 16GB in size
    while (items.hasNext && logicalPosition < Consts.TargetMaxFileSize) {
      val orgEntry = items.next()
      val currentPosition = logicalPosition
      val entry = orgEntry
      val md5 = entry.identifierMD5()
      val entryBytes = Item.encodeStreamed(entry, writeCtx)

      pairs = pairs.appended((Helpers.toHex(md5), md5, currentPosition))

      val frameLen = 4 + entryBytes.length
      if (frameLen <= BufferCapacity) {
        if (frameLen > batch.remaining()) flushBatch()
        batch.putInt(entryBytes.length)
        batch.put(entryBytes)
      } else {
        // Item larger than BufferCapacity: flush whatever is queued,
        // then write the frame directly to the channel. Rare in
        // practice (per-item CBOR is typically < a few KB).
        flushBatch()
        val header = ByteBuffer.allocate(4)
        header.putInt(entryBytes.length)
        header.flip()
        while (header.hasRemaining()) writer.write(header)
        val body = ByteBuffer.wrap(entryBytes)
        while (body.hasRemaining()) writer.write(body)
      }
      logicalPosition += frameLen

      // Record this item's location for backref encoding of items later in
      // the stream. The recorded offset is the start of the length-prefixed
      // frame (currentPosition), so the read path's `ReadContext.record`
      // can match positions exactly without having to skip the frame
      // header.
      writeCtx.record(entry.identifierString, currentPosition)

      previousPosition = currentPosition; // itemEnvelope.position;

      afterWrite(entry)
      loopCnt += 1
      if (loopCnt % 1000000 == 0) {
        logger.debug(
          f"Write loop ${loopCnt} at ${Duration.between(start, Instant.now())}"
        )
      }
    }

    // Final flush before writing the EOF trailer.
    flushBatch()

    // 4-byte -1 sentinel — matches the 4-byte length prefix `readNext`
    // expects for each item frame, so the reader can detect end-of-file
    // by reading -1 here. (Older revisions of this file wrote a 2-byte
    // short, which `readNext`'s `Helpers.readInt` could not recognise.)
    Helpers.writeInt(writer, -1)

    // write final back-pointer (to the last entry record)
    Helpers.writeLong(writer, previousPosition)

    // compute SHA256 of the file
    writer.close()

    logger.debug(
      f"Finished write loop at ${Duration.between(start, Instant.now())}"
    )

    // rename the file to <sha256>.grd
    val sha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(tempFile.toFile()))
    )

    val targetFileName =
      new File(targetDirectory, f"${Helpers.toHex(sha256Long)}.grd")

    tempFile.toFile().renameTo(targetFileName)

    logger.debug(
      f"Finished rename at ${Duration.between(start, Instant.now())}"
    )

    val targetIndexName =
      new File(targetDirectory, f"${Helpers.toHex(sha256Long)}.gri")

    val indexWriter = new FileOutputStream(targetIndexName).getChannel()
    Helpers.writeInt(indexWriter, Consts.IndexFileMagicNumber)
    val indexEnv = IndexFileEnvelope.build(
      size = pairs.length,
      dataFiles = Vector(sha256Long)
    )
    val indexEnvBytes = indexEnv.encode()
    Helpers.writeShort(indexWriter, indexEnvBytes.length)

    indexWriter.write(ByteBuffer.wrap(indexEnvBytes))
    val indexBB = ByteBuffer.allocate(pairs.length * 32)

    // Sort by MD5 byte array (unsigned, lex order). The collection is in
    // *write* order (topological from `Storage.prepareForWrite`), but the
    // .gri reader (both goatrodeo's own GRDWalker and BigTent's binary
    // search) requires entries sorted by raw MD5 bytes for lookups to
    // succeed.
    val sortedPairs = pairs.sortWith { (a, b) =>
      val ab = a._2
      val bb = b._2
      val n = math.min(ab.length, bb.length)
      var i = 0
      var result = 0
      while (i < n && result == 0) {
        result = (ab(i) & 0xff) - (bb(i) & 0xff)
        i += 1
      }
      if (result != 0) result < 0 else ab.length < bb.length
    }

    for { v <- sortedPairs } {
      indexBB.put(v._2)
      indexBB.putLong(sha256Long)
      indexBB.putLong(v._3)
    }

    indexBB.flip()

    indexWriter.write(indexBB)

    indexWriter.close()

    logger.debug(
      f"Finished index write at ${Duration.between(start, Instant.now())}"
    )

    val indexSha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(targetIndexName))
    )

    val indexTargetFileName =
      new File(targetDirectory, f"${Helpers.toHex(indexSha256Long)}.gri")

    targetIndexName.renameTo(indexTargetFileName)

    logger.debug(
      f"Finished index rename at ${Duration.between(start, Instant.now())}"
    )

    DataAndIndexFiles(sha256Long, indexSha256Long)
  }

  /** Write a collection of Items to GRD/GRI/GRC files.
    *
    * This is the main entry point for persisting Items to disk. It:
    *   1. Writes Items to GRD data files (splitting at TargetMaxFileSize) 2.
    *      Creates GRI index files for each GRD file 3. Creates a GRC cluster
    *      file referencing all GRD/GRI files 4. Writes a history.jsonl file
    *      with build metadata
    *
    * @param targetDirectory
    *   the directory to write files to
    * @param entries
    *   the Items to write
    * @return
    *   a tuple of (list of data/index file pairs, the cluster file)
    */
  def writeEntries(
      targetDirectory: File,
      entries: Iterator[Item],
      aliasMap: TreeMap[String, TreeSet[String]] = TreeMap.empty
  ): (Seq[DataAndIndexFiles], File) = {
    var previousInChain: Long = 0L
    var biggest: Vector[(Item, Int)] = Vector()

    def updateBiggest(item: Item): Unit = {
      val containedBy =
        item.connections.filter(_._1 == EdgeType.containedBy).size
      if (biggest.length <= 50) {
        biggest = (biggest :+ (item -> containedBy)).sortBy(_._2).reverse
      } else if (biggest.last._2 < containedBy) {
        biggest =
          (biggest.dropRight(1) :+ (item -> containedBy)).sortBy(_._2).reverse
      }
    }

    var fileSet: List[DataAndIndexFiles] = Nil
    val writeCtx = new WriteContext()
    while (entries.hasNext) {
      val dataAndIndex = writeABlock(
        targetDirectory,
        entries,
        previous = previousInChain,
        updateBiggest,
        writeCtx
      )
      previousInChain = dataAndIndex.dataFile
      fileSet = dataAndIndex :: fileSet
      // Next DataFile in this cluster gets a fresh ordinal so cross-file
      // backrefs are unambiguous. We cap at Byte.MaxValue; a cluster with
      // more than 127 DataFiles (each up to 15 GB → ~2 TB cluster) is
      // beyond the design target, but rather than truncate we fall back
      // to External edges past the cap.
      if (writeCtx.currentFileOrdinal < Byte.MaxValue)
        writeCtx.currentFileOrdinal =
          (writeCtx.currentFileOrdinal + 1).toByte
    }

    // If alias collapse produced a non-empty map, write it out to a
    // sidecar `.gra` file and capture the hash for the cluster envelope.
    // We do this here (after data/index files and before the cluster
    // envelope) so the cluster envelope can reference the sidecar by
    // hash and stay small itself.
    val aliasMapFileHash: Option[Long] =
      if (aliasMap.isEmpty) None
      else Some(writeAliasMapFile(targetDirectory, aliasMap))

    val tempFile =
      Files.createTempFile(
        targetDirectory.toPath(),
        "goat_rodeo_cluster_",
        ".grc"
      )
    val fileWriter = new FileOutputStream(tempFile.toFile())
    val writer = fileWriter.getChannel()

    Helpers.writeInt(writer, Consts.ClusterFileMagicNumber)
    val clusterEnvelope =
      ClusterFileEnvelope.build(
        indexFiles = fileSet.map(_.indexFile).toVector,
        dataFiles = fileSet.map(_.dataFile).toVector,
        aliasMapFile = aliasMapFileHash
      )
    val envelopeBytes = clusterEnvelope.encode()
    Helpers.writeShort(writer, envelopeBytes.length)
    writer.write(ByteBuffer.wrap(envelopeBytes))
    writer.close()
    val sha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(tempFile.toFile()))
    )

    val now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)

    val grcName =
      f"${now.getYear()}_${"%02d".format(now.getMonthValue())}_${"%02d"
          .format(now.getDayOfMonth())}_${"%02d".format(
          now
            .getHour()
        )}_${"%02d".format(now.getMinute())}_${"%02d"
          .format(now.getSecond())}_${Helpers.toHex(sha256Long)}.grc"

    val targetFile =
      new File(
        targetDirectory,
        grcName
      )

    tempFile.toFile().renameTo(targetFile)
    if (false) {
      for { i <- biggest } {
        logger.info(
          f"Item ${i._1.identifier} ${i._1.bodyAsItemMetaData.map(_.fileNames).getOrElse(Vector())} has ${i._2} connections"
        )
      }
    }

    import org.json4s.native.JsonMethods._

    val jsonLine = ("date" -> DateTimeFormatter.ISO_DATE_TIME.format(
      ZonedDateTime.now(ZoneId.of("UTC"))
    )) ~
      ("goat_rodeo_version" -> hellogoat.BuildInfo.version) ~
      ("operation" -> "build_adg") ~
      ("goat_rodeo_commit" -> hellogoat.BuildInfo.commit) ~ ("cluster_name" -> grcName)

    Files.writeString(
      File(targetDirectory, "history.jsonl").toPath(),
      f"${compact(render(jsonLine))}\n"
    )
    (fileSet, targetFile)
  }

  /** Write the cluster-wide alias map to a `<sha256_low_63>.gra`
    * sidecar file in `targetDirectory` and return the hash that names
    * the file (and is referenced from
    * [[io.spicelabs.goatrodeo.envelopes.ClusterFileEnvelope.aliasMapFile]]).
    *
    * File layout:
    *
    *   - 4 bytes — [[Consts.AliasMapFileMagicNumber]] (big-endian).
    *   - Rest of the file — a single CBOR-encoded
    *     `TreeMap[String, TreeSet[String]]` mapping canonical
    *     identifiers to their alternates.
    *
    * No length prefix on the CBOR payload: the map is self-delimiting,
    * and the file is consumed by reading to EOF. This avoids the 16-bit
    * length-prefix overflow that previously corrupted large embedded
    * alias maps inside `DataFileEnvelope` (v3).
    */
  private def writeAliasMapFile(
      targetDirectory: File,
      aliasMap: TreeMap[String, TreeSet[String]]
  ): Long = {
    val tempFile =
      Files.createTempFile(
        targetDirectory.toPath(),
        "goat_rodeo_alias_",
        ".gra"
      )
    val fileWriter = new FileOutputStream(tempFile.toFile())
    val writer = fileWriter.getChannel()
    try {
      Helpers.writeInt(writer, Consts.AliasMapFileMagicNumber)
      val cborBytes = Cbor.encode(aliasMap).toByteArray
      writer.write(ByteBuffer.wrap(cborBytes))
    } finally {
      writer.close()
    }
    val sha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(tempFile.toFile()))
    )
    val finalFile =
      new File(targetDirectory, f"${Helpers.toHex(sha256Long)}.gra")
    tempFile.toFile().renameTo(finalFile)
    sha256Long
  }
}

/** A walker for reading Items from a GRD (Goat Rodeo Data) file.
  *
  * Provides sequential access to Items stored in a GRD file. Use `open()` to
  * validate the file and read the envelope, then `readNext()` or `items()` to
  * iterate through Items.
  *
  * For v2 GRD files, the walker maintains an internal `ReadContext` so that
  * `WireEdge.SameFile` / `CrossFile` backrefs can be resolved as items are
  * streamed in. Callers that need cross-DataFile resolution should supply a
  * shared `ReadContext` via `open(sharedCtx)`.
  *
  * @param source
  *   the FileChannel to read from
  */
class GRDWalker(source: FileChannel) {

  private var envelopeOpt: Option[DataFileEnvelope] = None
  private var readCtx: ReadContext = new ReadContext()

  /** Open the GRD file and read its envelope.
    *
    * Validates the magic number and reads the DataFileEnvelope.
    *
    * @return
    *   a Try containing the envelope on success, or an error on failure
    */
  def open(): Try[DataFileEnvelope] = open(new ReadContext())

  /** Open the GRD file with an externally-managed `ReadContext`. Useful when
    * reading multiple DataFiles of a cluster sequentially: pass the same
    * context to each walker so cross-file backrefs resolve correctly. Caller
    * is responsible for bumping `ctx.currentFileOrdinal` between files. */
  def open(ctx: ReadContext): Try[DataFileEnvelope] = {
    readCtx = ctx
    val magic_? = Helpers.readInt(source)
    if (magic_? != GraphManager.Consts.DataFileMagicNumber) {
      // FIXME log the error
      throw new Exception(f"Found incorrect magic number ${magic_?}")
    }

    val len = Helpers.readShort(source)
    val ba = ByteBuffer.allocate(len)
    val readLen = source.read(ba)
    if (len != readLen) {
      throw new Exception(f"Wanted ${len} bytes got ${readLen}")
    }
    val env = DataFileEnvelope.decode(ba.position(0).array())
    env.foreach(e => envelopeOpt = Some(e))
    env
  }

  /** Read the next Item from the file.
    *
    * @return
    *   Some(item) if there is another Item, None if at end of file
    */
  def readNext(): Option[Item] = {
    if (source.position() == source.size()) {
      None
    } else {
      val frameStart = source.position()
      val entryLen = Helpers.readInt(source)
      if (entryLen == -1) {
        None
      } else {
        val entryByteBuffer = ByteBuffer.allocate(entryLen)
        source.read(entryByteBuffer)

        val entryBytes = entryByteBuffer.array()
        val rawItem: Item = envelopeOpt match {
          case Some(env) if env.version >= 2 =>
            Item.decodeStreamed(entryBytes, readCtx).get
          case _ =>
            Item.decode(entryBytes).get
        }
        // Record this item's identifier against the frame-start offset so
        // any later backref edges pointing here can resolve. Mirrors the
        // write side, which records `currentPosition` before writing.
        readCtx.record(frameStart, rawItem.identifierString)

        // Alias-edge synthesis for v3+ canonicals is no longer the
        // walker's responsibility. The cluster-wide alias map lives in
        // a separate `.gra` sidecar referenced from `ClusterFileEnvelope`
        // and `GoatRodeoCluster.open` loads it; callers reading items via
        // the cluster get alias synthesis at that layer. Standalone
        // GRDWalker callers (e.g. `forwardEdgeIndex`'s internal scan)
        // operate on the raw item set.
        Some(rawItem)
      }
    }
  }

  /** Get an Iterator over all Items in the file.
    *
    * Note: The file must have been opened with `open()` first.
    *
    * @return
    *   an Iterator that yields each Item in the file
    */
  def items(): Iterator[Item] = {
    var nextItem = readNext()
    new Iterator[Item] {

      override def hasNext: Boolean = nextItem.isDefined

      override def next(): Item = {
        val ret = nextItem
        nextItem = readNext()
        ret.get // should be save because tested in hasNext
      }

    }
  }
}
