# Configuration

Every value that affects a Goat Rodeo run lives in one strongly-typed record,
`io.spicelabs.goatrodeo.util.Configuration`. It is built once at startup and passed to
everything that needs it as a `using` parameter named `config`:

```scala
def buildDB(dest: File, tag: Option[TagInfo], …)(using config: Configuration): Unit
```

There are exactly two ways to build one, and they produce the same value:

| Route | Entry point |
| --- | --- |
| Command line | `ConfigurationParser.parse(args)` — see `util/ConfigurationParser.scala` |
| Embedded (library) | `GoatRodeoBuilder`, e.g. `GoatRodeo.builder().withOutput(…).run()` |

**There is no third route.** No environment variable and no system property configures Goat
Rodeo. If you find yourself reaching for one, add a field here and a flag there instead —
that is the whole point of this file.

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
