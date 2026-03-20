package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties

import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path

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
  *   - Other: ISO9660, DEB (Debian packages)
  */
object FileWalker {
  val logger: Logger = Logger(getClass())

  /** Get the names of the files in an archive if the Apache commons tools can
    * open the archive
    *
    * @param inputStream
    *   the input stream to the archive
    * @return
    *   if it's an openable archive, the names of the files inside it and true
    *   if the name is a directory
    */
  def getContentNamesFromArchive(
      inputStream: BufferedInputStream
  ): Option[Vector[(String, Boolean, Option[String])]] = {
    Some({
      val factory = (new ArchiveStreamFactory())
      val input: ArchiveInputStream[ArchiveEntry] = factory
        .createArchiveInputStream(
          inputStream
        )

      Helpers
        .iteratorFor(input)
        .map(ae => {
          val name = ae.getName()
          val dir = ae.isDirectory()

          (
            name,
            dir,
            if (dir) None
            else {
              val metadata = new Metadata()
              metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name)
              val bi =
                TikaInputStream.get(Helpers.slurpInputNoClose(input), metadata)

              Some(ArtifactWrapper.mimeTypeFor(bi, name))
            }
          )
        })
        .toVector
    })
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
  def notArchive(path: String, mimeType: String): Boolean = {
    mimeType.startsWith("text/") ||
    mimeType.startsWith("image/") ||
    definitelyNotArchive.contains(mimeType) ||
    (mimeType == "application/zip" && path.endsWith(".xpi"))
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
  // Steve says: path is not being used right now.
  // If this changes, look at how this is being used because other
  // code depends on it working like it is.
  def notCompressed(path: String, mimeType: String): Boolean = {
    mimeType.startsWith("text/") ||
    mimeType.startsWith("image/") ||
    notCompressedSet.contains(mimeType)
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
    Containers.asContainer(in, tempDir)
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
  type OptionalArchiveStream =
    Option[(Vector[ArtifactWrapper], String)]
}
