package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.mime.MediaType

import java.io.File
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

    private def metadataNoop(f: File, mime: MediaType): Try[Map[String, String]] = {
      logger.info(s"metadataNoop file: $f mime: $mime")
      // todo - is this a failure or a empty success?
      Success(Map.empty)
    }

    def resolveMetadata(f: File): Try[Map[String, String]] = {
      val tika = new TikaConfig()
      val metadata = new Metadata() // tika metadata ; todo - maybe import alias this?
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, f.toString)
      val detected = tika.getDetector.detect(TikaInputStream.get(f), metadata)
      logger.debug(s"Detected filetype for ${f.toString} media type: $detected Main Type: ${detected.getType} Subtype: ${detected.getSubtype}")
      val packageMeta = detected match {
        case MIME_DEB => parseDebMetadata(f)
        case MIME_GEM => parseGemMetadata(f)
        case MIME_ZIP | MIME_JAR | MIME_WAR | MIME_EAR |
             MIME_ISO | MIME_RPM | MIME_APK | MIME_TAR | MIME_GZIP =>
          metadataNoop(f, detected)
      }
      logger.debug(s"Retrieved Package Metadata for type $detected: $packageMeta")

      packageMeta
    }

  }


  def parseDebMetadata(f: File): Try[Map[String, String]] = {
    Success(Map("deb" -> "debian", "foo" -> "bar"))
  }

  def parseGemMetadata(f: File): Try[Map[String, String]] = {
    Success(Map("gem" -> "ruby", "spam" -> "eggs"))
  }

  /*def getPackageMetadata(f: File): Try[Map[String, String]] = {
  }*/

  /*def getPackageMetadata(bytes: Array[Byte], fileName: String): Try[Map[String, String]] = ???*/

}

