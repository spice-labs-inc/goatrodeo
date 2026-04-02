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
import java.util.concurrent.atomic.AtomicReference
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using
import org.apache.tika.utils.XMLReaderUtils

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
  protected def asStream(): BufferedInputStream

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
  def withStream[T](f: BufferedInputStream => T): T = {
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

  private lazy val _mimeType: Set[String] = {
    val base = Set(Using.resource(getTikaInputStream()) { stream =>
      ArtifactWrapper.mimeTypeFor(stream, this.path())
    })
    ArtifactWrapper.augmentMimeTypes(this, base)
  }

  def isRealFile(): Boolean = false

  def mimeType: Set[String] = _mimeType

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

  // these methods implement the RodeoArtifact interface for the API

  /** Returns true if the artifact is represented by a real file, false
    * otherwise
    *
    * @return
    *   true if the artifact is a real file, false otherwise
    */
  def getIsRealFile(): Boolean = isRealFile()

  /** Returns the mime type of the artifact
    *
    * @return
    *   a string representing the mime type of the artifact
    */
  def getMimeType(): Set[String] = mimeType

  /** Get a path or a name of the artifact. If the artifact is not represented
    * by a real file, then this may be simple the name of the artifact within a
    * container.
    *
    * @return
    *   the path or name of the artifact
    */
  def getPath(): String = path()

  /** Gets the size of the artifact in bytes
    *
    * @return
    *   the size of the artifact in bytes
    */
  def getSize(): Long = size()

  /** Gets a unique identifier for the artifact
    *
    * @return
    *   a unique identifier for the artifact
    */
  def getUuid(): String = uuid

  /** Get the name of the file without the path
    *
    * @return
    */
  def getFilenameWithNoPath(): String = filenameWithNoPath

  /** Execute the function with a file on the filesystem. The file may be
    * temporary and may only exist for the duration of the scope of `withFile`,
    * however, files are on a POSIX filesystem so having a handle to the file
    * (e.g., `FileInputStream`), the FileInputStream will live for the duration
    * of the reference.
    */
  def withFile[T](func: File => T): T
}

/** Companion object for ArtifactWrapper with factory methods and MIME type
  * detection.
  */
object ArtifactWrapper {
  private val logger = Logger(getClass())

  /** Maximum size for in-memory artifact storage (32MB). */
  val maxInMemorySize: Long = 32L * 1024 * 1024;
  private val tika = new TikaConfig()
  // avoid the WARN Contention waiting for a SAXParser. Consider increasing the XMLReaderUtils.POOL_SIZE warning
  XMLReaderUtils.setPoolSize(200)

  /** Given an input stream and a filename, get the mime type
    *
    * @param data
    *   -- the input stream
    * @param fileName
    *   -- the name of the file
    */
  protected def mimeTypeFor(
      rawData: TikaInputStream,
      fileName: String
  ): String = {
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

  private val mimeTypeAugmenters
      : AtomicReference[Vector[(ArtifactWrapper, Set[String]) => Set[String]]] =
    AtomicReference(Vector())

  /** Augment the mime type with other mime types
    */
  def augmentMimeTypes(
      artifact: ArtifactWrapper,
      mimes: Set[String]
  ): Set[String] = {
    mimeTypeAugmenters.get().foldLeft(mimes) { case (cur, theFn) =>
      theFn(artifact, cur)
    }
  }

  def addMimeTypeAugmenter(
      theFn: (ArtifactWrapper, Set[String]) => Set[String]
  ): Unit = {
    mimeTypeAugmenters.getAndUpdate(v => v :+ theFn)
  }

  // constructor
  addMimeTypeAugmenter(DotnetDetector.mimeTypeAugmenter)
  addMimeTypeAugmenter(SaffronDetector.mimeTypeAugmentor)

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

  private def isNupkg(
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

  // constructor
  if (!wrappedFile.exists()) {
    throw Exception(
      f"Tried to create file wrapper for ${wrappedFile.getCanonicalPath()} that does not exist"
    )
  }

  override def isRealFile(): Boolean = true
  override protected def getTikaInputStream(): TikaInputStream = {
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, thePath)
    TikaInputStream.get(wrappedFile.toPath(), metadata)
  }

  override protected def asStream(): BufferedInputStream =
    new BufferedInputStream(
      FileInputStream(wrappedFile)
    )

  /** Execute the function with a file on the filesystem. The file may be
    * temporary and may only exist for the duration of the scope of `withFile`,
    * however, files are on a POSIX filesystem so having a handle to the file
    * (e.g., `FileInputStream`), the FileInputStream will live for the duration
    * of the reference.
    */
  def withFile[T](func: File => T): T = {
    func(wrappedFile)
  }

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

  /** Execute the function with a file on the filesystem. The file may be
    * temporary and may only exist for the duration of the scope of `withFile`,
    * however, files are on a POSIX filesystem so having a handle to the file
    * (e.g., `FileInputStream`), the FileInputStream will live for the duration
    * of the reference.
    */
  def withFile[T](func: File => T): T = {
    FileWalker.withinTempDir { path =>
      val file = Helpers.tempFileFromStream(asStream(), false, path)
      try {
        func(file)
      } finally {
        file.delete()
      }
    }
  }

  override def path(): String = fileName

  override def size(): Long = bytes.length

  /** When the ArtifactWrapper is done being processed and it was processed
    * successfully, call this method
    */
  def finished(): Unit = {} // do nothing
}
