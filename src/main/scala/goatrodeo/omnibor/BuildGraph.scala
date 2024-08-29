package io.spicelabs.goatrodeo.omnibor

import java.io.File
import scala.util.Try
import io.spicelabs.goatrodeo.util.Helpers
import java.io.BufferedWriter
import java.io.FileWriter
import io.spicelabs.goatrodeo.util.PackageIdentifier
import io.spicelabs.goatrodeo.util.{GitOID, FileWalker, FileWrapper, GitOIDUtils}
import io.spicelabs.goatrodeo.util.FileType
import scala.collection.immutable.TreeSet
import scala.collection.immutable.TreeSet

/** Tools for opening files including containing files and building graphs
  */
object BuildGraph {
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
        val (sourceMap, _) = buildItemsFor(
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
          sourceMap,
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

  /**
    * Build a graph of identifiers for a given File
    *
    * @param root the root file to test
    * @param name the name of the file
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
      root: File,
      name: String,
      store: Storage,
      topConnections: Vector[Edge],
      topPackageIdentifier: Option[PackageIdentifier],
      associatedFiles: Map[String, GitOID],
      purlOut: BufferedWriter,
      dontSkipFound: Boolean
  ): (Map[String, String], Map[Vector[String], String]) = {
    var ret: Map[String, String] = Map()
    var ret2: Map[Vector[String], String] = Map()
    FileWalker.processFileAndSubfiles(
      FileWrapper(root, false),
      name,
      None,
      Vector(name),
      dontSkipFound,
      (file, name, parent, parentStack) => {
        // Compute the gitoid-sha256 (main) and other hash aliases for the item
        val (main, foundAliases) =
          GitOIDUtils.computeAllHashes(file, s => !store.exists(s))
        val foundGitOID = store.exists(main)
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

        val fileType = FileType.theType(name, Some(file), associatedFiles)

        val computedConnections: TreeSet[Edge] =
          // built from a source file
          (fileType.sourceGitOid() match {
            case None => TreeSet[Edge]()
            case Some(source) =>
              TreeSet[Edge]((EdgeType.BuiltFrom, source))
          })
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
          /* no pURL... index is took expensive
          ++
          // create the pURL DB
          (
            packageId.toVector.map(id =>
              (packageType(id), EdgeType.ContainedBy, Some(id))
            )
          )*/

        val item = Item(
          identifier = main,
          reference = Item.noopLocationReference,
          connections = computedConnections,
          fileSize = file.size(),
          metadata = Some(
            ItemMetaData.from(
              name,
              fileType,
              if (parent.isEmpty) topPackageIdentifier else None
            )
          ),
          mergedFrom = TreeSet(),
        ).fixReferences(store)
        ret = ret + (name -> main)
        ret2 = ret2 + (parentStack -> main)

        store.write(
          main,
          current => {
            current match {
              case None        => item
              case Some(other) => other.merge(item)
            }
          }
        )
        (main, foundGitOID, None)
      }
    )
    ret -> ret2
  }
}
