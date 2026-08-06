# Configuration

Every value that affects a Goat Rodeo run lives in one strongly-typed record,
`io.spicelabs.goatrodeo.util.Configuration`. It is built once at startup and passed to
everything that needs it as a `using` parameter named `config`:

```scala
def buildDB(dest: File, tag: Option[TagInfo], …)(using config: Configuration): Unit
```

There are three ways to build one, and they produce the same value:

| Route | Entry point |
| --- | --- |
| Command line | `ConfigurationParser.parse(args)` — see `util/ConfigurationParser.scala` |
| Config file | `--config <file>`, read by `ConfigurationToml` |
| Embedded (library) | `GoatRodeoBuilder`, e.g. `GoatRodeo.builder().withOutput(…).run()` |

**There is no fourth route.** No environment variable and no system property configures Goat
Rodeo. If you find yourself reaching for one, add a field here and a flag there instead —
that is the whole point of this file.

Precedence, lowest to highest:

    defaults  <  config file  <  command line

## Values

| Value | Type | Flag | Builder | Default |
| --- | --- | --- | --- | --- |
| `out` | `Option[File]` | `-o`, `--out` | `withOutput` | — (required) |
| `build` | `Vector[File]` | `-b`, `--build` | `withPayload` | empty |
| `fileList` | `Vector[File]` | `--file-list` | `withFileList` | empty |
| `ingested` | `Option[File]` | `--ingested` | `withIngested` | none |
| `ignore` | `Vector[File]` | `--ignore` | `withIgnore` | empty |
| `blockList` | `Option[File]` | `--block` | `withBlockList` | none |
| `exclude` | `Vector[(String, Try[Pattern])]` | `--exclude-pattern` | `withExcludePattern` | empty |
| `threads` | `Int` | `-t`, `--threads` | `withThreads` | `4` |
| `maxRecords` | `Int` | `--maxrecords` | `withMaxRecords` | `50000` |
| `tempDir` | `Option[File]` | `--tempdir` | `withTempDir` | none |
| `useStaticMetadata` | `Boolean` | `--static-metadata` | `withStaticMetadata` | `false` |
| `fsFilePaths` | `Boolean` | `--fs-file-paths` | `withFsFilePaths` | `false` |
| `dumpRootDir` | `Option[File]` | `--dump-roots` | — | none |
| `emitJsonDir` | `Option[File]` | `--dump-json` | `withExtraArg("emitJsonDir", …)` | none |
| `mimeFilter` | `IncludeExclude` | `--mime-filter`, `--mime-filter-file` | `withMimeFilter`, `withMimeFilterFile` | empty |
| `tag` | `Option[String]` | `--tag` | `withTag` | none |
| `tagJson` | `Option[Dom.Element]` | `--tag-json` | `withTagJson` | none |
| `tagVersion` | `Option[String]` | `--tag-version` (needs `--tag`) | `withTagVersion` | none |
| `tagDate` | `Option[Date]` | `--tag-date` (needs `--tag`) | `withTagDate` | none |
| `packageTags` | `Boolean` | `--package-tags` | `withPackageTags` | `false` |
| `packageTagsShortName` | `Boolean` | `--package-tags-short-name` | `withPackageTagsShortName` | `false` |
| `expiry` | `Option[Instant]` | `--expiry` | `withExpiry` | none |
| `progressListener` | `Option[ProgressListener]` | — (not on the CLI) | `withProgressListener` | none |
| `runtime` | `RuntimeEnvironment` | — (captured at startup) | — | the real process |

## `expiry` has exactly one source

`expiry` — "refuse to analyze internal files modified after this instant, and drop
everything that transitively contains one" — used to have a second, ambient source: the
`goatrodeo.expiry` system property.

That property was consulted by `Howdy.run` on the CLI path but **not** by
`GoatRodeoBuilder`, so the same `-D` flag silently did nothing when Goat Rodeo was embedded
in another JVM — which is how Spice and Allspice actually use it. It has been removed. The
value now arrives only via `--expiry` or `withExpiry`, and behaves identically on both paths.

Callers that need the cutoff to originate in a Spice Pass (Allspice, the `spice` CLI) decode
the pass themselves and call `withExpiry`. Goat Rodeo has no notion of a pass and does not
want one.

## `RuntimeEnvironment`

Ambient process state — working directory, home directory, environment, system properties —
is captured once, in `RuntimeEnvironment.default`, and carried on the configuration. This is
the only place Goat Rodeo reads it. Tilde expansion (`ExpandFiles.fixTilde`) takes the home
directory as a parameter rather than calling `System.getProperty("user.home")` at the point
of use, so a test can supply a different one without mutating global JVM state.


## The config file

`--config <file>` reads a TOML file whose keys are the snake_case spelling of the
corresponding flag — `--maxrecords` is `max_records`, `--fs-file-paths` is `fs_file_paths`:

```toml
out = "/srv/adg"
build = ["/srv/artifacts"]
threads = 16
max_records = 100000
mime_filter = ["+application/java-archive"]
```

Two rules are worth knowing:

- **An unknown key is an error.** A mistyped key that is silently ignored is how a config file
  becomes undebuggable: the value you wrote is simply not in force and nothing says so.
- **Paths must be absolute.** A config file may be read from a directory the process cannot
  see — a container mount, say — so a relative path in one has no dependable meaning. It also
  keeps the `spice` wrapper's guarantee that every path it must bind-mount is visible on the
  command line, since it cannot see inside a TOML table.

### Embedded in another program's config file

`ConfigurationToml.fromToml` takes a `TomlTable`, not a path, so the same schema and the same
code serve both a whole Goat Rodeo config file and one table nested inside a `spice` or
Allspice config:

```toml
# spice's config file — spice carries this table without understanding it
[registry.analysis]
threads = 16
max_records = 100000
```

Callers pass the table path as a label so errors name the table the user actually wrote.

`TomlTables` adapts a plain nested `Map` — how the plugin SPI hands a table over, to stay
dependency-free — back to a `TomlTable`, so there is one reader either way. Use
`TomlTables.toPlainMap` rather than `TomlTable.toMap()` when producing such a map:
`toMap()` is shallow and leaves nested tables as tomlj objects.

### `expiry` is the one key nesting changes

`expiry` is a Goat Rodeo knob and is accepted in a Goat Rodeo config file: standalone, Goat
Rodeo knows nothing about Spice Passes.

Nested inside `spice` or Allspice it is **rejected**. There the cutoff is the pass's
`x-cutoff`, which constrains what the platform is willing to accept, so letting a config file
supply it would hand a user the ability to widen a scope the platform deliberately narrowed.
`ConfigurationToml.nestedFromToml` is the entry point that enforces this.
