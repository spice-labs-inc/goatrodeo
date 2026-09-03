# Goat Rodeo Dependency Gate (OSV)

> Human documentation; the LLM copy is `dependency_gate_llm.md`.

## What it does

Every CI run queries [api.osv.dev](https://osv.dev) (no API key) with the
product's **resolved dependency set** — everything the product ships,
builds, and tests — and fails the CI job if any dependency (direct or
transitive) has a **concrete CVSS score ≥ 7.0** (high or above).

## How it works

1. `sbt osvDumpJson` writes `target/osv-dump.json`: the raw resolved
   dependency set (every configuration) from the build tool's own
   resolution. The build tool is the single source of truth; nothing is
   hand-maintained.
2. `python3 housekeeping/osv_check.py --input target/osv-dump.json`
   filters to the product's configurations (compile, compile-internal,
   test, test-internal, runtime, runtime-internal, provided,
   provided-internal), excludes the build-tool-only configurations
   (scala-tool, scala-doc-tool, scala-repl-tool) and the guava `9999.0`
   placeholder, builds the OSV batch-query body, and POSTs it to
   `https://api.osv.dev/v1/querybatch`.
3. The script prints a JSON summary (queries, findings, unscored, high)
   and exits:
   - `0` pass — no finding with a concrete score ≥ 7.0
   - `1` fail — at least one finding with a concrete score ≥ 7.0
   - `2` infrastructure failure — transport error or malformed
     response (CI should alert; never a silent pass)

## Semantics

- **Fail-open on unscored advisories.** An advisory without a resolvable
  CVSS score is reported to stderr (warn) and never fails the gate.
- **Fail at the boundary.** A score of exactly 7.0 fails; 6.999 passes.
- **Independent CI job.** The `osv` job in
  `.github/workflows/build_test.yml` runs separately from the build/test
  job (no `needs:`).
- The script's exact request is pinned by `OsvGateScriptSuite` against a
  loopback stub, so CI changes don't silently break.

## Running locally

```sh
sbt osvDumpJson
python3 housekeeping/osv_check.py --input target/osv-dump.json
```

## Claims → tests

| Claim | Test |
|---|---|
| Resolved set contains exactly the pinned versions; no SNAPSHOTs; lz4 from the at.yawk fork | `ResolutionPinsSuite.T1.1–T1.3` |
| The fat jar survives sqlite natives + one JDBC driver; no signature files | `FatJarContentsTest.T1.4a–c` |
| RPM payload streaming works end to end | `RpmStreamingSuite.T1.5` |
| The dump is the raw resolved set incl. tool configs; sorted; contains the pins | `OsvDumpSuite.T2.1–T2.4` |
| The gate script POSTs exactly the filtered batch to /v1/querybatch | `OsvGateScriptSuite.T3.1` |
| Verdict matrix incl. the 7.0 boundary | `OsvGateScriptSuite.T3.2` |
| Unscored advisories pass fail-open and are reported | `OsvGateScriptSuite.T3.3` |
| Transport/malformed = distinct exit 2 | `OsvGateScriptSuite.T3.4, T3.5` |
| CI declares an independent `osv` job running dump + script | `OsvCiWiringSuite.T4.1a–c` |