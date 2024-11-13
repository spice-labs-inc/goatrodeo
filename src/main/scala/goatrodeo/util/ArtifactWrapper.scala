package io.spicelabs.goatrodeo.util

import com.palantir.isofilereader.isofilereader.{GenericInternalIsoFile, IsoFileReader}
import com.typesafe.scalalogging.Logger

import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters.*
import java.io.InputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.ByteArrayInputStream
import scala.annotation.tailrec

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
  def fileExtension(): Option[String]
}

object ArtifactWrapper {
  private val logger = Logger("ArtifactWrapper")

  // todo - return a Try[ArtifactWrapper]?
  def fromFile(f: File, deleteOnFinalize: Boolean): ArtifactWrapper = {
    val path = f.getPath()
    val ext = path.slice(path.lastIndexWhere(_ == '.') + 1, path.length)
    logger.info(s"File Extension: $ext")
    ext match {
      case "iso" =>
        logger.info(s"Found a '.iso' file: $path / $ext")
        ISOFileWrapper.fromFile(f, deleteOnFinalize) match {
          case Success(wrapper) => wrapper
          case Failure(e) =>
            logger.error(s"Exception creating an ISOFileWrapper: ${e.getMessage}")
            throw e
        }
      case _ =>
        logger.info(s"Found a '.$ext' file; defaulting to `FileWrapper`")
        FileWrapper(f, deleteOnFinalize)
    }
  }
  def fileExtension(path: String): String = {
    path.slice(path.lastIndexWhere(_ == '.') + 1, path.length)
  }
}

case class FileWrapper(f: File, deleteOnFinalize: Boolean)
    extends ArtifactWrapper {
  private val logger = Logger("FileWrapper")

  override protected def finalize(): Unit = {
    if (deleteOnFinalize) {
      f.delete()
    }
  }

  def exists(): Boolean = f.exists()
  override def isRealFile(): Boolean = true

  override def delete(): Boolean = f.delete()

  override def isFile(): Boolean = f.isFile()

  override def listFiles(): Vector[ArtifactWrapper] = {
    // the javadocs for `listFiles` says that if it is not a directory, it returns `null`… we need to guard against that
    Option(f.listFiles()) match {
      case Some(files) =>
        files.toVector
          .filter(!_.getName().startsWith("."))
          .map(FileWrapper(_, false))
      case None => Vector.empty
    }
  }

  override def getParentDirectoryPath(): String =
    f.getAbsoluteFile().getParentFile().getCanonicalPath()

  override def getCanonicalPath(): String = f.getCanonicalPath()

  override def isDirectory(): Boolean = f.isDirectory()

  override def asStream(): InputStream = BufferedInputStream(FileInputStream(f))

  override def name(): String = f.getName()

  override def size(): Long = f.length()

  override def fileExtension(): Option[String] = {
    val path = f.getPath()
    val ext = path.slice(path.lastIndexWhere(_ == '.') + 1, path.length)
    logger.debug(s"File Extension for '$path': '$ext'")
    Some(ext)
  }

}

case class ByteWrapper(bytes: Array[Byte], fileName: String)
    extends ArtifactWrapper {
  private val logger = Logger("")

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

  override def fileExtension(): Option[String] = {
    logger.debug(s"File Extension against ByteWrapper, returning None")
    None
  }
}

case class ISOFileWrapper(f: File, isoReader: IsoFileReader, deleteOnFinalize: Boolean)
  extends ArtifactWrapper {

  private val logger = Logger("ISOFileWrapper")


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

  override def listFiles(): Vector[InternalISOFileWrapper] = {
      Option(isoReader.getAllFiles) match {
        case Some(files) =>
          files.toVector
            .filter(!_.getFileName().startsWith("."))
            .map(InternalISOFileWrapper(_, isoReader))
            .flatMap(_.listFiles())
        case None => Vector.empty
      }
  }

  override def getCanonicalPath(): String = f.getCanonicalPath()

  override def getParentDirectoryPath(): String = f.getAbsoluteFile().getParentFile().getCanonicalPath()

  override def delete(): Boolean = f.delete()

  override def exists(): Boolean = f.exists()

  override def fileExtension(): Option[String] = {
    logger.debug("File Extension requested for 'ISO' - Returning static '.iso' result")
    Some("iso")
  }
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

case class InternalISOFileWrapper(f: GenericInternalIsoFile, isoReader: IsoFileReader)
  extends ArtifactWrapper {

  private val logger = Logger("InternalISOFileWrapper")

  override def asStream(): InputStream = isoReader.getFileStream(f)

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
   */
  override def listFiles(): Vector[InternalISOFileWrapper] = {
    logger.info("List Files")
    Option(f.getChildren()) match {
      case Some(files) =>
        files.toVector
          .filter(!_.getFileName().startsWith("."))
          .flatMap(entry =>  {
            val x = InternalISOFileWrapper.getISOSubfileTree(InternalISOFileWrapper(entry, isoReader), Vector.empty)
            logger.info(s"Entry: ${entry.getFileName}, children: $x")
            x
          })
      case None => Vector.empty
    }
  }


  override def getCanonicalPath(): String = f.getFullFileName('/')

  override def getParentDirectoryPath(): String = f.getParent().getFullFileName('/')

  // TODO - we can't delete contents of ISO Files (they are immutable) so we probably need a way to NOOP or declare capability…
  override def delete(): Boolean = false

  // TODO - there is no "exists" on the `GenericInternalIsoFile` so we'll just assume it exists for now
  override def exists(): Boolean = true

  override def fileExtension(): Option[String] = {
    val path = f.getFileName()
    val ext = ArtifactWrapper.fileExtension(path)
    logger.debug(s"File Extension for '$path': '$ext'")
    Some(ext)
  }

}

object InternalISOFileWrapper {
  private val logger = Logger("InternalISOFileWrapper")

  // TODO - make tail recursive
  def getISOSubfileTree(entry: InternalISOFileWrapper, files: Vector[InternalISOFileWrapper]): Vector[InternalISOFileWrapper] = {
    val children = entry.f.getChildren()
    if (children.isEmpty) {
      return files
    }

    val eB = Vector.newBuilder[InternalISOFileWrapper]
    for (child <- children) {
      logger.info(s"Subfiles: ${child.getFileName}")
      eB ++= getISOSubfileTree(InternalISOFileWrapper(child, entry.isoReader), files :+ InternalISOFileWrapper(child, entry.isoReader))
    }

    eB.result()
  }

}

