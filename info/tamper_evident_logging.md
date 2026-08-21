# Tamper-Evident Logging for Goat Rodeo

> **Navigation:** [Documentation Index](README.md) | [CBOM from an ADG](cbom_enhancements.md)

## Purpose and threat model

**Goal.** Make it easy to audit whether the ADG outputs a Goat Rodeo run produced
are the genuine outputs of that run, and make tampering *detectable* and
*difficult* (an adversary would have to modify many files across many systems to
hide it).

**Threat model.** Someone tampers with the logs that create the ADGs. The ADG
files (`.grd`/`.gri`/`.grc`) are content-addressed and therefore tamper-evident:
their names embed a content hash. To forge an ADG, an adversary must either
produce content that hashes to the logged name (cryptographically hard) or
change the name — and changing the name breaks the binding to the Goat Rodeo
run logs. If the run logs are themselves tamper-evident (hash-chained), then any
tampering anywhere in the chain is visible.

**Explicit non-goal.** This is **not** a keyed/HMAC mechanism. Unkeyed hash
chaining detects tampering; it does not prevent it against an adversary who can
rewrite the log file *and* the chain. The design makes that attack require
touching the log, the `.grc`, the ADG files, and the final checksum file across
different systems.

## The trust chain (how it fits together)

1. At run start, Goat Rodeo generates a **correlation ID** (UUID), logs it as
   the *first* log line (always, even without tamper-evidence enabled), and
   records it in every `.grc` it writes.
2. A hash-chained logger records every subsequent log line (when enabled).
3. Each batch's `.grc` embeds the **log chain head** at the moment the `.grc` is
   written, so the `.grc` itself commits to all log lines up to a few lines
   before it.
4. Each `.grc` also records the full SHA-256 of every `.grd`/`.gri` it
   references (index-aligned), plus the correlation ID.
5. The full SHA-256 of each `.grc` is itself logged (a chained line).
6. At the end of the run, a **final checksum file** (JSON) in the base output
   directory records the correlation ID, the final log chain head, and the name
   + full SHA-256 of every `.grc` across all batch directories.

To tamper, an adversary must now modify: the run log (and recompute its chain
consistently), every affected `.grd`/`.gri`/`.grc` (and keep their digests
consistent with the chain and the checksum), and the final checksum file — and
do so without leaving a discrepancy that a verifier following the chain will
catch. Because the correlation ID ties the run, the batches, and the checksum
together, this requires coordinated tampering across all of them.

## Correlation ID

- A `UUID` generated once at the start of a Goat Rodeo run.
- Emitted as the **first log line** (always, even without `--tamper-evident-log`).
- Written into **every** `.grc` (via the cluster `info` map).
- Written into the final **checksum file**.
- One correlation ID per run; it spans all batch subdirectories (`<dest>_<n>`).

## CLI flags

| Flag | Meaning |
|------|---------|
| `--tamper-evident-log <file>` | Install the hash-chaining log appender writing to `<file>`. The chain head is then available to embed into `.grc` files and the checksum. |
| `--print-files` | **Change:** log each processed top-level file as a log line (instead of stdout), so it participates in the chain. |

`--print-files` currently prints each finished top-level file via `println`
(Main.scala `onFileFinish`); this is changed to `logger.info(...)`. When
`--tamper-evident-log` is active, each processed file therefore becomes a chained
log line, giving a tamper-evident record of the run's inputs.

Tamper-evident logging is wired through `Howdy.run` (the CLI). The programmatic
`GoatRodeoBuilder` API routes through the same path, so it supports the feature
via `withPrintFiles(true)` / `withTamperEvidentLog(path)` (or the
`printFiles` / `tamperEvidentLog` extra-arg keys).

## Hash-chained log

### Activation

`--tamper-evident-log <file>` installs a custom logback appender
programmatically at run start (it must not depend on a consumer-supplied
`logback.xml`, which is excluded from the packaged jar). The appender is the
**single** serialization point for the chain: logback already serializes
appender calls, so a total order is established at the appender even under
multi-threaded processing.

### Chain algorithm

Let `payload_N` be the rendered text of log line *N*.

- `digest_1 = SHA256(payload_1)`
- `digest_N = SHA256(digest_{N-1} || payload_N)` for *N* > 1

Each emitted line is self-contained and carries its cumulative digest, so the
file can be verified line-by-line without external state:

```
<digest_N> <payload_N>
```

A verifier walks the file, recomputes `digest_N` from the previous line's digest
and the payload, and compares to the recorded `digest_N`. The digest is the
plain (single) SHA-256 of the line's payload (the payload's trailing line
terminator is not included in the hash).

### Chain-head API

The appender exposes `currentChainHead(): String`, returning `digest_N` for the
last line emitted so far. `GraphManager` calls this at `.grc` write time to embed
the chain head (it reflects all log lines emitted up to a few lines before the
`.grc` write).

## `.grc` cluster `info` additions

The `.grc` is a `ClusterFileEnvelope` (CBOR) with a `dataFiles: Vector[Long]`,
`indexFiles: Vector[Long]`, and `info: TreeMap[String, String]`. The additions
live in `info` and are **purely additive** — the `ClusterFileEnvelopeVersion`
is **not** bumped.

| `info` key | Value | Always / flag-gated |
|------------|-------|---------------------|
| `correlation_id` | `<uuid>` | Always |
| `sha256` | JSON `{"grd": [<sha>…], "gri": [<sha>…]}` — full 256-bit SHA-256 of every `.grd`/`.gri`, index-aligned with `dataFiles`/`indexFiles` | Always |
| `log_chain_head` | `<hex>` — the log chain head at `.grc` write time | Only with `--tamper-evident-log` |

The `sha256` value is a JSON-formatted string because `info` is `(String, String)`.

## CBOM filename

**Change.** The CBOM filename currently is `cbom_gitoid_blob_sha256_<full-hex>.json`.
Because the CBOM already contains the full gitoid, the filename does not need it.
New format:

```
cbom_<escaped-file-name>_<last-16-of-gitoid>.json
```

- `<escaped-file-name>` = the root Item's **first** `fileNames` entry (a
  `TreeSet`, so "first" is deterministic sorted order), escaped by replacing
  every character outside `[A-Za-z0-9_-]` with `_`. E.g. `root-ca.crt` →
  `root-ca_crt`, `firmware.img` → `firmware_img`.
- If the file name is a full path (the `--fs-file-paths` option), truncate the
  escaped name so the filename does not become extremely long. The truncation
  keeps the tail of the name (the meaningful path end); the cap is a named
  constant.
- `<last-16-of-gitoid>` = the last 16 hex characters of the root gitoid (i.e.,
  after stripping the `gitoid:blob:sha256:` prefix).

Example: root file `arducopter`, gitoid
`gitoid:blob:sha256:67cce3f1…fec595a` → `cbom_arducopter_fec595a.json`.

**Collision note.** 16 hex chars = 64 bits. Two distinct roots sharing the last
16 hex characters (probability ≈ 2⁻⁶⁴) would collide on the filename and one
CBOM would overwrite the other. This is accepted: the full gitoid is present
inside the CBOM, and the filename is for human readability/disambiguation only.

The emitter already has the root Item (its `identifier` and `fileNames`), so the
naming is derived in `cbomFilename(root)` without any new capture path.

Additionally, each CBOM document carries a top-level `goatrodeo:correlation-id`
property with the run correlation ID (present only when a correlation ID is set,
i.e. during a real run). This lets a CBOM be traced back to the Goat Rodeo run
and its tamper-evident log.

## Final checksum file

A single JSON file written at the **very end of the run** (the final action; no
chained log line follows it, so its `final_chain_head` is the digest of the last
line of the log) to the **base** output directory (not a batch subdirectory).

File name: `goat_rodeo_<correlationId>_checksum.json`.

Contents:

```json
{
  "correlation_id": "<uuid>",
  "final_chain_head": "<hex>",
  "grcs": [
    { "name": "<grc-file-name>", "sha256": "<full-256-bit-hex>" }
  ]
}
```

The run accumulates the `.grc` file name + full SHA-256 from each batch's
`writeGoatRodeoFiles` return value; at run end it writes this file. The
`final_chain_head` is captured after the last log line (the log chain is global
and continuous across the whole run, so it is a single value).

## CBOM emission placement (unchanged)

CBOMs are emitted **per batch**, in `emitForStorage`, at the point where that
batch's ADG is fully in memory (Builder.scala). Multiple batches write into
suffixed subdirectories (`<dest>_<n>`), which is correct and unchanged. The
`.grc` (and thus the `log_chain_head`) for a batch is written in the same batch
as its CBOMs.

## Verifier (Python)

A standalone verifier (`verify_tamper_evidence.py`, at the repository root) that
checks the chain end-to-end. Given the output directory, the
`--tamper-evident-log` file, and the checksum file:

```
python3 verify_tamper_evidence.py --out <dir> --log <run.log> --checksum <*.json>
```

It must:

1. **Replay the log chain.** Walk the log file, recompute each line's cumulative
   digest, and confirm the last digest equals `final_chain_head` in the checksum
   file. Confirm the correlation ID line is the first line.
2. **Check the checksum file.** Confirm `correlation_id` matches.
3. **For each `.grc`** in `grcs`, recompute its SHA-256 and confirm it matches
   the recorded `sha256`. Confirm its `info.correlation_id` equals the run
   correlation ID, and that its `info.log_chain_head` is a **prefix** (an
   earlier ancestor) of `final_chain_head` — i.e., the chain state embedded in
   the `.grc` is a valid earlier point on the same chain.
4. **For each `.grd`/`.gri`** referenced by a `.grc`, recompute its SHA-256 and
   confirm it matches the `info.sha256` array (index-aligned).
5. **CBOM naming.** For each root, confirm the CBOM filename matches
   `cbom_<escaped-first-file-name>_<last-16-of-gitoid>.json` and that the CBOM
   content's root gitoid matches.

Any mismatch is reported. The verifier is a Python script (the project's
non-JVM tooling convention) so it can be run on a system separate from the one
that produced the logs/ADGs.

## Test plan (red-to-green)

1. **Chain appender unit test** — N log lines produce a verifiable chain; a
   tampered line (edit payload) breaks verification; a truncated line breaks it.
2. **Chain-head exposure** — `currentChainHead()` reflects lines emitted so far;
   digest algorithm matches the verifier's.
3. **Correlation ID** — generated at run start, logged first, stable across a
   run, written into every `.grc`.
4. **`.grc` `info`** — `correlation_id` and `sha256` present on every `.grc`;
   `log_chain_head` present only with `--tamper-evident-log`; `sha256` is
   index-aligned with `dataFiles`/`indexFiles`.
5. **CBOM naming** — naming matches the spec for a normal name, a dotted name,
   a name with unsafe characters, a full-path name (`--fs-file-paths`, truncated),
   and the last-16 gitoid; deterministic across runs.
6. **Checksum file** — one file in the base dir, correct JSON shape, lists all
   `.grc`s across batches, `final_chain_head` matches the log end.
7. **`--print-files`** — routes through the logger (chained), not stdout; input
   list present in the log.
8. **End-to-end (integration)** — a multi-batch run (`--maxrecords` small)
   produces a valid chain + checksum; the Python verifier passes on the outputs
   and fails on a tampered ADG/log.