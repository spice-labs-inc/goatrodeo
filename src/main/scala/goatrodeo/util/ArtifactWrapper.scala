package goatrodeo.util

import java.io.InputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.ByteArrayInputStream

trait ArtifactWrapper {
  def asStream(): InputStream
  def name(): String
  def size(): Long
  def isFile(): Boolean
  def isDirectory(): Boolean
  def isRealFile(): Boolean
  // def asFile(): (File, Boolean)
  def listFiles(): Vector[ArtifactWrapper]
  def getCanonicalPath(): String
  def getParentDirectory(): File
  def delete(): Boolean
  def exists(): Boolean
}

case class FileWrapper(f: File, deleteOnFinalize: Boolean)
    extends ArtifactWrapper {

  override protected def finalize(): Unit = {
    if (deleteOnFinalize) {
      f.delete()
    }
  }

  def exists(): Boolean = f.exists()
  override def isRealFile(): Boolean = true

  override def delete(): Boolean = f.delete()

  override def isFile(): Boolean = f.isFile()

  override def listFiles(): Vector[ArtifactWrapper] =
    f.listFiles().toVector.map(FileWrapper(_, false))

  override def getParentDirectory(): File = f.getAbsoluteFile().getParentFile()

  override def getCanonicalPath(): String = f.getCanonicalPath()

  // override def asFile(): (File, Boolean) = (f, false)

  override def isDirectory(): Boolean = f.isDirectory()

  override def asStream(): InputStream = BufferedInputStream(FileInputStream(f))

  override def name(): String = f.getName()

  override def size(): Long = f.length()
}

case class ByteWrapper(bytes: Array[Byte], fileName: String)
    extends ArtifactWrapper {

  def exists(): Boolean = true

  override def isRealFile(): Boolean = false

  override def delete(): Boolean = true

  override def isFile(): Boolean = true

  override def listFiles(): Vector[ArtifactWrapper] = Vector()

  override def getParentDirectory(): File = File("/")

  override def getCanonicalPath(): String = "/"

  // override def asFile(): (File, Boolean) =
  //   (Helpers.tempFileFromStream(asStream(), true, ".goat"), true)

  override def isDirectory(): Boolean = false

  override def asStream(): InputStream = ByteArrayInputStream(bytes)

  override def name(): String = fileName

  override def size(): Long = bytes.length
}

