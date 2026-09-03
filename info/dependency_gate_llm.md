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
2. `sbt osvCheck` runs the gate entirely inside the build tool (no
   Python, no shell scripts — the `osvCheck` task is implemented by
   `io.spicelabs.goatrodeo.util.OsvGate`, compiled with the product and
   invoked by the task). It filters to the product's configurations
   (compile, compile-internal, test, test-internal, runtime,
   runtime-internal, provided, provided-internal), excludes the
   build-tool-only configurations (scala-tool, scala-doc-tool,
   scala-repl-tool) and the guava `9999.0` placeholder, builds the OSV
   batch-query body, and POSTs it to `https://api.osv.dev/v1/querybatch`.
3. The gate prints a JSON summary (queries, findings, unscored, high)
   and ends with PASS, FAIL (task throws), or INFRA (task throws —
   transport error or malformed response; never a silent pass).

## Semantics

- **Fail-open on unscored advisories.** An advisory without a resolvable
  CVSS score is reported to stderr (warn) and never fails the gate.
- **Fail at the boundary.** A score of exactly 7.0 fails; 6.999 passes.
- **Independent CI job.** The `osv` job in
  `.github/workflows/build_test.yml` runs separately from the build/test
  job (no `needs:`).
- The gate's exact request is pinned by `OsvGateScriptSuite` against a
  loopback stub, so CI changes don't silently break.

## Running locally

```sh
sbt osvCheck
```

## Claims → tests

| Claim | Test |
|---|---|
| The fat jar survives sqlite natives + one JDBC driver; no signature files | `FatJarContentsTest.T1.4a–c` |
| RPM payload streaming works end to end | `RpmStreamingSuite.T1.5` |
| The dump is the raw resolved set incl. tool configs; sorted; contains the pins | `OsvDumpSuite.T2.1–T2.4` |
| The gate posts exactly the filtered batch to /v1/querybatch | `OsvGateScriptSuite.T3.1` |
| Verdict matrix incl. the 7.0 boundary | `OsvGateScriptSuite.T3.2` |
| Unscored advisories pass fail-open and are reported | `OsvGateScriptSuite.T3.3` |
| Transport/malformed = distinct INFRA failure | `OsvGateScriptSuite.T3.4, T3.5` |
| CI declares an independent `osv` job running `sbt osvCheck` | `OsvCiWiringSuite.T4.1a–c` |