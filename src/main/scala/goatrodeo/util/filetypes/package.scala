package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveInputStream, ArchiveStreamFactory}
import org.apache.commons.compress.compressors.{CompressorInputStream, CompressorStreamFactory}
import org.apache.commons.io.IOUtils as CommonsIOUtils
import org.apache.commons.compress.utils.IOUtils as CompressIOUtils
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{TikaCoreProperties, Metadata as TikaMetadata}
import org.apache.tika.mime.MediaType
import org.yaml.snakeyaml.{LoaderOptions, Yaml}

import java.io.{BufferedInputStream, ByteArrayInputStream, File, FileInputStream}
import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters.*

package object filetypes {
  private val logger = Logger("filetypes$")

  private val tika = new TikaConfig()

  /**
   * Some constants that we can reference to represent different known / expected MIME Types
   */
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

    /**
     * Fallback method to point at if we don't have a metadata extractor implementation
     * for a given mime type; this mapping is in `mimeTypeLookup`
     * This is a noop - it returns a successful empty map
     * @param f a java.io.File
     * @return This method returns a static `Success(Map.empty)` to represent no metadata
     */
    private def metadataNoop(f: File): Try[Map[String, MetadataValue]] = {
      logger.debug(s"metadataNoop file: $f")
      // todo - is this a failure or a empty success?
      Success(Map.empty)
    }

    /**
     * A mapping of Tika `MediaType` to a function that takes a `java.io.File` and returns
     * a `Try[Map[String, MetadataValue]]` which, if successful, should contain the metadata
     * found by the selected parser
     */
    val mimeTypeLookup: Map[MediaType, File => Try[Map[String, MetadataValue]]] = Map(
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

    /**
     * Given a `java.io.File`, return a MIME Type (Tika `MediaType`) representing
     * the correct mime type of the given file.
     * e.g. "foobar-1.23.deb" should detect as `application/x-debian-package`
     * @param f a `java.io.File` to detect `MediaType` from
     * @return The detected MIME Type (`MediaType)` of the given file
     */
    def detectMIMEType(f: File): MediaType = {
      val metadata = new TikaMetadata() // tika metadata
      // set the filename for Tika… some of the detectors in the stack fall back on filename to decide
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, f.toString)
      /**
       * temporary hack until we get custom-mimetypes.xml working
       * basically, by default tika detects gem files as `application/x-tar` which
       * is technically correct as a Gem is a tar… but we want to treat it as a gem
       * with its own distinct mime type. The *correct* way to do this is
       * by defining a custom type in `custom-mimetypes.xml` but at this time
       * we can't seem to get that functionality working, so for now we just
       * manually override the detection
       */
      if (f.getName.endsWith(".gem")) // todo - should we check also that it was detected as a Tar, or is short circuit ok?
        MIMETypeMappings.MIME_GEM
      else {
        val detected = Timing.time(s"detectMimeType: $f") {
          tika.getDetector().detect(TikaInputStream.get(f), metadata)
        }
        logger.debug(s"Detected filetype for ${f.toString} media type: $detected" +
          s"Type: ${detected.getType} Subtype: ${detected.getSubtype}")
        detected
      }
    }


    /**
     * Resolve the metadata associated with a given `java.io.File`
     * e.g. "foobar-1.23.deb" should detect as `application/x-debian-package`,
     * and we should extract metadata as a `Map[String, MetadataValue]` from the deb package
     * control file
     * todo - bytearray input
     * @param f `java.io.File` to extract metadata from
     * @return A `Try[Map[String, MetadataValue]]`, which, if `Success` contains the extracted metadata from the package, which, if `Success` contains the extracted metadata from the package, which, if `Success` contains the extracted metadata from the package, which, if `Success` contains the extracted metadata from the package
     */
    def resolveMetadata(f: File): Try[Map[String, MetadataValue]] = {
      val detected = detectMIMEType(f)

      // Fetch the metadata lookup method for the given MIME Type
      val lookup = MIMETypeMappings.mimeTypeLookup(detected)
      logger.debug(s"Retrieved Package Metadata lookup function for type $detected : $lookup")

      // execute the lookup, which will hopefully return a success
      // todo - handle the Success / Failure for at least some local logging
      val packageMeta = lookup(f)
      logger.debug(s"*** Retrieved Package Metadata: $packageMeta")
      packageMeta
    }


  }


  /** Apache Commons Compress factories */
  private val archFactory = new ArchiveStreamFactory()
  private val compressorFactory = new CompressorStreamFactory()

  /**
   * Extract and Parse the Metadata ("control" file) for .deb Debian Packages
   *
   * @param f a `java.io.File` representing the debian package file to extract from
   * @return A `Try[Map[String, MetadataValue]]` where, if success, contains the extracted Metadata from the Debian Package
   */
  def parseDebMetadata(f: File): Try[Map[String, MetadataValue]] = {
    // Open a stream against the .deb file, which is archived as a tar
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
    var attrs = Map.empty[String, MetadataValue]
    for (x <- tarIter) {
      // there are actually several compressed formats + file extensions that I've seen in debs for compressing `control`,
      // so we won't assume it's .tar.gz; I've seen zstd, gzip, and pkzip so far…
      if (x.getName.startsWith("control.tar")) {
        logger.info(s"Found compressed control file: ${x.getName}")
        val ctrlData = new Array[Byte](x.getSize.toInt) /* unless somethings' really weird the deb file shouldn't contain anything that needs long to describe its size */
        CompressIOUtils.readFully(tarStream, ctrlData) // this should give us gzip bytes…
        // Ok, so now we have `control.tar.<some compression extension>`, and we need to uncompress it
        val ctrlStream: CompressorInputStream =
          compressorFactory.createCompressorInputStream(new ByteArrayInputStream(ctrlData))

        /**
         * The Matryoshka of compressed and archived files continues; `control.tar.<compression extension>` is a
         * compressed tar…
         * `ctrlStream` is the tar file that was held inside the compressed container e.g. `control.tar.zst`
         */
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
          CompressIOUtils.readFully(innerTarStream, innerCtrlData)
          println(s"*** ${new String(innerCtrlData)}")
          attrs = processDebControlFile(new String(innerCtrlData))
        }


      }
    }
    Success(attrs)
  }

  /**
   * Extract and Parse the Metadata ("metadata" file) for .gem Ruby Packages
   * These are represented as "quasi" YAML (ruby adds a bunch of crap to the file that most yaml parsers seem to choke on)
   *
   * @param f a `java.io.File` representing the ruby gem package file to extract from
   * @return A `Try[Map[String, MetadataValue]]` where, if success, contains the extracted Metadata from the Ruby Gem
   */
  def parseGemMetadata(f: File): Try[Map[String, MetadataValue]] = {
    // Open a stream against the .gem file, which is archived as a tar
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

    // Grab the `metadata.gz` archive which contains the metadata YAML file
    val metaGZ = tarIter
      .filter(_.getName().endsWith("metadata.gz"))
      .nextOption() match {
        case Some(metaFile) => metaFile
        case None =>
          val err = s"Didn't find a `metadata.gz` file in the Gem; this is not a valid Gem file"
          logger.error(err)
          val e = new IllegalArgumentException() // todo - custom error / exception
          return Failure(e)
      }


    logger.debug(s"Filtered: $metaGZ")
    logger.info(s"Found compressed Ruby Gem metadata file: $metaGZ")
    val gemMetaData = new Array[Byte](metaGZ.getSize.toInt) /* unless something's really weird the metadata file shouldn't contain anything that needs long to describe its size */
    CompressIOUtils.readFully(tarStream, gemMetaData) // get the gzip bytes
    // decompress the YAML file from .gz
    val gemMetaStream: CompressorInputStream =
      compressorFactory.createCompressorInputStream(new ByteArrayInputStream(gemMetaData))

    val gemSpec = String(gemMetaStream.readAllBytes())

    logger.trace(s" gemSpec: $gemSpec")

    // Generate the map of Metadata entries…
    val metaMap = processGemMetadataFile(gemSpec)

    logger.debug(s"Meta Map from Ruby Gems: $metaMap")

    Success(metaMap) // todo - not hardcode succes…
  }

  /**
   * TODO - make this other types besides string
   *
   * Given a `String` containing the contents of a Ruby Gem `metadata` file, parse
   * the metadata into a `Map[String, MetadataValue]` representing the key / value pairs from the YAML
   * @param str A `String` containing a YAML file representing the Ruby Gem Metadata
   * @return A `Map[String, MetadataValue]` containing the parsed key/value pairs from the Ruby Gem Metadata file
   */
  def processGemMetadataFile(str: String): Map[String, MetadataValue] = {
    import scala.jdk.CollectionConverters._
    val yamlOpts = new LoaderOptions()
    val yaml = new Yaml(yamlOpts)
    // the metadata file from ruby adds some Ruby identifiers to the YAML but in a way that it is now NOT valid YAML, but it's just a file start marker so we'll skip it
    val re = raw"!ruby/object:.*\\s".r

    val yamlStr = str.tail.replaceAll("!ruby/object:.*\\s", "\n").tail.tail
    logger.trace(s"Yaml String: $yamlStr")

    val data: Map[String, Object] = (yaml.load(ByteArrayInputStream(yamlStr.getBytes())): java.util.Map[String, Object]).asScala.toMap

    logger.debug(s"Yaml Data Loaded: $data")

    val mapped: Map[String, MetadataValue] = data map { (k, v) => v match {
      case s: String => k -> MetadataString(s)
      case m: Map[String, Object] => k -> MetadataMap.toMetadataMap(m)
      case l: List[Object] => k -> MetadataList.toMetadataList(l)
      case other =>
        // for now make it a string
        if (other == null) { // there are null values in valid gem metadatas, e.g. empty yaml value. map to empty string for now
          k -> MetadataString("")
        } else k -> MetadataString(other.toString)
    }}
    mapped
  }

  /**
   * Given a `String` containing the contents of a Debian package's `control` metadata file, parse
   * the metadata into a `Map[String, MetadataValue]` representing the key / value pairs from the YAML
   *
   * This code is partly based on the python library "deb-parse" - https://github.com/aihaddad/deb-parse
   * todo - we get this as a string for now but we might want to change to take a file or inputstream later
   *
   * @param str A `String` containing a YAML file representing the Debian Package's `control` file
   * @return A `Map[String, MetadataValue]` containing the parsed key/value pairs from the Debian Package `control` file
   */
  def processDebControlFile(data: String): Map[String, MetadataValue] = {
    val split_regex = """^([A-Za-z-]+):\s(.*)$""".r

    /**
     * Parse out the key/value pairs, handling multiline fields
     */
    val map = data
      .replaceAll("""[\r\n]+\s+""", " ")
      .split("\n")
      .map {
        case split_regex(k, v) =>
          k -> MetadataString(v)
        case xs =>
          throw new Exception(s"Didn't parse deb control entry correctly: ${xs}")
      }
      .toMap

    logger.debug(s"Calculated K/V Map: $map")
    map
  }

  implicit def stringToMetadataString(value: String): MetadataString =
    MetadataString(value)

  implicit def metadataStringtoString(value: MetadataString): String =
    value.value

  implicit def metadataMapToMap(value: MetadataMap): Map[String, MetadataValue] =
    value.value

  implicit def metadataListToList(value: MetadataList): List[MetadataValue] =
    value.value
}
