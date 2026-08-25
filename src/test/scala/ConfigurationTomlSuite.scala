import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.ConfigurationParser
import io.spicelabs.goatrodeo.util.ConfigurationToml
import io.spicelabs.goatrodeo.util.TomlTables
import org.tomlj.Toml
import org.tomlj.TomlTable

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** WHAT: covers reading a [[Configuration]] from TOML — the schema itself, the
  * precedence of the command line over the file, the rules that keep a config
  * file from becoming a second way to say something it should not, and the
  * map-backed [[TomlTables]] adapter the plugin SPI hands tables through.
  *
  * WHY: this schema is now published in three places — the parser, the docs,
  * and the templates Allspice generates. Tests are what stop those drifting.
  * The previous cross-program configuration channel failed exactly there: an
  * allowlist of Goat Rodeo flags kept inside Allspice drifted until it
  * permitted flags Goat Rodeo does not have, and nothing noticed because
  * nothing checked.
  */
class ConfigurationTomlSuite extends munit.FunSuite {

  private def parse(toml: String): TomlTable = {
    val result = Toml.parse(toml)
    assert(
      result.errors().isEmpty(),
      s"fixture is not valid TOML: ${result.errors()}"
    )
    result
  }

  private def read(toml: String): Either[String, Configuration] =
    ConfigurationToml.fromToml(parse(toml))

  private def readOk(toml: String): Configuration =
    read(toml) match {
      case Right(config) => config
      case Left(error)   => fail(s"expected success, got: $error")
    }

  // ==================== the schema ====================

  test("every scalar key round-trips into the configuration") {
    val config = readOk("""
      |out = "/tmp/out"
      |threads = 12
      |max_records = 5000
      |temp_dir = "/tmp/scratch"
      |static_metadata = true
      |fs_file_paths = true
      |tag = "nightly"
      |tag_version = "1.2.3"
      |package_tags = true
      |package_tags_short_name = true
      |cbom_version = "1.7"
      |""".stripMargin)

    assertEquals(config.out.map(_.getPath()), Some("/tmp/out"))
    assertEquals(config.threads, 12)
    assertEquals(config.maxRecords, 5000)
    assertEquals(config.tempDir.map(_.getPath()), Some("/tmp/scratch"))
    assertEquals(config.useStaticMetadata, true)
    assertEquals(config.fsFilePaths, true)
    assertEquals(config.tag, Some("nightly"))
    assertEquals(config.tagVersion, Some("1.2.3"))
    assertEquals(config.packageTags, true)
    assertEquals(config.packageTagsShortName, true)
    assertEquals(config.cbomVersion, "1.7")
  }

  test("array keys accumulate onto the base configuration") {
    val config = readOk("""
      |build = ["/srv/a", "/srv/b"]
      |mime_filter = ["+application/java-archive"]
      |exclude_pattern = ["html$"]
      |""".stripMargin)

    assertEquals(config.build.map(_.getPath()), Vector("/srv/a", "/srv/b"))
    assertEquals(config.exclude.map(_._1), Vector("html$"))
    assert(
      config.mimeFilter.shouldInclude(Set("application/java-archive")),
      "the mime filter from the file must be in force"
    )
  }

  test("a key the schema does not have is an error, not silence") {
    // Silently ignoring a typo is how a config file becomes undebuggable: the
    // value the user wrote is simply not in force and nothing says so.
    val error = read("thraeds = 4").left.getOrElse(fail("expected an error"))
    assert(error.contains("unknown key"), error)
    assert(error.contains("thraeds"), error)
  }

  test("a key of the wrong type names the key and both types") {
    val error =
      read("""threads = "many"""").left.getOrElse(fail("expected an error"))
    assert(error.contains("threads"), error)
    assert(error.contains("whole number"), error)
    // and quotes the value it could not read
    assert(error.contains("many"), error)
  }

  test("relative paths are rejected") {
    // The `spice` wrapper bind-mounts paths it reads off the command line before
    // the JVM starts; it cannot see inside a TOML table, so a relative path here
    // has no dependable meaning.
    val error =
      read("""out = "relative/dir"""").left.getOrElse(fail("expected an error"))
    assert(error.contains("absolute"), error)
  }

  test("validation matches the flags: threads >= 1, cbom_version 1.6 or 1.7") {
    assert(read("threads = 0").isLeft)
    assert(read("""cbom_version = "1.5"""").isLeft)
    assert(read("""cbom_version = "1.6"""").isRight)
  }

  // ==================== nesting ====================

  test("cutoff is refused in Goat Rodeo's own config file too") {
    // An entitlement, not a preference: --cutoff is the only way to ask for one when
    // standalone, so that the embedded rule has no second spelling to be forgotten by.
    val error = read("""cutoff = "2026-01-01"""").left
      .getOrElse(fail("expected an error"))
    assert(error.contains("cutoff"), error)
    assert(error.contains("not settable here"), error)
  }

  test("cutoff is refused in a table nested inside another program's config") {
    // Embedded, the cutoff is the Spice Pass's `x-cutoff`: it constrains what the
    // platform will accept, so a config file must not be able to widen it.
    val result = ConfigurationToml.nestedFromToml(
      parse("""cutoff = "2026-01-01""""),
      Configuration(),
      "registry.analysis"
    )
    val error = result.left.getOrElse(fail("expected an error"))
    assert(error.contains("Spice Pass"), error)
    assert(error.contains("registry.analysis"), error)
  }

  test("errors name the table the user wrote, not an internal component") {
    val error = ConfigurationToml
      .nestedFromToml(
        parse("nonsense = 1"),
        Configuration(),
        "registry.analysis"
      )
      .left
      .getOrElse(fail("expected an error"))
    assert(error.contains("[registry.analysis]"), error)
    assert(
      !error.toLowerCase().contains("goat"),
      s"leaked an internal name: $error"
    )
  }

  // ==================== precedence ====================

  test("the command line beats the config file") {
    withConfigFile("[analysis]\nthreads = 4\nmax_records = 999999\n") { path =>
      val config = ConfigurationParser
        .parse(Array("--config", path.toString, "--threads", "9"))
        .getOrElse(fail("expected a parse"))
      assertEquals(config.threads, 9, "the flag must win")
      assertEquals(
        config.maxRecords,
        999999,
        "the file still supplies the rest"
      )
    }
  }

  test(
    "an overridden setting is reported against the source that supplied it"
  ) {
    // The file and the environment are resolved together, so crediting the file
    // for everything the command line displaces names the wrong loser whenever
    // the value came from a variable.
    val reported = scala.collection.mutable.ArrayBuffer[String]()
    withConfigFile("[analysis]\nthreads = 4\nmax_records = 999999\n") { path =>
      ConfigurationToml
        .fromSources(
          Some(path),
          Configuration(),
          environment = Map("GOATRODEO_ANALYSIS_THREADS" -> "9"),
          report = reported.append(_)
        ) match {
        case Right((resolved, resolution)) =>
          assertEquals(
            ConfigurationToml.sourceOf(resolution, "threads"),
            Some("GOATRODEO_ANALYSIS_THREADS"),
            "the environment set this one last"
          )
          assertEquals(
            ConfigurationToml.sourceOf(resolution, "maxRecords"),
            Some(s"[analysis] in $path"),
            "and the file set this one"
          )
          assertEquals(
            ConfigurationToml.sourceOf(resolution, "tag"),
            None,
            "a setting left at its default has no source to name"
          )
        case Left(error) => fail(error)
      }
    }
  }

  test("an overridden setting is named as its writer spelled it") {
    // Configuration's field names are a fourth spelling, unrelated to the three
    // the documentation promises. Reporting `maxRecords` describes a name that
    // appears in no file, flag or variable.
    assertEquals(ConfigurationToml.displayName("maxRecords"), "max_records")
    assertEquals(ConfigurationToml.displayName("emitJsonDir"), "dump_json")
    assertEquals(ConfigurationToml.displayName("cbomDir"), "emit_cbom_dir")
  }

  test("every setting a config file may name is one the schema accepts") {
    // The mirror of the test below. That one catches a key claimed by
    // keyForField that knownKeys does not accept; this one catches a field
    // added to Configuration that keyForField never mentions -- which is how a
    // setting ends up with a command-line flag and no config-file or
    // environment spelling without anyone deciding it should be flag-only.
    //
    // The exemptions are written out, with the reason, so that adding a field
    // means making the choice rather than inheriting it by silence.
    val exempt: Map[String, String] = Map(
      "cutoff" ->
        "an entitlement, not a preference: --cutoff only, and alwaysRejected refuses it by name",
      "configFile" -> "how the run was started, not a setting anybody wrote",
      "runtime" -> "the ambient process state, not a setting",
      "logging" ->
        "carried for another program to apply; validated against the shared [logging] schema",
      "progressListener" -> "a callback; reachable only through the builder",
      "componentArgs" -> "flag-only diagnostic",
      "printComponentInfo" -> "flag-only diagnostic",
      "printComponentArgumentInfo" -> "flag-only diagnostic",
      "nonexistentDirectories" -> "derived while parsing, never supplied"
    )

    val unaccounted = Configuration().productElementNames.toVector
      .filterNot(ConfigurationToml.mappedFields.contains)
      .filterNot(exempt.contains)

    assert(
      unaccounted.isEmpty,
      s"${unaccounted.mkString(", ")} can be set on the command line but named " +
        "by no config-file key. Either give it one in keyForField and knownKeys, " +
        "or add it to this test's `exempt` map with the reason it is flag-only."
    )

    // and the exemptions must stay real: a field removed or renamed should not
    // leave a stale excuse behind.
    val stale = exempt.keySet.diff(Configuration().productElementNames.toSet)
    assert(
      stale.isEmpty,
      s"exempt names no such field: ${stale.mkString(", ")}"
    )
  }

  test("a key refused by name is a key the schema knows about") {
    // The point of alwaysRejected is to say "you spelled this correctly and it
    // is deliberately unavailable" rather than "unknown key". That only works
    // while the key is also in knownKeys -- otherwise the unknown-key check
    // fires first and reports it as a typo. Today only `cutoff` is involved,
    // and it was put in both sets by hand.
    val notKnown = ConfigurationToml.rejectedKeys.filterNot(
      ConfigurationToml.accepts
    )
    assert(
      notKnown.isEmpty,
      s"${notKnown.mkString(", ")} would be reported as a typo rather than refused by name"
    )
  }

  test("no two fields claim the same config-file key") {
    // Two fields sharing a key would make sourceOf report one field's origin
    // for the other, silently, and a file setting the key would write to
    // whichever handler `read` happens to run.
    val keys = ConfigurationToml.fieldKeys
    assertEquals(
      keys.size,
      ConfigurationToml.mappedFields.size,
      "keyForField maps two fields onto one key"
    )
  }

  test("every field with a config-file key names a key that exists") {
    // The map is written out by hand because the relation is not mechanical;
    // this is what stops it drifting from the schema beside it.
    ConfigurationToml.fieldKeys.foreach { key =>
      assert(
        ConfigurationToml.accepts(key),
        s"$key is reported as a config key but the schema does not accept it"
      )
    }
  }

  test("--config records the file it read") {
    withConfigFile("[analysis]\nthreads = 4\n") { path =>
      val config = ConfigurationParser
        .parse(Array("--config", path.toString))
        .getOrElse(fail("expected a parse"))
      assertEquals(config.configFile.map(_.toPath()), Some(path))
    }
  }

  test(
    "a config file that does not parse fails the run rather than being ignored"
  ) {
    withConfigFile("[analysis]\nthreads = \n") { path =>
      assertEquals(
        ConfigurationParser.parse(Array("--config", path.toString)),
        None
      )
    }
  }

  // ==================== the environment ====================

  test("the environment supplies settings under the component's own prefix") {
    withConfigFile("[analysis]\nthreads = 4\n") { path =>
      val config = ConfigurationToml
        .fromFile(
          path,
          Configuration(),
          environment = Map("GOATRODEO_ANALYSIS_MAX_RECORDS" -> "12345")
        )
        .getOrElse(fail("expected a configuration"))
      assertEquals(config.maxRecords, 12345, "the environment supplies it")
      assertEquals(config.threads, 4, "and the file still supplies the rest")
    }
  }

  test("the environment beats the config file, and says so") {
    val reported = scala.collection.mutable.ArrayBuffer[String]()
    withConfigFile("[analysis]\nthreads = 4\n") { path =>
      val config = ConfigurationToml
        .fromFile(
          path,
          Configuration(),
          environment = Map("GOATRODEO_ANALYSIS_THREADS" -> "9"),
          report = reported.append(_)
        )
        .getOrElse(fail("expected a configuration"))
      assertEquals(config.threads, 9)
      assertEquals(reported.size, 1, s"expected one report, got: $reported")
      assert(
        reported.head.contains("GOATRODEO_ANALYSIS_THREADS"),
        reported.head
      )
      assert(reported.head.contains("overrides"), reported.head)
    }
  }

  test("the environment is read on a run that names no config file") {
    // The environment is a source in its own right. Reaching it only through
    // --config meant a run that wanted to set one thing, and said so the way the
    // documentation describes, was silently ignored.
    val config = ConfigurationParser
      .parse(
        Array("--out", "/tmp/out"),
        environment = Map("GOATRODEO_ANALYSIS_THREADS" -> "9")
      )
      .getOrElse(fail("expected a parse"))
    assertEquals(config.threads, 9)
    assertEquals(config.configFile, None, "and there was no file")
  }

  test("the command line beats the environment, with no config file either") {
    val config = ConfigurationParser
      .parse(
        Array("--threads", "12"),
        environment = Map("GOATRODEO_ANALYSIS_THREADS" -> "9")
      )
      .getOrElse(fail("expected a parse"))
    assertEquals(config.threads, 12)
  }

  test("an unknown variable in a group this program claims is an error") {
    // The same rule as a mistyped key in the file, for the same reason: a
    // setting that is quietly not in force is how configuration becomes
    // undebuggable.
    assertEquals(
      ConfigurationParser.parse(
        Array("--out", "/tmp/out"),
        environment = Map("GOATRODEO_ANALYSIS_TREADS" -> "9")
      ),
      None
    )
  }

  test("a variable that names no group is not a setting") {
    // The wrapper's own variables share the namespace and must be left alone.
    withConfigFile("[analysis]\nthreads = 4\n") { path =>
      val config = ConfigurationToml
        .fromFile(
          path,
          Configuration(),
          environment =
            Map("GOATRODEO_IMAGE" -> "something", "PATH" -> "/usr/bin")
        )
        .getOrElse(fail("expected a configuration"))
      assertEquals(config.threads, 4)
    }
  }

  // ==================== the file's shape ====================

  test("settings outside a table are refused, not ignored") {
    // Bare keys at the root are how this file used to be written. Silently doing
    // nothing with them is exactly the failure this schema exists to prevent, so
    // the message says where they belong.
    withConfigFile("threads = 4\n") { path =>
      val error = ConfigurationToml
        .fromFile(path, Configuration())
        .left
        .getOrElse(fail("expected an error"))
      assert(error.contains("threads"), error)
      assert(error.contains("[analysis]"), error)
    }
  }

  test("the command line beats the file, and each difference is reported") {
    withConfigFile("[analysis]\nthreads = 4\nmax_records = 999999\n") { path =>
      val config = ConfigurationParser
        .parse(Array("--config", path.toString, "--threads", "9"))
        .getOrElse(fail("expected a parse"))
      assertEquals(config.threads, 9)
      assertEquals(config.maxRecords, 999999)
      assertEquals(
        config.differencesFrom(config.copy(threads = 4)).map(_._1),
        Vector("threads"),
        "and the difference the report is derived from is exactly that field"
      )
    }
  }

  test("an array of tables crosses as tables, not as plain maps") {
    // tomlj's contract lets a caller write toList().get(0).asInstanceOf[TomlTable],
    // and every other accessor here wraps nested values to honour it. toList did
    // not, which nothing could notice until a config file had an array of tables
    // in it — Allspice's [[repositories]] is the first.
    val table = TomlTables.fromMap(
      Map(
        "repositories" -> java.util.List.of(
          java.util.Map.of("id", "one"),
          java.util.Map.of("id", "two")
        )
      )
    )

    val entries = table.getArrayOrEmpty("repositories").toList()
    assertEquals(entries.size(), 2)
    entries.asScala.foreach { entry =>
      assert(
        entry.isInstanceOf[TomlTable],
        s"expected a TomlTable, got ${entry.getClass.getName}"
      )
    }
    assertEquals(
      entries.get(0).asInstanceOf[TomlTable].getString("id"),
      "one"
    )
  }

  test("text that names a number is read as one") {
    // Every environment value is a string, and the program reading a setting
    // out of a map cannot say "this one is a count" at the point it arrives.
    // This adapter never sees a real TOML file, so a string saying `16` can
    // only have come from somewhere that could not have written 16.
    val table = TomlTables.fromMap(
      Map(
        "threads" -> "16",
        "static_metadata" -> "true",
        "tag" -> "42"
      )
    )

    assertEquals(table.getLong("threads"), java.lang.Long.valueOf(16L))
    assertEquals(table.getBoolean("static_metadata"), java.lang.Boolean.TRUE)
    assertEquals(
      table.getString("tag"),
      "42",
      "and a setting that wants text still gets the text"
    )
  }

  // ==================== logging ====================

  test(
    "the logging group is read like any other, and is the same one everywhere"
  ) {
    withConfigFile(
      "[analysis]\nthreads = 4\n\n[logging]\nlevel = \"debug\"\n"
    ) { path =>
      val config = ConfigurationToml
        .fromFile(path, Configuration(), environment = Map.empty)
        .getOrElse(fail("expected a configuration"))
      assertEquals(config.logging.get("level"), Some("debug"))
      assertEquals(config.threads, 4, "and the analysis group is unaffected")
    }
  }

  test("the environment supplies a log level, under this program's prefix") {
    withConfigFile("[analysis]\nthreads = 4\n") { path =>
      val config = ConfigurationToml
        .fromFile(
          path,
          Configuration(),
          environment = Map("GOATRODEO_LOGGING_LEVEL" -> "trace")
        )
        .getOrElse(fail("expected a configuration"))
      assertEquals(config.logging.get("level"), Some("trace"))
    }
  }

  test("a log level from the environment needs no config file") {
    // The variable is the whole point of the group for anyone running a
    // container: asking for one noisy run should not mean writing a file.
    val config = ConfigurationParser
      .parse(
        Array("--out", "/tmp/out"),
        environment = Map("GOATRODEO_LOGGING_LEVEL" -> "debug")
      )
      .getOrElse(fail("expected a parse"))
    assertEquals(config.logging.get("level"), Some("debug"))
  }

  test("a mistyped logging key is an error, not silence") {
    // This group is carried rather than interpreted, so nothing downstream is
    // in a position to complain about it: unchecked, `levl` would be dropped
    // wherever the level is finally applied, and nothing would say so.
    withConfigFile("[analysis]\nthreads = 4\n\n[logging]\nlevl = \"debug\"\n") {
      path =>
        val error = ConfigurationToml
          .fromFile(path, Configuration(), environment = Map.empty)
          .left
          .getOrElse(fail("expected an error"))
        assert(error.contains("levl"), error)
        assert(error.contains("unknown key"), error)
    }
  }

  test("a mistyped logging variable is an error too") {
    assertEquals(
      ConfigurationParser.parse(
        Array("--out", "/tmp/out"),
        environment = Map("GOATRODEO_LOGGING_LEVL" -> "debug")
      ),
      None
    )
  }

  test("the command line beats the file for logging too") {
    withConfigFile("[analysis]\nthreads = 4\n\n[logging]\nlevel = \"warn\"\n") {
      path =>
        val config = ConfigurationParser
          .parse(Array("--config", path.toString, "--log-level", "debug"))
          .getOrElse(fail("expected a parse"))
        assertEquals(config.logging.get("level"), Some("debug"))
    }
  }

  private def withConfigFile[A](contents: String)(f: Path => A): A = {
    val path = Files.createTempFile("goatrodeo-config", ".toml")
    try {
      Files.writeString(path, contents)
      f(path)
    } finally { Files.deleteIfExists(path); () }
  }

  // ==================== the map-backed adapter ====================

  test("a table survives the round trip through a plain map") {
    // This is the path a plugin's configuration takes: `spice` parses the file,
    // hands the plugin `toMap()`, and the plugin adapts it back. Anything lost
    // here is a setting that silently stops working when run under `spice`.
    val original = parse("""
      |out = "/tmp/out"
      |threads = 7
      |static_metadata = true
      |mime_filter = ["+a", "-b"]
      |
      |[nested]
      |inner = "value"
      |depth = 2
      |""".stripMargin)

    val adapted = TomlTables.fromJavaMap(TomlTables.toPlainMap(original))

    assertEquals(
      adapted.keySet().asScala.toSet,
      original.keySet().asScala.toSet
    )
    assertEquals(adapted.getString("out"), original.getString("out"))
    assertEquals(adapted.getLong("threads"), original.getLong("threads"))
    assertEquals(
      adapted.getBoolean("static_metadata"),
      original.getBoolean("static_metadata")
    )
    assertEquals(
      adapted.getArray("mime_filter").toList().asScala.toVector,
      original.getArray("mime_filter").toList().asScala.toVector
    )
    assertEquals(
      adapted.getTable("nested").getString("inner"),
      original.getTable("nested").getString("inner")
    )
    assertEquals(
      adapted.dottedKeySet().asScala.toSet,
      original.dottedKeySet().asScala.toSet
    )
  }

  test(
    "a configuration read through the adapter equals one read from the file"
  ) {
    val toml =
      """
        |out = "/tmp/out"
        |threads = 7
        |mime_filter = ["+application/java-archive"]
        |package_tags = true
        |""".stripMargin
    val direct = readOk(toml)
    val viaMap = ConfigurationToml
      .fromToml(TomlTables.fromJavaMap(TomlTables.toPlainMap(parse(toml))))
      .getOrElse(fail("expected success"))

    assertEquals(viaMap.out, direct.out)
    assertEquals(viaMap.threads, direct.threads)
    assertEquals(viaMap.packageTags, direct.packageTags)
    assertEquals(
      viaMap.mimeFilter.shouldInclude(Set("application/java-archive")),
      direct.mimeFilter.shouldInclude(Set("application/java-archive"))
    )
  }

  // ==================== the embedding seam ====================

  test("a table applied through the builder reaches the configuration") {
    // This is the whole point of the exercise: `spice` hands its
    // `[survey.inventory.analysis]` table over without knowing what is in it.
    val builder = io.spicelabs.goatrodeo.GoatRodeo
      .builder()
      .withThreads(2)
      .withConfiguration(
        TomlTables.toPlainMap(parse("threads = 11\nmax_records = 4242")),
        "survey.inventory.analysis"
      )
    val applied = builderConfig(builder)
    assertEquals(applied.threads, 11)
    assertEquals(applied.maxRecords, 4242)
  }

  test("the builder refuses a cutoff from an embedding program's config file") {
    val error = intercept[IllegalArgumentException] {
      io.spicelabs.goatrodeo.GoatRodeo
        .builder()
        .withConfiguration(
          TomlTables.toPlainMap(parse("cutoff = \"2026-01-01\"")),
          "survey.inventory.analysis"
        )
    }
    assert(error.getMessage().contains("Spice Pass"), error.getMessage())
  }

  test("the builder rejects an unknown key rather than ignoring it") {
    val error = intercept[IllegalArgumentException] {
      io.spicelabs.goatrodeo.GoatRodeo
        .builder()
        .withConfiguration(
          TomlTables.toPlainMap(parse("thraeds = 4")),
          "survey.inventory.analysis"
        )
    }
    assert(error.getMessage().contains("thraeds"), error.getMessage())
  }

  /** The builder keeps its configuration private; tests read it reflectively
    * rather than widening the API for their own convenience.
    */
  private def builderConfig(
      b: io.spicelabs.goatrodeo.GoatRodeoBuilder
  ): Configuration = {
    val field = classOf[io.spicelabs.goatrodeo.GoatRodeoBuilder]
      .getDeclaredField("config")
    field.setAccessible(true)
    field.get(b).asInstanceOf[Configuration]
  }

  test("an empty array claims no element type, matching tomlj") {
    val original = parse("empty = []")
    val adapted = TomlTables.fromJavaMap(TomlTables.toPlainMap(original))
    assertEquals(
      adapted.getArray("empty").containsStrings(),
      original.getArray("empty").containsStrings()
    )
    assertEquals(adapted.getArray("empty").isEmpty(), true)
  }
}
