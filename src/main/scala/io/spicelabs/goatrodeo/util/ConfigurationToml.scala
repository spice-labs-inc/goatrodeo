package io.spicelabs.goatrodeo.util

import io.bullet.borer.Dom
import io.bullet.borer.Json
import io.spicelabs.config.ConfigurationException
import io.spicelabs.config.Origin
import io.spicelabs.config.Resolution
import io.spicelabs.config.Resolver
import io.spicelabs.config.Setting
import org.tomlj.Toml
import org.tomlj.TomlTable

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

/** Reads a [[Configuration]] from a TOML table.
  *
  * WHY a table rather than a file: this is the same schema whether it is the
  * whole of a Goat Rodeo config file or one nested table inside a larger
  * `spice` or Allspice config. Taking a `TomlTable` means both cases run the
  * same code, with no "am I nested?" branch, and it is what lets an outer
  * program carry Goat Rodeo's settings verbatim without understanding them.
  *
  * WHY unknown keys are an error: a mistyped key that is silently ignored is
  * how a config file becomes undebuggable — the value the user wrote is simply
  * not in force and nothing says so. The previous attempt at cross-program
  * configuration failed in exactly this family: an allowlist of Goat Rodeo
  * flags maintained inside Allspice drifted until it permitted flags Goat Rodeo
  * does not have.
  *
  * Keys are the snake_case spelling of the corresponding command-line flag, and
  * the mapping is mechanical in both directions: `max_records` is
  * `--max-records`, `fs_file_paths` is `--fs-file-paths`. The same key is
  * `GOATRODEO_ANALYSIS_MAX_RECORDS` in the environment standalone, and
  * `SPICE_ANALYSIS_MAX_RECORDS` when embedded — the same setting under the same
  * name, differing only in whose program is running.
  */
object ConfigurationToml {

  private val logger = com.typesafe.scalalogging.Logger(getClass())

  /** The configuration group these settings belong to.
    *
    * Named for the job rather than for this component, and the same group
    * `spice` and Allspice carry, so `[analysis] threads = 16` means one thing
    * wherever it is written. A standalone Goat Rodeo config file therefore has
    * an `[analysis]` table too, rather than bare keys — one shape to learn.
    */
  val Group: String = "analysis"

  /** The environment-variable prefix when Goat Rodeo runs standalone.
    *
    * `GOATRODEO_ANALYSIS_THREADS` rather than `SPICE_ANALYSIS_THREADS`: the
    * setting is the same, but a variable naming a program that is not running
    * would be a lie, and it lets both be set on one machine without collision.
    */
  val EnvironmentPrefix: String = "GOATRODEO"

  /** Keys accepted in the `[analysis]` table. */
  private val knownKeys: Set[String] = Set(
    "out",
    "build",
    "file_list",
    "ingested",
    "ignore",
    "block_list",
    "exclude_pattern",
    "threads",
    "max_records",
    "temp_dir",
    "static_metadata",
    "fs_file_paths",
    "dump_roots",
    "dump_json",
    "mime_filter",
    "tag",
    "tag_json",
    "tag_version",
    "tag_date",
    "package_tags",
    "package_tags_short_name",
    "emit_cbom_dir",
    "cbom_version",
    // Recognised so that it is refused by name rather than reported as a typo, but with no
    // handler below: knowing the key exists is not the same as letting a file set it.
    "cutoff"
  )

  /** Keys no config file may supply, in any mode.
    *
    * `cutoff` is an entitlement, not a preference. Embedded in `spice` or
    * Allspice it is the pass's `x-cutoff`, which constrains what the platform
    * will accept, and a config file supplying it would hand a user the ability
    * to widen a scope the platform deliberately narrowed. Standalone there is
    * no pass to contradict, but the same value read from two kinds of place is
    * how the embedded rule gets forgotten — so it is refused there too, and
    * `--cutoff` remains the single way to ask for one.
    *
    * Refusing by name rather than by omission: an unrecognised key reports a
    * typo, which is the wrong thing to say about a key that is spelled
    * correctly and deliberately unavailable.
    */
  private val alwaysRejected: Map[String, String] = Map(
    "cutoff" ->
      "the analysis cutoff comes from the Spice Pass, or --cutoff when standalone, and cannot be set in a config file"
  )

  private case class Invalid(message: String) extends RuntimeException(message)

  /** Read a whole Goat Rodeo config file, plus the environment.
    *
    * Standalone, this is where the ladder is applied: defaults, then the
    * `[analysis]` table, then `GOATRODEO_ANALYSIS_*`. Command-line flags are
    * applied on top by [[ConfigurationParser]], which is the only part that
    * knows what a flag is.
    */
  def fromFile(
      path: Path,
      base: Configuration = Configuration(),
      environment: Map[String, String] = sys.env,
      report: String => Unit = message => logger.info(message)
  ): Either[String, Configuration] = {
    if (!Files.exists(path)) Left(s"config file not found: $path")
    else {
      val result = Toml.parse(Files.readString(path))
      if (!result.errors().isEmpty())
        Left(result.errors().asScala.map(_.toString).mkString("; "))
      else {
        val root = TomlTables.toPlainMap(result)
        val loose = root.asScala
          .collect {
            case (key, value) if !value.isInstanceOf[java.util.Map[?, ?]] => key
          }
          .toSeq
          .sorted
        if (loose.nonEmpty)
          Left(
            s"settings belong in a table: move ${loose.mkString(", ")} under [$Group]"
          )
        else {
          val resolved = new Resolver(
            EnvironmentPrefix,
            java.util.Set.of(Group, io.spicelabs.config.Logging.GROUP),
            message => report(message)
          )
            .withFile(path, root, java.util.List.of())
            .withEnvironment(environment.asJava)
            .resolve()
          fromResolution(resolved, base, Group).map(
            _.copy(logging =
              plainGroup(resolved, io.spicelabs.config.Logging.GROUP)
            )
          )
        }
      }
    }
  }

  /** Read a Goat Rodeo configuration from a table.
    *
    * @param label
    *   how to name this table in error messages. An embedding program passes
    *   the path it used — `registry.analysis`, say — so a user reads about the
    *   table they wrote rather than about an internal component name.
    */
  def fromToml(
      table: TomlTable,
      base: Configuration = Configuration(),
      label: String = ""
  ): Either[String, Configuration] =
    fromResolution(
      Resolution.of(
        java.util.Map.of(Group, TomlTables.toPlainMap(table)),
        Origin.embedded(if (label.isEmpty) Group else label)
      ),
      base,
      label
    )

  /** Read a table nested inside another program's config file. */
  def nestedFromToml(
      table: TomlTable,
      base: Configuration,
      label: String
  ): Either[String, Configuration] =
    fromToml(table, base, label)

  /** Read settings whose value has already been decided.
    *
    * The one reader, whether the values came from this program's own file and
    * environment or from a host that resolved them and passed them in. Both
    * arrive as a [[Resolution]], so there is no "am I embedded?" branch in the
    * reading itself — only in what is allowed to appear.
    */
  def fromResolution(
      resolved: Resolution,
      base: Configuration = Configuration(),
      label: String = ""
  ): Either[String, Configuration] = {
    val prefix = if (label.isEmpty) "" else s"[$label] "
    val keys = resolved.group(Group).keySet().asScala.toSet
    val unknown = keys.diff(knownKeys)
    val rejected = keys.intersect(alwaysRejected.keySet)

    if (unknown.nonEmpty)
      Left(
        s"${prefix}unknown ${plural(unknown.size, "key")}: ${unknown.toSeq.sorted.mkString(", ")}"
      )
    else if (rejected.nonEmpty)
      Left(
        rejected.toSeq.sorted
          .map(key =>
            s"$prefix$key is not settable here — ${alwaysRejected(key)}"
          )
          .mkString("; ")
      )
    else {
      try Right(read(resolved, base))
      catch {
        case Invalid(message) => Left(prefix + message)
        // A value that cannot be read names itself and the source it came from,
        // which is more use than anything this layer could add.
        case e: ConfigurationException => Left(e.getMessage)
      }
    }
  }

  /** A resolved group as a plain Scala map, for a group this program carries
    * but does not interpret.
    */
  private def plainGroup(
      resolved: Resolution,
      group: String
  ): Map[String, Any] =
    resolved.group(group).asScala.toMap.map { case (k, v) => k -> (v: Any) }

  private def read(table: Resolution, base: Configuration): Configuration = {
    var config = base
    str(table, "out").foreach(v => config = config.copy(out = Some(file(v))))
    strs(table, "build").foreach(vs =>
      config = config.copy(build = config.build ++ vs.map(file))
    )
    strs(table, "file_list").foreach(vs =>
      config = config.copy(fileList = config.fileList ++ vs.map(file))
    )
    str(table, "ingested").foreach(v =>
      config = config.copy(ingested = Some(file(v)))
    )
    strs(table, "ignore").foreach(vs =>
      config = config.copy(ignore = config.ignore ++ vs.map(file))
    )
    str(table, "block_list").foreach(v =>
      config = config.copy(blockList = Some(file(v)))
    )
    strs(table, "exclude_pattern").foreach(vs =>
      config = config.copy(exclude =
        config.exclude ++ vs.map(p => p -> Try(Pattern.compile(p)))
      )
    )
    int(table, "threads").foreach { v =>
      if (v < 1) throw Invalid(s"threads must be >= 1, got $v")
      config = config.copy(threads = v)
    }
    int(table, "max_records").foreach { v =>
      if (v <= 100) throw Invalid(s"max_records must be > 100, got $v")
      config = config.copy(maxRecords = v)
    }
    str(table, "temp_dir").foreach(v =>
      config = config.copy(tempDir = Some(file(v)))
    )
    bool(table, "static_metadata").foreach(v =>
      config = config.copy(useStaticMetadata = v)
    )
    bool(table, "fs_file_paths").foreach(v =>
      config = config.copy(fsFilePaths = v)
    )
    str(table, "dump_roots").foreach(v =>
      config = config.copy(dumpRootDir = Some(file(v)))
    )
    str(table, "dump_json").foreach(v =>
      config = config.copy(emitJsonDir = Some(file(v)))
    )
    strs(table, "mime_filter").foreach(vs =>
      config = config.copy(mimeFilter = vs.foldLeft(config.mimeFilter)(_ :+ _))
    )
    str(table, "tag").foreach(v => config = config.copy(tag = Some(v)))
    str(table, "tag_json").foreach { v =>
      val json = Try(Json.decode(v.getBytes("UTF-8")).to[Dom.Element].value)
      json match {
        case scala.util.Success(value) =>
          config = config.copy(tagJson = Some(value))
        case scala.util.Failure(_) =>
          throw Invalid(s"tag_json is not valid JSON: $v")
      }
    }
    str(table, "tag_version").foreach(v =>
      config = config.copy(tagVersion = Some(v))
    )
    str(table, "tag_date").foreach { v =>
      DateParser.parse(v) match {
        case Right(date) => config = config.copy(tagDate = Some(date))
        case Left(error) => throw Invalid(s"tag_date: $error")
      }
    }
    bool(table, "package_tags").foreach(v =>
      config = config.copy(packageTags = v)
    )
    bool(table, "package_tags_short_name").foreach(v =>
      config = config.copy(packageTagsShortName = v)
    )
    str(table, "emit_cbom_dir").foreach(v =>
      config = config.copy(cbomDir = Some(file(v)))
    )
    str(table, "cbom_version").foreach { v =>
      if (!Set("1.6", "1.7").contains(v))
        throw Invalid(s"cbom_version must be 1.6 or 1.7, got $v")
      config = config.copy(cbomVersion = v)
    }
    config
  }

  /** Config files may be read from a directory the process cannot see — a
    * container mount, say — so a relative path in one has no dependable
    * meaning. Requiring absolute paths also lets the `spice` wrapper keep its
    * guarantee that every path it must bind-mount is visible on the command
    * line, since it cannot see inside a TOML table.
    */
  private def file(value: String): File = {
    val f = File(value)
    if (!f.isAbsolute())
      throw Invalid(s"paths in a config file must be absolute, got: $value")
    f
  }

  private def setting(table: Resolution, key: String): Option[Setting] =
    table.setting(Group, key).toScala

  private def str(table: Resolution, key: String): Option[String] =
    setting(table, key).map(_.asString)

  private def strs(table: Resolution, key: String): Option[Vector[String]] =
    setting(table, key).map(_.asStringList.asScala.toVector)

  private def int(table: Resolution, key: String): Option[Int] =
    setting(table, key).map(_.asLong.toInt)

  private def bool(table: Resolution, key: String): Option[Boolean] =
    setting(table, key).map(_.asBoolean)

  private def plural(n: Int, word: String): String =
    if (n == 1) word else s"${word}s"
}
