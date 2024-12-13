package io.spicelabs.goatrodeo.util.filetypes

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.util.Helpers
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveInputStream, ArchiveStreamFactory}
import org.apache.commons.compress.compressors.{CompressorInputStream, CompressorStreamFactory}
import org.apache.commons.compress.utils.IOUtils
import org.apache.tika.config.TikaConfig
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.{ParseContext, Parser}
import org.xml.sax.ContentHandler

import scala.jdk.CollectionConverters.*
import java.io.{BufferedInputStream, ByteArrayInputStream, File, InputStream}
import java.util
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.mutable

/**
 * A Tika Parser interface for Debian packages
 */
class DebianPackageParser extends Parser  {
  val logger = Logger("DebianPackageParser")
  private val archFactory = new ArchiveStreamFactory()
  private val compressorFactory  = new CompressorStreamFactory()

  override def getSupportedTypes(context: ParseContext): util.Set[MediaType] =
    Set(MediaType.application("x-debian-package")).asJava


  /**
   * Parse the provided input stream as a .deb file, and extract its metadata
   *
   * Note that this uses a visitor pattern in the Java code with the way the Handler is managed
   * @param in This should be the very head of the stream - the entire .deb package, not just the 'control' file
   * @param handler
   * @param metadata
   * @param context
   */
  override def parse(in: InputStream, handler: ContentHandler, metadata: Metadata, context: ParseContext): Unit = {
    // todo - should we revalidate / redetect? that this is an `application/x-debian-package` in the input stream and not a control file directly?
    // require that the user set the package name, partly revalidates
    // val tarFilename = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
    val tarStream: ArchiveInputStream[ArchiveEntry] =
      archFactory.createArchiveInputStream(new BufferedInputStream(in))

    val tarIter =
      new Iterator[ArchiveEntry] {
        var last: ArchiveEntry = null
        override def hasNext: Boolean = {
          last = tarStream.getNextEntry()
          last != null
        }

        override def next(): ArchiveEntry = last
      }

    for (x <- tarIter) {
      // there are actually several compressed formats + file extensions that I've seen in debs,
      // so we won't assume it's .tar.gz; I've seen zstd, gzip, pkzip, etc
      if (x.getName.startsWith("control.tar")) {
        println(s"Found compressed control file: ${x.getName}")
        val ctrlData = new Array[Byte](x.getSize.toInt) /* unless somethings' really weird the deb file shouldn't contain anything that needs long to describe its size */
        IOUtils.readFully(tarStream, ctrlData) // this should give us gzip bytes…
        /*val str = new String(ctrlData)
        println(str)*/
        /* //just for testing  that we got something that is valid gzip…
        val tika = new TikaConfig()
        val tpe = tika.getDetector().detect(new ByteArrayInputStream(ctrlData), new Metadata())
        println(s"Type: $tpe")
         */
        val ctrlStream: CompressorInputStream =
          compressorFactory.createCompressorInputStream(new ByteArrayInputStream(ctrlData))
        // ctrlStream is the tar file that was held inside the compressed container e.g. control.tar.zst
        val innerTarStream: ArchiveInputStream[ArchiveEntry] =
          archFactory.createArchiveInputStream(new ByteArrayInputStream(ctrlStream.readAllBytes()))

        val innerIter = Helpers
          .iteratorFor(innerTarStream)
          .filter(!_.isDirectory)
        // we don't do a filter against filename here because we need to stop iterating as soon as we
        // find control, or the InputStream won't be pointign at that files data once
        // we iterate past it
        for (f <- innerIter if f.getName.equals("./control")) {
          val innerCtrlData = new Array[Byte](f.getSize.toInt) /* unless somethings' really weird the deb file shouldn't contain anything that needs long to describe its size */
          IOUtils.readFully(innerTarStream, innerCtrlData)
          println(s"*** ${new String(innerCtrlData)}")
          val attrs = parseControlFile(new String(innerCtrlData))
        }
        // parse the contents of the control file…


      }
    }
  }

  // Partly based on the python library "deb-parse" - https://github.com/aihaddad/deb-parse
  private def parseControlFile(data: String): Map[String, String] = {
    val split_regex   = raw"^[A-Za-z-]+:\s".r

    type KVMapBuilder = mutable.Builder[(String, String), Map[String, String]]

    // todo - this doesn't really need the mutable builders
    @tailrec
    def _findKV(lines: Array[String], kvBuffer: (Option[String], StringBuilder) = None -> new StringBuilder(),
                kvMapB: KVMapBuilder = Map.newBuilder[String, String]): Map[String, String] = {
      logger.debug(s"~~~ Lines Size: ${lines.size} kvBuffer: $kvBuffer kvMapB: $kvMapB")
      if (lines.size <= 0) {
        logger.debug(s"!!! Hit last line, collapsing out our buffer")
        kvBuffer match {
          case Some(key) -> value =>
            kvMapB += key -> value.result()
          case None -> _ =>
            logger.warn("Got to lines = 0 with nothing buffered? something might be hinky")
        }
        kvMapB.result()
      } else {
        val line = lines.head
        logger.debug(s"!!! Line: $line")
        val s = split_regex.findAllIn(line).toVector
        logger.debug(s"S Size: ${s.size} KVBuffer: $kvBuffer S: $s")
        if (s.size == 1) { // we found a key entry // todo - make me a little saner / cleaner
          logger.debug(s"*** Split size: ${s.size}")
          kvBuffer._1 match {
            case Some(key) => // was there a key set, which means we were building a multiline
              logger.debug(s"*** We are working on an existing key; key: $key")
              val value = kvBuffer._2.result()
              logger.debug(s"*** kvMapB += $key -> $value")
              // close out the multiline
              kvMapB += key -> value
              val newKey = s(0).split(":")(0)
              val newValue = split_regex.split(line)(1)
              logger.debug(s"*** newKey: $newKey newValue: $newValue")
              val b = new StringBuilder()
              b.append(newValue)
              _findKV(lines.tail, Some(newKey) -> b, kvMapB)
            case None => // no key set, start a new one
              val key = s(0).split(":")(0)
              val value = split_regex.split(line)(1) // everything after ":"
              logger.debug(s"*** Working on a new line K: $key V: $value")
              val newB = new StringBuilder()
              newB.append(value)
              _findKV(lines.tail, Some(key) -> newB, kvMapB)
          }
        } else { // we seem to have only found a value entry, this appends
          logger.info("*** Split 0")
          kvBuffer._1 match {
            case Some(key) =>
              logger.debug(s"*** Some(key): $key Line: $line")
              kvBuffer._2.append(line)
              _findKV(lines.tail, Some(key) -> kvBuffer._2, kvMapB)
            case None =>
              logger.debug(s"*** Calculated K/V Map: ${kvMapB.result()}")
              /*
               * this shouldn't happen, it means we got here where there was no Key regex on the line,
               * but we ALSO are not currently building another key / value pair?
               */
              val err = s"State Confusion: Building a new key / value pair; found a line with a Key prefix but no Key set in recursor"
              logger.error(err)
              throw new IllegalStateException(err)
          }

        }

      }
    }

    val map = _findKV(data.split("\n"))
    logger.debug(s"Calculated K/V Map: $map")
    map
  }

}

object DebianPackageParser {
  val logger = Logger("DebianPackageParser$")

}

class DebMetadataHandler {
  val logger = Logger("DebMetadataHandler")
}

object DebMetadataHandler {
  val logger = Logger("DebMetadataHandler$")
}
