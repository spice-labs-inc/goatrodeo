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
    val b = Map.newBuilder[String, String]

    for (k <- split_regex.findAllIn(data)) {
      logger.debug(s"K: $k")
      val sb = new StringBuilder()
      val tail = split_regex.split(data)(1) // this ends up being the tail: everything after the key declaration
      for (x <- tail.split("\n")) {
        // now to determine if the line we are processing is a continuation of a multiline, or a new field
        if (split_regex.matches(x)) {
          logger.debug(s"Found a new key field… ${sb.result()}")
        } else {
          // multiline
          sb.append(x)
          logger.debug(s"must've been multiline: $x")
        }
      }
    }
    b.result()
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
