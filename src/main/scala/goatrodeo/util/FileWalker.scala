package io.spicelabs.goatrodeo.util

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
import com.palantir.isofilereader.isofilereader.IsoFileReader

enum FileAction {
  case SkipDive
  case End
}
object FileWalker {

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

  /** If any of these file extensions are encountered, they must be turned into
    * a real file
    */
  lazy val forceToFileSuffix: Set[Option[String]] = zipSuffixs ++ isoSuffixs

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
    shouldForceToFile(wrapper.size(), wrapper.name())
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
    size >= forceToFileSize || forceToFileSuffix.contains(
      ArtifactWrapper.suffix(fileName)
    )
  }

  /** Suffixes for Zip/JAR/etc. files
    */
  private lazy val zipSuffixs: Set[Option[String]] =
    Set(Some(".zip"), Some(".jar"), Some(".aar"), Some(".war"))

  /** Suffixes for ISO files
    */
  private lazy val isoSuffixs: Set[Option[String]] = Set(Some(".iso"))

  /** Try to construct an `OptionalArchiveStream` from a Zip/WAR/etc. file
    *
    * @param in
    *   the file to try to construct the stream from
    * @return
    */
  private def asZipContainer(in: ArtifactWrapper): OptionalArchiveStream = {
    if (zipSuffixs.contains(in.suffix)) {
      try {
        import scala.collection.JavaConverters.asScalaIteratorConverter
        val theFile = in match {
          case FileWrapper(f, _) => f
          case _ => Helpers.tempFileFromStream(in.asStream(), true, in.name())
        }
        val zipFile = ZipFile(theFile)
        val it: Iterator[() => (String, ArtifactWrapper)] = zipFile
          .stream()
          .iterator()
          .asScala
          .filter(v => { !v.isDirectory() })
          .map(v =>
            () => {
              val name = v.getName()

              val wrapper = buildWrapper(name, v.getSize(), () => zipFile.getInputStream(v))
              (name, wrapper)
            }
          )
        Some(it -> (() => { zipFile.close(); () }))
      } catch {
        case e: Exception => None
      }
    } else None
  }

  /**
    * Build an appropriate wrapper
    *
    * @param name the name of the stream
    * @param size the size of the stream
    * @param asStream a function that builds the stream
    * @return the appropriate ArtifactWrapper
    */
  private def buildWrapper(
      name: String,
      size: Long,
      asStream: () => InputStream
  ): ArtifactWrapper = {
    if (shouldForceToFile(size, name)) {
      FileWrapper(
        Helpers
          .tempFileFromStream(
            asStream(),
            false,
            name
          ),
        true
      )
    } else
      ByteWrapper(
        Helpers.slurpInputNoClose(asStream()),
        name
      )
  }

  /** Try to open the thing as an ISO file wrapper
    *
    * @param in
    * @return
    */
  private def asISOWrapper(in: ArtifactWrapper): OptionalArchiveStream = {
    try {
      import scala.collection.JavaConverters.asScalaIteratorConverter
      val theFile = in match {
        case FileWrapper(f, _) => f
        case _ => Helpers.tempFileFromStream(in.asStream(), true, in.name())
      }
      val isoFileReader: IsoFileReader = new IsoFileReader(theFile)

      try {

        val files = isoFileReader.getAllFiles()
        
        val flatList =
          isoFileReader.convertTreeFilesToFlatList(files).iterator.asScala
        Some(
          for (cycleFile <- flatList if !cycleFile.isDirectory()) yield { () =>
            {
              val name = cycleFile.getFileName()

              val ret = buildWrapper(name, cycleFile.getSize(), () => isoFileReader.getFileStream(cycleFile))

              name -> ret
            }
          },
          () => (isoFileReader.close())
        )

      } catch {
        case _: Exception =>
          isoFileReader.close()
          None
      }
    } catch {
      case _: Exception => None
    }
  }

  /** Try to construct an `OptionalArchiveStream` using the Apache Commons "we
    * read most archive files" format
    *
    * @param in
    *   the file to try to construct the stream from
    * @return
    */
  private def asApacheCommonsWrapper(
      in: ArtifactWrapper
  ): OptionalArchiveStream = {
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

                val wrapper = buildWrapper(name, ae.getSize(), () => input)
                
                (name, wrapper)
              }
            )
          Some(theIterator -> (() => input.close()))
        } catch {
          case e: Throwable => fis.close(); None
        }
      }
    }.toOption.flatten
  }

  /** Try a series of strategies (except for uncompressing a file) for creating
    * an archive stream
    *
    * @param in
    * @return
    */
  def tryToConstructArchiveStream(
      in: ArtifactWrapper
  ): OptionalArchiveStream = {
    asZipContainer(in) orElse
    /* asISOWrapper(in) orElse FIXME -- figure out why this is causing the tests to not finish*/ 
     asApacheCommonsWrapper(in)
  }

  /** A stream of ArtifactWrappers... maybe
    */
  type OptionalArchiveStream =
    Option[(Iterator[() => (String, ArtifactWrapper)], () => Unit)]

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

    val ret = tryToConstructArchiveStream(in)

    // if we've got it, yay.
    ret match {
      case Some(archive) => Some(archive)

      // if not, then try to treat the file as a compressed file, decompress, and try again
      case None => {
        for {
          uncompressedFile <- fileForCompressed(in.asStream())
          uncompressedArchive = FileWrapper(uncompressedFile, true)
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
    val toProcess: Iterator[ArtifactWrapper] = if (root.isFile()) {
      Vector(root).toIterator
    } else if (root.isDirectory()) { root.listFiles() }
    else Iterator.empty
    var keepOn = true

    for { workOn <- toProcess if keepOn } {
      if (workOn.size() > 4) {
        val (ret, found, fileAction, newSpecific) = action(
          workOn,
          if (workOn == root) name
          else
            workOn
              .getCanonicalPath()
              .substring(root.getParentDirectory().getCanonicalPath().length()),
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
