package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveInputStream, ArchiveStreamFactory}
import org.apache.commons.compress.compressors.{CompressorInputStream, CompressorStreamFactory}
import org.apache.commons.io.IOUtils as CommonsIOUtils
import org.apache.commons.compress.utils.IOUtils as CompressIOUtils
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.mime.MediaType
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.{LoaderOptions, Yaml}

import java.io.{BufferedInputStream, BufferedReader, ByteArrayInputStream, File, FileInputStream, StringReader}
import scala.util.matching.Regex
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

    val mimeTypeLookup: Map[MediaType, File => Try[Map[String, String]]] = Map(
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

    def detectMIMEType(f: File): MediaType = {
      val tika = new TikaConfig()
      val metadata = new Metadata() // tika metadata ; todo - maybe import alias this?
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, f.toString)
      if (f.getName.endsWith(".gem")) // temporary hack until we get custom-mimetypes.xml working
        MIMETypeMappings.MIME_GEM
      else {
        val detected = tika.getDetector.detect(TikaInputStream.get(f), metadata)
        val refinedMimeType = if (detected.equals(MIME_TAR) && f.getName.endsWith(".gem")) MIMETypeMappings.MIME_GEM else detected
        logger.debug(s"Detected filetype for ${f.toString} media type: $refinedMimeType Type: ${refinedMimeType.getType} Subtype: ${refinedMimeType.getSubtype}")
        refinedMimeType
      }
    }

    // todo - bytearray input
    def resolveMetadata(f: File): Try[Map[String, String]] = {
      val detected = detectMIMEType(f)
      val lookup = MIMETypeMappings.mimeTypeLookup(detected)
      logger.debug(s"Retrieved Package Metadata lookup function for type $detected : $lookup")
      val packageMeta = lookup(f)
      logger.debug(s"*** Retrieved Package Metadata: $packageMeta")
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
  // we get this as a string for now but we might want to change to take a file or inputstream later
  def parseDebControlFile(data: String): Map[String, String] = {
    val split_regex = """^([A-Za-z-]+):\s(.*)$""".r

    type KVMapBuilder = mutable.Builder[(String, String), Map[String, String]]

    val map = data
      .replaceAll("""[\r\n]+\s+""", " ")
      .split("\n")
      .map {
        case split_regex(k, v) =>
          (k -> v)
        case xs =>
          throw new Exception(s"Didn't parse deb control entry correctly: ${xs}")
      }
      .toMap

    logger.debug(s"Calculated K/V Map: $map")
    map
  }

  def parseGemMetadata(f: File): Try[Map[String, String]] = {
    Success(Map("gem" -> "ruby", "spam" -> "eggs"))
  }
}
