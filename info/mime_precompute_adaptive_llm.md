# Adaptive MIME Precompute — LLM Digest

> **Navigation:** [Documentation Index](README.md) · Human twin: `mime_precompute_adaptive.md`

## One paragraph

`AdaptiveMimeBuilder.computeMimeTypes(files: Vector[ArtifactWrapper], args:
Config, logger): MimePassResult` replaces the old
`allFiles.par.foreach(f => f.mimeType)`. It runs a bounded set of
long-lived virtual-thread workers (start 2, bound `min(32, max(8,
args.threads))`) that pull file indices from an `AtomicLong` cursor. Each
worker forces `mimeType` on its file — the worker body is just
`a.mimeType`, nothing else — times it, and feeds the completion
time to `AdaptiveParallelism`, a pure median-of-window controller that
halves the worker target on slow windows and probes +1 on fast windows. The
calling thread is the coordinator (spawn/retire/drain); there are no
executor pools, no reflection, no per-file threads. Any failure escaping
the `mimeType` call is caught at the worker boundary, counted, and logged
(sanitized path, full cause, rate-limited); the pass never raises and
completes when `completed + failed == total`.

## Key files

- `src/main/scala/io/spicelabs/goatrodeo/util/AdaptiveParallelism.scala` —
  controller: `record(nanos)`, `forceWindowClose()`, `current`.
  Constructor params clamp (never raise); defaults: window 128
  completions/1s, collapse 3.0×/2 windows, growth 1.5×/3 windows, cooldown
  10 windows, emaAlpha 0.5, floorAlpha 0.1.
- `src/main/scala/io/spicelabs/goatrodeo/util/AdaptiveMimeBuilder.scala` —
  `MimePassResult(total, completed, failed, firstFailure, failureDirs)`,
  `sanitizePath`, `progressLog`, `computeMimeTypes` with injectable
  `progressEvery`, `progress: Option[(Long, Int) => Unit]`,
  `controller: Option[AdaptiveParallelism]`. The per-file computation is NOT
  injectable: the worker body is always `a.mimeType`.
- `src/main/scala/io/spicelabs/goatrodeo/omnibor/ToProcess.scala` — the call
  site (`buildQueueOnSeparateThread` gained an `args: Config` parameter;
  `Builder.scala` passes it).

## Invariants a reader can rely on

1. Exactly-once: every index is claimed once; each claim resolves as
   completed or failed → `total == completed + failed` always.
2. Never raises: per-file failures are counted; only a JVM fatal or an
   interrupt of the coordinator thread ends the pass early (partial
   accounting is returned).
3. Bounded workers: distinct worker threads ≤ the bound (test-pinned
   `T-A-03`); all virtual (`T-A-04`).
4. Deterministic controller: policy is a function of recorded completion
   times only; synthetic-trace tests pin every transition.
5. Ordering: once `failed` reaches the total, `firstFailure` and
   `failureDirs` are already captured; after resolution the coordinator
   drains workers so no log line is in flight when the pass returns.

## Test map

- `AdaptiveMimeBuilderSuite` — T-A-01..08 (deleted file, uniform wrappers,
  worker reuse bound, virtual threads, target growth, Option progress, empty
  corpus, real drain). Worker identity is observed via the progress
  callback, which fires on the resolving worker thread.
- `AdaptiveParallelismSuite` — T-AP-01..09 (defaults, clamping, boundedness
  property, collapse, growth, cooldown, median robustness, partial windows,
  buffer growth).
- `MimePipelineRulesSuite` — T-B-01..04 (source scans: no
  throw/require/assert; no withFile; no null/reflection/executors; catch-all
  boundary + counted failures present).

## Gotchas

- `mimeType` on `ArtifactWrapper` already guards its own I/O (returns
  octet-stream on failure), so most real-world file issues never reach the
  pass boundary and `failed` is almost always 0 in production; the boundary
  exists for what can still escape (e.g. a `StackOverflowError` from a
  pathological file).
- The controller re-grows after a slow steady state once the floor catches
  the EMA — that is correct behavior (steady slow work is the new normal).
- Log rate limit uses the per-failure sequence number (`failed.incrementAndGet()`),
  making the first-100 + per-10,000 limit exact under concurrency.
- `-release` is 21: `Thread.ofVirtual()` requires it.
