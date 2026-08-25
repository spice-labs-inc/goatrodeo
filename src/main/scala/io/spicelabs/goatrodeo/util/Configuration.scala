package io.spicelabs.goatrodeo.util

import io.bullet.borer.Dom
import io.spicelabs.goatrodeo.ProgressListener

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Date
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The ambient process state Goat Rodeo is running in, captured once rather
  * than read at the point of use.
  *
  * WHY: reading `System.getProperty("user.home")` (or the working directory)
  * from deep inside processing code makes the set of inputs undiscoverable and
  * the code untestable. Capturing it here means [[Configuration]] is the single
  * answer to "what is this run configured with", and a test can supply a
  * different environment without touching global JVM state.
  *
  * Only what is actually read: `homeDir` expands `~` in path flags, and
  * `workingDir` is the base a `--file-list` resolves against. This began by
  * capturing the whole process environment and every system property as well,
  * on the theory that ambient state belongs in one place. Nothing ever read
  * either, and a case class prints its fields -- so holding them left this one
  * `logger.info(s"$config")` away from writing `SPICE_PASS` and every CI token
  * into a log. The fix is not to hide them behind a custom `toString` but not
  * to hold them: what is never captured cannot leak.
  */
case class RuntimeEnvironment(workingDir: File, homeDir: File)

object RuntimeEnvironment {

  /** The real process environment, captured once at class-load. This is the
    * only place Goat Rodeo reads ambient process state.
    */
  lazy val default: RuntimeEnvironment = RuntimeEnvironment(
    workingDir = File(".").getCanonicalFile(),
    homeDir = File(System.getProperty("user.home"))
  )
}

/** The configuration in force, for code that has a [[Configuration]] in
  * contextual scope.
  *
  * WHY this rather than naming the context parameter: a method declares `(using
  * Configuration)` and says nothing about what to call it, so there is no name
  * to invent and none to get wrong. Before this, the same value travelled under
  * three names — `args`, `params` and `config` — and a reader had to check
  * which. Now every site reads `config`, and it always means the same thing.
  *
  * `inline` makes it a compile-time summon: no runtime cost over naming the
  * parameter directly.
  */
inline def config(using configuration: Configuration): Configuration =
  configuration

/** The complete configuration for a Goat Rodeo run.
  *
  * Every value that affects how a run behaves lives here, is strongly typed,
  * and is established once at startup — from the command line
  * ([[ConfigurationParser]]) or from the fluent
  * [[io.spicelabs.goatrodeo.GoatRodeoBuilder]]. Processing code receives it as
  * a `using` parameter named `config` and reads what it needs, rather than
  * having individual values threaded through as separate arguments or picked up
  * from system properties.
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
  * @param nonexistentDirectories
  *   directories that were specified but don't exist
  * @param mimeFilter
  *   include/exclude filter for MIME types
  * @param componentArgs
  *   arguments to pass to components
  * @param printComponentArgumentInfo
  *   whether to print component argument help
  * @param printComponentInfo
  *   whether to print component information
  * @param tagVersion
  *   optional version to include in top-level tag JSON
  * @param tagDate
  *   optional date to include in top-level tag JSON (parsed flexibly, stored as
  *   Date)
  * @param progressListener
  *   optional callback notified at phase boundaries and during the main
  *   processing loop. Set via
  *   [[io.spicelabs.goatrodeo.GoatRodeoBuilder.withProgressListener]]; not
  *   exposed on the command line.
  * @param cutoff
  *   refuse to analyze internal files modified after this instant; dependents
  *   are dropped too. Set only via `--cutoff` or
  *   [[io.spicelabs.goatrodeo.GoatRodeoBuilder.withCutoff]] — there is
  *   deliberately no system-property or environment channel, so the CLI and
  *   library paths behave identically.
  * @param cbomDir
  *   optional directory to emit CycloneDX cryptographic bill-of-materials
  *   (CBOM) files, one per top-level input
  * @param cbomVersion
  *   CycloneDX CBOM specification version to emit ("1.6" or "1.7")
  * @param configFile
  *   the TOML file this configuration was read from, when `--config` named one
  * @param runtime
  *   the ambient process state this run was started in
  */
case class Configuration(
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
    nonexistentDirectories: Vector[File] = Vector(),
    mimeFilter: IncludeExclude = IncludeExclude(),
    componentArgs: Map[String, Vector[Array[String]]] = Map(),
    printComponentArgumentInfo: Boolean = false,
    printComponentInfo: Boolean = false,
    packageTags: Boolean = false,
    packageTagsShortName: Boolean = false,
    tagVersion: Option[String] = None,
    tagDate: Option[Date] = None,
    progressListener: Option[ProgressListener] = None,
    cutoff: Option[Instant] = None,
    cbomDir: Option[File] = None,
    cbomVersion: String = "1.6",
    logFilenames: Boolean = false,
    tamperEvidentLog: Option[File] = None,
    configFile: Option[File] = None,
    logging: Map[String, Any] = Map(),
    runtime: RuntimeEnvironment = RuntimeEnvironment.default
) {

  /** The settings that differ between this configuration and another.
    *
    * Used to report what the command line changed. Deriving it by comparing two
    * configurations, rather than recording an origin in each of two dozen flag
    * actions, means a flag added later is covered without anyone remembering to
    * cover it — the failure mode for hand-maintained lists here has always been
    * that they quietly fall behind.
    *
    * `runtime` and `configFile` are skipped: they are how the run was started,
    * not settings anybody wrote.
    */
  def differencesFrom(other: Configuration): Vector[(String, Any, Any)] = {
    val ignored = Set("runtime", "configFile")
    productElementNames.zipWithIndex
      .filterNot((name, _) => ignored.contains(name))
      .flatMap { (name, index) =>
        val mine = productElement(index)
        val theirs = other.productElement(index)
        if (mine == theirs) None else Some((name, theirs, mine))
      }
      .toVector
  }

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
        (runtime.workingDir, () => fileNames)
      })
  }
}
