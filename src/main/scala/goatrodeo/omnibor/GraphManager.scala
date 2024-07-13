package goatrodeo.omnibor

import java.io.File
import goatrodeo.envelopes.PayloadCompression
import java.nio.file.Files
import java.io.FileOutputStream
import goatrodeo.util.Helpers
import goatrodeo.envelopes.DataFileEnvelope
import java.nio.ByteBuffer
import goatrodeo.envelopes.MD5
import goatrodeo.envelopes.Position
import goatrodeo.envelopes.MultifilePosition
import goatrodeo.envelopes.PayloadType
import goatrodeo.envelopes.ItemEnvelope
import java.io.FileInputStream
import goatrodeo.envelopes.IndexFileEnvelope
import scala.util.Try
import goatrodeo.envelopes.BundleFileEnvelope
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneOffset
import java.nio.channels.FileChannel
import java.time.Duration
import scala.math.Ordering.Implicits._

/** Manage many parts of persisting/retrieving the graph information
  */
object GraphManager {
  object Consts {
    val DataFileMagicNumber: Int = 0x00be1100 // Bell
    val IndexFileMagicNumber: Int = 0x54154170 // Shishitō
    val BundleFileMagicNumber: Int = 0xba4a4a // Banana
    val TargetMaxFileSize: Long = 15L * 1024L * 1024L * 1024L // 7G
  }

  case class DataAndIndexFiles(dataFile: Long, indexFile: Long)
  private def writeABlock(
      targetDirectory: File,
      items: Iterator[Item],
      compression: PayloadCompression,
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
      val entry = orgEntry.fixReferencePosition(0L, currentPosition)

      val md5 = entry.identifierMD5()

      val entryBytes = compression.compress(entry.encodeCBOR())

      // (for { env <- walker.envelopes() } yield {
      //   (Helpers.toHex(env.keyMd5.hash), env.keyMd5.hash, env.position)
      // }).toVector.sortBy(_._1)

      val itemEnvelope: ItemEnvelope = ItemEnvelope(
        keyMd5 = MD5(md5),
        position = currentPosition,
        backpointer = previousPosition,
        dataLen = entryBytes.length,
        dataType = PayloadType.ENTRY
      )

      pairs = pairs.appended((Helpers.toHex(md5), md5, currentPosition))

      val envelopeBytes = itemEnvelope.encodeCBOR()

      val toAlloc = 256 + (envelopeBytes.length) + (entryBytes.length)
      val bb = ByteBuffer.allocate(toAlloc)
      // println(f"Writing # ${loopCnt} ${orgEntry.identifier} eb ${envelopeBytes.length} entB ${entryBytes.length} tried ${backing.length} bb alloc ${bb.capacity()}")

      bb.putShort((envelopeBytes.length & 0xffff).toShort)
      bb.putInt(entryBytes.length)
      bb.put(envelopeBytes)
      bb.put(entryBytes)

      bb.flip()

      writer.write(bb)

      previousPosition = itemEnvelope.position;

      afterWrite(entry)
      loopCnt += 1
      if (loopCnt % 1000000 == 0) {
        println(
          f"Write loop ${loopCnt} at ${Duration.between(start, Instant.now())}"
        )
      }
    }

    Helpers.writeShort(writer, -1) // a marker that says end of file

    // write final back-pointer (to the last entry record)
    Helpers.writeLong(writer, previousPosition)

    // compute SHA256 of the file
    writer.close()

    println(f"Finished write loop at ${Duration.between(start, Instant.now())}")

    // rename the file to <sha256>.grd
    val sha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(tempFile.toFile()))
    )

    val targetFileName =
      new File(targetDirectory, f"${Helpers.toHex(sha256Long)}.grd")

    tempFile.toFile().renameTo(targetFileName)

    println(f"Finished rename at ${Duration.between(start, Instant.now())}")

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

    println(
      f"Finished index write at ${Duration.between(start, Instant.now())}"
    )

    val indexSha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(targetIndexName))
    )

    val indexTargetFileName =
      new File(targetDirectory, f"${Helpers.toHex(indexSha256Long)}.gri")

    targetIndexName.renameTo(indexTargetFileName)

    println(
      f"Finished index rename at ${Duration.between(start, Instant.now())}"
    )

    DataAndIndexFiles(sha256Long, indexSha256Long)
  }

  def writeEntries(
      targetDirectory: File,
      entries: Iterator[Item],
      compression: PayloadCompression = PayloadCompression.NONE
  ): (Seq[DataAndIndexFiles], File) = {
    var previousInChain: Long = 0L
    var biggest: Vector[(Item, Int)] = Vector()

    def updateBiggest(item: Item): Unit = {
      val containedBy =
        item.connections.filter(_._1 == EdgeType.ContainedBy).size
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
        compression,
        previous = previousInChain,
        updateBiggest
      )
      previousInChain = dataAndIndex.dataFile
      fileSet = dataAndIndex :: fileSet

    }

    val tempFile =
      Files.createTempFile(
        targetDirectory.toPath(),
        "goat_rodeo_bundle_",
        ".grb"
      )
    val fileWriter = new FileOutputStream(tempFile.toFile())
    val writer = fileWriter.getChannel()

    Helpers.writeInt(writer, Consts.BundleFileMagicNumber)
    val bundleEnvelope =
      BundleFileEnvelope.build(
        indexFiles = fileSet.map(_.indexFile).toVector,
        dataFiles = fileSet.map(_.dataFile).toVector
      )
    val envelopeBytes = bundleEnvelope.encode()
    Helpers.writeShort(writer, envelopeBytes.length)
    writer.write(ByteBuffer.wrap(envelopeBytes))
    writer.close()
    val sha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(tempFile.toFile()))
    )

    val now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)

    val targetFile =
      new File(
        targetDirectory,
        f"${now.getYear()}_${"%02d".format(now.getMonthValue())}_${"%02d"
            .format(now.getDayOfMonth())}_${"%02d".format(
            now
              .getHour()
          )}_${"%02d".format(now.getMinute())}_${"%02d"
            .format(now.getSecond())}_${Helpers.toHex(sha256Long)}.grb"
      )

    tempFile.toFile().renameTo(targetFile)
    if (false) {
      for { i <- biggest } {
        println(
          f"Item ${i._1.identifier} ${i._1.metadata.map(_.fileNames).getOrElse(Vector())} has ${i._2} connections"
        )
      }
    }
    (fileSet, targetFile)
  }

}

class GRDWalker(source: FileChannel) {
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

  def readNext(): Option[(ItemEnvelope, Item)] = {
    if (source.position() == source.size()) {
      None
    } else {
      val envLen = Helpers.readShort(source)
      if (envLen == 0xffff) {
        Helpers.readLong(source) // advance to end of time
        None
      } else {
        val entryLen = Helpers.readInt(source)
        val envBytes = ByteBuffer.allocate(envLen)
        val entryByteBuffer = ByteBuffer.allocate(entryLen)
        source.read(envBytes)
        source.read(entryByteBuffer)

        val envelope = ItemEnvelope
          .decodeCBOR(envBytes.position(0).array())
          .get
        val entryBytes = entryByteBuffer.array()
        val entry = Item.decode(entryBytes).get
        Some(envelope -> entry)
      }
    }
  }

  def readNextEnvelope(): Option[ItemEnvelope] = {
    val startPosition = source.position()
    if (startPosition == source.size()) {
      None
    } else {
      val envLen = Helpers.readShort(source)
      if (envLen == 0xffff) {
        Helpers.readLong(source) // advance to end of time
        None
      } else {
        val entryLen = Helpers.readInt(source)
        val envBytes = ByteBuffer.allocate(envLen)

        source.read(envBytes)
        val envelope =
          ItemEnvelope.decodeCBOR(envBytes.position(0).array()).get
        source.position(
          envelope.position + entryLen.toLong + envLen.toLong + 6L
        )

        Some(envelope)
      }
    }
  }

  def envelopes(): Iterator[ItemEnvelope] = {
    var nextItem: Option[ItemEnvelope] = readNextEnvelope()
    new Iterator[ItemEnvelope] {

      override def hasNext: Boolean = nextItem.isDefined

      override def next(): ItemEnvelope = {
        val ret = nextItem
        nextItem = readNextEnvelope()
        ret.get // should be save because tested in hasNext
      }

    }
  }

  def items(): Iterator[(ItemEnvelope, Item)] = {
    var nextItem = readNext()
    new Iterator[(ItemEnvelope, Item)] {

      override def hasNext: Boolean = nextItem.isDefined

      override def next(): (ItemEnvelope, Item) = {
        val ret = nextItem
        nextItem = readNext()
        ret.get // should be save because tested in hasNext
      }

    }
  }
}
