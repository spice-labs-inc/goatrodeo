package goatrodeo.omnibor

import java.io.File
import scala.util.Try
import org.apache.commons.compress.compressors.CompressorStreamFactory
import goatrodeo.util.Helpers
import java.io.BufferedInputStream
import java.io.FileInputStream
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import goatrodeo.util.PackageIdentifier
import goatrodeo.util.GitOID
import goatrodeo.util.GitOIDUtils
import goatrodeo.util.FileType

/** Tools for opening files including containing files and building graphs
  */
object BuildGraph {

  /** Look at a File. If it's compressed, read the full stream into a new file
    * and return that file
    *
    * @param in
    *   the file to inspect
    * @return
    *   a decompressed file
    */
  def fileForCompressed(in: File): File = {
    val ret = Try {
      val input = new CompressorStreamFactory()
        .createCompressorInputStream(
          BufferedInputStream(FileInputStream(in))
        )
      input
    }

    ret.toOption match {
      case Some(stream) => Helpers.tempFileFromStream(stream)
      case None         => in
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
  def streamForArchive(in: File): Option[ArchiveInputStream[ArchiveEntry]] = {
    val uncompressedFile = fileForCompressed(in)

    val ret = Try {
      val factory = (new ArchiveStreamFactory())

      val input: ArchiveInputStream[ArchiveEntry] = factory
        .createArchiveInputStream(
          BufferedInputStream(FileInputStream(uncompressedFile))
        )
      input
    }.toOption

    ret
  }

  /** Create a Scala `Iterator` for the `ArchiveInputStream`
    *
    * @param archive
    *   the ArchiveInputStream to iterate over
    * @return
    *   an iterator
    */
  private def iteratorFor(
      archive: ArchiveInputStream[ArchiveEntry]
  ): Iterator[ArchiveEntry] = {
    new Iterator[ArchiveEntry] {
      var last: ArchiveEntry = null
      override def hasNext: Boolean = {
        last = archive.getNextEntry()
        last != null
      }

      override def next(): ArchiveEntry = last

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
  def processFileAndSubfiles(
      root: File,
      name: String,
      parentId: Option[String],
      action: (File, String, Option[String]) => String
  ): Unit = {
    val toProcess: Vector[File] = if (root.isFile()) { Vector(root) }
    else if (root.isDirectory()) { root.listFiles().toVector }
    else Vector()

    for { workOn <- toProcess } {
      if (workOn.length() > 0) {
        val ret = action(
          workOn,
          if (workOn == root) name
          else
            workOn
              .getCanonicalPath()
              .substring(root.getParentFile().getCanonicalPath().length()),
          parentId
        )

        for {
          stream <- streamForArchive(workOn)
          archive <- iteratorFor(stream)
        } {
          if (stream.canReadEntryData(archive) && !archive.isDirectory()) {
            val name = archive.getName()
            val dot = name.lastIndexOf(".")
            val suffix: String = if (dot >= 0) {
              name.substring(dot + 1)
            } else "tmp"

            val file = Helpers.tempFileFromStream(stream)

            processFileAndSubfiles(file, name, Some(ret), action)
            file.delete()
          }
        }
      }
    }
  }

  def graphForToProcess(item: ToProcess, store: Storage): Unit = {
    item match {
      case ToProcess(pom, main, Some(source), pomFile) => {
        val sourceMap = buildItemsFor(
          source,
          pom.map(_.purl() + "?packaging=sources").getOrElse(main.getName()),
          store,
          Vector(),
          pom,
          Map()
        )

        buildItemsFor(
          main,
          pom.map(_.purl()).getOrElse(main.getName()),
          store,
          Vector(),
          pom,
          sourceMap
        )

      }

      case ToProcess(pom, main, _, _) =>
        buildItemsFor(
          main,
          pom.map(_.purl()).getOrElse(main.getName()),
          store,
          Vector(),
          pom,
          Map()
        )
    }
  }

  def buildItemsFor(
      root: File,
      name: String,
      store: Storage,
      topConnections: Vector[Edge],
      topPackageIdentifier: Option[PackageIdentifier],
      associatedFiles: Map[String, GitOID]
  ): Map[String, String] = {
    println(f"Building items for ${name}")
    var ret: Map[String, String] = Map()
    processFileAndSubfiles(
      root,
      name,
      None,
      (file, name, parent) => {
        val (main, foundAliases) = GitOIDUtils.computeAllHashes(file)

        val aliases =
          if (parent.isEmpty && topPackageIdentifier.isDefined)
            topPackageIdentifier
              .map(
                _.purl() +
                  (if (
                     name.endsWith("?packaging=sources") ||
                     file.getName().indexOf("-sources.") >= 0 ||
                     name.indexOf("-sources.") >= 0
                   ) { "?packaging=sources" }
                   else { "" })
              )
              .toVector
          else foundAliases

        val fileType = FileType.theType(name, Some(file), associatedFiles)

        val item = Item(
          identifier = main,
          reference = Item.noopLocationReference,
          connections = (fileType.sourceGitOid() match {
            case None         => Vector()
            case Some(source) => Vector((source -> EdgeType.BuiltFrom))
          }) ++
            (parent match {
              case Some(parentId) =>
                Vector((parentId -> EdgeType.ContainedBy))
              case None => topConnections
            }) ++ aliases
              .map(alias => (alias, EdgeType.AliasFrom))
              .sortBy(_._1),
          altIdentifiers = aliases,
          previousReference = None,
          metadata = Some(
            ItemMetaData.from(
              name,
              fileType,
              if (parent.isEmpty) topPackageIdentifier else None
            )
          ),
          mergedFrom = Vector(),
          _timestamp = System.currentTimeMillis(),
          _type = "Item",
          _version = 1
        ).fixReferences(store)
        ret = ret + (name -> main)

        val itemMerged = store.read(main) match {
          case None        => item
          case Some(other) => other.merge(Vector(item))
        }
        store.write(main, itemMerged)
        main
      }
    )
    ret
  }
}
