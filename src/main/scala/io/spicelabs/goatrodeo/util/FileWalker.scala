package io.spicelabs.goatrodeo.util

import com.palantir.isofilereader.isofilereader.IsoFileReader
import com.typesafe.scalalogging.Logger
import io.spicelabs.baharat.rpm.RpmReader
import io.spicelabs.baharat.rpm.payload.PayloadEntry
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.compressors.CompressorInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import scala.util.Try
import scala.util.Using
import io.spicelabs.saffron.DiskReader
import scala.util.Failure
import scala.util.Success
import io.spicelabs.saffron.fs.FileSystemMount
import scala.jdk.CollectionConverters.*
import io.spicelabs.saffron.fs.FileSystemEntry.EntryType
import io.spicelabs.saffron.fs.FileSystemEntry
import io.spicelabs.saffron.fs.FileSystemEntry.RegularFile

/** Actions that can be taken while walking files in an archive.
  */
enum FileAction {

  /** Skip diving into the current artifact (don't process its contents). */
  case SkipDive

  /** End the file walk immediately. */
  case End
}

/** Utilities for traversing and extracting files from various archive formats.
  *
  * FileWalker provides functionality to:
  *   - Detect archive types (ZIP, JAR, TAR, TAR.GZ, ISO, DEB, etc.)
  *   - Extract and iterate over contents of archives
  *   - Handle nested archives recursively
  *   - Create temporary files for archive content processing
  *
  * Supported archive formats include:
  *   - ZIP-based: ZIP, JAR, WAR, Android APK
  *   - TAR-based: TAR, TAR.GZ, TAR.BZ2
  *   - RPM files
  *   - Other: ISO9660, DEB (Debian packages)
  */
object FileWalker {
  val logger: Logger = Logger(getClass())

  private lazy val zipMimeTypes: Set[String] =
    Set(
      "application/zip",
      "application/java-archive",
      "application/vnd.android.package-archive"
    )

  private lazy val isoMimeTypes: Set[String] = Set(
    "application/x-iso9660-image"
  )

  private lazy val rpmMimeTypes: Set[String] = Set("application/x-rpm")

  /** Try to construct an `OptionalArchiveStream` from a Zip/WAR/etc. file
    *
    * @param in
    *   the file to try to construct the stream from
    * @return
    */
  private def asZipContainer(
      in: ArtifactWrapper,
      tempDir: Path
  ): OptionalArchiveStream = {
    if (zipMimeTypes.intersect(in.mimeType).nonEmpty) {
      import scala.jdk.CollectionConverters.IteratorHasAsScala

      in.withFile { theFile =>
        try {

          val zipFile = ZipFile(theFile)
          try {
            val it: Vector[ArtifactWrapper] = zipFile
              .stream()
              .iterator()
              .asScala
              .filter(v => { !v.isDirectory() })
              .map(v => {
                val name = v.getName()
                val size = v.getSize()
                ArtifactWrapper
                  .newWrapper(
                    name,
                    size,
                    zipFile.getInputStream(v),
                    in.tempDir,
                    tempDir
                  )
              })
              .toVector
            Some(
              it,
              "Zip Container"
            )
          } finally {
            zipFile.close()
          }

        } catch {
          case e: Exception =>
            // if creating the artifact wrappers from the thing that might be a zip file fail, it
            // just means it's not a valid zip. This is okay.
            logger.trace(
              f"Failed for ${in.path()} mime type ${in.mimeType} error ${e.getMessage()}",
              e
            )
            None
        }
      }
    } else {
      None
    }
  }

  /** Try opening as an RPM file
    */
  private def asRPMWrapper(
      in: ArtifactWrapper,
      tempPath: Path
  ): OptionalArchiveStream = {
    if (rpmMimeTypes.intersect(in.mimeType).nonEmpty) {
      try {
        in.withFile(f => {
          Using.resource(RpmReader.streamPayload(f.toPath())) {
            import scala.jdk.CollectionConverters.IteratorHasAsScala
            payload =>
              val wrappers = for {
                entry <- payload.iterator().asScala

                file <- entry match {
                  case f: PayloadEntry.FileEntry => Some(f)
                  case _                         => None
                }
              } yield ArtifactWrapper.newWrapper(
                file.path(),
                file.size(),
                file.content(),
                in.tempDir,
                tempPath
              )
              Some(wrappers.toVector -> "RPM")
          }
        })
      } catch {
        // Throw an exception and it's not an RPM
        case e: Exception =>
          None
      }
    } else {
      None
    }
  }

  /** Try to open the thing as an ISO file wrapper
    *
    * @param in
    * @return
    */
  private def asISOWrapper(
      in: ArtifactWrapper,
      tempPath: Path
  ): OptionalArchiveStream = {
    if (isoMimeTypes.intersect(in.mimeType).nonEmpty) {
      try {
        val theFile = in match {
          case fw: FileWrapper => fw.wrappedFile
          case _ => in.withStream(Helpers.tempFileFromStream(_, true, tempPath))
        }

        val isoFileReader: IsoFileReader = new IsoFileReader(theFile)

        try {

          val files = isoFileReader.getAllFiles()

          import scala.jdk.CollectionConverters.ListHasAsScala
          val flatList =
            isoFileReader.convertTreeFilesToFlatList(files).asScala.toVector

          val wrappers =
            for (cycleFile <- flatList)
              yield {

                val nameWithThing = cycleFile.getFullFileName('/')
                val name =
                  (if (nameWithThing.endsWith(";1")) {
                     nameWithThing.substring(0, nameWithThing.length() - 2)

                   } else nameWithThing).toLowerCase()

                val size = cycleFile.getSize()

                ArtifactWrapper.newWrapper(
                  name,
                  size,
                  isoFileReader.getFileStream(cycleFile),
                  in.tempDir,
                  tempPath
                )
              }

          isoFileReader.close()

          Some(
            wrappers,
            "ISO Reader"
          )

        } catch {
          case e: Exception =>
            // if creating the artfiact wrappers from the thing that might be an iso file fail, it
            // just means it's not a valid iso. This is okay.
            logger.trace(s"Try getAllFiles failed: ${e.getMessage}", e)
            isoFileReader.close()
            None
        }
      } catch {
        case e: Exception =>
          // if creating the artfiact wrappers from the thing that might be a zip file fail, it
          // just means it's not a valid zip. This is okay.
          logger.trace(s"Whole ISO Fetch process failed… ${e.getMessage}", e)
          None
      }
    } else None
  }

  // partial list of saffron supported mime types
  // some, like raw (application/octet-stream) and google cloud
  // platform (application/gzip containing raw) are problematic in
  // that octect/stream is any unrecognized mime type and application/gzip is
  // already overloaded.
  private val saffronMimeTypes = Set[String](
    "application/x-qcow2",
    "application/x-vmdk",
    "application/x-vhd",
    "application/x-vhdx",
    "application/x-vdi",
    "application/x-ami"
  )

  private def isSaffronSupported(mime: Set[String], path: Path) = {
    // if we get some recognized mimes, succeed fast, otherwise check DiskReader
    mime.intersect(saffronMimeTypes).nonEmpty || DiskReader.isSupported(path)
  }

  private def asSaffronFilesystem(
      in: ArtifactWrapper,
      tempPath: Path
  ): OptionalArchiveStream = {
    in.withFile(f => {
      val path = f.toPath()
      if (isSaffronSupported(in.mimeType, path)) {
        Try {
          val disk = DiskReader.open(path)
          val systems = FileSystemMount.mountAll(disk).asScala
          val files =
            for {
              system <- systems
              file <- system.walk().iterator().asScala
            } yield file
          val artifacts = files
            .filter(f => f.`type`() == EntryType.REGULAR_FILE)
            .map(fileSystemEntry => {
              fileSystemEntry match {
                case regular: RegularFile =>
                  Some(
                    ArtifactWrapper.newWrapper(
                      regular.path(),
                      regular.size(),
                      regular.openStream(),
                      None,
                      tempPath
                    )
                  )
                case _ => None
              }
            })
            .flatten
            .toVector
          artifacts -> s"Saffron ${disk.format().name()}"
        } match {
          case Failure(exception) => None
          case Success(value)     => Some(value)
        }
      } else {
        None
      }
    })
  }

  /** Try to construct an `OptionalArchiveStream` using the Apache Commons "we
    * read most archive files" format
    *
    * Note this only reads *archives* – that is to say, something like a
    * `.tar.gz` with many files It will *not* work on simple single compressed
    * files in a gzip wrapper, which needs the `CompressorStreamFactory` rather
    * than the `ArchiveStreamFactory`
    *
    * @param in
    *   the file to try to construct the stream from
    * @return
    */
  private def asApacheCommonsArchiveWrapper(
      inputStream: BufferedInputStream,
      name: String,
      tempPath: Option[File],
      tempDir: Path
  ): OptionalArchiveStream = {
    val factory = (new ArchiveStreamFactory())

    try {
      val input: ArchiveInputStream[ArchiveEntry] = factory
        .createArchiveInputStream(
          inputStream
        )
      val theIterator = Helpers
        .iteratorFor(input)
        .filter(!_.isDirectory())
        .map(ae => {
          val artifactName = ae.getName()

          val size = ae.getSize()

          ArtifactWrapper
            .newWrapper(artifactName, size, input, tempPath, tempDir)
        })
        .toVector
      input.close()

      Some(theIterator -> "Apache Common Wrapper")
    } catch {
      case e: Exception =>
        None
    }
  }

  val definitelyNotArchive: Set[String] = Set(
    "application/java-vm",
    "text/plain",
    "multipart/appledouble",
    "application/xml-dtd",
    "application/xml",
    "text/x-java-properties",
    "text/x-scala",
    "text/x-java-source",
    "application/x-sh",
    "application/x-sharedlib",
    "image/png",
    "application/vnd.ms-fontobject",
    "application/x-font-ttf",
    "image/vnd.microsoft.icon",
    "image/gif",
    "application/x-sqlite3",
    "application/x-msdownload; format=pe64",
    "application/x-msdownload",
    "application/x-msdownload; format=pe32",
    "image/jpeg",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "audio/mpeg",
    "application/rdf+xml",
    "application/xhtml+xml",
    "image/svg+xml",
    "application/x-bat",
    "application/wsdl+xml",
    "application/x-mspublisher",
    "application/octet-stream",
    "application/x-dtbresource+xml",
    "application/vnd.iccprofile",
    "multipart/appledouble"
  )

  val notZip: Set[String] = Set(
    "application/x-rpm",
    "application/x-archive",
    "application/x-iso9660-image",
    "application/x-gtar",
    "application/x-debian-package",
    "application/gzip",
    "application/zstd"
  )

  val notCompressedSet: Set[String] = Set[String](
    "application/vnd.android.package-archive"
    // "application/x-debian-package",
    // "application/gzip",
    // "application/x-gtar"
  )

  /** Check if an artifact is definitely not an archive based on MIME type.
    *
    * @param in
    *   the artifact to check
    * @return
    *   true if this is definitely not an archive
    */
  def notArchive(in: ArtifactWrapper): Boolean =
    notArchive(in.path(), in.mimeType)

  /** Check if a file is definitely not an archive based on MIME type and path.
    *
    * @param path
    *   the file path
    * @param mimeType
    *   the MIME type of the file
    * @return
    *   true if this is definitely not an archive
    */
  def notArchive(path: String, mimeType: Set[String]): Boolean = {
    mimeType.exists(_.startsWith("text/")) ||
    mimeType.exists(_.startsWith("image/")) ||
    mimeType.forall(definitelyNotArchive.contains(_)) /*||
    (mimeType.contains("application/zip") && path.endsWith(".xpi"))*/
  }

  /** Check if a file is definitely not compressed based on MIME type and path.
    *
    * @param path
    *   the file path
    * @param mimeType
    *   the MIME type of the file
    * @return
    *   true if this is definitely not a compressed file
    */
  def notCompressed(path: String, mimeType: Set[String]): Boolean = {

    mimeType.exists(_.startsWith("text/")) ||
    mimeType.exists(_.startsWith("image/")) ||
    notCompressedSet.intersect(mimeType).nonEmpty

  }

  /** Try a series of strategies (except for uncompressing a file) for creating
    * an archive stream
    *
    * @param in
    *   the artifact wrapper to attempt to create a stream for
    * @return
    *   if the stream is created, an iterator of fuctions to access each stream
    *   piece and a closing/cleanup function which *must* be called
    */
  private def tryToConstructArchiveStream(
      in: ArtifactWrapper,
      tempDir: Path
  ): OptionalArchiveStream = {
    val mimeType = in.mimeType
    if (notArchive(in)) None
    else {
      {
        val ret = asZipContainer(in, tempDir)

        ret
      }.orElse(asRPMWrapper(in, tempDir))
        .orElse(asSaffronFilesystem(in, tempDir))
        .orElse(
          asISOWrapper(in, tempDir)
            .orElse {
              // the inputstream to the apache stuff is either a
              // decompressed file or the input stream from the artifact

              val withInputStreamFn
                  : BufferedInputStream => OptionalArchiveStream =
                theStream =>
                  asApacheCommonsArchiveWrapper(
                    theStream,
                    in.path(),
                    in.tempDir,
                    tempDir
                  )

              // if it's not compressed, just run with the stream
              if (notCompressed(in.path(), in.mimeType))
                in.withStream(s =>
                  withInputStreamFn(new BufferedInputStream(s))
                )
              else {
                // if it might be compressed, try to decompress the stream into a file
                val maybeFile: Option[File] = Try {
                  in.withStream { stream =>
                    val factory = new CompressorStreamFactory()
                    val fis = new BufferedInputStream(stream)

                    val input: CompressorInputStream =
                      factory.createCompressorInputStream(fis)

                    val theFile =
                      Files
                        .createTempFile(tempDir, "goats", "uncompressed")
                        .toFile()

                    val fos = new FileOutputStream(theFile)
                    Helpers.copy(input, fos)
                    fos.close()
                    theFile
                  }
                }.toOption

                maybeFile match {
                  // if we were able to decompress, then use the temp file
                  case Some(uncompressedFile) => {
                    try {
                      Using.resource(
                        new BufferedInputStream(
                          new FileInputStream(uncompressedFile)
                        )
                      ) { stream =>
                        withInputStreamFn(stream)
                      }
                    } finally {
                      uncompressedFile.delete()
                    }
                  }
                  case None =>
                    in.withStream(s =>
                      withInputStreamFn(new BufferedInputStream(s))
                    )
                }

              }
            }
        )
    }
  }

  private val threadTempDir: ThreadLocal[Option[Path]] = ThreadLocal()

  /** If the Artifact is a "container" (e.g., tar file, zip file, etc.) then
    * expand the contained artifacts and traverse into the container with a
    * function.
    *
    * @param artifact
    *   -- the artifact to potentially traverse into
    * @param function
    *   -- the function that does "a thing" with the expanded artifacts
    *
    * @return
    *   if the thing is a container, then `Some(T)` where T is the value
    *   returned from `function`
    */
  def withinArchiveStream[T](artifact: ArtifactWrapper)(
      function: Vector[ArtifactWrapper] => T
  ): Option[T] = {
    if (notArchive(artifact)) None
    else {
      // create temporary directory
      val tempDir: Path = artifact.tempDir match {
        case Some(p) =>
          Files.createTempDirectory(p.toPath(), "goatrodeo_temp_dir")
        case None => Files.createTempDirectory("goatrodeo_temp_dir")
      }

      try {
        threadTempDir.set(Some(tempDir))
        tryToConstructArchiveStream(artifact, tempDir) match {
          case None => None
          case Some(artifacts -> wrapperName) =>
            try {

              // call the function
              Some(function(artifacts))
            } catch {
              case e: Exception =>
                logger.error(
                  f"Failed to process ${artifact.path()} -- ${artifact.mimeType} wrapper ${wrapperName} exception ${e
                      .getMessage()}",
                  e
                )
                None
            }
        }
      } finally {
        threadTempDir.set(None)
        // clean up
        Helpers.deleteDirectory(tempDir)

      }
    }
  }

  /** Execute a function within a temporary directory context.
    *
    * If already within a withinArchiveStream call, reuses that temp directory.
    * Otherwise, creates a new temporary directory that is cleaned up after the
    * function completes.
    *
    * @param f
    *   the function to execute with the temp directory path
    * @tparam T
    *   the return type of the function
    * @return
    *   the result of the function
    */
  def withinTempDir[T](f: Path => T): T = {
    val (del, dir) = threadTempDir.get() match {
      case Some(p) => false -> p
      case _ =>
        true -> Files.createTempDirectory("goatrodeo_temp_dir")

    }

    try {
      f(dir)
    } finally {
      if (del) {
        Helpers.deleteDirectory(dir)
      }
    }
  }

  /** A stream of ArtifactWrappers... maybe
    */
  private type OptionalArchiveStream =
    Option[(Vector[ArtifactWrapper], String)]
}
