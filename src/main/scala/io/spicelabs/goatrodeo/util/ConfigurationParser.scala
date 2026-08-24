package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import io.bullet.borer.Dom
import io.bullet.borer.Json
import org.apache.commons.io.filefilter.WildcardFileFilter
import scopt.OParser
import scopt.OParserBuilder

import java.io.File
import java.io.FileFilter
import java.time.Instant
import java.util.regex.Pattern
import scala.io.Source
import scala.util.Try
import scala.util.Using

/** Turns a command line into a [[Configuration]].
  *
  * WHY this is separate from [[Configuration]]: parsing is one *source* of
  * configuration, not configuration itself. Keeping the two apart means the
  * fluent [[io.spicelabs.goatrodeo.GoatRodeoBuilder]] path and the CLI path
  * produce the same value by construction, and that adding a source later does
  * not grow the type that everything else depends on.
  */
object ConfigurationParser {
  private val logger = Logger(getClass())

  /** Parse an cutoff cutoff from its textual form: epoch milliseconds, an
    * ISO-8601 instant, or a flexible date string.
    */
  def parseCutoff(raw: String): Option[Instant] =
    Option(raw).map(_.trim).filter(_.nonEmpty).flatMap { raw =>
      val fromMillis =
        if (raw.forall(_.isDigit))
          Try(Instant.ofEpochMilli(raw.toLong)).toOption
        else None
      fromMillis
        .orElse(Try(Instant.parse(raw)).toOption)
        .orElse(DateParser.parse(raw).toOption.map(_.toInstant()))
    }

  /** The scopt parser builder instance. */
  lazy val builder: OParserBuilder[Configuration] =
    OParser.builder[Configuration]

  /** Parse a command line into a [[Configuration]], seeded with the ambient
    * process state the run started in.
    *
    * The lower sources are read first and the command line applied on top, so
    * precedence runs defaults < config file < environment < command line. That
    * is done by parsing twice: once to discover `--config`, then again seeded
    * with what the file and the environment said. Parsing is pure and cheap,
    * and this keeps the flag definitions the single description of what the
    * command line means.
    *
    * The second parse happens whether or not `--config` named a file: the
    * environment is a source in its own right, and skipping the resolution when
    * there was no file to read is what used to make `GOATRODEO_ANALYSIS_*` do
    * nothing on a run that named none.
    */
  def parse(
      args: Array[String],
      runtime: RuntimeEnvironment = RuntimeEnvironment.default,
      environment: Map[String, String] = sys.env
  ): Option[Configuration] =
    parseWith(args, Configuration(runtime = runtime)).flatMap { discovered =>
      val file = discovered.configFile
      ConfigurationToml.fromSources(
        file.map(_.toPath()),
        Configuration(runtime = runtime, configFile = file),
        environment
      ) match {
        case Right((resolved, resolution)) =>
          val withFlags = parseWith(args, resolved)
          // The file and the environment report their own disagreements as they
          // are resolved; this is the last layer, and the only one that cannot
          // report itself, because scopt folds flags straight into the
          // configuration with nowhere to record where a value came from. The
          // resolution is what remembers which of the lower sources actually
          // supplied the value being displaced.
          withFlags.foreach { full =>
            full.differencesFrom(resolved).foreach { (field, was, now) =>
              ConfigurationToml.sourceOf(resolution, field).foreach { source =>
                logger.info(
                  s"${ConfigurationToml.displayName(field)} = ${show(now)} " +
                    s"(command line) overrides ${show(was)} ($source)"
                )
              }
            }
          }
          withFlags
        case Left(error) => {
          logger.error(
            file.fold(s"Invalid configuration: $error")(file =>
              s"Invalid config file $file: $error"
            )
          )
          None
        }
      }
    }

  /** Values as a person wrote them, not as Scala prints them. */
  private def show(value: Any): String = value match {
    case Some(v)         => show(v)
    case None            => "unset"
    case v: Vector[?]    => v.map(show).mkString("[", ", ", "]")
    case f: java.io.File => f.toString
    case other           => other.toString
  }

  private def parseWith(
      args: Array[String],
      base: Configuration
  ): Option[Configuration] =
    OParser.parse(parser, args, base)

  /** Render the usage text. */
  def usage: String = OParser.usage(parser)

  /** A flag's pre-rename spelling, still accepted and warned about.
    *
    * Hidden, so that `--help` describes one way to say each thing and nobody
    * learns the old spelling from this program. Present, because these flags
    * are written down in scripts and images that are not rebuilt when Goat
    * Rodeo is, and a rename that breaks them on the day it merges is a rename
    * that gets reverted. They come out a release after the callers are fixed.
    */
  private def deprecated[A: scopt.Read](old: String, current: String)(
      action: (A, Configuration) => Configuration
  ): OParser[A, Configuration] =
    builder
      .opt[A](old)
      .hidden()
      .text(s"Deprecated: use --$current")
      .action { (x, c) =>
        logger.warn(
          s"--$old has been renamed to --$current and will stop working in a future release"
        )
        action(x, c)
      }

  /** The command line argument parser definition. */
  lazy val parser: OParser[Unit, Configuration] = {
    import builder._
    OParser.sequence(
      programName("goatrodeo"),
      head("goatrodeo", hellogoat.BuildInfo.version),
      opt[File]("block-list")
        .text(
          "The gitoid block list. Do not process these gitoids. Used for common gitoids such as license files"
        )
        .action((x, c) =>
          c.copy(blockList = ExpandFiles(x, c.runtime.homeDir).headOption)
        ),
      deprecated[File]("block", "block-list")((x, c) =>
        c.copy(blockList = ExpandFiles(x, c.runtime.homeDir).headOption)
      ),
      opt[File]('b', "build")
        .text("Build gitoid database from jar files in a directory")
        .action((x, c) => {
          val tildeExpand = ExpandFiles.fixTilde(x, c.runtime.homeDir)
          if (!tildeExpand.exists()) {
            c.copy(nonexistentDirectories = c.nonexistentDirectories :+ x)
          } else {
            c.copy(build =
              (c.build ++ ExpandFiles(x, c.runtime.homeDir))
                .filter(f => f.exists())
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
      opt[String]("cutoff")
        .text(
          "Refuse to analyze internal files modified after this date/time (e.g. 2026-01-01); dependents are dropped too"
        )
        .action((x, c) =>
          parseCutoff(x) match {
            case Some(instant) => c.copy(cutoff = Some(instant))
            case None          =>
              // Stop, rather than log and carry on with `c` unchanged. A cutoff that fails to
              // parse would otherwise leave `cutoff = None`, and the run would analyse
              // everything -- a restriction failing open, which is the one direction it must
              // not fail in. Nothing types this flag by hand: allspice and spice both set the
              // cutoff through withCutoff(Instant) from the Spice Pass, so a malformed value
              // here means the tool that built the command line is wrong, and saying so
              // loudly is more use than scopt's usage text.
              logger.error(f"Invalid --cutoff value: ${x}")
              Helpers.exitWrapper(1)
              c
          }
        ),
      opt[File]("ingested")
        .text(
          "Append all the ingested files to this file on successful completion"
        )
        .action((x, c) =>
          c.copy(ingested = ExpandFiles(x, c.runtime.homeDir).headOption)
        ),
      opt[Boolean]("print-files")
        .text(
          "Log the path of each top-level file after it is processed, one log line per file"
        )
        .action((x, c) => c.copy(printProcessedFiles = x)),
      opt[File]("tamper-evident-log")
        .text(
          "Write a hash-chained, tamper-evident log of this run to the given file"
        )
        .action((x, c) =>
          c.copy(tamperEvidentLog =
            ExpandFiles(x, c.runtime.homeDir).headOption
          )
        ),
      opt[File]("ignore")
        .text(
          "A file containing paths to ignore, likely because they have been processed in the past"
        )
        .action((x, c) =>
          c.copy(ignore =
            (c.ignore ++ ExpandFiles(x, c.runtime.homeDir)).filter(_.exists())
          )
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
        .action((file: File, config: Configuration) =>
          config.copy(fileList =
            (config.fileList ++ ExpandFiles(file, config.runtime.homeDir))
              .filter(_.exists())
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
      opt[String]("log-level")
        .text("error, warn, info, debug or trace (default: info)")
        .action((x, c) => c.copy(logging = c.logging + ("level" -> x))),
      opt[String]("log-file")
        .text("Also write log output to this file")
        .action((x, c) => c.copy(logging = c.logging + ("file" -> x))),
      opt[Int]("max-records")
        .text(
          "The maximum number of records to process at once. Default 50,000"
        )
        .action((x, c) => if (x > 100) c.copy(maxRecords = x) else c),
      deprecated[Int]("maxrecords", "max-records")((x, c) =>
        if (x > 100) c.copy(maxRecords = x) else c
      ),
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
      opt[File]("config")
        .text(
          "Read settings from this TOML file. Anything also given on the command line wins."
        )
        .action((x, c) => c.copy(configFile = Some(x))),
      opt[File]("emit-cbom-dir")
        .text(
          "Emit one CycloneDX cryptographic bill-of-materials (CBOM) JSON file per top-level input into this directory"
        )
        .action((x, c) => c.copy(cbomDir = Some(x))),
      opt[String]("cbom-version")
        .text("CycloneDX CBOM version to emit (1.6 or 1.7). Default 1.6")
        .action((v, c) => c.copy(cbomVersion = v))
        .validate(v =>
          if (Set("1.6", "1.7").contains(v)) success
          else failure(s"--cbom-version must be 1.6 or 1.7, got $v")
        ),
      opt[File]("temp-dir")
        .text("Where to temporarily store files... should be a RAM disk")
        .action((x, c) => c.copy(tempDir = Some(x))),
      deprecated[File]("tempdir", "temp-dir")((x, c) =>
        c.copy(tempDir = Some(x))
      ),
      opt[Int]('t', "threads")
        .text(
          "How many threads to run (default 4). Should be 2x-3x number of cores"
        )
        .validate(t =>
          if (t >= 1) success
          else failure(s"threads must be >= 1, got $t")
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
          logger.info(OParser.usage(parser))
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

            case _ => {
              logger.info(OParser.usage(parser))
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
        .action((_, c) => c.copy(printComponentArgumentInfo = true)),
      opt[Unit]("package-tags")
        .text("Create per-package tags for identified packages")
        .action((_, c) => c.copy(packageTags = true)),
      opt[Unit]("package-tags-short-name")
        .text(
          "Use short package names (e.g., artifactId) instead of full qualified names"
        )
        .action((_, c) => c.copy(packageTagsShortName = true)),
      opt[String]("tag-version")
        .text("Set version field in top-level tag JSON (requires --tag)")
        .action((v, c) => c.copy(tagVersion = Some(v))),
      opt[String]("tag-date")
        .text(
          "Set date field in top-level tag JSON (requires --tag). Supports ISO8601, MM/DD/YYYY, DD/MM/YYYY, 'today', 'yesterday', 'now'"
        )
        .action((d, c) =>
          DateParser.parse(d) match {
            case Right(date) => c.copy(tagDate = Some(date))
            case Left(error) =>
              logger.error(error)
              Helpers.exitWrapper(1)
              c
          }
        )
        .validate { d =>
          DateParser.parse(d) match {
            case Right(_)    => success
            case Left(error) => failure(error)
          }
        },
      checkConfig { c =>
        if (c.tagVersion.isDefined && c.tag.isEmpty) {
          failure("--tag-version requires --tag to be specified")
        } else if (c.tagDate.isDefined && c.tag.isEmpty) {
          failure("--tag-date requires --tag to be specified")
        } else {
          success
        }
      }
    )
  }
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
  *
  * The home directory is a parameter rather than an ambient
  * `System.getProperty("user.home")` read, so it travels with the run's
  * [[RuntimeEnvironment]].
  */
object ExpandFiles {

  /** Expand a file path, supporting wildcards in the filename.
    *
    * If the file doesn't exist, returns a Vector containing just the input
    * file. If the file exists, expands any wildcards in the filename portion.
    *
    * @param in
    *   the file to expand
    * @param homeDir
    *   the directory a leading `~` refers to
    * @return
    *   a Vector of matching files, or just the input if it doesn't exist
    */
  def apply(
      in: File,
      homeDir: File = RuntimeEnvironment.default.homeDir
  ): Vector[File] = {

    val fixed = fixTilde(in, homeDir)
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

  /** Expand tilde (~) at the start of a file path to the user's home directory.
    *
    * @param in
    *   the file whose path may contain a leading tilde
    * @param homeDir
    *   the directory a leading `~` refers to
    * @return
    *   a new File with ~ replaced by the home directory, or the original file
    *   if it doesn't start with ~/
    */
  def fixTilde(
      in: File,
      homeDir: File = RuntimeEnvironment.default.homeDir
  ): File = {
    if (in.getPath().startsWith("~" + File.separator)) {
      new File(homeDir.getPath() + in.getPath().substring(1))
    } else in
  }
}
