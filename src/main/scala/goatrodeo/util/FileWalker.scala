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

enum FileAction {
  case SkipDive
  case End
}
object FileWalker {
  val logger = Logger("FileWalker")

  /** Look at a File. If it's compressed, read the full stream into a new file
    * and return that file
    *
    * @param in
    *   the file to inspect
    * @return
    *   a decompressed file
    */
  def fileForCompressed(in: InputStream): Option[File] = {
    val ret = Try {
      logger.trace(s"fileForCompressed($in)")
      val fis = in
      try {
        new CompressorStreamFactory()
          .createCompressorInputStream(
            fis
          )
      } catch {
        case e: Exception => {
          logger.trace(s"fileForCompressed on $in failed: ${e.getMessage}")
          fis.close()
          throw e
        }
      }
    }

    ret.toOption match {
      case Some(stream) =>
        Some(Helpers.tempFileFromStream(stream, true, ".goats"))
      case None => None
    }
  }

  /** If any of these file extensions are encountered, they must be turned into
    * a real file
    */
  //  lazy val forceToFileSuffix: Set[Option[String]] = zipSuffixes ++ isoSuffixes

  /** If the file size is greater than 16MB, then always make it a file, don't
    * keep the bytes in memory
    */
  val forceToFileSize = (16L * 1024L * 1024L)

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
    shouldForceToFile(wrapper.size(), wrapper.getPath())
  }

  /** Should the entity be turned into a real file or will an in-memory set of
    * bytes suffice?
    *
    * @param size
    *   the size of the entity
    * @param fileName
    *   the name of the entity
    * @return
    *   true if the file is large or it's on the "we care about these suffixes
    *   list"
    */
  def shouldForceToFile(size: Long, fileName: String): Boolean = {
    size >= forceToFileSize
  }

  // /** Suffixes for Zip/JAR/etc. files
  //   */
  // private lazy val zipSuffixes: Set[Option[String]] =
  //   Set(Some(".zip"), Some(".jar"), Some(".aar"), Some(".war"))

  // /** Suffixes for ISO files
  //   */
  // private lazy val isoSuffixes: Set[Option[String]] = Set(Some(".iso"))

  // /** Suffixes for Ruby GEM Files
  //   */
  // private lazy val gemSuffixes: Set[Option[String]] = Set(Some(".gem"))

  private lazy val zipMimeTypes = Set(
    "application/zip",
    "application/java-archive",
    "application/x-tika-java-web-archive",
    "application/x-tika-java-enterprise-archive"
  )

  private lazy val isoMimeTypes = Set("application/x-iso9660-image")

  /** Try to construct an `OptionalArchiveStream` from a Zip/WAR/etc. file
    *
    * @param in
    *   the file to try to construct the stream from
    * @return
    */
  private def asZipContainer(in: ArtifactWrapper): OptionalArchiveStream = {
    if (zipMimeTypes.contains(in.mimeType)) {
      try {
        import scala.collection.JavaConverters.asScalaIteratorConverter
        val theFile = in match {
          case FileWrapper(f, _, _) => f
          case _ =>
            Helpers.tempFileFromStream(in.asStream(), true, in.getPath())
        }
        val zipFile = ZipFile(theFile)
        val it: Iterator[() => ArtifactWrapper] = zipFile
          .stream()
          .iterator()
          .asScala
          .filter(v => { !v.isDirectory() })
          .map(v =>
            () => {
              val name = v.getName()

              val wrapper =
                buildWrapperNoClose(
                  name,
                  v.getSize(),
                  () => zipFile.getInputStream(v)
                )
              wrapper
            }
          )
        Some(ArchiveStream(it, () => { zipFile.close(); }))
      } catch {
        case e: Exception =>
          logger.error(f"Exception dealing with zip/jar ${e}")
          None
      }
    } else None
  }

  /** Build an appropriate wrapper
    *
    * @param path
    *   the "directory path" to the item
    * @param size
    *   the size of the stream
    * @param asStream
    *   a function that builds the stream
    * @return
    *   the appropriate ArtifactWrapper
    */
  private def buildWrapperNoClose(
      path: String,
      size: Long,
      asStream: () => InputStream
  ): ArtifactWrapper = {
    if (shouldForceToFile(size, path)) {
      val file = Helpers
        .tempFileFromStream(
          asStream(),
          false,
          path
        )
      FileWrapper(
        file,
        ArtifactWrapper.mimeType(file),
        true
      )
    } else
      val bytes = Helpers.slurpInputNoClose(asStream())
      ByteWrapper(
        bytes,
        ArtifactWrapper.mimeType(bytes, path),
        path
      )
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
        val theFile = in match {
          case FileWrapper(f, _, _) => f
          case _ =>
            Helpers.tempFileFromStream(in.asStream(), true, in.getPath())
        }

        val isoFileReader: IsoFileReader = new IsoFileReader(theFile)
        try {
          import scala.collection.JavaConverters.collectionAsScalaIterableConverter
          val files = isoFileReader.getAllFiles()

          val flatList =
            isoFileReader.convertTreeFilesToFlatList(files).asScala.iterator
          Some(
            ArchiveStream(
              for (cycleFile <- flatList if !cycleFile.isDirectory())
                yield { () =>
                  {
                    val name = cycleFile.getFileName()

                    val ret = buildWrapperNoClose(
                      name,
                      cycleFile.getSize(),
                      () => isoFileReader.getFileStream(cycleFile)
                    )

                    ret
                  }
                },
              () => (isoFileReader.close())
            )
          )

        } catch {
          case e: Exception =>
            logger.error(s"Try getAllFiles failed: ${e.getMessage}", e)
            isoFileReader.close()
            None
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
      in: ArtifactWrapper
  ): OptionalArchiveStream = {
    logger.trace(s"asApacheCommonsArchiveWrapper($in)")
    val factory = (new ArchiveStreamFactory())
    Try {
      {
        val fis = in.asStream()

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
                val wrapper =
                  buildWrapperNoClose(name, ae.getSize(), () => input)

                wrapper
              }
            )
          Some(ArchiveStream(theIterator, (() => input.close())))
        } catch {
          case e: Throwable => fis.close(); None
        }
      }
    }.toOption match {
      case Some(Some(x)) => Some(x)
      case _             => None
    }
  }

  /** Try to construct an `OptionalArchiveStream` using the Apache Commons
    * "Compressed file" support;
    *
    * Note this only reads *simple compressed files* – that is to say, something
    * like a `.gz` around a single file It will *not* work on archive files such
    * as `.tar.gz`, which needs the `ArchiveStreamFactory` rather than the
    * `ArchiveStreamFactory`
    *
    * @param in
    *   the file to try to construct the stream from
    * @return
    */
  private def asApacheCommonsCompressedWrapper(
      in: ArtifactWrapper
  ): OptionalArchiveStream = {
    val factory = new CompressorStreamFactory()
    Try {
      val fis = in.asStream()

      try {
        val input: CompressorInputStream =
          factory.createCompressorInputStream(fis)

        val theIterator: Iterator[() => ArtifactWrapper] =
          new Iterator[() => ArtifactWrapper] {
            private var x =
              0 // count accesses for hasNext // todo should this be an atomic?

            override def hasNext: Boolean = {
              x == 0
            }

            override def next(): () => ArtifactWrapper = {
              x += 1
              val name = in.getPath()
              val wrapper = buildWrapperNoClose(name, in.size(), () => input)

              val result = () => wrapper
              logger.trace(s"Result for CompressedWrapper: ${result()}")
              result
              // else throw IllegalArgumentException(s"Unknown compressed file extension on ${in.name()}") // todo - non-exception control
            }

          }

        Some(ArchiveStream(theIterator, (() => input.close())))
      } catch {
        case e: Throwable => fis.close(); None
      }
    } match {
      case Success(Some(iter)) =>
        Some(iter)
      case Success(None) =>
        logger.trace("Success(None) ??")
        None
      case Failure(e) =>
        logger.trace(
          s"Failed to get an Apache Commons Compressed Wrapper: ${e.getMessage}"
        )
        None
    }
  }

  /** Process Ruby Gem dependency files. These archives are suffixed `.gem`, but
    * are tarballs with 3 files:
    *   - metadata.gz - a gzipped file containing the metadata (dependency info,
    *     etc) and Gem Spec
    *   - checksums.yaml.gz - a gzipped YAML file with the checksums for the
    *     archive
    *   - data.tar.gz - a tarball containing the actual ruby dependency code
    *
    * This is a separate method from `asApacheCommonsArchiveWrapper`, to allow
    * us to do anything extra with checksums or metadata or such
    * @param in
    *   the file to try to construct the stream from
    * @return
    *   OptionalArchiveStream
    */
  // private def asGemWrapper(in: ArtifactWrapper): OptionalArchiveStream = {
  //   logger.trace(s"asGemWrapper($in)")
  //   val factory = (new ArchiveStreamFactory())
  //   Try {

  //     {
  //       val fis = in.asStream()

  //       try {
  //         val input: ArchiveInputStream[ArchiveEntry] = factory
  //           .createArchiveInputStream(
  //             fis
  //           )
  //         val theIterator = Helpers
  //           .iteratorFor(input)
  //           .filter(!_.isDirectory())
  //           .map(ae =>
  //             () => {
  //               val name = ae.getName()

  //               val wrapper = buildWrapper(name, ae.getSize(), () => input)

  //               (name, wrapper)
  //             }
  //           )
  //         Some(theIterator -> (() => input.close()))
  //       } catch {
  //         case e: Throwable => fis.close(); None
  //       }
  //     }
  //   }.toOption.flatten
  // }

  /** Try a series of strategies (except for uncompressing a file) for creating
    * an archive stream
    *
    * @param in
    * @return
    */
  private def tryToConstructArchiveStream(
      in: ArtifactWrapper
  ): OptionalArchiveStream = {
    asZipContainer(in) orElse
      asISOWrapper(in) orElse
      asApacheCommonsArchiveWrapper(in) orElse
      asApacheCommonsCompressedWrapper(in)
  }

  // type ArchiveStream = (Seq[() => (String, ArtifactWrapper)], () => Unit)
  case class ArchiveStream(
      artifacts: Iterator[() => ArtifactWrapper],
      whenDone: () => Unit
  ) {

    /** Prepend an artifactwrapper to the artifacts
      *
      * @param rootArtifact
      * @return
      */
    def prepend(rootArtifact: ArtifactWrapper): ArchiveStream = {
      ArchiveStream(
        PrefixedIterator(() => rootArtifact, this.artifacts),
        this.whenDone
      )
    }
  }

  object ArchiveStream {

    /** Create an ArchiveStream from a single artifact Wrapper
      *
      * @param artifact
      *   the artifact wrapper
      * @return
      */
    def from(artifact: ArtifactWrapper): ArchiveStream = {
      ArchiveStream(List(() => artifact).iterator, () => ())
    }
  }

  /** A stream of ArtifactWrappers... maybe
    */
  type OptionalArchiveStream =
    Option[ArchiveStream]

  /** Given a file that might be an archive (Zip, cpio, tar, etc.) or might be a
    * compressed archive (e.g. tar.Z), return a stream of `ArchiveEntry` so the
    * archive can be walked.
    *
    * @param in
    *   the file to test
    * @return
    *   an `ArchiveStream` if the file is an archive
    */
  def streamForArchive(
      in: ArtifactWrapper
  ): OptionalArchiveStream = {

    logger.trace(s"streamForArchive($in)")

    val ret = tryToConstructArchiveStream(in)

    logger.trace(s"ret for $in: $ret")

    // if we've got it, yay.
    ret match {
      case Some(archive) =>
        logger.trace(s"for $in got archive stream $archive")
        Some(archive)

      // if not, then try to treat the file as a compressed file, decompress, and try again
      case None => {
        logger.trace(s"not able to get archive stream from $in")
        for {
          uncompressedFile <- fileForCompressed(in.asStream())
          _ = logger.trace(s"for $in unCompressed file: ${uncompressedFile}")
          uncompressedArchive = FileWrapper(
            uncompressedFile,
            ArtifactWrapper.mimeType(uncompressedFile),
            true
          )
          ret <- tryToConstructArchiveStream(uncompressedArchive)
        } yield ret
      }

    }
  }

  /** Process a file and subfiles (if the file is an archive)
    *
    * @param root
    *   the file to process
    * @param name
    *   the name of the file (not the name on disk, but the name based on where
    *   it came from, e.g. an archive)
    * @param parentId
    *   the optioned parent GitOID of the file if the file is contained within
    *   something else
    * @param action
    *   the action to take with the file and any of the subfiles (if this is an
    *   archive)
    */
  def processFileAndSubfiles[T](
      root: ArtifactWrapper,
      parentId: Option[String],
      specific: T,
      dontSkipFound: Boolean,
      action: (
          ArtifactWrapper,
          Option[String],
          T
      ) => (String, Boolean, Option[FileAction], T)
  ): Unit = {
    try {
      if (root.size() > 4) {
        val (ret, found, fileAction, newSpecific) = action(
          root,
          parentId,
          specific
        )

        var keepOn = true

        root.children() match {
          case Some(ArchiveStream(artifacts, whenDone)) => {
            try {
              for { artifactFn <- artifacts if keepOn } {
                val artifact = artifactFn()
                processFileAndSubfiles(
                  artifact,
                  Some(ret),
                  newSpecific,
                  dontSkipFound,
                  action
                )
              }
            } finally {
              // clean up
              whenDone()
            }
          }
          case None => {}
        }
      }
    } finally {
      root.cleanUp()
    }
  }
}
