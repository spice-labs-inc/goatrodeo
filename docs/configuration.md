# Configuration

Every value that affects a Goat Rodeo run lives in one strongly-typed record,
`io.spicelabs.goatrodeo.util.Configuration`. It is built once at startup and passed to
everything that needs it as an anonymous `using` parameter:

```scala
def buildDB(dest: File, tag: Option[TagInfo], …)(using Configuration): Unit
```

It is read through the global `config` accessor, defined once in
`io.spicelabs.goatrodeo.util`:

```scala
inline def config(using configuration: Configuration): Configuration = configuration
```

Naming the parameter would let one file call it `config`, another `cfg`, and a third shadow
it; leaving it anonymous means there is exactly one name for the configuration anywhere in
the codebase. Allspice uses the same idiom.

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
| `blockList` | `Option[File]` | `--block-list` | `withBlockList` | none |
| `exclude` | `Vector[(String, Try[Pattern])]` | `--exclude-pattern` | `withExcludePattern` | empty |
| `threads` | `Int` | `-t`, `--threads` | `withThreads` | `4` |
| `maxRecords` | `Int` | `--max-records` | `withMaxRecords` | `50000` |
| `tempDir` | `Option[File]` | `--temp-dir` | `withTempDir` | none |
| `useStaticMetadata` | `Boolean` | `--static-metadata` | `withStaticMetadata` | `false` |
| `fsFilePaths` | `Boolean` | `--fs-file-paths` | `withFsFilePaths` | `false` |
| `dumpRootDir` | `Option[File]` | `--dump-roots` | — | none |
| `emitJsonDir` | `Option[File]` | `--dump-json` | `withExtraArg("emitJsonDir", …)` | none |
| `mimeFilter` | `IncludeExclude` | `--mime-filter`, `--mime-filter-file` | `withMimeFilter`, `withMimeFilterFile` | empty |
| `cbomDir` | `Option[File]` | `--emit-cbom-dir` | `withExtraArg("cbomDir", …)` | none |
| `cbomVersion` | `String` | `--cbom-version` (`1.6` or `1.7`) | — | `1.6` |
| `componentArgs` | `Map[String, Vector[Array[String]]]` | `--component <name>[,arg…]` (repeatable) | — | empty |
| `tag` | `Option[String]` | `--tag` | `withTag` | none |
| `tagJson` | `Option[Dom.Element]` | `--tag-json` | `withTagJson` | none |
| `tagVersion` | `Option[String]` | `--tag-version` (needs `--tag`) | `withTagVersion` | none |
| `tagDate` | `Option[Date]` | `--tag-date` (needs `--tag`) | `withTagDate` | none |
| `packageTags` | `Boolean` | `--package-tags` | `withPackageTags` | `false` |
| `packageTagsShortName` | `Boolean` | `--package-tags-short-name` | `withPackageTagsShortName` | `false` |
| `cutoff` | `Option[Instant]` | `--cutoff` | `withCutoff` | none |
| `progressListener` | `Option[ProgressListener]` | — (not on the CLI) | `withProgressListener` | none |
| `runtime` | `RuntimeEnvironment` | — (captured at startup) | — | the real process |
| `configFile` | `Option[File]` | `--config` | — | none |

## Not settings

Three things live on `Configuration` without belonging in the table above, and are
deliberately absent from it rather than overlooked.

`printComponentInfo` and `printComponentArgumentInfo` are set by `--print-component-info`
and `--print-component-arg-help`. Both are `opt[Unit]` actions that print and exit, in the
same family as `--help` and `--version`: an instruction rather than a preference. Neither
has a config-file key, because a file that made every run print and quit would be a strange
thing to be able to write.

`nonexistentDirectories` is not an input at all. `--build` records there any directory it
was given that does not exist, so the run can report them afterwards. It is output that
happens to travel in the configuration.

`configFile` records which file `--config` named. Like `runtime`, it describes how the run
was started; `differencesFrom` skips both for that reason, so neither shows up in the
override reporting.

## `cutoff` has exactly one source

`cutoff` — "refuse to analyze internal files modified after this instant, and drop
everything that transitively contains one" — used to have a second, ambient source: the
`goatrodeo.expiry` system property.

That property was consulted by `Howdy.run` on the CLI path but **not** by
`GoatRodeoBuilder`, so the same `-D` flag silently did nothing when Goat Rodeo was embedded
in another JVM — which is how Spice and Allspice actually use it. It has been removed. The
value now arrives only via `--cutoff` or `withCutoff`, and behaves identically on both paths.

Callers that need the cutoff to originate in a Spice Pass (Allspice, the `spice` CLI) decode
the pass themselves and call `withCutoff`. Goat Rodeo has no notion of a pass and does not
want one.

No config file may supply it either — not a standalone Goat Rodeo file, and not a table
nested inside a `spice` or Allspice one. `cutoff` is an entitlement rather than a preference:
embedded, it is the pass's `x-cutoff`, which constrains what the platform is willing to
accept, and a file supplying it would hand a user the ability to widen a scope the platform
deliberately narrowed. Standalone there is no pass to contradict, but a value that can be
read from two kinds of place is how the embedded rule gets forgotten.

It is refused *by name* (`ConfigurationToml.alwaysRejected`) rather than by being left out of
the schema: an unrecognised key reports a typo, which is the wrong thing to say about a key
that is spelled correctly and deliberately unavailable.

## `RuntimeEnvironment`

Working directory and home directory are captured once, in `RuntimeEnvironment.default`, and
carried on the configuration, so that nothing downstream reaches for them itself. Tilde
expansion (`ExpandFiles.fixTilde`) takes the home directory as a parameter rather than
calling `System.getProperty("user.home")` at the point of use, so a test can supply a
different one without mutating global JVM state.

The process environment is ambient state too, but it is a configuration *source* rather than
something a run is described by, so it enters through the resolver alongside the config file
— see below — rather than being carried here.


## The config file

`--config <file>` reads a TOML file whose settings live in an `[analysis]` table:

```toml
[analysis]
out = "/srv/adg"
build = ["/srv/artifacts"]
threads = 16
max_records = 100000
mime_filter = ["+application/java-archive"]
```

`analysis` is the *group* — named for the job rather than for this component, and the same
group `spice` and Allspice carry, so `[analysis] threads = 16` means one thing wherever it is
written. There is one shape to learn, and moving a setting between a Goat Rodeo config file
and a `spice` one is a copy rather than a translation.

The three names of a setting are related by a rule with no exceptions:

| Form | Shape | Example |
| --- | --- | --- |
| Config key | `snake_case` in `[analysis]` | `max_records` |
| Flag | its kebab-case form | `--max-records` |
| Environment | `GOATRODEO_ANALYSIS_<KEY>` | `GOATRODEO_ANALYSIS_MAX_RECORDS` |

Embedded in `spice`, the same setting is `SPICE_ANALYSIS_MAX_RECORDS`: the prefix names
whichever program is running, and nothing else changes.

Lowest to highest:

    defaults  <  [analysis]  <  environment  <  command line

Each layer is consulted on every run. The config file is the one that may be absent, and its
absence is not a reason to skip the ones above it: `GOATRODEO_ANALYSIS_THREADS=16` works on a
run that passes no `--config` at all.

When one layer displaces another, it says so, naming the source that supplied the value being
displaced:

```
threads = 9 (GOATRODEO_ANALYSIS_THREADS) overrides 4 ([analysis] in /etc/goatrodeo.toml)
threads = 12 (command line) overrides 9 (GOATRODEO_ANALYSIS_THREADS)
```

Overriding a *default* is not reported: that is every setting on every run, and the noise
would bury the cases where two deliberate choices conflict.

A setting is named by its config key throughout, including on the command-line line, so the
three spellings above are the only ones that ever appear. `Configuration`'s field names are a
fourth, unrelated spelling — `emitJsonDir` for `dump_json` — and `ConfigurationToml.keyForField`
exists to keep them out of anything a person reads.

The rules themselves — naming, layering, precedence, provenance — live in
[`spice-config`](https://github.com/spice-labs-inc/spice-config), shared with every
component, because rules copied into several codebases are rules that will disagree. The
model as a whole is described in `spice`'s `docs/configuration.md`; this page covers what
is particular to Goat Rodeo.

Three rules are worth knowing:

- **An unknown key is an error.** A mistyped key that is silently ignored is how a config file
  becomes undebuggable: the value you wrote is simply not in force and nothing says so. The
  same holds for a variable — `GOATRODEO_ANALYSIS_TREADS=9` fails the run rather than doing
  nothing. Variables outside a group this program claims (`GOATRODEO_IMAGE`, say) are not
  settings and are left alone.
- **A setting outside a table is an error.** Bare keys at the root are how this file used to be
  written, and silently ignoring them would be the same failure by another route; the message
  names the keys and says they belong under `[analysis]`.
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

Nesting changes nothing about which keys are accepted: the schema is the same either way, and
`cutoff` is refused in both — see [`cutoff` has exactly one source](#cutoff-has-exactly-one-source).
