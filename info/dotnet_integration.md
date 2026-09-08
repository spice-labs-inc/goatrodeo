# Cilantro 0.4.0 .NET Integration

> **Navigation:** [Documentation Index](README.md) | [MIME Types](mime_types.md) | [Resource Handling](resource_handling.md)

## Overview

Goat Rodeo consumes Cilantro (the .NET assembly reader) as an ordinary
container subsystem, exactly like zip/tar/saffron/ISO: **probe, walk,
wrap**. Cilantro 0.3.0+ froze this streaming contract; Goat Rodeo
consumes Cilantro 0.4.0:

- A .NET assembly (`.dll`/`.exe`) is a **container** whose entries
  (classes, embedded resources, authenticode certificates, win32
  resources, debug blobs) become child `ArtifactWrapper`s.
- A **portable PDB** (standalone `.pdb` file or the type-17
  `MPDB`-envelope debug blob embedded in an assembly) is itself a
  **container** whose entries are the embedded **source files**.
- Nested assemblies (a DLL embedded as a managed resource inside
  another assembly) are recursed as containers, like zip-in-zip.

The reader is lazy and memory-mapped: `processStream` hands Goat Rodeo
an `InputStream` per entry; payloads are never materialized into heap
arrays by the walk.

## Probe — detection (MIME)

`DotnetDetector` is MIME-gated and probe-first:

1. If the artifact's MIME set already contains
   `application/x-msdownload; format=pe32-dotnet`, nothing more is
   done (`DOTNET_MIME_STRING` is reused — never redefined).
2. Otherwise `DotnetAssemblyProbe.isDotnetAssembly(stream)` — a cheap,
   bounded, never-throwing probe (PE magic + CLR runtime header +
   `BSJB` metadata root) — decides whether the artifact becomes the
   dotnet MIME. No `withFile`, no full parse.

Goat Rodeo has **no PE-offset math of its own** — the probe lives in
Cilantro (per the handoff; GR deleted its old `isPE32` sniff).

Verified by `DotnetStreamingSuite.GRW-1a..d`.

## Walk — the assembly as a container (FileWalker)

`FileWalker.tryToConstructArchiveStream` has an
`asDotnetAssemblyContainer` branch, gated like the other container
branches: MIME gate → cheap probe → walk:

```scala
AssemblyWalker.withinAssemblyStream(file) { entries =>
  entries.map { e =>
    e.processStream { stream =>
      ArtifactWrapper.newWrapper(
        nominalPath = e.name,
        size = e.length,          // exact byte count (Cilantro 0.4.0)
        data = stream,
        tempDir = in.tempDir,
        tempPath = tempDir,
        mimeHint = e.mimeHint
      )
    }
  }
}
```

Contract facts GR relies on:

- `None` from the walk = not a .NET assembly / hostile / unreadable:
  **no children, never a partial vector, never an exception out of the
  walk.**
- `Some(entries)` = one complete vector in the pinned order (classes,
  embedded resources, certificates, win32 leaves, debug blobs).
- **Entry streams live only inside the callback.** `newWrapper` runs
  inside the walk; after the walk returns, entries refuse cleanly.
- **`entry.length` is the exact byte count** (for `Class` entries it is
  the canonical-JSON byte count, the single documented exception);
  `newWrapper` gets a real size — no byte-counting in memory.
- **Entry names are hardened by Cilantro** (`DotnetNameSanitizer`) but
  not guaranteed unique; the name is passed through as Item metadata
  as-is. Duplicate names are not disambiguated and need no collision
  handling (the Item graph keys by hash; the name is metadata only).
- MIME hints are cilantro-owned and passed through at wrapper creation:

  | Kind | Hint |
  |---|---|
  | Class | `cilantro/type` |
  | EmbeddedResource | `None` (content detection) |
  | AuthenticodeCertificate | `None` (GR's Certificates policy stamps) |
  | Win32Resource | `pe/resource` |
  | DebugBlob (codeview/pdbchecksum/...) | `pe/debug` |
  | DebugBlob type 17 (PDB envelope) | `pe/debug; format=mpdb` |

Verified by `DotnetStreamingSuite.GRW-2a..d, GRW-3a, GRW-5a..c`.

## PDB — a container of source files (FileWalker)

A PDB is an ordinary container. The FileWalker `asPdbContainer` branch
is keyed on the **`pe/debug; format=mpdb`** MIME (the hint the assembly
walk stamps on a type-17 debug blob). Inside a single `withFile`:

```scala
PortablePdbFile.withPdb(file, Some(spool)) { outcome =>
  outcome match {
    case Success(Some(view)) =>
      // wrap view.sources into ArtifactWrappers (inside the callback)
    case _ => // not a portable PDB, or hostile: not a container
  }
}
```

- **`PortablePdbFile.withPdb`** (Cilantro 0.4.0) handles both the
  standalone portable PDB file (BSJB root) and the MPDB envelope; the
  view is live only inside the callback; cleanup is automatic whether
  the callback returns or throws.
- The **spool dir** is a uniquely-named subdirectory of the walk's
  temp dir (`Files.createTempDirectory(tempDir, "cilantro")`, never a
  fixed name) — nested/parallel walks each get their own. Cilantro
  never creates/owns/deletes the caller's directory; GR's scope-exit
  delete removes the tree.
- A wrapper with the mpdb MIME whose bytes are **not** a parseable
  portable PDB (`Success(None)` or `Failure`) is rejected as a
  container: no children, no throw.
- The `EmbeddedResource` walk entries are `None`-hinted (content
  detection); a nested DLL embedded as a resource is probed as an
  assembly and recursed (next section).

Verified by `DotnetPdbContainerSuite.PDB-1..PDB-3`.

## Nested assemblies (recursion)

An embedded-resource DLL is itself an entry; the child wrapper is an
ordinary artifact that, when it probes as a .NET assembly (MIME
augmentation + probe), is itself walked as a container. The recursion
happens **inside the parent's walk scope** — Goat Rodeo's child-walk
processes each child within `FileWalker.withinArchiveStream`'s
callback, so the parent's temp dir is alive while the nested assembly
is walked (this is the same container recursion as zip-in-zip; it is
never a post-scope re-open).

Verified by `DotnetNestedAssemblySuite.nested-1, nested-3` using the
Docker-built `test_data/dotnet/OuterApp.dll` (embeds
`InnerLib1/2/3.dll`).

## Canonical type JSON as Item metadata

Cilantro's walk yields a `cilantro/type` entry per class whose payload
is the deterministic canonical type JSON (`format: "cilantro-type"`,
version 1). During child processing, `DotnetState.accumulateTypeJson`
harvests these payloads (whole, untruncated) via the assembly's parent
scope, and `applyAccumulatedAugmentation` stores them on the assembly
Item's metadata under the key **`cilantro:TypeJson`**
(`MetadataKeyConstants.adHoc("cilantro")("TypeJson")`), newline-joined
and sorted — the same shape Maven uses to accumulate class-derived
structural metadata onto the JAR Item.

This makes the per-type fingerprints readable: "what types does this
assembly define" is queryable from the assembly Item.

Verified by `DotnetTypeMetadataSuite.type-1..type-4`.

## Fixtures

Real, committed, **non-LFS** corpus in `test_data/dotnet/`:

| Fixture | Purpose |
|---|---|
| `polly.8.4.1.nupkg` | standalone portable PDB with embedded sources (`lib/net6.0/Polly.pdb`) |
| `fluentassertions.6.12.0.nupkg` | second embedded-source PDB source |
| `coverlet.collector.6.0.2.nupkg` | third embedded-source PDB source |
| `OuterApp.dll` + `InnerLib1/2/3.dll` | Docker-built nested-assembly exe (embeds 3 DLLs) |
| `ilspy_windows_11.0.0.9375-x64.zip` | real ILSpy distribution (unweaved; corpus of real assemblies) |

`.gitattributes` forces `* -filter -diff -merge -text`: **no git-lfs
anywhere** in this repository.

## Summary of verified claims

| Claim | Test |
|---|---|
| DotnetDetector is MIME-gated, uses `DOTNET_MIME_STRING`, and probes via `DotnetAssemblyProbe` (no GR PE math, no `withFile`) | `DotnetStreamingSuite.GRW-1a..d` |
| Non-PE/corrupt/truncated input → not dotnet, no exception | `DotnetStreamingSuite.GRW-1b` |
| `withinAssemblyStream` yields every kind with name/kind/mimeHint; payloads readable via `processStream` exactly | `DotnetStreamingSuite.GRW-2a..d` |
| `entry.length` is the exact byte count used as the wrapper size (no in-memory counting) | `DotnetStreamingSuite.GRW-3a` |
| FileWalker walks a dotnet assembly as a container; children carry `cilantro/type`, `pe/resource`, `pe/debug` hints and are full, readable wrappers | `DotnetStreamingSuite.GRW-5a..c` |
| A `pe/debug; format=mpdb` wrapper is a PDB container; sources are `.cs` files with real content | `DotnetPdbContainerSuite.PDB-1, PDB-2` |
| A fake mpdb blob is rejected as a container, never thrown | `DotnetPdbContainerSuite.PDB-3` |
| A .NET assembly embeds DLLs as resources and the full graph recurses into them (nested classes present) | `DotnetNestedAssemblySuite.nested-1, nested-3` |
| Every class's canonical JSON is surfaced whole on the assembly Item under `cilantro:TypeJson` | `DotnetTypeMetadataSuite.type-1..type-4` |

## Out of scope (pending Cilantro enhancement)

- **Per-type source association** (build:up/build:down between a PDB
  source file and the class it was built from) — Cilantro 0.4.0 does
  not expose the PDB's per-method document/sequence-point mapping. Only
  whole-PDB containment is surfaced today. An enhancement request to
  surface method→document mapping would enable it.
- **Java class canonical JSON** — the analogous `cilantro-type`
  fingerprint for JVM classes is not implemented.