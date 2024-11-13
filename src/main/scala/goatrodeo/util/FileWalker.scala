package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger

import java.io.File
import scala.util.Try
import org.apache.commons.compress.compressors.CompressorStreamFactory

import java.io.InputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveEntry

import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.IOException

enum FileAction {
  case SkipDive
  case End
}
object FileWalker {
  private val logger = Logger("FileWalker")

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
      val fis = in
      try {
        new CompressorStreamFactory()
          .createCompressorInputStream(
            fis
          )
      } catch {
        case e: Exception => {
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
  ): Option[(Iterator[() => (String, ArtifactWrapper)], () => Unit)] = {
    in match {
      case wrapper @ ISOFileWrapper(f, isoReader, deleteOnFinalize) =>
        logger.info(s"Found an ISOFileWrapper($f, $isoReader, $deleteOnFinalize)")
        streamForISOFileWrapper(wrapper)
      case wrapper @ InternalISOFileWrapper(f, isoFileReader) =>
        logger.info(s"Found an InternalISOFileWrapper($f, $isoFileReader)")
        streamForInternalISOFileWrapper(wrapper)
      // todo - a specific type for Compressed artifacts, and possibly a distinct one for Zipped ones
      case FileWrapper(_, _) | ByteWrapper(_, _) =>
        in.fileExtension() match {
          case Some(".zip") |
               Some(".jar") |
               Some(".aar") |
               Some(".war") =>
            logger.info(s"Found a Zipped Archive '${in.name()}'…")
            streamForZippedArchive(in) match {
              case Some(toReturn) =>
                logger.info(s"Got a result from Zipped Archive process: $toReturn")
                Some(toReturn)
              case None =>
                logger.info(s"Got nothing from streamForZippedArchive, falling through to generic (non-zip) Compressed Archive")
                streamForCompressedArchive(in)
            }
          case default =>
            logger.info(s"Got extension '$default'; defaulting to treating it as a generic (non-zip) Compressed Archive…")
            streamForCompressedArchive(in)
        }
    }
  }

  /**
   * Handler for an 'outer' `ISOFileWrapper` (that is to say, the top level actual .iso file, who holds all the `InternalISOFileWrappers`…
   * @param in A `ISOFileWrapper` representing an actual .iso file; it is represented as a stream of `InternalISOFileWrapper`s
   * @return a tuple of Iterator of `InternalISOFileWrapper`s and a destructor method
   */
  private def streamForISOFileWrapper(in: ISOFileWrapper): Option[(Iterator[() => (String, InternalISOFileWrapper)], () => Unit)] = {
    import scala.collection.JavaConverters.asScalaIteratorConverter
    logger.info(s"Streaming an ISOFileWrapper ${in.name()} into an Iterator of InternalISOFileWrappers")
    val files = in.listFiles()
    if (!files.isEmpty) {
      val it = files
        .map(f =>
          logger.info(s"[ISOFileWrapper] Internal ISO File Entry: ${f.name()} -> $f")
          f.name() -> f
        )
        .iterator
        .map(v => () => v)

      val destruct = () => {
        in.isoReader.close()
      }
      Option(it -> destruct)

    } else None // if it's not a directory, we want to stop here…
  }

  /**
   * Handler for an 'inner' `InternalISOFileWrapper` (that is to say, a file or directory inside of a 'iso' file…)
   *
   * This only returns a Some() if the file is a directory, else None
 *
   * @param in Another `InternalISOFileWrapper` representing an actual file inside the '.iso'; it is represented as a stream of `InternalISOFileWrapper`s
   * @return a tuple of Iterator of `InternalISOFileWrapper`s and a destructor method
   */
  private def streamForInternalISOFileWrapper(in: InternalISOFileWrapper): Option[(Iterator[() => (String, InternalISOFileWrapper)], () => Unit)] = {
    import scala.collection.JavaConverters.asScalaIteratorConverter
    logger.info(s"Streaming an InternalISOFileWrapper '${in.name()}' into an Iterator")
    val files = in.listFiles()
    if (!files.isEmpty) {
      val it = files
        .map(f =>
          logger.info(s"[ISOInternalFileWrapper] Internal ISO File Entry: ${f.name()} -> $f")
          f.name() -> f
        )
        .iterator
        .map(v => () => v)

      val destruct = () => {} // noop, as this is an internal file and can't be closed or cleaned up or anything
      Option(it -> destruct)
    } else None // if it's not a directory, we want to stop here…
  }

  /**
   * 'Generic' (not a zip file) Compressed Archives which can be processed by Apache Commons `ArchiveStreamFactory`
   * @param in An `ArtifactWrapper`
   * @return A tuple of an Iterator of `ArtifactWrappers` & a destructor method
   */
  private def streamForCompressedArchive(in: ArtifactWrapper): Option[(Iterator[() => (String, ArtifactWrapper)], () => Unit)] = {
      val factory = new ArchiveStreamFactory()
      val ret = Try {
        {
          val fis = in.asStream()

          try {
            val input: ArchiveInputStream[ArchiveEntry] = factory
              .createArchiveInputStream(
                fis
              )
            val theIterator = Helpers
              .archiveIteratorFor(input)
              .filter(!_.isDirectory())
              .map(ae =>
                () => {
                  val name = ae.getName()

                  val wrapper =
                    if (
                      ae.getSize() > (1024L * 1024L * 1024L) || name
                        .endsWith(".zip") || name.endsWith(".jar") || name
                        .endsWith(".aar") || name.endsWith(".war")
                    ) {
                      FileWrapper(
                        Helpers
                          .tempFileFromStream(input, false, name),
                        true
                      )
                    } else {
                      ByteWrapper(Helpers.slurpInputNoClose(input), name)
                    }

                  (name, wrapper)
                }
              )
            Some(theIterator -> (() => input.close()))
          } catch {
            case e: Throwable => fis.close(); None
          }
        }
      }.toOption.flatten

      ret match {
        case Some(archive) => Some(archive)
        case None =>
          for {
            uncompressedFile <- fileForCompressed(in.asStream())
            ret <- Try {

              val fis = FileInputStream(uncompressedFile)
              try {
                val input: ArchiveInputStream[ArchiveEntry] = factory
                  .createArchiveInputStream(
                    BufferedInputStream(fis)
                  )
                val theIterator = Helpers
                  .archiveIteratorFor(input)
                  .filter(!_.isDirectory())
                  .map(ae =>
                    () => {
                      val name = ae.getName()

                      val wrapper =
                        if (
                          ae.getSize() > (1024L * 1024L * 1024L) || name
                            .endsWith(".zip") || name.endsWith(".jar") || name
                            .endsWith(".war") || name.endsWith(".aar")
                        ) {
                          FileWrapper(
                            Helpers
                              .tempFileFromStream(input, false, name),
                            true
                          )
                        } else {
                          ByteWrapper(Helpers.slurpInputNoClose(input), name)
                        }

                      (name, wrapper)
                    }
                  )
                theIterator -> (() => {
                  input.close();
                  uncompressedFile.delete();
                  ()
                })
              } catch {
                case e: Exception => fis.close(); throw e
              }
            }.toOption
          } yield ret

      } //
    }

  /**
   * Zip file Archives processor
   * @param in An `ArtifactWrapper` representing a zip file
   * @return A tuple of an Iterator of `ArtifactWrappers` & a destructor method
   */
  private def streamForZippedArchive(in: ArtifactWrapper): Option[(Iterator[() => (String, ArtifactWrapper)], () => Unit)] = {
    try {
      import scala.collection.JavaConverters.asScalaIteratorConverter
      val theFile = in match {
        case FileWrapper(f, _) =>
          logger.info(s"FileWrapper($f, _)")
          f
        case _ =>
          val x = Helpers.tempFileFromStream(in.asStream(), true, in.name())
          logger.info(s"TempFileFromStream: $x")
          x
      }
      val zipFile = ZipFile(theFile)
      val it: Iterator[() => (String, ArtifactWrapper)] = zipFile
        .stream()
        .iterator()
        .asScala
        .filter(v => {
          !v.isDirectory()
        })
        .map(v =>
          () => {
            val name = v.getName()

            val wrapper =
              if (
                v.getSize() > (512L * 1024L * 1024L) ||
                  name.endsWith(".zip") || name.endsWith(".jar") || name
                  .endsWith(".aar") || name
                  .endsWith(".war")
              ) {
                FileWrapper(
                  Helpers
                    .tempFileFromStream(
                      zipFile.getInputStream(v),
                      false,
                      name
                    ),
                  true
                )
              } else {
                ByteWrapper(
                  Helpers.slurpInput(zipFile.getInputStream(v)),
                  name
                )
              }

            (
              name,
              wrapper
            )
          }
        )
      Some(it -> (() => {
        zipFile.close();
        ()
      }))
    } catch {
      case e: Exception =>
        logger.error(s"Got an error processing Zip File: ${e}")
        None // fall through
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
      name: String,
      parentId: Option[String],
      specific: T,
      dontSkipFound: Boolean,
      action: (
          ArtifactWrapper,
          String,
          Option[String],
          T
      ) => (String, Boolean, Option[FileAction], T)
  ): Unit = {
    val toProcess: Vector[ArtifactWrapper] = if (root.isFile()) { Vector(root) }
    else if (root.isDirectory()) { root.listFiles() }
    else Vector.empty
    var keepOn = true

    for { workOn <- toProcess if keepOn } {
      if (workOn.size() > 4) {
        val (ret, found, fileAction, newSpecific) = action(
          workOn,
          if (workOn == root) name
          else
            workOn
              .getCanonicalPath()
              .substring(root.getParentDirectoryPath().length()),
          parentId,
          specific
        )

        fileAction match {
          case Some(FileAction.End) => keepOn = false
          case _                    =>
        }

        // don't go into archives we've already seen
        if (
          keepOn && fileAction != Some(
            FileAction.SkipDive
          ) && (dontSkipFound || !found)
        ) {
          for {
            (stream, toDelete) <- streamForArchive(workOn)
          } {
            try {
              for {
                theFn <- stream
              } {
                val (name, file) = theFn()

                processFileAndSubfiles(
                  file,
                  name,
                  Some(ret),
                  newSpecific,
                  dontSkipFound,
                  action
                )
                file.delete()
              }

            } catch {
              case ioe: IOException if parentId.isDefined =>
              // println(
              //   f"Swallowed bad substream ${name} with exception ${ioe.getMessage()}"
              // )
            }

            // stream.close()
            toDelete()
          }
        }
      }
    }
  }
}
