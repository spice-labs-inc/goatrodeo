package goatrodeo.omnibor

import java.io.File
import scala.util.Try
import goatrodeo.util.Helpers
import java.io.BufferedWriter
import java.io.FileWriter
import goatrodeo.util.PackageIdentifier
import goatrodeo.util.{GitOID, FileWalker, FileWrapper, GitOIDUtils}
import goatrodeo.util.FileType
import scala.collection.immutable.TreeSet

/** Tools for opening files including containing files and building graphs
  */
object BuildGraph {

  def foo(a: EdgeType, b: EdgeType): TreeSet[EdgeType] = TreeSet(a, b)

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
        val sourceMap = buildItemsFor(
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

  def buildItemsFor(
      root: File,
      name: String,
      store: Storage,
      topConnections: Vector[Edge],
      topPackageIdentifier: Option[PackageIdentifier],
      associatedFiles: Map[String, GitOID],
      purlOut: BufferedWriter,
      dontSkipFound: Boolean
  ): Map[String, String] = {
    var ret: Map[String, String] = Map()
    FileWalker.processFileAndSubfiles(
      FileWrapper(root, false),
      name,
      None,
      dontSkipFound,
      (file, name, parent) => {
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
    ret
  }
}
