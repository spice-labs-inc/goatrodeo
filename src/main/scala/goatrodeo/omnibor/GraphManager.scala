package goatrodeo.omnibor

import java.io.File
import goatrodeo.envelopes.PayloadCompression
import java.nio.file.Files
import java.io.FileOutputStream
import goatrodeo.util.Helpers
import goatrodeo.envelopes.DataFileEnvelope
import java.nio.ByteBuffer
import goatrodeo.envelopes.EntryEnvelope
import goatrodeo.envelopes.MD5
import goatrodeo.envelopes.Position
import goatrodeo.envelopes.MultifilePosition
import goatrodeo.envelopes.PayloadFormat
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
      previous: Long
  ): DataAndIndexFiles = {
    // create temporary file
    val tempFile =
      Files.createTempFile(targetDirectory.toPath(), "goat_rodeo_data_", ".grd")
    val fileWriter = new FileOutputStream(tempFile.toFile())
    val writer = fileWriter.getChannel()
    var previousPosition: Long = 0
    Helpers.writeInt(writer, Consts.DataFileMagicNumber)
    val dataFileEnvelope =
      DataFileEnvelope.build(
        timestamp = System.currentTimeMillis(),
        previous = previous,
        builtFromMerge = false
      )
    val envelopeBytes = dataFileEnvelope.encode()
    // write the DataFileEnvelope length
    Helpers.writeShort(writer, envelopeBytes.length)

    // write DataFileEnvelope
    val writtenLen = writer.write(ByteBuffer.wrap(envelopeBytes))

    DataFileEnvelope.decode(envelopeBytes)
    // loop writing entries until empty or the file is >= 16GB in size
    while (items.hasNext && writer.position() < Consts.TargetMaxFileSize) {
      val orgEntry = items.next()
      val currentPosition = writer.position()
      val entry = orgEntry.fixReferencePosition(0L, currentPosition)

      val md5 = entry.identifierMD5()

      val entryBytes = compression.compress(entry.encodeCBOR())

      val itemEnvelope: ItemEnvelope = ItemEnvelope(
        MD5(md5),
        Position(currentPosition),
        entry._timestamp,
        MultifilePosition.NA,
        previousPosition,
        entryBytes.length,
        PayloadFormat.CBOR,
        PayloadType.ENTRY,
        compression,
        false
      )

      val envelopeBytes = itemEnvelope.encodeCBOR()

      //   // write Entry Envelope size
      Helpers.writeShort(writer, envelopeBytes.length)
      //   // write Entry Size
      Helpers.writeInt(writer, entryBytes.length)

      //   // Write Entry Envelope
      writer.write(ByteBuffer.wrap(envelopeBytes))
      //   // Write actual entry
      writer.write(ByteBuffer.wrap(entryBytes))
      // set the previous position
      previousPosition = itemEnvelope.position.offset;

    }
    Helpers.writeShort(writer, -1) // a marker that says end of file

    // write final back-pointer (to the last entry record)
    Helpers.writeLong(writer, previousPosition)

    // compute SHA256 of the file
    writer.close()

    // rename the file to <sha256>.grd
    val sha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(tempFile.toFile()))
    )

    val targetFileName =
      new File(targetDirectory, f"${Helpers.toHex(sha256Long)}.grd")

    tempFile.toFile().renameTo(targetFileName)

    val walker = GRDWalker(FileInputStream(targetFileName).getChannel())

    val env = walker.open().get

    // walk the generated file and get the items
    val pairs =
      (for { env <- walker.envelopes() } yield {
        (Helpers.toHex(env.keyMd5.hash), env.keyMd5.hash, env.position.offset)
      }).toVector.sortBy(_._1)
    // walk the file

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
    for { v <- pairs } {
      indexWriter.write(ByteBuffer.wrap(v._2))
      Helpers.writeLong(indexWriter, sha256Long)
      Helpers.writeLong(indexWriter, v._3)

    }

    indexWriter.close()

    val indexSha256Long = Helpers.byteArrayToLong63Bits(
      Helpers.computeSHA256(new FileInputStream(targetIndexName))
    )

    val indexTargetFileName =
      new File(targetDirectory, f"${Helpers.toHex(indexSha256Long)}.gri")

    targetIndexName.renameTo(indexTargetFileName)

    DataAndIndexFiles(sha256Long, indexSha256Long)
  }

  def writeEntries(
      targetDirectory: File,
      entries: Iterator[Item],
      compression: PayloadCompression = PayloadCompression.NONE
  ): Try[(Seq[DataAndIndexFiles], String)] = {
    var previousInChain: Long = 0L
    Try {

      var fileSet: List[DataAndIndexFiles] = Nil
      while (entries.hasNext) {
        val dataAndIndex = writeABlock(
          targetDirectory,
          entries,
          compression,
          previous = previousInChain
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
          timestamp = System.currentTimeMillis(),
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

      val targetFileName =
        new File(
          targetDirectory,
          f"${now.getYear()}_${"%02d".format(now.getMonthValue())}_${"%02d"
              .format(now.getDayOfMonth())}_${"%02d".format(
              now
                .getHour()
            )}_${"%02d".format(now.getMinute())}_${"%02d"
              .format(now.getSecond())}_${Helpers.toHex(sha256Long)}.grb"
        )

      tempFile.toFile().renameTo(targetFileName)
      (fileSet, targetFileName.getName())
    }
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

        val envelope = EntryEnvelope
          .decodeCBOR(envBytes.position(0).array())
          .get
        val entryBytes =
          envelope.compression.deCompress(entryByteBuffer.array())
        val entry = Item.decode(entryBytes, envelope.dataFormat).get
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
          EntryEnvelope.decodeCBOR(envBytes.position(0).array()).get
        source.position(
          envelope.position.offset + entryLen.toLong + envLen.toLong + 6L
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
