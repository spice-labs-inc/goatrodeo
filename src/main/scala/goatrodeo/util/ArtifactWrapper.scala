package goatrodeo.util

import java.io.InputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.ByteArrayInputStream

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
  def asStream(): BufferedInputStream

  /** The name of the Artifact. This corresponds to the name of a `File` on disk
    *
    * @return
    */
  def name(): String

  /** The number of bytes in the artifact. This is
    *
    * @return
    */
  def size(): Long

  /** Corresponds to `File.isFile()`
    *
    * @return
    */
  def isFile(): Boolean

  /** Corresponds to `File.isDirectory()`
    *
    * @return
    */
  def isDirectory(): Boolean

  /** Get all the `ArtifactWrappers` for the entities in a directory. This will
    * only return something if the ArtifactWrapper is a wrapper around `File`
    * and the entity being traversed is a real filesystem.
    *
    * For "container" file types (e.g., a TAR), see
    * `FileWalker.streamForArchive`
    *
    * @return
    */
  def listFiles(): Iterator[ArtifactWrapper]

  def getCanonicalPath(): String
  def getParentDirectory(): File
  def delete(): Boolean

  /** Does the entity exist. Corresponds to `File.exists`
    *
    * A `ByteWrapper` will always exist.
    *
    * @return
    */
  def exists(): Boolean

  lazy val suffix = ArtifactWrapper.suffix(name())
}

object ArtifactWrapper {

  /** What's the suffix for the name
    *
    * @param name
    *   the file name
    * @return
    *   the optional suffix to lower case
    */
  def suffix(name: String): Option[String] = {
    val lastDot = name.lastIndexOf(".")
    if (lastDot >= 0) Some(name.substring(lastDot)) else None
  }
}

final case class FileWrapper(f: File, deleteOnFinalize: Boolean)
    extends ArtifactWrapper {

  override protected def finalize(): Unit = {
    if (deleteOnFinalize) {
      f.delete()
    }
  }

  def exists(): Boolean = f.exists()

  override def delete(): Boolean = f.delete()

  override def isFile(): Boolean = f.isFile()

  def listFiles(): Iterator[ArtifactWrapper] =
    f.listFiles().iterator.map(FileWrapper(_, false))

  override def getParentDirectory(): File = f.getAbsoluteFile().getParentFile()

  override def getCanonicalPath(): String = f.getCanonicalPath()

  // override def asFile(): (File, Boolean) = (f, false)

  def isDirectory(): Boolean = f.isDirectory()

  override def asStream(): BufferedInputStream = BufferedInputStream(FileInputStream(f))

  override def name(): String = f.getName()

  override def size(): Long = f.length()


}

final case class ByteWrapper(bytes: Array[Byte], fileName: String)
    extends ArtifactWrapper {

  def exists(): Boolean = true

  override def delete(): Boolean = true

  def isFile(): Boolean = true

  def listFiles(): Iterator[ArtifactWrapper] = Iterator.empty

  override def getParentDirectory(): File = File("/")

  override def getCanonicalPath(): String = "/"

  def isDirectory(): Boolean = false

  override def asStream(): BufferedInputStream = new BufferedInputStream(ByteArrayInputStream(bytes))

  override def name(): String = fileName

  override def size(): Long = bytes.length
}
