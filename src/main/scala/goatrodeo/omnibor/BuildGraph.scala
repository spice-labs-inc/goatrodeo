package goatrodeo.omnibor

import com.typesafe.scalalogging.Logger

import java.io.File
import scala.util.Try
import goatrodeo.util.Helpers

import java.io.BufferedWriter
import java.io.FileWriter
import goatrodeo.util.PackageIdentifier
import goatrodeo.util.{FileWalker, FileWrapper, GitOID, GitOIDUtils}
import scala.collection.immutable.TreeSet
import scala.collection.immutable.TreeMap

/** Tools for opening files including containing files and building graphs
  */
object BuildGraph {
  val logger = Logger("BuildGraph")
  def graphForToProcess(
      item: ToProcess,
      store: Storage,
      purlOut: BufferedWriter
  ): Unit = {

    item match {
      case ToProcess(pom, main, Some(source), pomFile) => {
        // process the POM file
        pomFile.foreach(pf =>
          buildItemsFor(
            pf,
            pf.getName(),
            store,
            Vector(),
            None,
            Map(),
            purlOut,
            false
          )
        )

        // process the sources
        val sourceBuilt = buildItemsFor(
          source,
          pom
            .flatMap(_.purl().headOption.map(_ + "?packaging=sources"))
            .getOrElse(main.getName()),
          store,
          Vector(),
          pom,
          Map(),
          purlOut,
          true
        )

        pom.toVector
          .flatMap(_.purl().map(_ + "?packaging=sources"))
          .foreach(pid => purlOut.write(f"${pid}\n"))

        // process the main class file
        buildItemsFor(
          main,
          pom.flatMap(_.purl().headOption).getOrElse(main.getName()),
          store,
          Vector(),
          pom,
          sourceBuilt.nameToGitOID,
          purlOut,
          false
        )
        pom.toVector
          .flatMap(_.purl())
          .foreach(pid => purlOut.write(f"${pid}\n"))
      }

      case ToProcess(pom, main, _, _) =>
        buildItemsFor(
          main,
          pom.flatMap(_.purl().headOption).getOrElse(main.getName()),
          store,
          Vector(),
          pom,
          Map(),
          purlOut = purlOut,
          false
        )
    }

  }

  private def packageType(name: String): String = {
    name.indexOf("/") match {
      case n if n > 1 => name.substring(0, n)
      case _          => "pkg:"
    }
  }

  /** Build a graph of identifiers for a given File
    *
    * @param root
    *   the root file to test
    * @param name
    *   the name of the file
    * @param store
    *   the backing store to load/save/inspect for the graph
    * @param topConnections
    *   the back-reference for connections/aliases
    * @param topPackageIdentifier
    *   the top level package identifier
    * @param associatedFiles
    *   files associated with this artifact. Used to create graphs between
    *   source archives/jars and compiled artifacts
    * @param purlOut
    *   where to write pURLs
    * @param dontSkipFound
    *   flag if true, even if the artifact was already found in the graph,
    *   process it again
    * @return
    *   a tuple of (Map[file-name, gitoid-sha256],
    *   Map[Vector[filenames-for-embedded-artifacts], gitoid-sha256])
    */
  def buildItemsFor(
      root: File,
      name: String,
      store: Storage,
      topConnections: Vector[Edge],
      topPackageIdentifier: Option[PackageIdentifier],
      associatedFiles: Map[String, GitOID],
      purlOut: BufferedWriter,
      dontSkipFound: Boolean
  ): BuiltItemResult = {
    var nameToGitOID: Map[String, String] = Map()
    var parentStackToGitOID: Map[Vector[FileAndGitoid], String] = Map()
    var rootGitoid: String = ""

    FileWalker.processFileAndSubfiles[Vector[FileAndGitoid]](
      FileWrapper(root, false),
      name,
      None,
      Vector(),
      dontSkipFound,
      (file, name, parent, lastParentStack) => {
        // Compute the gitoid-sha256 (main) and other hash aliases for the item
        val (mainFileGitOID, foundAliases) =
          GitOIDUtils.computeAllHashes(file, s => !store.exists(s))

        val parentStack = lastParentStack :+ FileAndGitoid(name, mainFileGitOID)
        if (parent.isEmpty) {
          rootGitoid = mainFileGitOID
        }
        val foundGitOID = store.exists(mainFileGitOID)
        val packageIds: Vector[String] = topPackageIdentifier.toVector
          .flatMap(
            _.purl().map(
              _ +
                (if (
                   name.endsWith("?packaging=sources") ||
                   file.name().indexOf("-sources.") >= 0 ||
                   name.indexOf("-sources.") >= 0
                 ) { "?packaging=sources" }
                 else { "" })
            )
          )
          .filter(_ => parent.isEmpty)
        packageIds.foreach(pid => purlOut.write(f"${pid}\n"))
        val aliases = foundAliases ++ packageIds

        val mimeType = Helpers.mimeTypeFor(file.asStream(), name)
        val associatedSource = Helpers.computeAssociatedSource(
          file,
          mimeType,
          associatedFiles
        ) // FileType.theType(name, Some(file), associatedFiles)

        logger.trace(s"File Name: $name Type: $mimeType")

        val computedConnections: TreeSet[Edge] =
          // built from a source files
          associatedSource.map(gitoid => (EdgeType.builtFrom, gitoid))
          ++
          // include parent back-reference
          (parent match {
            case Some(parentId) =>
              Vector[Edge]((EdgeType.containedBy, parentId))
            case None => topConnections
          })
          ++
          // include aliases only if we aren't merging this item (if we're)
          // merging, then the aliases already exist and no point in regenerating them
          (aliases.map(alias => (EdgeType.aliasFrom, alias))).toSet

        val thePackageIdentifier =
          if (parent.isEmpty) topPackageIdentifier else None

        val item = Item(
          identifier = mainFileGitOID,
          reference = Item.noopLocationReference,
          connections = computedConnections,
          bodyMimeType = Some("application/vnd.cc.goatrodeo"),
          body = Some(
            ItemMetaData.from(
              name,
              mimeType,
              thePackageIdentifier match {
                case Some(pi) => pi.toStringMap()
                case None     => TreeMap()
              },
              thePackageIdentifier,
              fileSize = file.size()
            )
          )
        ).fixReferences(store)
        nameToGitOID = nameToGitOID + (name -> mainFileGitOID)
        parentStackToGitOID =
          parentStackToGitOID + (parentStack -> mainFileGitOID)

        store.write(
          mainFileGitOID,
          current => {
            current match {
              case None        => item
              case Some(other) => other.merge(item)
            }
          }
        )
        (mainFileGitOID, foundGitOID, None, parentStack)
      }
    )
    BuiltItemResult(rootGitoid, nameToGitOID, parentStackToGitOID)
  }
}

/** The results of running `buildItemsFor`
  *
  * @param mainGitOID
  *   the GitOID SHA256 of the root file
  * @param nameToGitOID
  *   the map of name to gitoid
  * @param parentStackToGitOID
  *   the map of full path to gitoid
  */
case class BuiltItemResult(
    mainGitOID: String,
    nameToGitOID: Map[String, String],
    parentStackToGitOID: Map[Vector[FileAndGitoid], String]
)

case class FileAndGitoid(file: String, gitoid: String)
