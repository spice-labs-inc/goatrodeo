package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.apache.commons.io.FilenameUtils
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
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact

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
sealed trait ArtifactWrapper extends RodeoArtifact {

  /** Convert the Artifact to a stream of bytes. Note that this is mostly used
    * for Hashing which is a block operation. No need for any buffering because
    * all the operations will be pulling large blocks of bytes.
    *
    * @return
    */
  protected def asStream(): InputStream

  /** Execute a function with an InputStream to this artifact's content.
    *
    * The stream is automatically closed after the function completes.
    *
    * @param f
    *   the function to execute with the stream
    * @tparam T
    *   the return type of the function
    * @return
    *   the result of the function
    */
  def withStream[T](f: InputStream => T): T = {
    Using.resource(asStream()) { stream =>
      f(stream)
    }
  }

  /** The name of the Artifact. This corresponds to the name of a `File` on disk
    * or the name of the artifact if it was extracted from a container (Zip,
    * TAR, etc.).
    *
    * The path may be removed in certain cases. This is just a name and should
    * not be considered a path to the file on local storage
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

  /** Get the name for the file irregardless of the path
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

  // these methods implement the RodeoArtifact interface for the API

  /** Returns true if the artifact is represented by a real file, false
    * otherwise
    *
    * @return
    *   true if the artifact is a real file, false otherwise
    */
  override def getIsRealFile(): Boolean = isRealFile()

  /** Returns the mime type of the artifact
    *
    * @return
    *   a string representing the mime type of the artifact
    */
  override def getMimeType(): String = mimeType

  /** Get a path or a name of the artifact. If the artifact is not represented
    * by a real file, then this may be simple the name of the artifact within a
    * container.
    *
    * @return
    *   the path or name of the artifact
    */
  override def getPath(): String = path()

  /** Gets the size of the artifact in bytes
    *
    * @return
    *   the size of the artifact in bytes
    */
  override def getSize(): Long = size()

  /** Gets a unique indentifier for the artifact
    *
    * @return
    *   a unique identifier for the artifact
    */
  override def getUuid(): String = uuid

  /** Get the name of the file without the path
    *
    * @return
    */
  override def getFilenameWithNoPath(): String = filenameWithNoPath
}

/** Companion object for ArtifactWrapper with factory methods and MIME type
  * detection.
  */
object ArtifactWrapper {
  private val logger = Logger(getClass())

  /** Maximum size for in-memory artifact storage (32MB). */
  val maxInMemorySize: Long = 32L * 1024 * 1024;
  private val tika = new TikaConfig()
  private val detectorFactory =
    TikaDetectorFactory(tika, DotnetDetector(), ComponentDetector())

  /** Given an input stream and a filename, get the mime type
    *
    * @param data
    *   -- the input stream
    * @param fileName
    *   -- the name of the file
    */
  def mimeTypeFor(rawData: TikaInputStream, fileName: String): String = {
    Try {
      val data = rawData
      val len = rawData.getLength()

      val metadata = new Metadata()
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
      val detected = detectorFactory.toDetector().detect(data, metadata)
      massageMimeType(fileName, rawData, detected)
    } match {
      case Success(value) => value
      case Failure(e) =>
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
    Try {
      if (detected == MediaType.TEXT_PLAIN && rawData.getLength() < 1000000) {
        import org.json4s._
        import org.json4s.native.JsonMethods._

        if (rawData.getPosition() > 0) {
          rawData.skip(-rawData.getPosition())
        }
        val bytes = new String(Helpers.slurpInputNoClose(rawData), "UTF-8")
        parseOpt(bytes).isDefined
      } else false
    }.toOption match {
      case Some(true) => "application/json"
      case _ if isNupkg(fileName, detected.toString(), rawData) == Some(true) =>
        "application/zip"
      case _ => detected.toString()
    }
  }

  def isNupkg(
      fileName: String,
      detectedMime: String,
      rawData: TikaInputStream
  ): Option[Boolean] = {
    Try {
      if (
        fileName.endsWith(
          ".nupkg"
        ) && detectedMime == "application/x-tika-ooxml" && rawData
          .getLength() > 4
      ) {
        // rewind
        if (rawData.getPosition() > 0) {
          rawData.skip(-rawData.getPosition())
        }
        val bytes = rawData.readNBytes(4)
        // does it match nupkg header?
        Some(
          bytes(0) == 0x50 && bytes(1) == 0x4b && bytes(2) == 0x03 && bytes(
            3
          ) == 0x04
        )
      } else Some(false)
    } match {
      case Success(v) => v
      case Failure(e) =>
        logger.error(f"Nupkg detect failure ${e.getMessage()}")
        None
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
    val forceTempFile = requireTempFile(name)

    // a defined temp dir implies a RAM disk... copy everything but the smallest items
    if (
      !forceTempFile && size <= (if (tempDir.isDefined) (64L * 1024L)
                                 else maxInMemorySize)
    ) {
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

  /** Determine if a file must be written to a temp file rather than kept in
    * memory.
    *
    * Some file types (like .NET assemblies) require actual files on disk for
    * processing by external tools.
    *
    * @param name
    *   the filename to check
    * @return
    *   true if the file must be a temp file, false if it can be in memory
    */
  def requireTempFile(name: String): Boolean = {
    // for .NET assemblies, we'll force a temp file so that cilantro can open them.
    FilenameUtils.getExtension(name) match {
      case "dll" | "exe" => true
      case _             => false
    }
  }
}

/** An ArtifactWrapper backed by an actual file on disk.
  *
  * @param wrappedFile
  *   the actual File on disk
  * @param thePath
  *   the logical path of this artifact (may differ from wrappedFile's path)
  * @param tempDir
  *   optional temp directory for creating temporary files
  * @param finishedFunc
  *   callback function called when processing is complete
  */
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

/** Companion object for FileWrapper with factory methods. */
object FileWrapper {

  /** Create a FileWrapper from a file path.
    *
    * @param name
    *   the path to the file
    * @param tempDir
    *   optional temp directory for creating temporary files
    * @return
    *   a FileWrapper for the specified file
    */
  def fromName(name: String, tempDir: Option[File]): FileWrapper = {
    FileWrapper(new File(name), name, tempDir = tempDir)
  }
}

/** An ArtifactWrapper backed by an in-memory byte array.
  *
  * Used for small files or archive contents that can be held in memory.
  *
  * @param bytes
  *   the byte content of this artifact
  * @param fileName
  *   the logical filename of this artifact
  * @param tempDir
  *   optional temp directory for creating temporary files
  */
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
