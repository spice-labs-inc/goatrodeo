package io.spicelabs.goatrodeo.util

import com.palantir.isofilereader.isofilereader.{GenericInternalIsoFile, IsoFileReader}

import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters.*

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
  def listFiles(): Vector[ArtifactWrapper]
  def getCanonicalPath(): String
  def getParentDirectoryPath(): String
  def delete(): Boolean
  def exists(): Boolean
}

/**
 * While initially "FileWrapper" has handled all object types, we need this to also be a generic
 * constructor that can choose its ArtifactWrapper type based on the file extension…
 */
object FileWrapper {
  def fromFile(f: File, deleteOnFinalize: Boolean): ArtifactWrapper = {
    if (f.getPath().endsWith(".iso")) {
      // todo - logging library
      println(s"Found a .iso file: ${f.getPath()}")
      ISOFileWrapper.fromFile(f, deleteOnFinalize) match {
        case Success(wrapper) => wrapper
        case Failure(e) =>
          sys.error(s"Exception creating an ISOFileWrapper: ${e.getMessage}")
          throw e
      }
    } else FileWrapper(f, deleteOnFinalize)
  }
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

  // todo - the javadocs for `listFiles` says that if it is not a directory, it returns `null`…
  // we need to guard against that
  override def listFiles(): Vector[ArtifactWrapper] =
    f.listFiles().toVector.filter(!_.getName().startsWith(".")).map(FileWrapper.fromFile(_, false))

  override def getParentDirectoryPath(): String =
    f.getAbsoluteFile().getParentFile().getCanonicalPath()

  override def getCanonicalPath(): String = f.getCanonicalPath()

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

  override def getParentDirectoryPath(): String = "/"

  override def getCanonicalPath(): String = "/"

  override def isDirectory(): Boolean = false

  override def asStream(): InputStream = ByteArrayInputStream(bytes)

  override def name(): String = fileName

  override def size(): Long = bytes.length
}

case class ISOFileWrapper(f: File, isoReader: IsoFileReader, deleteOnFinalize: Boolean)
  extends ArtifactWrapper {

  override protected def finalize(): Unit = {
    if (deleteOnFinalize) {
      f.delete()
    }
  }

  /**
   * ISOFileWrapper is an "outer" wrapper for the ISO file itself; all the actual internal files
   * are of type `InternalISOFileWrapper`; here for now we'll return an empty stream
   * TODO - figure out better pathing
   */
  override def asStream(): InputStream =
    InputStream.nullInputStream()

  override def name(): String = f.getName()

  override def size(): Long = f.length()

  override def isFile(): Boolean = f.isFile()

  override def isDirectory(): Boolean = f.isDirectory()

  // TODO: Check that "isRealFile" denotes the right thing
  // it isn't called anywhere, and i'm not sure if the actual .iso is "real" or the files inside of it are "Real"…
  override def isRealFile(): Boolean = true

  override def listFiles(): Vector[InternalISOFileWrapper] =
    isoReader.getAllFiles()
      .toVector
      .filter(!_.getFileName().startsWith("."))
      .map(InternalISOFileWrapper(_, isoReader))

  override def getCanonicalPath(): String = f.getCanonicalPath()

  override def getParentDirectoryPath(): String = f.getAbsoluteFile().getParentFile().getCanonicalPath()

  override def delete(): Boolean = f.delete()

  override def exists(): Boolean = f.exists()
}

/**
 * This is the "Outer" wrapper - specifically, it should be the actual `.iso` file.
 * When you walk the file tree of an `ISOFileWrapper` you should expect to get `InternalFileISOWrapper` objects back
 * TODO: should we put a guard that detects if the file ends with '.iso'?
 */
object ISOFileWrapper {
  def fromFile(f: File, deleteOnFinalize: Boolean): Try[ISOFileWrapper] = {
    Try {
      val reader = new IsoFileReader(f)
      // let the reader code figure out the best settings for the various formats
      // that are available for ISO files…
      reader.findOptimalSettings()
      ISOFileWrapper(f, reader, deleteOnFinalize)
    }
  }
}

/**
 * Each file inside an ISO is a distinct entity of, in this case, `GenericInternalIsoFile`
 * This represents a file "inside" of the ISO, which is where you get the individual stream
 */

case class InternalISOFileWrapper(f: GenericInternalIsoFile, isoFileReader: IsoFileReader)
  extends ArtifactWrapper {

  override def asStream(): InputStream = isoFileReader.getFileStream(f)

  override def name(): String = f.getFileName()

  override def size(): Long = f.getSize()

  // GenericInternalIsoFile has no `isFile` so i'm hoping assuming if it isnt a directory it's a file works…
  override def isFile(): Boolean = !f.isDirectory

  override def isDirectory(): Boolean = f.isDirectory

  // TODO: Check that "isRealFile" denotes the right thing
  override def isRealFile(): Boolean = true

  /**
   * an `InternalISOFileWrapper` is a concrete file (or directory) inside an actual .iso;
   * as such it can have children
   *
   * At this time, `FileWalker` has an outer control guard for whether this is a directory or file
   * TODO: Update this interface to better handle someone calling "listFiles()" when it's not a directory…
   */
  override def listFiles(): Vector[ArtifactWrapper] =
    f.getChildren()
      .toVector
      .filter(!_.getFileName().startsWith(".")) // TODO - check what other filters we may need for ISO
      .map(InternalISOFileWrapper(_, isoFileReader))

  override def getCanonicalPath(): String = f.getFullFileName('/')

  override def getParentDirectoryPath(): String = f.getParent().getFullFileName('/')

  // TODO - we can't delete contents of ISO Files (they are immutable) so we probably need a way to NOOP or declare capability…
  override def delete(): Boolean = false

  // TODO - there is no "exists" on the `GenericInternalIsoFile` so we'll just assume it exists for now
  override def exists(): Boolean = true
}

