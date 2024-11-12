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
      case ISOFileWrapper(f, isoReader, deleteOnFinalize) =>
        logger.info(s"Found an ISOFileWrapper(${f}, ${isoReader}, ${deleteOnFinalize})")
        None
      case InternalISOFileWrapper(f, isoFileReader) => ???
        logger.info(s"Found an InternalISOFileWrapper(${f}, ${isoFileReader})")
        None
      // todo - a specific type for Compressed artifacts, and possibly a distinct one for Zipped ones
      case _ =>
        streamForZippedArchive(in) match {
          case Some(toReturn) => return Some(toReturn)
          case None => // fall through
        }
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
                .iteratorFor(input)
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
                        FileWrapper.fromFile(
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
          case None => {
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
                    .iteratorFor(input)
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
                            FileWrapper.fromFile(
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
          }

        } //
    }
  }


  private def streamForZippedArchive(in: ArtifactWrapper): Option[(Iterator[() => (GitOID, ArtifactWrapper)], () => Unit)] = {
    if (
      in.name().endsWith(".zip") ||
      in.name().endsWith(".jar") ||
      in.name().endsWith(".aar") ||
      in.name().endsWith(".war")
    ) {
      try {
        import scala.collection.JavaConverters.asScalaIteratorConverter
        val theFile = in match {
          case FileWrapper(f, _) => f
          case _ => Helpers.tempFileFromStream(in.asStream(), true, in.name())
        }
        val zipFile = ZipFile(theFile)
        val it: Iterator[() => (GitOID, ArtifactWrapper)] = zipFile
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
                  FileWrapper.fromFile(
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
        return Some((it -> (() => {
          zipFile.close();
          ()
        })))
      } catch {
        case e: Exception => {} // fall through
      }
    }
    None
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
