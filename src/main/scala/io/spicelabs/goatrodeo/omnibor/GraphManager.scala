package io.spicelabs.goatrodeo.omnibor

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.envelopes.ClusterFileEnvelope
import io.spicelabs.goatrodeo.envelopes.DataFileEnvelope
import io.spicelabs.goatrodeo.envelopes.IndexFileEnvelope
import io.spicelabs.goatrodeo.envelopes.Position
import io.spicelabs.goatrodeo.util.Helpers
import org.json4s.JsonDSL
import org.json4s.JsonDSL.*

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
      afterWrite: Item => Unit
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

    // loop writing entries until empty or the file is >= 16GB in size
    while (items.hasNext && writer.position() < Consts.TargetMaxFileSize) {
      val orgEntry = items.next()
      val currentPosition = writer.position()
      // val entry = orgEntry.fixReferencePosition(0L, currentPosition)
      val entry = orgEntry

      val md5 = entry.identifierMD5()

      val entryBytes =
        entry.encodeCBOR() // compression.compress(entry.encodeCBOR())

      pairs = pairs.appended((Helpers.toHex(md5), md5, currentPosition))

      val toAlloc = 256 + (entryBytes.length)
      val bb = ByteBuffer.allocate(toAlloc)

      bb.putInt(entryBytes.length)
      bb.put(entryBytes)

      bb.flip()

      writer.write(bb)

      previousPosition = currentPosition; // itemEnvelope.position;

      afterWrite(entry)
      loopCnt += 1
      if (loopCnt % 1000000 == 0) {
        logger.info(
          f"Write loop ${loopCnt} at ${Duration.between(start, Instant.now())}"
        )
      }
    }

    Helpers.writeShort(writer, -1) // a marker that says end of file

    // write final back-pointer (to the last entry record)
    Helpers.writeLong(writer, previousPosition)

    // compute SHA256 of the file
    writer.close()

    logger.info(
      f"Finished write loop at ${Duration.between(start, Instant.now())}"
    )

    // rename the file to <sha256>.grd
    val sha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(tempFile.toFile()))
    )

    val targetFileName =
      new File(targetDirectory, f"${Helpers.toHex(sha256Long)}.grd")

    tempFile.toFile().renameTo(targetFileName)

    logger.info(f"Finished rename at ${Duration.between(start, Instant.now())}")

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

    for { v <- pairs } {
      indexBB.put(v._2)
      indexBB.putLong(sha256Long)
      indexBB.putLong(v._3)
    }

    indexBB.flip()

    indexWriter.write(indexBB)

    indexWriter.close()

    logger.info(
      f"Finished index write at ${Duration.between(start, Instant.now())}"
    )

    val indexSha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(targetIndexName))
    )

    val indexTargetFileName =
      new File(targetDirectory, f"${Helpers.toHex(indexSha256Long)}.gri")

    targetIndexName.renameTo(indexTargetFileName)

    logger.info(
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
      entries: Iterator[Item]
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
    while (entries.hasNext) {
      val dataAndIndex = writeABlock(
        targetDirectory,
        entries,
        previous = previousInChain,
        updateBiggest
      )
      previousInChain = dataAndIndex.dataFile
      fileSet = dataAndIndex :: fileSet

    }

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
        dataFiles = fileSet.map(_.dataFile).toVector
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

}

/** A walker for reading Items from a GRD (Goat Rodeo Data) file.
  *
  * Provides sequential access to Items stored in a GRD file. Use `open()` to
  * validate the file and read the envelope, then `readNext()` or `items()` to
  * iterate through Items.
  *
  * @param source
  *   the FileChannel to read from
  */
class GRDWalker(source: FileChannel) {

  /** Open the GRD file and read its envelope.
    *
    * Validates the magic number and reads the DataFileEnvelope.
    *
    * @return
    *   a Try containing the envelope on success, or an error on failure
    */
  def open(): Try[DataFileEnvelope] = {
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
    DataFileEnvelope.decode(ba.position(0).array())
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
      val entryLen = Helpers.readInt(source)
      if (entryLen == -1) {
        None
      } else {
        val entryByteBuffer = ByteBuffer.allocate(entryLen)
        source.read(entryByteBuffer)

        val entryBytes = entryByteBuffer.array()
        val entry = Item.decode(entryBytes).get
        Some(entry)
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
