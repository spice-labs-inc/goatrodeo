package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import io.spicelabs.cilantro.AssemblyDefinition
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.mime.MediaType

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import scala.util.Try
import scala.util.Using

/** In OmniBOR, everything is seen as a byte stream.
  *
  * `ArtifactWrapper` provides a wrapper around artifacts... byte streams.
  *
  * `ArtifactWrapper` is somewhat modeled on the JVM `java.io.File` class so
  * that it can represent both files on a filesystem with directories and
  * virtual files contained in a Zip or TAR or other container file.
  *
  * Those byte streams may be in-memory or they may be on disk.
  */
sealed trait ArtifactWrapper {

  /** Convert the Artifact to a stream of bytes. Note that this is mostly used
    * for Hashing which is a block operation. No need for any buffering because
    * all the operations will be pulling large blocks of bytes.
    *
    * @return
    */
  protected def asStream(): InputStream

  def withStream[T](f: InputStream => T): T = {
    Using.resource(asStream()) { stream =>
      f(stream)
    }
  }

  /** The name of the Artifact. This corresponds to the name of a `File` on disk
    *
    * @return
    */
  def path(): String

  /** The number of bytes in the artifact.
    *
    * @return
    */
  def size(): Long

  private lazy val _mimeType: String = Using.resource(getTikaInputStream()) {
    stream =>
      ArtifactWrapper.mimeTypeFor(stream, this.path())
  }

  def isRealFile(): Boolean = false

  def mimeType: String = _mimeType

  protected def getTikaInputStream(): TikaInputStream

  lazy val uuid: String = UUID.randomUUID().toString()

  /** Get the name for the file irregarless of the path
    */
  lazy val filenameWithNoPath: String = (new File(path())).getName()

  protected def fixPath(p: String): String = ArtifactWrapper.fixPath(p)

  def tempDir: Option[File]

  /** When the ArtifactWrapper is done being processed and it was processed
    * successfully, call this method
    */
  def finished(): Unit

  /** If the ArtifactWraper doesn't point to a file, create a temporary file
    *
    * @param tempDir
    *   the temporary directory to put the file in
    *
    * @return
    *   the file
    */
  def forceFile(tempDir: Path): File = {
    this match {
      case fw: FileWrapper => fw.wrappedFile
      case _ => this.withStream(Helpers.tempFileFromStream(_, true, tempDir))
    }
  }
}

object ArtifactWrapper {
  private val logger = Logger(getClass())
  // max in memory size 32MB
  val maxInMemorySize: Long = 32L * 1024 * 1024;
  private val tika = new TikaConfig()

  /** Given an input stream and a filename, get the mime type
    *
    * @param data
    *   -- the input stream
    * @param fileName
    *   -- the name of the file
    */
  def mimeTypeFor(rawData: TikaInputStream, fileName: String): String = {
    try {
      val data = rawData
      val len = rawData.getLength()

      val metadata = new Metadata()
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
      val detected = tika.getDetector.detect(data, metadata)

      massageMimeType(fileName, rawData, detected)
    } catch {
      case e: Exception =>
        logger.error(
          f"Tika failed, ${e.getMessage()}. Returning application/octet-stream",
          e
        )
        "application/octet-stream"
    }
  }

  private def massageMimeType(
      fileName: String,
      rawData: TikaInputStream,
      detected: MediaType
  ): String = {
    // if you add more changes to this, add to the mime_types.md documentation
    val isJson = Try {
      if (detected == MediaType.TEXT_PLAIN && rawData.getLength() < 1000000) {
        import org.json4s._
        import org.json4s.native.JsonMethods._

        if (rawData.getPosition() > 0) {
          rawData.skip(-rawData.getPosition())
        }
        val bytes = new String(Helpers.slurpInputNoClose(rawData), "UTF-8")
        parseOpt(bytes).isDefined
      } else false
    }.toOption
    if (isJson == Some(true))
      return "application/json"

    val detectedString = detected.toString()
    val isDotNet = testForDotNet(fileName, detectedString);
    if (isDotNet)
      return "application/x-msdownload; format=pe32-dotnet"
    detectedString
  }

  private def testForDotNet(fileName: String, detectedString: String) = {
    if (detectedString != "application/x-msdownload; format=pe32") {
      false
    } else {
      try {
        val assembly = AssemblyDefinition.readAssembly(fileName)
        assembly != null && assembly.mainModule != null
      } catch { _ =>
        false
      }
    }
  }

  protected def fixPath(p: String): String = {
    if (p.startsWith("./")) fixPath(p.substring(2))
    else if (p.startsWith("/")) fixPath(p.substring(1))
    else if (p.startsWith("../")) fixPath(p.substring(3))
    else p
  }

  /** Give some data, create a new wrapper for it
    *
    * @param nominalPath
    *   the path
    * @param size
    *   the size of the data
    * @param data
    *   the InputStream of the data
    * @param tempPath
    *   the temporary directory to put a file in
    *
    * @return
    *   the built item
    */
  def newWrapper(
      nominalPath: String,
      size: Long,
      data: InputStream,
      tempDir: Option[File],
      tempPath: Path
  ): ArtifactWrapper = {
    val name = fixPath(nominalPath)

    // a defined temp dir implies a RAM disk... copy everything but the smallest items
    if (size <= (if (tempDir.isDefined) (64L * 1024L) else maxInMemorySize)) {
      val bos = ByteArrayOutputStream()
      Helpers.copy(data, bos)
      val bytes = bos.toByteArray()
      if (size != bytes.length) {
        throw Exception(
          f"Failed to create wrapper for ${name} expecting ${size} bytes, but got ${bytes.length}"
        )
      }
      ByteWrapper(bytes, name, tempDir = tempDir)
    } else {
      val tempFile = Files.createTempFile(tempPath, "goats", ".temp").toFile()
      val fos = FileOutputStream(tempFile)
      Helpers.copy(data, fos)
      fos.flush()
      fos.close()
      FileWrapper(tempFile, name, tempDir = tempDir)
    }
  }
}

final case class FileWrapper(
    wrappedFile: File,
    thePath: String,
    tempDir: Option[File],
    finishedFunc: File => Unit = f => ()
) extends ArtifactWrapper {
  override def isRealFile(): Boolean = true
  override protected def getTikaInputStream(): TikaInputStream = {
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, thePath)
    TikaInputStream.get(wrappedFile.toPath(), metadata)
  }

  if (!wrappedFile.exists()) {
    throw Exception(
      f"Tried to create file wrapper for ${wrappedFile.getCanonicalPath()} that does not exist"
    )
  }
  override protected def asStream(): InputStream = new BufferedInputStream(
    FileInputStream(wrappedFile)
  )

  override def path(): String = fixPath(thePath)

  override def size(): Long = wrappedFile.length()

  /** When the ArtifactWrapper is done being processed and it was processed
    * successfully, call this method
    */
  def finished(): Unit = finishedFunc(wrappedFile)
}

object FileWrapper {
  def fromName(name: String, tempDir: Option[File]): FileWrapper = {
    FileWrapper(new File(name), name, tempDir = tempDir)
  }
}

final case class ByteWrapper(
    bytes: Array[Byte],
    fileName: String,
    tempDir: Option[File]
) extends ArtifactWrapper {

  override protected def getTikaInputStream(): TikaInputStream = {
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
    TikaInputStream.get(bytes, metadata)
  }
  override def asStream(): BufferedInputStream = new BufferedInputStream(
    ByteArrayInputStream(bytes)
  )

  override def path(): String = fileName

  override def size(): Long = bytes.length

  /** When the ArtifactWrapper is done being processed and it was processed
    * successfully, call this method
    */
  def finished(): Unit = {} // do nothing
}
