# Adaptive MIME Precompute

> **Navigation:** [Documentation Index](README.md)

The MIME precompute pass forces the (lazy) `ArtifactWrapper.mimeType` on
every file in the corpus before the strategy phase runs, so later phases can
filter and route by MIME type without paying detection costs per access.

## Architecture

```
ToProcess.buildQueueOnSeparateThread (runs on its own thread)
  └── AdaptiveMimeBuilder.computeMimeTypes(files, args, logger)
        ├── AdaptiveParallelism        # concurrency policy (pure, deterministic)
        ├── coordinator loop (caller thread): spawn/retire workers,
        │     wall-clock window closes, drain, completion check
        └── workers: virtual threads ("mime-pass-N"), long-lived,
              pull indices from an AtomicLong cursor, call mimeType,
              record completion times, count failures
```

- **Workers** are long-lived virtual threads (`Thread.ofVirtual()`): one
  thread per worker slot, not per file. Each loop iteration claims the next
  index (`AtomicLong.getAndIncrement`), runs the file, records the
  completion time with the controller, and loops. A worker retires when the
  controller's target has shrunk below the live worker count, or when the
  work is exhausted.
- **The coordinator is the calling thread.** It spawns workers until the
  live count equals the controller target (or work is exhausted), closes
  controller windows on the wall-clock cadence (`windowNanos`), and parks
  (~100µs) when there is nothing to do, so it does not busy-spin.
- **Completion** is progress-based: when `completed + failed == total`,
  every file has resolved exactly once. The coordinator then waits for the
  winding-down workers (final log lines) and returns. No file failure can
  change this: failures are counted, never raised.
- **No pools, no reflection.** The only shared state is the cursor and a few
  atomics. This is the deliberate opposite of the failed first design, which
  carried a dedicated carrier scheduler attached reflectively.

## Adaptation policy (`AdaptiveParallelism`)

The controller is a pure state machine over completion times (no I/O, no
clock reads), driven identically in production and in tests:

| Mechanism | Behavior |
|-----------|----------|
| Window | `windowSize` completions (default 128), or whatever has accumulated at a wall-clock close (default 1s) |
| Statistic | median completion time per window (outlier-resistant) |
| Signal | EMA of medians vs a slowly-decaying floor (best median seen) |
| Collapse | `ema > 3.0 × floor` for 2 consecutive windows → concurrency halves |
| Growth | `ema < 1.5 × floor` for 3 consecutive windows → concurrency +1 |
| Cooldown | 10 windows after a collapse, growth is banned (settles borderline corpora) |

Defaults: min 1, max `min(32, max(8, --threads))`, start 2.
All constructor parameters clamp instead of raising (`T-AP-02`).

## Failure handling

Each worker treats its current file as a boundary:

- Whatever escapes a worker's `mimeType` call is caught there,
  counted, and logged. Nothing propagates out of the pass.
- Logs carry the **sanitized** path (C0/C1 control characters escaped, so an
  untrusted corpus cannot inject terminal escapes) and the **full cause
  chain** — the previous production failure lost its root cause to
  `getMessage()`-only logging, which this design forbids.
- Rate limit: the first 100 failures are logged in full, then one summary
  line per 10,000. The per-failure sequence number makes the limit exact
  under concurrency.
- The result `MimePassResult(total, completed, failed, firstFailure,
  failureDirs)` always satisfies `total == completed + failed`. In practice
  `failed` is almost always 0: `ArtifactWrapper.mimeType` already guards its
  own I/O internally (returns `application/octet-stream` on trouble), so the
  boundary exists for whatever can still escape it (e.g. a JVM-level
  `StackOverflowError` from a pathological file — the likely killer of the
  33.5h production run, which previously escaped the pass entirely).

## Tuning

- `--threads` raises the adaptive upper bound (cap 32).
- The wall-clock window close (`windowNanos`, 1s) matters only for very slow
  workloads where 128 completions take longer than 1s.
- All policy knobs live on `AdaptiveParallelism` and are injectable into
  `computeMimeTypes` (`controller` parameter) for testing and experiments.
  The per-file computation is not injectable — the worker body is always
  `a.mimeType`, nothing else.

## Known properties (documented, by design)

- A worker blocked forever in a native blocking call would prevent
  resolution (documented deployment property; the plain `.par` code had the
  same property — see the ADR).
- A JVM-level fatal (OOM) propagates loudly instead of being counted.

## Claims and their tests

| # | Claim | Verified by |
|---|-------|-------------|
| C1 | Nothing a file does can escape the pass: the worker body is a catch-all boundary around `a.mimeType` with counted failures, and the in-scope files contain no `throw`. | `MimePipelineRulesSuite.T-B-01`, `T-B-04`; behavior on real wrappers in `AdaptiveMimeBuilderSuite.T-A-01`, `T-A-08` |
| C2 | A file deleted after the walk completes the pass without failure (the wrapper guards its own I/O). | `AdaptiveMimeBuilderSuite.T-A-01` |
| C3 | `FileWrapper` and `ByteWrapper` flow through the same worker path; no wrapper-type special-casing. | `AdaptiveMimeBuilderSuite.T-A-02` |
| C4 | Workers are long-lived and bounded: distinct threads stay within the bound, never one per file. | `AdaptiveMimeBuilderSuite.T-A-03` |
| C5 | All workers are virtual threads (no pools). | `AdaptiveMimeBuilderSuite.T-A-04` |
| C6 | Worker count grows under sustained fast completions (wiring); the collapse policy is pinned with synthetic traces. | `AdaptiveMimeBuilderSuite.T-A-05`, `AdaptiveParallelismSuite.T-AP-04` |
| C7 | Progress is an `Option` callback firing every `progressEvery` completions. | `AdaptiveMimeBuilderSuite.T-A-06` |
| C8 | The empty corpus completes immediately. | `AdaptiveMimeBuilderSuite.T-A-07` |
| C9 | Real `mimeType` work drains cleanly; the pass terminates. | `AdaptiveMimeBuilderSuite.T-A-08` |
| C10 | Controller defaults are byte-identical to the approved v1 algorithm. | `AdaptiveParallelismSuite.T-AP-01` |
| C11 | Controller construction never raises; invalid parameters clamp. | `AdaptiveParallelismSuite.T-AP-02` |
| C12 | The concurrency target stays within [min, max] for arbitrary traces. | `AdaptiveParallelismSuite.T-AP-03` |
| C13 | Collapse/growth/cooldown policies behave as specified. | `AdaptiveParallelismSuite.T-AP-04`, `T-AP-05`, `T-AP-06` |
| C14 | The window statistic is the median (outlier-robust); partial windows and buffer growth work. | `AdaptiveParallelismSuite.T-AP-07`, `T-AP-08`, `T-AP-09` |
| C15 | The in-scope files contain no `throw`/`require(`/`assert(`. | `MimePipelineRulesSuite.T-B-01` |
| C16 | The in-scope files contain no `withFile` (uniform wrapper abstraction). | `MimePipelineRulesSuite.T-B-02` |
| C17 | The in-scope files contain no `null`, reflection, or executor pools. | `MimePipelineRulesSuite.T-B-03` |
| C18 | The integrated pipeline (Builder → ToProcess → pass) still runs the full regression suite. | full `sbt test` run |

## Related

- `info/adrs/adr_2026_08_17_adaptive_mime_workers.md`
- `src/main/scala/io/spicelabs/goatrodeo/util/AdaptiveMimeBuilder.scala`
- `src/main/scala/io/spicelabs/goatrodeo/util/AdaptiveParallelism.scala`
- `info/mime_types.md` (MIME detection itself)
