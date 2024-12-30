package goatrodeo.omnibor

import com.typesafe.scalalogging.Logger

import java.io.File
import scala.util.Try
import goatrodeo.util.Helpers

import java.io.BufferedWriter
import java.io.FileWriter
import goatrodeo.strategies.Strategy
import goatrodeo.util.{FileWalker, FileWrapper, GitOID, GitOIDUtils}
import goatrodeo.util.FileType
import goatrodeo.util.PackageIdentifier
import scala.collection.immutable.TreeSet
import scala.collection.immutable.TreeSet
import goatrodeo.util.ArtifactWrapper

/** Tools for opening files including containing files and building graphs
  */
object BuildGraph {
  val logger = Logger("BuildGraph")
  def graphForToProcess(
      item: Strategy,
      store: Storage,
      purlOut: BufferedWriter
  ): Unit = {
    val processGroup = item.processGroup()

    var clean = false
    try {
      // get the items to process
      val items = processGroup.artifacts
      val res = items.foldLeft(Vector[(ArtifactWrapper, BuiltItemResult)]())((state, next) => {
        val main = next()
        val res =  buildItemsFor(
          main,
          store,
          Vector(),
          None,
          state,
          purlOut = purlOut,
          false
        )
        state :+ (main, res)
      })
        processGroup.cleanUp(res, store, purlOut)
        clean = true
    } finally {
      // do the cleanup even if the other stuff didn't work
      processGroup.cleanUp(Vector(), store, purlOut)
      
    }

  }

  private def packageType(name: String): String = {
    name.indexOf("/") match {
      case n if n > 1 => name.substring(0, n)
      case _          => "pkg:"
    }
  }

  /**
    * Build a graph of identifiers for a given File
    *
    * @param root the root file to test
    * @param store the backing store to load/save/inspect for the graph
    * @param topConnections the back-reference for connections/aliases
    * @param topPackageIdentifier the top level package identifier
    * @param associatedFiles files associated with this artifact. Used to create graphs between source archives/jars and 
    * compiled artifacts
    * @param purlOut where to write pURLs
    * @param dontSkipFound flag if true, even if the artifact was already found in the graph, process it again
    * @return a tuple of (Map[file-name, gitoid-sha256], Map[Vector[filenames-for-embedded-artifacts], gitoid-sha256])
    */
  def buildItemsFor(
      root: ArtifactWrapper,
      store: Storage,
      topConnections: Vector[Edge],
      topPackageIdentifier: Option[PackageIdentifier],
      associatedFiles: Vector[(ArtifactWrapper, BuiltItemResult)],
     // customConnections: () => TreeSet[Edge],
      purlOut: BufferedWriter,
      dontSkipFound: Boolean
  ): BuiltItemResult = {
    var nameToGitOID: Map[String, String] = Map()
    var parentStackToGitOID: Map[Vector[FileAndGitoid], String] = Map()
    var rootGitoid: String = ""

    FileWalker.processFileAndSubfiles[Vector[FileAndGitoid]](
      root,
      None,
      Vector(),
      dontSkipFound,
      (file, parent, lastParentStack) => {
        // Compute the gitoid-sha256 (main) and other hash aliases for the item
        val (mainFileGitOID, foundAliases) =   GitOIDUtils.computeAllHashes(file, s => !store.exists(s))

        val name = file.getPath()
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
                   file.getPath().indexOf("-sources.") >= 0 ||
                   name.indexOf("-sources.") >= 0
                 ) { "?packaging=sources" }
                 else { "" })
            )
          )
          .filter(_ => parent.isEmpty)
        packageIds.foreach(pid => purlOut.write(f"${pid}\n"))
        val aliases = foundAliases ++ packageIds

        val fileType = file.mimeType

        logger.trace(f"File Name: ${name} Type: ${fileType}")

        val computedConnections: TreeSet[Edge] = TreeSet.empty
          // built from a source file FIXME
          // (fileType.sourceGitOid() match {
          //   case None => TreeSet[Edge]()
          //   case Some(source) =>
          //     TreeSet[Edge]((EdgeType.BuiltFrom, source))
          // })
          ++
          // include parent back-reference
          (parent match {
            case Some(parentId) =>
              Vector[Edge]((EdgeType.ContainedBy, parentId))
            case None => topConnections
          })
          ++
          // include aliases only if we aren't merging this item (if we're)
          // merging, then the aliases already exist and no point in regenerating them
          (aliases.map(alias => (EdgeType.AliasFrom, alias))).toSet


        val item = Item(
          identifier = mainFileGitOID,
          reference = Item.noopLocationReference,
          connections = computedConnections,
          
          metadata = Some(
            ItemMetaData.from(
              name,
              fileType,
              if (parent.isEmpty) topPackageIdentifier else None,
              fileSize = file.size(),
            )
          ),
          mergedFrom = TreeSet(),
        ).fixReferences(store)
        nameToGitOID = nameToGitOID + (name -> mainFileGitOID)
        parentStackToGitOID = parentStackToGitOID + (parentStack -> mainFileGitOID)

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

/**
  * The results of running `buildItemsFor`
  *
  * @param mainGitOID the GitOID SHA256 of the root file
  * @param nameToGitOID the map of name to gitoid
  * @param parentStackToGitOID the map of full path to gitoid
  */
case class BuiltItemResult(mainGitOID: String, nameToGitOID: Map[String, String], parentStackToGitOID: Map[Vector[FileAndGitoid], String])

case class FileAndGitoid(file: String, gitoid: String)