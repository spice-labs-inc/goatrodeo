package goatrodeo.util

import java.io.InputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.ByteArrayInputStream
import org.apache.tika.config.TikaConfig
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import java.util.UUID

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
  def asStream(): InputStream

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

  /** Corresponds to `File.isFile()`
    *
    * @return
    */
  // def isFile(): Boolean

  /** Corresponds to `File.isDirectory()`
    *
    * @return
    */
  // def isDirectory(): Boolean

  /** Get all the `ArtifactWrappers` for the entities in a directory. This will
    * only return something if the ArtifactWrapper is a wrapper around `File`
    * and the entity being traversed is a real filesystem.
    *
    * For "container" file types (e.g., a TAR), see
    * `FileWalker.streamForArchive`
    *
    * @return
    */
  // def listFiles(): Iterator[ArtifactWrapper]

  // def delete(): Boolean

  /** Does the entity exist. Corresponds to `File.exists`
    *
    * A `ByteWrapper` will always exist.
    *
    * @return
    */
  // def exists(): Boolean

  lazy val mimeType: String =
    ArtifactWrapper.mimeTypeFor(this.asStream(), this.path())

  lazy val uuid = UUID.randomUUID().toString()

  /** Get the name for the file irregarless of the path
    */
  lazy val filenameWithNoPath: String = (new File(path())).getName()

  protected def fixPath(p: String): String = {
    if (p.startsWith("./")) fixPath(p.substring(2))
    else if (p.startsWith("/")) fixPath(p.substring(1))
    else if (p.startsWith("../")) fixPath(p.substring(3))
    else p
  }
}

object ArtifactWrapper {

  private val tika = new TikaConfig()

  /** Given an input stream and a filename, get the mime type
    *
    * @param data
    *   -- the input stream
    * @param fileName
    *   -- the name of the file
    */
  def mimeTypeFor(rawData: InputStream, fileName: String): String = {

    val data = new BufferedInputStream(rawData)

    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
    val detected = tika.getDetector.detect(data, metadata)

    detected.toString()
  }

  /** What's the suffix for the name
    *
    * @param name
    *   the file name
    * @return
    *   the optional suffix to lower case
    */
  // def suffix(name: String): Option[String] = {
  //   val lastDot = name.lastIndexOf(".")
  //   if (lastDot >= 0) Some(name.substring(lastDot)) else None
  // }
}

final case class FileWrapper(f: File, thePath: String) extends ArtifactWrapper {
  override def asStream(): InputStream = new BufferedInputStream(
    FileInputStream(f)
  )

  override def path(): String = fixPath(thePath)

  override def size(): Long = f.length()

}

object FileWrapper {
  def fromName(name: String): FileWrapper = {
    FileWrapper(new File(name), name)
  }
}

final case class InputStreamWrapper(stream: () => InputStream, thePath: String)
    extends ArtifactWrapper {

  override def asStream(): InputStream = stream()

  override def path(): String = fixPath(thePath)

  private lazy val computedSize: Long = {
    val ba = new Array[Byte](4096)
    var cnt = 0L
    val is = stream()
    var keepRunning = true
    while (true) {
      is.read(ba) match {
        case -1 => keepRunning = false
        case n  => cnt += n
      }
    }
    cnt
  }
  override def size(): Long = computedSize

}

// final case class ByteWrapper(bytes: Array[Byte], fileName: String)
//     extends ArtifactWrapper {

//   def exists(): Boolean = true

//   override def delete(): Boolean = true

//   def isFile(): Boolean = true

//   def listFiles(): Iterator[ArtifactWrapper] = Iterator.empty

//   def isDirectory(): Boolean = false

//   override def asStream(): BufferedInputStream = new BufferedInputStream(
//     ByteArrayInputStream(bytes)
//   )

//   override def path(): String = fileName

//   override def size(): Long = bytes.length
// }
