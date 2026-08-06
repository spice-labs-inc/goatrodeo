package io.spicelabs.goatrodeo.util

import io.bullet.borer.Dom
import io.bullet.borer.Json
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
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
  * Keys are the snake_case spelling of the corresponding command-line flag, so
  * `--maxrecords` is `max_records` and `--fs-file-paths` is `fs_file_paths`.
  */
object ConfigurationToml {

  /** Keys accepted at the top level of a Goat Rodeo config file. */
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
    "expiry"
  )

  /** Keys an *embedded* Goat Rodeo may not be given.
    *
    * `expiry` is Goat Rodeo's own knob when it runs standalone — it knows
    * nothing about Spice Passes. Embedded in `spice` or Allspice it is the
    * pass's `x-cutoff`, which constrains what the platform will accept; letting
    * a config file supply it there would hand a user the ability to widen a
    * scope the platform deliberately narrowed.
    */
  private val nestedOnlyRejected: Map[String, String] = Map(
    "expiry" ->
      "the analysis cutoff comes from the Spice Pass and cannot be set in a config file"
  )

  private case class Invalid(message: String) extends RuntimeException(message)

  /** Read a whole Goat Rodeo config file. */
  def fromFile(
      path: Path,
      base: Configuration = Configuration()
  ): Either[String, Configuration] = {
    if (!Files.exists(path)) Left(s"config file not found: $path")
    else {
      val result = Toml.parse(Files.readString(path))
      if (!result.errors().isEmpty())
        Left(result.errors().asScala.map(_.toString).mkString("; "))
      else fromToml(result, base)
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
      label: String = "",
      nested: Boolean = false
  ): Either[String, Configuration] = {
    val prefix = if (label.isEmpty) "" else s"[$label] "
    val keys = table.keySet().asScala.toSet
    val unknown = keys.diff(knownKeys)
    val rejected =
      if (nested) keys.intersect(nestedOnlyRejected.keySet)
      else Set.empty[String]

    if (unknown.nonEmpty)
      Left(
        s"${prefix}unknown ${plural(unknown.size, "key")}: ${unknown.toSeq.sorted.mkString(", ")}"
      )
    else if (rejected.nonEmpty)
      Left(
        rejected.toSeq.sorted
          .map(key =>
            s"$prefix$key is not settable here — ${nestedOnlyRejected(key)}"
          )
          .mkString("; ")
      )
    else {
      try Right(read(table, base))
      catch { case Invalid(message) => Left(prefix + message) }
    }
  }

  /** Read a table nested inside another program's config file. */
  def nestedFromToml(
      table: TomlTable,
      base: Configuration,
      label: String
  ): Either[String, Configuration] =
    fromToml(table, base, label, nested = true)

  private def read(table: TomlTable, base: Configuration): Configuration = {
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
    str(table, "expiry").foreach { v =>
      ConfigurationParser.parseExpiry(v) match {
        case Some(instant) => config = config.copy(expiry = Some(instant))
        case None          => throw Invalid(s"expiry is not a date/time: $v")
      }
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

  private def valueOf(table: TomlTable, key: String): Option[Any] =
    Option(table.get(key)).map(_.asInstanceOf[Any])

  private def str(table: TomlTable, key: String): Option[String] =
    valueOf(table, key).map {
      case s: String => s
      case other =>
        throw Invalid(s"$key must be a string, got ${typeName(other)}")
    }

  private def strs(table: TomlTable, key: String): Option[Vector[String]] =
    valueOf(table, key).map {
      case a: TomlArray =>
        a.toList().asScala.toVector.map {
          case s: String => s
          case other =>
            throw Invalid(
              s"$key must be an array of strings, got an array of ${typeName(other)}"
            )
        }
      case other =>
        throw Invalid(
          s"$key must be an array of strings, got ${typeName(other)}"
        )
    }

  private def int(table: TomlTable, key: String): Option[Int] =
    valueOf(table, key).map {
      case l: java.lang.Long => l.intValue()
      case other =>
        throw Invalid(s"$key must be an integer, got ${typeName(other)}")
    }

  private def bool(table: TomlTable, key: String): Option[Boolean] =
    valueOf(table, key).map {
      case b: java.lang.Boolean => b.booleanValue()
      case other =>
        throw Invalid(s"$key must be true or false, got ${typeName(other)}")
    }

  private def typeName(value: Any): String = value match {
    case _: String            => "a string"
    case _: java.lang.Long    => "an integer"
    case _: java.lang.Double  => "a float"
    case _: java.lang.Boolean => "a boolean"
    case _: TomlArray         => "an array"
    case _: TomlTable         => "a table"
    case other                => other.getClass().getSimpleName()
  }

  private def plural(n: Int, word: String): String =
    if (n == 1) word else s"${word}s"
}
