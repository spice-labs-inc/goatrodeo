package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveInputStream, ArchiveStreamFactory}
import org.apache.commons.compress.compressors.{CompressorInputStream, CompressorStreamFactory}
import org.apache.commons.compress.utils.IOUtils
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.mime.MediaType

import java.io.{BufferedInputStream, File, FileInputStream, ByteArrayInputStream}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

package object filetypes {
  val logger = Logger("filetypes$")

  object MIMETypeMappings {
    val MIME_ZIP = MediaType.application("zip")
    val MIME_JAR = MediaType.application("java-archive")
    val MIME_WAR = MediaType.application("x-tika-java-web-archive")
    val MIME_EAR = MediaType.application("x-tika-java-enterprise-archive")
    val MIME_ISO = MediaType.application("x-iso9660-image")
    val MIME_DEB = MediaType.application("x-debian-package")
    val MIME_RPM = MediaType.application("x-rpm")
    //val MIME_GEM = "application/x-tar" // TODO - we should add a custom detecter to custom-types.xml for gems based on .gem
    val MIME_GEM = MediaType.application("x-ruby-gem-package") // Not working right now with the custom mime types, return later
    val MIME_APK = MediaType.application("vnd.android.package-archive")
    val MIME_TAR = MediaType.application("x-gtar")
    val MIME_GZIP = MediaType.application("gzip")

    private def metadataNoop(f: File): Try[Map[String, String]] = {
      logger.info(s"metadataNoop file: $f")
      // todo - is this a failure or a empty success?
      Success(Map.empty)
    }

    val mediaTypeLookup: Map[MediaType, File => Try[Map[String, String]]] = Map(
      MIME_DEB -> parseDebMetadata _,
      MIME_GEM -> parseGemMetadata _,
      MIME_ZIP -> metadataNoop _,
      MIME_JAR -> metadataNoop _,
      MIME_EAR -> metadataNoop _,
      MIME_WAR -> metadataNoop _,
      MIME_ISO -> metadataNoop _,
      MIME_RPM -> metadataNoop _,
      MIME_APK -> metadataNoop _,
      MIME_TAR -> metadataNoop _,
      MIME_GZIP -> metadataNoop _
    )

    // todo - bytearray input
    def resolveMetadata(f: File): Try[Map[String, String]] = {
      val tika = new TikaConfig()
      val metadata = new Metadata() // tika metadata ; todo - maybe import alias this?
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, f.toString)
      val detected = tika.getDetector.detect(TikaInputStream.get(f), metadata)
      logger.debug(s"Detected filetype for ${f.toString} media type: $detected Main Type: ${detected.getType} Subtype: ${detected.getSubtype}")
      val lookup = MIMETypeMappings.mediaTypeLookup(detected)
      logger.debug(s"Retrieved Package Metadata lookup for type $detected : $lookup")
      val packageMeta = lookup(f)
      packageMeta
    }


  }



  def parseDebMetadata(f: File): Try[Map[String, String]] = {
    val tarStream: ArchiveInputStream[ArchiveEntry] =
      archFactory.createArchiveInputStream(new BufferedInputStream(FileInputStream(f)))

    val tarIter = new Iterator[ArchiveEntry] {
      var last: ArchiveEntry = null

      override def hasNext: Boolean = {
        last = tarStream.getNextEntry()
        last != null
      }

      override def next(): ArchiveEntry = last
    }

    // todo - this is a temporary thing while i refactor the method to return values directly
    // instead of the Tika approach of using a callback
    var attrs = Map.empty[String, String]
    for (x <- tarIter) {
      // there are actually several compressed formats + file extensions that I've seen in debs,
      // so we won't assume it's .tar.gz; I've seen zstd, gzip, pkzip, etc
      if (x.getName.startsWith("control.tar")) {
        println(s"Found compressed control file: ${x.getName}")
        val ctrlData = new Array[Byte](x.getSize.toInt) /* unless somethings' really weird the deb file shouldn't contain anything that needs long to describe its size */
        IOUtils.readFully(tarStream, ctrlData) // this should give us gzip bytes…
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
          attrs = parseDebControlFile(new String(innerCtrlData))
        }


      }
    }
    Success(attrs)
  }

  private val archFactory = new ArchiveStreamFactory()
  private val compressorFactory = new CompressorStreamFactory()

  // Partly based on the python library "deb-parse" - https://github.com/aihaddad/deb-parse
    def parseDebControlFile(data: String): Map[String, String] = {
      val split_regex = raw"^[A-Za-z-]+:\s".r

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

    Success(Map("deb" -> "debian", "foo" -> "bar"))
  }

  def parseGemMetadata(f: File): Try[Map[String, String]] = {
    Success(Map("gem" -> "ruby", "spam" -> "eggs"))
  }

  /*def getPackageMetadata(f: File): Try[Map[String, String]] = {
  }*/

  /*def getPackageMetadata(bytes: Array[Byte], fileName: String): Try[Map[String, String]] = ???*/


