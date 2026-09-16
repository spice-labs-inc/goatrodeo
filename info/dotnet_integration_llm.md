# Cilantro 0.4.0 .NET Integration — LLM copy

> Human version: `dotnet_integration.md`. This file is the LLM-oriented
> parallel copy (same facts, terser, structured for machine reading).

## Overview

Goat Rodeo treats a .NET assembly and a portable PDB as ordinary
containers consumed through Cilantro 0.4.0: **probe → walk → wrap**,
the same model as zip/tar/saffron/ISO. The walk is lazy/mmap-backed;
payloads reach GR as `InputStrem`s through `processStream`, never as
heap arrays. GR holds no PE-offset math; Cilantro owns all format
knowledge.

## Architecture layers (must not be conflated)

1. **`DotnetDetector` (MIME layer):** MIME-gated; if the artifact is
   not already `application/x-msdownload; format=pe32-dotnet`, probe
   via `DotnetAssemblyProbe.isDotnetAssembly(stream)` (bounded,
   never-throws); add `DOTNET_MIME_STRING` only when probed true.
   Uses the single constant `DotnetDetector.DOTNET_MIME_STRING`.
2. **`FileWalker` (container layer):** `asDotnetAssemblyContainer`
   (MIME gate → probe → `AssemblyWalker.withinAssemblyStream` → wrap
   entries with `entry.length`, `mimeHint`, name) and `asPdbContainer`
   (keyed on the `pe/debug; format=mpdb` MIME → single `withFile` →
   `PortablePdbFile.withPdb` → wrap `view.sources` inside the
   callback). Both chained in `tryToConstructArchiveStream`.
   Container-child edges (`contained:up`) are automatic via the generic
   child-walk; children are processed INSIDE the parent walk callback
   (temp dir is alive → nested recursion works).
3. **`DotnetState` (strategy/metadata layer):** assembly metadata,
   purls, and — added for 0.4.0 — accumulation of the canonical
   per-class JSON (`cilantro/type` payloads) onto the assembly Item as
   `cilantro:TypeJson`. This layer must NOT be conflated with
   FileWalker.

## Cilantro 0.4.0 API surface GR consumes

```
DotnetAssemblyProbe.isDotnetAssembly(File|Path|String|InputStream): Boolean
AssemblyWalker.withinAssemblyStream[T](File|Path)(f: Vector[AssemblyEntry] => T): Option[T]
  // NOTE: NO spool argument in 0.4.0 (the walk creates no temp state).
sealed trait AssemblyEntry extends PayloadSource {
  def name: String      // pre-hardened, NOT guaranteed unique
  def kind: AssemblyEntryKind
  def mimeHint: Option[String]
  def length: Long      // exact byte count (0.4.0; B-4)
}
enum AssemblyEntryKind { Class, EmbeddedResource, AuthenticodeCertificate, Win32Resource, DebugBlob }
  // EmbeddedSource kind was REMOVED vs 0.3.1; sources come only from PDB readers.
trait PayloadSource { def processStream[T](f: InputStream => T): T }
PortablePdbFile.isPortablePdb(File|Path|String): Boolean
PortablePdbFile.withPdb[T](file, spoolDir: Option[Path])
  (f: Try[Option[PDBView]] => T): Try[Option[T]]   // callback-owned; auto-cleanup
PDBView.sources: Vector[EmbeddedSourceFile]         // (name, PayloadSource)
DotnetNameSanitizer.sanitize(name): String
// MetadataReader.withEmbeddedPdb exists but GR does NOT use it
// (the container model extracts the type-17 wrapper bytes and walks them).
```

## MIME hints (cilantro-owned; passed through at wrapper creation)

| Kind | Hint |
|---|---|
| Class | `cilantro/type` |
| EmbeddedResource | `None` (content detection) |
| AuthenticodeCertificate | `None` (GR Certificates policy stamps; raw blobs only, no PKCS#7) |
| Win32Resource | `pe/resource` |
| DebugBlob (non-17) | `pe/debug` |
| DebugBlob type 17 | `pe/debug; format=mpdb` |

## PDB container contract

- Keyed on `pe/debug; format=mpdb` only (the MIME already associated
  with the PDB by the walk — no extra probe, no new MIME rule).
- Single `withFile` → `PortablePdbFile.withPdb(file, Some(spool))`.
- Spool: uniquely-named subdir of the walk's temp dir
  (`Files.createTempDirectory(tempDir, "cilantro")`); Cilantro never
  creates/renames/deletes the caller's dir; GR scope-exit delete
  removes the tree; spool maps released before the call returns.
- Outcome handling: `Success(Some(view))` → wrap `view.sources`;
  `Success(None)` (not a portable PDB) or `Failure` (hostile) → not a
  container, no children, no throw.
- The walk itself creates zero temp state.

## Walk contract

- `None` = not an assembly / hostile / unreadable → no children, no
  partial vector, no exception out of the walk.
- Entry streams valid only inside the callback; `newWrapper` runs
  inside it.
- `length` = exact bytes (for Class = canonical-JSON bytes).
- Names hardened but not unique; passed through as Item metadata
  as-is; no collision handling (graph keys by hash).

## Nested assemblies

- An embedded-resource DLL gets `None` hint → content detection → MIME
  augmentation to dotnet → probed → walked as its own container.
- Recursion happens inside the parent walk scope (child processing
  runs within the parent's `withinArchiveStream` callback), so the
  parent's temp dir lives during the nested walk. Never a post-scope
  re-open.

## Canonical type JSON (`cilantro:TypeJson`)

- Harvested from `cilantro/type` children via `DotnetState
  .accumulateTypeJson` (called through the assembly ParentScope's
  `accumulateInfo`); stored whole (no budget, no truncation) in
  `applyAccumulatedAugmentation` under
  `MetadataKeyConstants.adHoc("cilantro")("TypeJson")`, sorted +
  newline-joined, on the assembly Item's metadata via `store.write`.
- This mirrors Maven's accumulate-child-metadata-onto-parent pattern.

## Tests (claim → test mapping)

| Claim | Test |
|---|---|
| Detector MIME-gated, `DOTNET_MIME_STRING`, probe-based, no `withFile` | `DotnetStreamingSuite.GRW-1a..d` |
| Non-PE/corrupt/truncated → false, no exception; bounded probe | `DotnetStreamingSuite.GRW-1b, GRW-1d` |
| Walk yields every kind; payloads exact via `processStream`; deterministic order | `DotnetStreamingSuite.GRW-2a..d` |
| `entry.length` is the wrapper size (no in-memory counting) | `DotnetStreamingSuite.GRW-3a` |
| FileWalker walks assembly container; hints `cilantro/type`/`pe/resource`/`pe/debug` | `DotnetStreamingSuite.GRW-5a..c` |
| mpdb wrapper → PDB container → `.cs` sources with content | `DotnetPdbContainerSuite.PDB-1, PDB-2` |
| fake mpdb → rejected, no throw | `DotnetPdbContainerSuite.PDB-3` |
| nested embedded DLLs recursed; nested classes in graph | `DotnetNestedAssemblySuite.nested-1, nested-3` |
| canonical JSON whole on assembly Item under `cilantro:TypeJson` | `DotnetTypeMetadataSuite.type-1..type-4` |

## Fixtures (non-LFS; `.gitattributes` = `* -filter -diff -merge -text`)

- `test_data/dotnet/polly.8.4.1.nupkg` — standalone portable PDB (BSJB
  root) with embedded sources at `lib/net6.0/Polly.pdb`.
- `test_data/dotnet/fluentassertions.6.12.0.nupkg`,
  `test_data/dotnet/coverlet.collector.6.0.2.nupkg` — two more
  embedded-source PDB sources.
- `test_data/dotnet/OuterApp.dll` + `InnerLib1/2/3.dll` — Docker-built
  nested-assembly fixture (exe embeds 3 DLLs as resources).
- `test_data/dotnet/ilspy_windows_11.0.0.9375-x64.zip` — real (unweaved)
  ILSpy distribution; corpus of real assemblies.

## Out of scope / pending Cilantro enhancement

- Per-type PDB-source ↔ class build edges: Cilantro 0.4.0 exposes only
  `(name, stream)` sources, not per-method document/sequence-point
  mapping. Whole-PDB containment works today.
- Java-class canonical JSON (a `cilantro-type`-analog for JVM classes)
  is not implemented.

## Historical notes (do not reintroduce)

- The 0.3.1-era `PortablePdbSpool.spoolAndParse` rejected standalone
  BSJB portable PDBs (it required the MPDB envelope); 0.4.0's
  `PortablePdbFile.withPdb` handles both. GR uses `withPdb` only.
- The 0.3.1 walk took a spool argument; 0.4.0 removed it. All GR call
  sites use the 2-arg form.
- GR must never define its own dotnet MIME string literal; use
  `DotnetDetector.DOTNET_MIME_STRING`.