package goatrodeo.util

import java.io.InputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.ByteArrayInputStream
import goatrodeo.util.Helpers.artifactWrapper
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
  val uuid: String = UUID.randomUUID().toString()
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
  // def name(): String

  /** The number of bytes in the artifact. This is
    *
    * @return
    */
  def size(): Long

  /** Get all the `ArtifactWrappers` for the entities in a directory. This will
    * only return something if the ArtifactWrapper is a wrapper around `File`
    * and the entity being traversed is a real filesystem.
    *
    * For "container" file types (e.g., a TAR), see
    * `FileWalker.streamForArchive`
    *
    * @return
    */
  def children(): FileWalker.OptionalArchiveStream = FileWalker
    .streamForArchive(this)

  def getPath(): String

  /** The mime type of the artifact if it's a File
    *
    * @return
    */
  def mimeType: String

  /** Does the entity exist. Corresponds to `File.exists`
    *
    * A `ByteWrapper` will always exist.
    *
    * @return
    */
  def exists(): Boolean

  /** If the ArtifactWrapper is a temporarily created file, deleted it
    */
  def cleanUp(): Unit

  // lazy val suffix = ArtifactWrapper.suffix(name())
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

  /** Get the mime type for a `File`
    *
    * @param f
    *   File
    * @return
    *   mime type
    */
  def mimeType(f: File): String = {
    Helpers.mimeTypeFor(new BufferedInputStream(new FileInputStream(f)), f.getName())
  }

  /** Get the mime type for an array of bytes
    *
    * @param b
    *   the `Array[Byte]`
    * @param name
    *   the name
    * @return
    *   mime type
    */
  def mimeType(b: Array[Byte], name: String): String = {
    Helpers.mimeTypeFor(new ByteArrayInputStream(b), name)
  }
}

object FileWrapper {

  /** Create a file wrapper from a `File` instance
    *
    * @param f
    *   the file
    * @return
    *   the FileWrapper
    */
  def from(f: File): FileWrapper =
    FileWrapper(f, ArtifactWrapper.mimeType(f), false)

  def fromTemp(f: File): FileWrapper =
    FileWrapper(f, ArtifactWrapper.mimeType(f), true)
}

final case class FileWrapper(
    f: File,
    mimeType: String,
    deleteOnFinalize: Boolean
) extends ArtifactWrapper {

  override def equals(other: Any): Boolean = other match {
    case af: ArtifactWrapper => af.uuid == this.uuid
    case _ => false
  }
  override def getPath(): String = f.getPath()

  override protected def finalize(): Unit = {
    if (deleteOnFinalize) {
      f.delete()
    }
  }

  def cleanUp(): Unit = if (deleteOnFinalize) f.delete()

  def exists(): Boolean = f.exists()

  override def asStream(): BufferedInputStream = BufferedInputStream(
    FileInputStream(f)
  )

  override def size(): Long = f.length()

}

final case class ByteWrapper(
    bytes: Array[Byte],
    mimeType: String,
    fileName: String
) extends ArtifactWrapper {
  override def equals(other: Any): Boolean = other match {
    case af: ArtifactWrapper => af.uuid == this.uuid
    case _ => false
  }
  
  override def getPath(): String = fileName

  override def exists(): Boolean = true

  private lazy val nameAsFile = new File(fileName)

  // override def getParentDirectory(): File = File("/")

  override def asStream(): BufferedInputStream = new BufferedInputStream(
    ByteArrayInputStream(bytes)
  )

  /// override def name(): String = nameAsFile.getName()

  override def size(): Long = bytes.length

  def cleanUp(): Unit = {}
}
