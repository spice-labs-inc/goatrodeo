# Run Outputs — LLM Reference

Companion to [`run_outputs.md`](run_outputs.md). Dense form, with the code
locations and the exact assertions. Every claim names the test that verifies it;
if you change the behaviour, change the test, and if the test cannot express the
claim, the claim is wrong.

## Output set (single batch)

Written by `Builder.writeGoatRodeoFiles` into the batch's `MemStorage` target.

| File | Producer | Notes |
|---|---|---|
| `<16hex>.grd` | `GraphManager.writeABlock` | CBOR items; magic `0x00be1100` |
| `<16hex>.gri` | `GraphManager.writeABlock` | MD5 key → (grd, offset); magic `0x54154170` |
| `<ts>_<16hex>.grc` | `GraphManager.writeEntries` | cluster manifest; magic `0x00ba4a4a` |
| `purls.txt` | `Builder.writeGoatRodeoFiles` | `store.purls()`, one per line |
| `history.jsonl` | `GraphManager.writeEntries` | one line per cluster write, truncated per write |
| `items_<millis>.json` | `Storage.emitAllItemsToDir` | only with `emitJsonDir`; one per batch |

## Naming rule

```
name = hex( clearHighBit(sha256(file)[0..8]) )      # 16 chars, first char 0-7
grc  = yyyy_MM_dd_HH_mm_ss + "_" + name(sha256(grc))
```

- `Helpers.byteArrayToLong63Bits` (`Helpers.scala:694-701`) clears the top bit:
  `toDo(0) = (toDo(0) & 0x7f)`. **Intended** (user, 2026-10-14).
- `.gri` is named for the **index** file's digest (`GraphManager.scala:197-204`),
  `.grd` for the **data** file's digest.
- `info/append_only_graph.md:97,103` used to omit the mask; corrected.

Test: `RunOutputContractSuite.O-2`. It computes the expected name with its own
bit arithmetic, deliberately **not** by calling `Helpers`, so removing the mask
from `Helpers` cannot silently move the expectation.

## Cluster file (`ClusterFileEnvelope`)

```
magic:int32 | envelopeLen:int16 | CBOR(ClusterFileEnvelope)
```

- `dataFiles: Vector[Long]`, `indexFiles: Vector[Long]` — the 63-bit names.
- `info["sha256"]` — JSON text `{"grd":[hex...],"gri":[hex...]}`, index-aligned.
- `info["correlation_id"]` — present **only** when `tamperEvidentLog` is Some
  (`GraphManager.scala:285-288`).
- `info["log_chain_head"]` — present only when the chain is active.

Tests: `RunOutputContractSuite.O-3` (resolves + hashes match, end-to-end);
`TamperEvidentSuite.T-07`/`T-08` (envelope internals, hand-built items).
Shared decoder: `testsupport.RunOutputs.readClusterEnvelope`.

## Batching

- `maxRecords` caps items per batch: `Builder.scala:421`.
- Batch directory: `Builder.destWithCount` = `File(dest.getParentFile(), s"${dest.getName()}_$cnt")`
  — a **sibling** of the output directory. First batch writes into the output
  directory itself unless `runningCnt > maxRecords` at entry, which relocates it
  to `<output>_0` (`Builder.scala:216-225`).
- Per batch: its own `MemStorage(Some(destDir))` (`Builder.scala:336`) and its
  own Saver calling `writeGoatRodeoFiles` (`Builder.scala:635-640`).
- `preWriteDB` (which includes `emitAllItemsToDir`) is folded **inside** the
  Saver (`Builder.scala:609`) → one JSON dump per batch.
- Overlap: the next batch starts when the current one's live workers reach
  `windDownThreshold` and the previous batch's saver is done
  (`Builder.scala:271-281`). **The batch count is timing-dependent — never assert
  it.**

Tests: `RunOutputContractSuite.O-8` (one worked example at `maxRecords = 1`),
`RunOutputContractPropertySuite.P-1` (enumerated sizes 1, 2, 3, 5). Both assert
the **union** is the whole graph and that each batch directory is
self-describing. `O-8` asserts a superset of the single-batch identifiers, not
equality, because each batch stamps its own tag item (`Builder.scala:342`).

Discovery helper: `testsupport.RunOutputs.batchDirectories` — output directory
plus any sibling or child containing a `.grc`. Location is deliberately not
asserted; cleanup is.

## Cleanup

- Temp names: `goat_rodeo_data_` (`GraphManager.scala:84`) and
  `goat_rodeo_cluster_` (`GraphManager.scala:265-270`).
- Both `renameTo` results are **ignored** (`:160`, `:204`), so a failed rename
  leaves the temp file and omits the artifact silently. That is why `O-10`
  asserts absence of temp-prefixed files and that the artifacts present are
  exactly those the batch's `.grc` records.

## Disk images

- `FileWalker.asSaffronFilesystem` names each wrapper with Saffron's
  `regular.path()` (no leading slash) and returns `None` when
  `FileSystemMount.mountAll` yields nothing (`FileWalker.scala:471-472`).
- `ToProcess.scala:441-446` records `Vector(artifact.path())` as the item's
  `file_names`.
- `fsFilePaths` affects only top-level names (`ToProcess.scala:943-947`), not
  in-container names.
- `SaffronDetector` promotes `DiskFormat.RAW` to `application/x-saffron-raw-disk`
  (`SaffronDetector.scala:54-58`).

| Fixture | Measured (2026-10-14) |
|---|---|
| `test_data/raw.img` | `files/small.txt` 21 B `text/plain`; `files/README.md` 15 B `text/markdown`; image 4194304 B |
| `test_data/alpine-minimal.squashfs` | 58 items with a file name; `bin/busybox`, `etc/os-release` |
| `test_data/minimal.raw` | 1 item; `application/x-saffron-raw-disk`; no file items |

Tests: `SaffronImageItemSuite.S-1` … `S-4`.

## Test inventory

| Test | Claim |
|---|---|
| `RunOutputContractSuite.O-1` | single-batch layout: all five artifacts in the output directory |
| `RunOutputContractSuite.O-2` | `.grd`/`.gri`/`.grc` naming rule, independently computed |
| `RunOutputContractSuite.O-3` | `.grc` names resolve and hash as recorded |
| `RunOutputContractSuite.O-4` | `purls.txt` is exactly the run's purls |
| `RunOutputContractSuite.O-5` | `history.jsonl` version/commit/operation/date/cluster |
| `RunOutputContractSuite.O-6` | JSON dump and `.grd` hold the same items |
| `RunOutputContractSuite.O-7` | item-count floor (30; 37 measured) and purl reachability |
| `RunOutputContractSuite.O-8` | batch boundary loses nothing; batches self-describing |
| `RunOutputContractSuite.O-9` | JSON dump free of private-key material; sentinel proves the sweep fires |
| `RunOutputContractSuite.O-10` | no temporary file survives in any batch directory |
| `RunOutputContractPropertySuite.P-1` | the same, at `maxRecords` ∈ {1,2,3,5} |
| `SaffronImageItemSuite.S-1` | `raw.img` files become items with paths, sizes, MIMEs |
| `SaffronImageItemSuite.S-2` | squashfs contributes ≥40 file items incl. `bin/busybox`, `etc/os-release` |
| `SaffronImageItemSuite.S-3` | `fsFilePaths` does not change in-container names |
| `SaffronImageItemSuite.S-4` | filesystem-less disk: one opaque artifact, no error, no file items |

## Deliberate non-assertions

- `items_*.json` field order, absent fields, `body` shape — the dump is
  `Item.encode`-shaped; pinning it is brittle without protecting a consumer.
- Exact batch count / directory count — timing-dependent.
- Literal in-container path strings in `S-3` — it asserts equality between two
  runs instead, so a deliberate convention change in both branches passes.
- Exact file set in `O-10` — the property is "nothing temporary survived".

## Known production hazard (not fixed here)

`Storage.emitAllItemsToDir` names its file `items_${System.currentTimeMillis()}.json`
(`Storage.scala:130`). Two batches finishing inside the same millisecond would
overwrite one another. `O-6`/`O-8` would surface it as a missing-union failure.
Tracked in `discussions/2026_10_14_run_output_contract_tests.md`; no issue filed.
