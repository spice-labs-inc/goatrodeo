# Goat Rodeo API (LLM)

> **Navigation:** [Docs](README.md) | [Operation](goat_rodeo_operation_llm.md) | [Architecture](architecture_llm.md)

## Overview
Embed Goat Rodeo via `GoatRodeo.builder()` and chain `with*` methods. Every method returns the builder for fluent configuration. The builder is internally immutable: each call returns a new instance carrying the updated `Config`.

## Builder Methods
- `withPayload(String o)` — add a directory of artifacts to process.
- `withOutput(String o)` — set the ADG output directory.
- `withThreads(Int t)` — set worker thread count.
- `withIngested(String i)` — set the ingested-file list path.
- `withIgnore(String i)` — add an ignore file (accumulator).
- `withFileList(String f)` — add a file list (accumulator).
- `withExcludePattern(String p)` — add an exclude regex (accumulator).
- `withMaxRecords(Int r)` — batch size.
- `withBlockList(String b)` — GitOID block list file.
- `withTempDir(String d)` — temporary directory.
- `withTag(String t)` — survey tag.
- `withStaticMetadata(Boolean b)` — enable Syft static metadata.
- `withTagJson(String t)` — extra tag JSON.
- `withPackageTags()` — enable per-package tags.
- `withPackageTagsShortName()` — use short package names.
- `withMimeFilter(String filter)` — add a MIME filter (accumulator).
- `withMimeFilterFile(String f)` — add a file of MIME filters.
- `withCbomDir(String d)` — set CBOM output directory.
- `withCbomVersion(String v)` — set CycloneDX CBOM version (`1.6` or `1.7`).
- `withProgressListener(ProgressListener l)` — attach a progress callback.
- `withExtraArgs(Map<String, String> args)` — set many extra args at once.
- `withExtraArg(String key, String value)` — set a single extra arg.

## Extra Arg Keys
`withExtraArg` recognizes: `payload`, `output`, `threads`, `maxRecords`, `ingested`, `ignore`, `fileList`, `excludePattern`, `blockList`, `tempDir`, `tag-json`, `tag`, `package-tags`, `package-tags-short-name`, `mimeFilter`, `mimeFilterFile`, `emitJsonDir`, `emitCbomDir`, `cbomVersion`.

## Execution
Call `run()` after chaining configuration. No arguments; it uses the accumulated `Config`.

## Verified By
- `ConfigCbomFlagsSuite.T4.4` — `withCbomDir`, `withCbomVersion`, and `withExtraArg` round-trip to the expected `Config` values.
