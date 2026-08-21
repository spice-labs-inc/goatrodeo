# Tamper-Evident Logging — LLM Reference

## Goal

Make Goat Rodeo's ADG outputs auditable against the run that produced them, and
make tampering detectable and costly (touch many files across many systems).
This is **unkeyed** (no HMAC/key management) by design: it detects tampering but
does not prevent an adversary who fully controls the log file.

## Trust chain

1. Correlation ID (UUID) generated at run start; logged as the first log line
   (always); recorded in every `.grc`.
2. Hash-chained logger records all subsequent lines (enabled by
   `--tamper-evident-log <file>`).
3. Each batch's `.grc` embeds the log chain head at write time (covers log up to
   a few lines before it).
4. Each `.grc` also records full SHA-256 of every `.grd`/`.gri` (index-aligned)
   + correlation ID.
5. Full SHA-256 of each `.grc` is logged (a chained line).
6. End of run: final checksum JSON in the base output dir records correlation
   ID, final chain head, and every `.grc` (name + full SHA-256) across all batch
   subdirs.

## Files / CLI

- `--tamper-evident-log <file>` — installs a custom logback appender
  programmatically (not via consumer `logback.xml`, which is excluded from the
  jar). The appender is the single serialization point (logback serializes
  appender calls), giving a total order under concurrency.
- `--print-files` — **changed** to log each processed top-level file via the
  logger (not `println`), so it is chained when tamper-evidence is on.

## Chain algorithm

- `digest_1 = SHA256(payload_1)`
- `digest_N = SHA256(digest_{N-1} || payload_N)`
- Each line emitted as `<digest_N> <payload_N>` (self-contained; verifiable
  line-by-line). The digest is the plain single SHA-256 of the payload (the
  payload's trailing newline is not hashed).
- Appender exposes `currentChainHead()`; `GraphManager` embeds it into `.grc`.

## `.grc` `info` additions (purely additive; do NOT bump ClusterFileEnvelopeVersion)

| key | value | gate |
|-----|-------|------|
| `correlation_id` | uuid | always |
| `sha256` | JSON string `{"grd":[<sha>…],"gri":[<sha>…]}` full 256-bit, index-aligned with `dataFiles`/`indexFiles` | always |
| `log_chain_head` | hex | only with `--tamper-evident-log` |

## CBOM filename (change)

`cbom_gitoid_blob_sha256_<fullhex>.json` → `cbom_<escaped-first-file-name>_<last-16-of-gitoid>.json`.

- name = root Item's first `fileNames` (TreeSet → deterministic sorted first)
- escape: chars outside `[A-Za-z0-9_-]` → `_`
- if full path (`--fs-file-paths`), truncate the escaped name (keep tail); cap is
  a named constant
- last-16 = last 16 hex chars after stripping `gitoid:blob:sha256:`
- 64-bit collision on last-16 accepted (full gitoid is inside the CBOM)

## Final checksum file

- Name: `goat_rodeo_<correlationId>_checksum.json`, in the **base** output dir,
  written as the **final action** (no chained line follows; final_chain_head =
  digest of the last log line).
- Contents: `{correlation_id, final_chain_head, grcs:[{name, sha256}]}`.
- Accumulated from each batch's `writeGoatRodeoFiles` return; chain is global +
  continuous across the run.

## CBOM

- Filename `cbom_<escaped-first-file-name>_<last-16-of-gitoid>.json` (see above).
- Each CBOM carries a top-level `goatrodeo:correlation-id` property (present only
  when a correlation ID is set), linking it to the run and its tamper-evident log.

## CBOM emission placement (unchanged)

Per-batch `emitForStorage` (when that batch's ADG is in memory). Multi-batch →
suffixed subdirs `<dest>_<n>`; unchanged.

## Python verifier

`verify_tamper_evidence.py` (repo root; requires `cbor2`). Run:
`python3 verify_tamper_evidence.py --out <dir> --log <run.log> --checksum <*.json>`.
Steps: (1) replay log chain, last digest == `final_chain_head`, correlation ID
first line; (2) checksum file matches; (3) each `.grc` SHA-256 recomputed ==
recorded, its `correlation_id` matches, its `log_chain_head` is a point on the
chain; (4) each `.grd`/`.gri` SHA-256 == `info.sha256`; (5) CBOM filename ==
`cbom_<escaped>_<last16>.json` and agrees with the root gitoid/name from the
CBOM's `goatrodeo:omnibor-path`/`goatrodeo:path`.

## Tests (planned)

Chain appender verifiability + tamper/truncation detection; chain-head exposure;
correlation id generation/placement; `.grc` info always vs flag-gated + alignment;
CBOM naming (normal/dotted/unsafe/full-path-truncated/deterministic); checksum
file shape + all batches + chain head; `--print-files` routed to logger;
end-to-end multi-batch integration + Python verifier pass/fail.