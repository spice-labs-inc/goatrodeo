package io.spicelabs.goatrodeo.util

import io.bullet.borer.Dom
import io.bullet.borer.Json
import io.spicelabs.config.ConfigurationException
import io.spicelabs.config.Logging
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

  /** The config-file key each [[Configuration]] field is written as.
    *
    * WHY this is written out rather than derived: the flag, the key and the
    * environment variable are three spellings of one name, related mechanically
    * — but the *field* is a fourth, and it is not related to them at all.
    * `emitJsonDir` is `dump_json`, `cbomDir` is `emit_cbom_dir`,
    * `useStaticMetadata` is `static_metadata`. Anything that turns a field name
    * into a key by rule would be wrong for a third of them.
    *
    * Used to report an overridden setting under the name its writer used.
    * Fields absent here have no config-file key — a flag-only setting, or
    * `runtime`, which is not a setting at all.
    */
  private val keyForField: Map[String, String] = Map(
    "out" -> "out",
    "build" -> "build",
    "fileList" -> "file_list",
    "ingested" -> "ingested",
    "ignore" -> "ignore",
    "blockList" -> "block_list",
    "exclude" -> "exclude_pattern",
    "threads" -> "threads",
    "maxRecords" -> "max_records",
    "tempDir" -> "temp_dir",
    "useStaticMetadata" -> "static_metadata",
    "fsFilePaths" -> "fs_file_paths",
    "dumpRootDir" -> "dump_roots",
    "emitJsonDir" -> "dump_json",
    "mimeFilter" -> "mime_filter",
    "tag" -> "tag",
    "tagJson" -> "tag_json",
    "tagVersion" -> "tag_version",
    "tagDate" -> "tag_date",
    "redactGitInfo" -> "redact_git_info",
    "packageTags" -> "package_tags",
    "packageTagsShortName" -> "package_tags_short_name",
    "cbomDir" -> "emit_cbom_dir",
    "cbomVersion" -> "cbom_version",
    "logFilenames" -> "log_filenames",
    "tamperEvidentLog" -> "tamper_evident_log"
  )

  /** How a setting should be named in a message, given the field it lives in.
    *
    * Its config-file key where it has one, so that the three-spellings rule
    * holds in output too; the field name otherwise, which is the best available
    * name for something a file cannot set.
    */
  def displayName(field: String): String = keyForField.getOrElse(field, field)

  /** Where the value of a setting came from, named as the person who set it
    * would recognise it — `GOATRODEO_ANALYSIS_THREADS`, or `[analysis] in
    * /etc/goatrodeo.toml`.
    *
    * `None` for a setting no source supplied, which is every setting left at
    * its default.
    */
  def sourceOf(resolved: Resolution, field: String): Option[String] =
    keyForField
      .get(field)
      .flatMap(key => resolved.setting(Group, key).toScala)
      .map(_.origin.describe)

  /** The config-file keys [[keyForField]] claims exist, so that a test can hold
    * it against the schema rather than trusting two hand-written lists to
    * agree.
    */
  def fieldKeys: Set[String] = keyForField.values.toSet

  /** Whether the `[analysis]` table accepts this key. */
  def accepts(key: String): Boolean = knownKeys.contains(key)

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
    "redact_git_info",
    "log_filenames",
    "tamper_evident_log",
    "package_tags",
    "package_tags_short_name",
    "emit_cbom_dir",
    "cbom_version"
  )

  /** Read the environment, and a config file if there is one.
    *
    * Standalone, this is where the ladder is applied: defaults, then the
    * `[analysis]` table, then `GOATRODEO_ANALYSIS_*`. Command-line flags are
    * applied on top by [[ConfigurationParser]], which is the only part that
    * knows what a flag is.
    *
    * WHY the file is optional rather than the way in: the environment is a
    * source in its own right, and making it reachable only through `--config`
    * meant `GOATRODEO_ANALYSIS_THREADS` silently did nothing on a run that
    * named no file — which is every run that only wanted to set one thing.
    * Every source is now consulted on every run, and the file is the one that
    * may be absent.
    *
    * The [[Resolution]] is returned alongside the configuration because it is
    * the only thing that knows *where* each value came from; [[Configuration]]
    * records values, not their origins, and the override reporting needs both.
    */
  def fromSources(
      path: Option[Path],
      base: Configuration = Configuration(),
      environment: Map[String, String] = sys.env,
      report: String => Unit = message => logger.info(message)
  ): Either[String, (Configuration, Resolution)] = {
    val resolver = new Resolver(
      EnvironmentPrefix,
      java.util.Set.of(Group, Logging.GROUP),
      message => report(message)
    )

    val withFile: Either[String, Resolver] = path match {
      case None => Right(resolver)
      case Some(path) if !Files.exists(path) =>
        Left(s"config file not found: $path")
      case Some(path) =>
        val result = Toml.parse(Files.readString(path))
        if (!result.errors().isEmpty())
          Left(result.errors().asScala.map(_.toString).mkString("; "))
        else {
          val root = TomlTables.toPlainMap(result)
          val loose = root.asScala
            .collect {
              case (key, value) if !value.isInstanceOf[java.util.Map[?, ?]] =>
                key
            }
            .toSeq
            .sorted
          if (loose.nonEmpty)
            Left(
              s"settings belong in a table: move ${loose.mkString(", ")} under [$Group]"
            )
          else Right(resolver.withFile(path, root, java.util.List.of()))
        }
    }

    withFile.flatMap { resolver =>
      val resolved = resolver.withEnvironment(environment.asJava).resolve()
      // `[logging]` is carried, not interpreted — but "carried" must not mean
      // "unchecked". The resolver filters by group and accepts any key inside
      // one, so without this a mistyped `levl` would travel all the way to the
      // program that applies it and be dropped there in silence: the same
      // failure the `[analysis]` schema exists to prevent, one table over.
      val unknownLogging = Logging.unknownKeys(resolved).asScala.toSeq
      if (unknownLogging.nonEmpty)
        Left(
          s"[${Logging.GROUP}] unknown ${plural(unknownLogging.size, "key")}: ${unknownLogging.mkString(", ")}"
        )
      else
        fromResolution(resolved, base, Group)
          .map(_.copy(logging = plainGroup(resolved, Logging.GROUP)))
          .map((_, resolved))
    }
  }

  /** Read a whole Goat Rodeo config file, plus the environment. */
  def fromFile(
      path: Path,
      base: Configuration = Configuration(),
      environment: Map[String, String] = sys.env,
      report: String => Unit = message => logger.info(message)
  ): Either[String, Configuration] =
    fromSources(Some(path), base, environment, report).map((config, _) =>
      config
    )

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

    if (unknown.nonEmpty)
      Left(
        s"${prefix}unknown ${plural(unknown.size, "key")}: ${unknown.toSeq.sorted.mkString(", ")}"
      )
    else {
      // The value validations inside `read` are values (Left), never thrown;
      // this catch is only for the TOML library's own ConfigurationException,
      // which names itself and the source it came from.
      val readResult =
        try read(resolved, base)
        catch {
          case e: ConfigurationException => Left(e.getMessage)
        }
      readResult match {
        case Right(c)      => Right(c)
        case Left(message) => Left(prefix + message)
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

  private def read(
      table: Resolution,
      base: Configuration
  ): Either[String, Configuration] = {
    var result: Either[String, Configuration] = Right(base)

    /** Apply `f` to the accumulator while no error has been raised. */
    def run(f: Configuration => Either[String, Configuration]): Unit = {
      result = result.flatMap(f)
    }

    run(c =>
      str(table, "out").fold(Right(c))(v =>
        absFile(v).map(f => c.copy(out = Some(f)))
      )
    )
    run(c =>
      strs(table, "build").fold(Right(c))(vs =>
        absFiles(vs).map(fs => c.copy(build = c.build ++ fs))
      )
    )
    run(c =>
      strs(table, "file_list").fold(Right(c))(vs =>
        absFiles(vs).map(fs => c.copy(fileList = c.fileList ++ fs))
      )
    )
    run(c =>
      str(table, "ingested").fold(Right(c))(v =>
        absFile(v).map(f => c.copy(ingested = Some(f)))
      )
    )
    run(c =>
      strs(table, "ignore").fold(Right(c))(vs =>
        absFiles(vs).map(fs => c.copy(ignore = c.ignore ++ fs))
      )
    )
    run(c =>
      str(table, "block_list").fold(Right(c))(v =>
        absFile(v).map(f => c.copy(blockList = Some(f)))
      )
    )
    run(c =>
      strs(table, "exclude_pattern").fold(Right(c))(vs =>
        Right(
          c.copy(exclude =
            c.exclude ++ vs.map(p => p -> Try(Pattern.compile(p)))
          )
        )
      )
    )
    run(c =>
      int(table, "threads").fold(Right(c))(v =>
        if (v < 1) Left(s"threads must be >= 1, got $v")
        else Right(c.copy(threads = v))
      )
    )
    run(c =>
      int(table, "max_records").fold(Right(c))(v =>
        if (v <= 100) Left(s"max_records must be > 100, got $v")
        else Right(c.copy(maxRecords = v))
      )
    )
    run(c =>
      str(table, "temp_dir").fold(Right(c))(v =>
        absFile(v).map(f => c.copy(tempDir = Some(f)))
      )
    )
    run(c =>
      bool(table, "static_metadata").fold(Right(c))(v =>
        Right(c.copy(useStaticMetadata = v))
      )
    )
    run(c =>
      bool(table, "fs_file_paths").fold(Right(c))(v =>
        Right(c.copy(fsFilePaths = v))
      )
    )
    run(c =>
      str(table, "dump_roots").fold(Right(c))(v =>
        absFile(v).map(f => c.copy(dumpRootDir = Some(f)))
      )
    )
    run(c =>
      str(table, "dump_json").fold(Right(c))(v =>
        absFile(v).map(f => c.copy(emitJsonDir = Some(f)))
      )
    )
    run(c =>
      strs(table, "mime_filter").fold(Right(c))(vs =>
        Right(c.copy(mimeFilter = vs.foldLeft(c.mimeFilter)(_ :+ _)))
      )
    )
    run(c =>
      str(table, "tag").fold(Right(c))(v => Right(c.copy(tag = Some(v))))
    )
    run(c =>
      str(table, "tag_json").fold(Right(c))(v =>
        Try(Json.decode(v.getBytes("UTF-8")).to[Dom.Element].value) match {
          case scala.util.Success(elm) =>
            Right(c.copy(tagJson = Some(elm)))
          case scala.util.Failure(_) =>
            Left(s"tag_json is not valid JSON: $v")
        }
      )
    )
    run(c =>
      str(table, "tag_version").fold(Right(c))(v =>
        Right(c.copy(tagVersion = Some(v)))
      )
    )
    run(c =>
      str(table, "tag_date").fold(Right(c))(v =>
        DateParser.parse(v) match {
          case Right(date) => Right(c.copy(tagDate = Some(date)))
          case Left(error) => Left(s"tag_date: $error")
        }
      )
    )
    run(c =>
      bool(table, "redact_git_info").fold(Right(c))(v =>
        Right(c.copy(redactGitInfo = v))
      )
    )
    run(c =>
      bool(table, "log_filenames").fold(Right(c))(v =>
        Right(c.copy(logFilenames = v))
      )
    )
    run(c =>
      str(table, "tamper_evident_log").fold(Right(c))(v =>
        absFile(v).map(f => c.copy(tamperEvidentLog = Some(f)))
      )
    )
    run(c =>
      bool(table, "package_tags").fold(Right(c))(v =>
        Right(c.copy(packageTags = v))
      )
    )
    run(c =>
      bool(table, "package_tags_short_name").fold(Right(c))(v =>
        Right(c.copy(packageTagsShortName = v))
      )
    )
    run(c =>
      str(table, "emit_cbom_dir").fold(Right(c))(v =>
        absFile(v).map(f => c.copy(cbomDir = Some(f)))
      )
    )
    run(c =>
      str(table, "cbom_version").fold(Right(c))(v =>
        if (!Set("1.6", "1.7").contains(v))
          Left(s"cbom_version must be 1.6 or 1.7, got $v")
        else Right(c.copy(cbomVersion = v))
      )
    )
    result
  }

  /** A config-file path must be absolute (the process may not see the caller's
    * working directory). Failure is a value, never thrown.
    */
  private def absFile(value: String): Either[String, File] = {
    val f = File(value)
    if (!f.isAbsolute())
      Left(s"paths in a config file must be absolute, got: $value")
    else Right(f)
  }

  private def absFiles(
      values: Vector[String]
  ): Either[String, Vector[File]] =
    values.foldLeft(Right(Vector.empty[File]): Either[String, Vector[File]])(
      (acc, v) => acc.flatMap(fs => absFile(v).map(f => fs :+ f))
    )

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
