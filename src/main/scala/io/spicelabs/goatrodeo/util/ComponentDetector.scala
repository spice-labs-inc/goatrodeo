package io.spicelabs.goatrodeo.util

import org.apache.tika.detect.Detector
import java.io.InputStream
import org.apache.tika.mime.MediaType
import org.apache.tika.metadata.Metadata
import io.spicelabs.goatrodeo.components.MimeHandling
import io.spicelabs.rodeocomponents.APIS.mimes.MimeIdentifier
import io.spicelabs.rodeocomponents.APIS.mimes.MimeInputStreamIdentifier
import io.spicelabs.rodeocomponents.APIS.mimes.MimeFileInputStreamIdentifier
import com.typesafe.scalalogging.Logger
import scala.annotation.tailrec
import scala.jdk.OptionConverters._
import java.io.FileInputStream

class ComponentDetector extends Detector {
  val log = Logger(classOf[ComponentDetector])
  override def detect(input: InputStream, metadata: Metadata): MediaType = {
    val isDetectors = MimeHandling.inputStreamIdentifiers.get()
    val fileDetectors = MimeHandling.fileStreamIdentifiers.get()
    (isDetectors.length > 0, fileDetectors.length > 0) match {
      case (true, false) => detectISOnly(input, metadata, isDetectors)
      case (false, true) => {
        FileInputStreamEx.toFileInputStream(input, metadata) match {
          case Some(fs) => {
            val result = detectFSOnly(fs, metadata, fileDetectors)
            FileInputStreamEx.closeIfNeeded(fs)
            result
          }
          case None => MediaType.OCTET_STREAM
        }
      }
      case (true, true) =>
        detectOnBoth(input, metadata, fileDetectors, isDetectors)
      case (false, false) => MediaType.OCTET_STREAM
    }
  }

  def detectISOnly(
      input: InputStream,
      metadata: Metadata,
      isDetectors: Vector[MimeInputStreamIdentifier]
  ): MediaType = {
    val possibleLength = isDetectors.map(det => det.preferredHeaderLength()).max
    val detectorsToUse = selectDetectors(
      input,
      isDetectors,
      (is, n) => is.mark(n),
      is => is.reset()
    )
    selectMime(
      presentMimes(
        input,
        ComponentDetector.octetStream,
        detectorsToUse,
        is => is.mark(Int.MaxValue),
        is => is.reset(),
        Vector()
      )
    )
  }

  def detectFSOnly(
      input: FileInputStream,
      metadata: Metadata,
      fileDetectors: Vector[MimeFileInputStreamIdentifier]
  ): MediaType = {
    val possibleLength =
      fileDetectors.map(det => det.preferredHeaderLength()).max
    val detectorsToUse = selectDetectors(
      input,
      fileDetectors,
      (is, n) => {},
      is => input.getChannel().position(0)
    )
    val channel = Option(input.getChannel())
    channel.foreach(c => c.position(0))
    selectMime(
      presentMimes(
        input,
        ComponentDetector.octetStream,
        detectorsToUse,
        is => {},
        is => input.getChannel().position(0),
        Vector()
      )
    )
  }

  def detectOnBoth(
      input: InputStream,
      metadata: Metadata,
      fileDetectors: Vector[MimeFileInputStreamIdentifier],
      isDetectors: Vector[MimeInputStreamIdentifier]
  ): MediaType = {
    val isResult = detectISOnly(input, metadata, isDetectors)
    if (isResult != MediaType.OCTET_STREAM)
      isResult
    else {
      FileInputStreamEx.toFileInputStream(input, metadata) match {
        case Some(fs) => {
          val result = detectFSOnly(fs, metadata, fileDetectors)
          FileInputStreamEx.closeIfNeeded(fs)
          result
        }
        case None => MediaType.OCTET_STREAM
      }
    }
  }

  private def selectMime(mimes: Vector[String]): MediaType = {
    if (mimes.isEmpty) {
      MediaType.OCTET_STREAM
    } else {
      MediaType.parse(mimes.last)
    }
  }

  private def selectDetectors[T <: InputStream](
      is: InputStream,
      detectors: Vector[MimeIdentifier[T]],
      preRead: (T, Int) => Unit,
      postRead: T => Unit
  ): Vector[MimeIdentifier[T]] = {
    val possibleLength = detectors.map(det => det.preferredHeaderLength()).max
    if (possibleLength > 0) {
      val buffer = new Array[Byte](possibleLength)
      if (is.markSupported())
        is.mark(possibleLength)
      is.read(buffer)
      if (is.markSupported())
        is.reset()
      detectors.filter(det => det.canHandleHeader(buffer))
    } else {
      detectors
    }
  }

  @tailrec
  private def presentMimes[T <: InputStream](
      is: T,
      mimeSoFar: String,
      detectors: Vector[MimeIdentifier[T]],
      preIdentify: T => Unit,
      postIdentify: T => Unit,
      results: Vector[String]
  ): Vector[String] = {
    if (detectors.length == 0) {
      results;
    } else {
      val detector = detectors.head
      preIdentify(is)
      val mimeOpt = detector.identifyMimeType(is, mimeSoFar).toScala
      postIdentify(is)
      mimeOpt match {
        case None =>
          presentMimes(
            is,
            mimeSoFar,
            detectors.tail,
            preIdentify,
            postIdentify,
            results
          )
        case Some(mime) =>
          presentMimes(
            is,
            mime,
            detectors.tail,
            preIdentify,
            postIdentify,
            results :+ mime
          )
      }
    }
  }
}

object ComponentDetector {
  lazy val octetStream = MediaType.OCTET_STREAM.toString()
}
