package goatrodeo.util

import java.io.File
import scala.util.{Failure, Success, Try}
import org.apache.commons.compress.compressors.{
  CompressorInputStream,
  CompressorStreamFactory
}

import java.io.InputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveEntry

import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.IOException
import com.palantir.isofilereader.isofilereader.IsoFileReader
import com.typesafe.scalalogging.Logger
import java.nio.file.Files
import java.nio.file.Path

enum FileAction {
  case SkipDive
  case End
}
object FileWalker {
  val logger = Logger(getClass())

  /** If the file size is greater than 16MB, then always make it a file, don't
    * keep the bytes in memory
    */
  val forceToFileSize = (16L * 1024L * 1024L)

  val forceToFileMimeTypes: Set[String] = Set(
    "application/x-rpm",
    "application/x-debian-package",
    "application/x-iso9660-image",
    "application/zlib",
    "type application/x-gtar",
    "application/zip",
    "application/java-archive",
    "application/gzip"
  )

  /** Should the entity be turned into a real file or will an in-memory set of
    * bytes suffice?
    *
    * @param wrapper
    *   the ArtifactWrapper to test
    * @return
    *   true if the file is large or it's on the "we care about these suffixes
    *   list"
    */
  def shouldForceToFile(wrapper: ArtifactWrapper): Boolean = {
    shouldForceToFile(wrapper.size(), wrapper.mimeType)
  }

  /** Should the entity be turned into a real file or will an in-memory set of
    * bytes suffice?
    *
    * @param size
    *   the size of the entity
    * @param mimeType
    *   Mime type of the entity
    * @return
    *   true if the file is large or it's on the "we care about these suffixes
    *   list"
    */
  def shouldForceToFile(size: Long, mimeType: String): Boolean = {

    size >= forceToFileSize || forceToFileMimeTypes.contains(mimeType)
  }

  private lazy val zipMimeTypes: Set[String] =
    Set(
      "application/zip",
      "application/java-archive"
    )

  private lazy val isoMimeTypes: Set[String] = Set(
    "application/x-iso9660-image"
  )

  /** Try to construct an `OptionalArchiveStream` from a Zip/WAR/etc. file
    *
    * @param in
    *   the file to try to construct the stream from
    * @return
    */
  private def asZipContainer(in: ArtifactWrapper): OptionalArchiveStream = {
    if (zipMimeTypes.contains(in.mimeType)) {
      import scala.collection.JavaConverters.asScalaIteratorConverter
      val (theFile, deleteIt) = in match {
        case FileWrapper(f, _) => f -> false
        case _ =>
          Helpers.tempFileFromStream(in.asStream(), true, in.path()) -> true
      }

      try {

        val zipFile = ZipFile(theFile)
        val it: Iterator[() => (String, InputStream, Boolean)] = zipFile
          .stream()
          .iterator()
          .asScala
          .filter(v => { !v.isDirectory() })
          .map(v =>
            () => {
              val name = v.getName()
              (name, zipFile.getInputStream(v), true)
            }
          )
        Some(
          it,
          "Zip Container",
          (() => {
            zipFile.close(); if (deleteIt) theFile.delete(); ()
          })
        )

      } catch {
        case e: Exception =>
          if (deleteIt) theFile.delete()

          logger.error(
            f"Failed for ${in.path()} mime type ${in.mimeType} error ${e.getMessage()}"
          )
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
  private def asISOWrapper(in: ArtifactWrapper): OptionalArchiveStream = {
    if (isoMimeTypes.contains(in.mimeType)) {
      try {
        import scala.collection.JavaConverters.asScalaIteratorConverter
        val (theFile, deleteIt) = in match {
          case FileWrapper(f, _) => f -> false
          case _ =>
            Helpers.tempFileFromStream(in.asStream(), true, in.path()) -> true
        }

        logger.trace(s"TheFile: $theFile")
        val isoFileReader: IsoFileReader = new IsoFileReader(theFile)
        logger.trace(s"isoFileReader: ${isoFileReader}")
        try {

          val files = isoFileReader.getAllFiles()

          val flatList =
            isoFileReader.convertTreeFilesToFlatList(files).iterator.asScala
          Some(
            for (cycleFile <- flatList if !cycleFile.isDirectory())
              yield { () =>
                {
                  val name = cycleFile.getFileName()

                  (name, isoFileReader.getFileStream(cycleFile), false)
                }
              },
            "ISO Reader",
            () => (isoFileReader.close())
          )

        } catch {
          case e: Exception =>
            logger.error(s"Try getAllFiles failed: ${e.getMessage}", e)
            isoFileReader.close()
            None
        } finally {
          // delete the temporary file
          if (deleteIt) theFile.delete()
        }
      } catch {
        case e: Exception =>
          logger.error(s"Whole ISO Fetch process failed… ${e.getMessage}", e)
          None
      }
    } else None
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
      in: () => InputStream,
      name: String,
      mime: String,
      synthetic: Boolean
  ): OptionalArchiveStream = {
    val factory = (new ArchiveStreamFactory())

    val fis = in()

    try {
      val input: ArchiveInputStream[ArchiveEntry] = factory
        .createArchiveInputStream(
          fis
        )
      val theIterator = Helpers
        .iteratorFor(input)
        .filter(!_.isDirectory())
        .map(ae =>
          () => {
            val name = ae.getName()

            (name, input, false)
          }
        )
      Some(theIterator, "Apache Common Wrapper", (() => input.close()))
    } catch {
      case e: Exception =>
        fis.close(); None
    }
  }

  val definitelyNotArchive = Set(
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
    "application/x-dtbresource+xml"
  )

  val notZip = Set(
    "application/x-rpm",
    "application/x-archive",
    "application/x-iso9660-image",
    "application/x-gtar",
    "application/x-debian-package",
    "application/gzip",
    "application/zstd"
  )

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
      in: ArtifactWrapper
  ): OptionalArchiveStream = {
    val mimeType = in.mimeType
    if (
      mimeType.startsWith("text/") || mimeType.startsWith(
        "image/"
      ) || definitelyNotArchive.contains(in.mimeType)
    ) None
    else {
      {
        val ret = asZipContainer(in)

        ret
      }.orElse(asISOWrapper(in).orElse {

        val (inputStreamBuilder, synthetic, uncompressedCleanupFunc)
            : (() => InputStream, Boolean, () => Unit) =
          Try {
            try {
              val factory = new CompressorStreamFactory()
              val fis = new BufferedInputStream(in.asStream())

              val input: CompressorInputStream =
                factory.createCompressorInputStream(fis)

              val theFile = Helpers.tempFileFromStream(input, true, "Temp")
              (
                () => new BufferedInputStream(new FileInputStream(theFile)),
                true,
                () => { theFile.delete(); () }
              )

            } catch {
              case e: Exception =>
                if (
                  in.path().endsWith(".tgz") || in.path().endsWith(".apk") || in
                    .path()
                    .endsWith(".xz")
                ) {
                  logger.error(
                    f"Expected to open apk/tgz/xz ${in
                        .path()} -- ${in.mimeType} but got ${e.getMessage()}"
                  )
                }
                throw e
            }
          }.toOption
            .getOrElse((() => in.asStream(), false, () => ()))
        asApacheCommonsArchiveWrapper(
          inputStreamBuilder,
          in.path(),
          in.mimeType,
          synthetic
        ) match {
          case None => uncompressedCleanupFunc(); None
          case Some(iterator, name, cleanup) =>
            Some(iterator, name, () => { uncompressedCleanupFunc(); cleanup() })
        }
      })
    }
  }

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
    tryToConstructArchiveStream(artifact) match {
      case None                                 => None
      case Some(stream, wrapperName, closeFunc) =>
        // create temporary directory
        val tempDir: Path = Files.createTempDirectory("goatrodeo_temp_dir")

        try {
          // for each stream, create a file in the temp dir and an associated FileWrapper

          val artifacts: Vector[ArtifactWrapper] = (for {
            artifactFunc <- stream
            (name, inputStream, closeIt) <- Try {
              artifactFunc()
            }.toOption.toVector
          } yield {
            val tempFile = Files
              .createTempFile(tempDir, "intermediate_goat", ".tmp")
              .toFile()
            Helpers.streamToFile(inputStream, closeIt, tempFile)
            FileWrapper(tempFile, name)
          }).toVector

          // call the function
          Some(function(artifacts))
        } catch {
          case e: Exception =>
            logger.error(
              f"Failed to process ${artifact.path()} -- ${artifact.mimeType} wrapper ${wrapperName} exception ${e.getMessage()}"
            )
            None
        } finally {
          // clean up
          Helpers.deleteDirectory(tempDir)

          // close the archive stream
          closeFunc()
        }
    }
  }

  /** A stream of ArtifactWrappers... maybe
    */
  private type OptionalArchiveStream =
    Option[(Iterator[() => (String, InputStream, Boolean)], String, () => Unit)]

}
