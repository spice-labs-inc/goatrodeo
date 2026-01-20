package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import io.bullet.borer.Dom
import io.bullet.borer.Json
import io.spicelabs.goatrodeo.components.Arguments
import io.spicelabs.goatrodeo.util.Config.ExpandFiles.fixTilde
import org.apache.commons.io.filefilter.WildcardFileFilter
import scopt.OParser
import scopt.OParserBuilder

import java.io.File
import java.io.FileFilter
import java.nio.file.Files
import java.util.regex.Pattern
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

/** Command line configuration for the Goat Rodeo application.
  *
  * This case class holds all the configuration options that can be specified
  * via command line arguments when running the application.
  *
  * @param out
  *   the output directory for the file-system based GitOID storage
  * @param build
  *   directories to scan for files to build the GitOID corpus from
  * @param ingested
  *   optional file to append successfully ingested files to
  * @param ignore
  *   files containing paths to ignore (e.g., previously processed files)
  * @param fileList
  *   files containing lists of files to process
  * @param tag
  *   optional tag for top-level artifacts with the current date
  * @param exclude
  *   regex patterns for excluding files from processing
  * @param threads
  *   number of parallel threads for processing (default 4)
  * @param tagJson
  *   optional JSON to include as part of the tag
  * @param blockList
  *   file containing GitOIDs to skip (e.g., common license files)
  * @param maxRecords
  *   maximum number of records to process at once (default 50000)
  * @param tempDir
  *   directory for temporary files (ideally a RAM disk)
  * @param useStaticMetadata
  *   whether to enhance metadata using Syft
  * @param dumpRootDir
  *   optional directory to dump root items as JSON
  * @param emitJsonDir
  *   optional directory to dump the ADG as JSON
  * @param fsFilePaths
  *   whether to include filesystem file paths in items
  * @param nonexistantDirectories
  *   directories that were specified but don't exist
  * @param mimeFilter
  *   include/exclude filter for MIME types
  * @param filenameFilter
  *   include/exclude filter for filenames
  * @param componentArgs
  *   arguments to pass to components
  * @param printComponentArgumentInfo
  *   whether to print component argument help
  * @param printComponentInfo
  *   whether to print component information
  */
case class Config(
    out: Option[File] = None,
    build: Vector[File] = Vector(),
    ingested: Option[File] = None,
    ignore: Vector[File] = Vector(),
    fileList: Vector[File] = Vector(),
    tag: Option[String] = None,
    exclude: Vector[(String, Try[Pattern])] = Vector(),
    threads: Int = 4,
    tagJson: Option[Dom.Element] = None,
    blockList: Option[File] = None,
    maxRecords: Int = 50000,
    tempDir: Option[File] = None,
    useStaticMetadata: Boolean = false,
    dumpRootDir: Option[File] = None,
    emitJsonDir: Option[File] = None,
    fsFilePaths: Boolean = false,
    nonexistantDirectories: Vector[File] = Vector(),
    mimeFilter: IncludeExclude = IncludeExclude(),
    filenameFilter: IncludeExclude = IncludeExclude(),
    componentArgs: Map[String, Vector[Array[String]]] = Map(),
    printComponentArgumentInfo: Boolean = false,
    printComponentInfo: Boolean = false
) {

  /** Build a list of file list builders from the configuration.
    *
    * Returns tuples of (base directory, function to get files). For `build`
    * directories, finds all files recursively. For `fileList` files, reads file
    * paths from each line.
    *
    * @return
    *   a Vector of tuples containing the base directory and a function that
    *   returns the files to process
    */
  def getFileListBuilders(): Vector[(File, () => Seq[File])] = {
    build.map(file => (file, () => Helpers.findFiles(file))) ++ fileList
      .map(f => {
        val fileNames =
          Files
            .readAllLines(f.toPath())
            .asScala
            .toSeq
            .map(fn => new File(fn))
            .filter(_.exists())
        (File(".").getCanonicalFile(), () => fileNames)
      })
  }
}

/** Companion object for Config providing command line argument parsing.
  *
  * Uses scopt for parsing command line arguments into a Config instance.
  */
object Config {
  private val logger = Logger(getClass())

  /** The scopt parser builder instance. */
  lazy val builder: OParserBuilder[Config] = OParser.builder[Config]

  /** The command line argument parser definition. */
  lazy val parser1: OParser[Unit, Config] = {
    import builder._
    OParser.sequence(
      programName("goatrodeo"),
      head("goatrodeo", hellogoat.BuildInfo.version),
      opt[File]("block")
        .text(
          "The gitoid block list. Do not process these gitoids. Used for common gitoids such as license files"
        )
        .action((x, c) => c.copy(blockList = ExpandFiles(x).headOption)),
      opt[File]('b', "build")
        .text("Build gitoid database from jar files in a directory")
        .action((x, c) => {
          val tildeExpand = fixTilde(x)
          if (!tildeExpand.exists()) {
            c.copy(nonexistantDirectories = c.nonexistantDirectories :+ x)
          } else {
            c.copy(build =
              (c.build ++ ExpandFiles(x))
                .filter(f => f.exists()),
            )
          }
        }),
      opt[Boolean]("fs-file-paths")
        .text("Include file paths for items on the filesystem")
        .action((x, c) => c.copy(fsFilePaths = x)),
      opt[Boolean]("static-metadata")
        .text(
          "Enhance metadata with Syft (must install https://github.com/anchore/syft)"
        )
        .action((x, c) => c.copy(useStaticMetadata = x)),
      opt[String]("tag")
        .text(
          "Tag all top level artifacts (files) with the current date and the text of the tag"
        )
        .action((x, c) => c.copy(tag = Some(x))),
      opt[File]("ingested")
        .text(
          "Append all the ingested files to this file on successful completion"
        )
        .action((x, c) => c.copy(ingested = ExpandFiles(x).headOption)),
      opt[File]("ignore")
        .text(
          "A file containing paths to ignore, likely because they have been processed in the past"
        )
        .action((x, c) =>
          c.copy(ignore = (c.ignore ++ ExpandFiles(x)).filter(_.exists()))
        ),
      opt[String]("tag-json")
        .text("Json that is included as part of the tag")
        .action((s, c) =>
          c.copy(tagJson =
            Some(Json.decode(s.getBytes("UTF-8")).to[Dom.Element].value)
          )
        ),
      opt[File]("file-list")
        .text(
          "A file containing a list of files to process. This may be used in conjunction with the `-b` (build) flag and this list may be generated by an external process"
        )
        .action((file: File, config: Config) =>
          config.copy(fileList =
            (config.fileList ++ ExpandFiles(file)).filter(_.exists())
          )
        ),
      opt[String]("exclude-pattern")
        .text(
          "A regular expression pattern that can be used to exclude files, for example `html$` will exclude all files that end in `html`"
        )
        .action((p, config) =>
          config.copy(exclude = (config.exclude :+ (p -> Try {
            Pattern.compile(p)
          })))
        ),
      opt[Int]("maxrecords")
        .text(
          "The maximum number of records to process at once. Default 50,000"
        )
        .action((x, c) => if (x > 100) c.copy(maxRecords = x) else c),
      opt[File]('o', "out")
        .text("output directory for the file-system based gitoid storage")
        .action((x, c) => c.copy(out = Some(x))),
      opt[File]("dump-roots")
        .text(
          "Make a directory and dump the roots in JSON files in the directory"
        )
        .action((x, c) => c.copy(dumpRootDir = Some(x))),
      opt[File]("dump-json")
        .text("Make a directory and dump the ADG as JSON in to directory")
        .action((x, c) => c.copy(emitJsonDir = Some(x))),
      opt[File]("tempdir")
        .text("Where to temporarily store files... should be a RAM disk")
        .action((x, c) => c.copy(tempDir = Some(x))),
      opt[Int]('t', "threads")
        .text(
          "How many threads to run (default 4). Should be 2x-3x number of cores"
        )
        .action((t, c) => c.copy(threads = t)),
      opt[String]("mime-filter")
        .text(
          "add an include or exclude MIME type filter:\n +mime include mime\n -mime exclude mime\n *regex include mime that matches regex\n /regex exclude mime that matches regex"
        )
        .action((x, c) => c.copy(mimeFilter = c.mimeFilter :+ x)),
      opt[File]("mime-filter-file")
        .text("a file of lines, each of which will be treated as a MIME filter")
        .action((f, c) =>
          c.copy(mimeFilter = c.mimeFilter ++ VectorOfStrings(f))
        ),
      opt[Unit]('V', "version")
        .text("print version and exit")
        .action((_, c) => {
          logger.info(f"Goat Rodeo version ${hellogoat.BuildInfo}")
          Helpers.exitZero()
          c
        }),
      opt[Unit]('?', "help")
        .text("print help and exit")
        .action((_, c) => {
          logger.info(OParser.usage(parser1))
          Helpers.exitZero()
          c
        }),
      opt[Seq[String]]("component")
        .text(
          "pass arguments to a component in the form --component <componentName>[,arg1,arg2...]"
        )
        .optional()
        .unbounded()
        .action((args, c) => {
          args match {
            case compName :: compArgs =>
              c.copy(componentArgs =
                Arguments.addArgs(compName, compArgs.toArray, c.componentArgs)
              )
            case _ => {
              logger.info(OParser.usage(parser1))
              logger.info("--component ")
              c
            }
          }
        }),
      opt[Unit]("print-component-info")
        .text("print component information")
        .action((_, c) => c.copy(printComponentInfo = true)),
      opt[Unit]("print-component-arg-help")
        .text("print component argument help")
        .action((_, c) => c.copy(printComponentArgumentInfo = true))
    )
  }

  /** Utility object for reading files into Vectors of Strings.
    *
    * Each line of the file becomes one element in the Vector. Newlines are not
    * included in the resulting strings.
    */
  object VectorOfStrings {

    /** Read a file and return its lines as a Vector of Strings.
      *
      * @param in
      *   the file to read
      * @return
      *   a Vector containing each line of the file
      */
    def apply(in: File): Vector[String] = {
      Using.resource(Source.fromFile(in.getAbsoluteFile())) { source =>
        source
          .getLines()
          .toVector // getLines() does not include new lines (yay!)
      }
    }

    /** Read a file by path and return its lines as a Vector of Strings.
      *
      * @param in
      *   the path to the file to read
      * @return
      *   a Vector containing each line of the file
      */
    def apply(in: String): Vector[String] = {
      val f = File(in)
      apply(f)
    }
  }

  /** Utility object for expanding file paths, supporting wildcards and tilde
    * expansion.
    *
    * Provides methods to:
    *   - Expand tilde (~) to the user's home directory
    *   - Expand wildcard patterns to matching files
    */
  object ExpandFiles {

    /** Expand a file path, supporting wildcards in the filename.
      *
      * If the file doesn't exist, returns a Vector containing just the input
      * file. If the file exists, expands any wildcards in the filename portion.
      *
      * @param in
      *   the file to expand
      * @return
      *   a Vector of matching files, or just the input if it doesn't exist
      */
    def apply(in: File): Vector[File] = {

      val fixed = fixTilde(in)
      if (!fixed.exists()) {
        Vector(fixed)
      } else {
        val allFiles = {
          val parent = fixed.getAbsoluteFile().getParentFile()
          val wcf: FileFilter =
            WildcardFileFilter.builder.setWildcards(fixed.getName()).get()
          parent.listFiles(wcf)
        }
        allFiles.toVector
      }
    }
    def unapply(in: Option[Vector[File]]): Option[Vector[File]] =
      in match {
        case None => None
        case Some(vf) =>
          val ret = for {
            fa <- vf
            f = fixTilde(fa)
            i <- {
              val parent = f.getAbsoluteFile().getParentFile()
              val wcf: FileFilter =
                WildcardFileFilter.builder.setWildcards(f.getName()).get()
              parent.listFiles(wcf)
            }
          } yield {
            i
          }
          Some(ret)
      }

    /** Expand tilde (~) at the start of a file path to the user's home
      * directory.
      *
      * @param in
      *   the file whose path may contain a leading tilde
      * @return
      *   a new File with ~ replaced by the home directory, or the original file
      *   if it doesn't start with ~/
      */
    def fixTilde(in: File): File = {
      if (in.getPath().startsWith("~" + File.separator)) {
        val path = System.getProperty("user.home") + in.getPath().substring(1)
        new File(path)
      } else in
    }
  }

}
