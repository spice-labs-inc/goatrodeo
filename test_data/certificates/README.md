# Certificates Fixture Corpus

This directory holds the test corpus for the **Certificates** strategy
(see `../../certificates-strategy-plan.md` and
`../../certificates-strategy/phase-0-corpus.md`).

An LLM-friendly parallel copy lives at [`README_llm.md`](README_llm.md).

## Layout

```
test_data/certificates/
├── README.md                # this file
├── README_llm.md            # LLM-friendly parallel copy
├── x509/                    # individual X.509 certificates, PEM and DER
│   ├── SOURCES.md           # URL + retrieval-date + SHA-256 per fixture
│   ├── generate.sh          # (optional) deterministic generator for synthetic certs
│   └── *.pem|*.der ...      # fixture files
├── keystores/               # JKS, JCEKS, PKCS#12, BKS
├── pem-bundles/             # multi-block PEM bundles
├── crls/                    # X.509 CRLs, PEM and DER
├── ssh/                     # SSH public keys and OpenSSH certs
├── pgp/                     # PGP public keys (armored and binary)
├── private-keys/            # unencrypted and encrypted private keys (test-only)
├── edge-cases/              # truncated, malformed, edge-case files
└── tools/                   # corpus-authoring utilities (Docker-wrapped)
```

Every fixture file `foo.ext` is paired with a sidecar
`foo.ext.expected.json` (schema documented in
`../../certificates-strategy/appendices.md` Appendix B). The sidecar
declares the exact Items, pURLs, MIME types, and metadata the Certificates
strategy must emit for that fixture.

## Adding a new fixture

1. Pick the right category directory. If no category fits, stop and raise
   the question — do not invent a new category silently.

2. Obtain the fixture:
   - **Downloaded fixtures** must come from a stable public URL (CA root
     store, CT log, kernel.org, keys.openpgp.org, etc.). Pin the URL and
     the SHA-256 of the downloaded bytes.
   - **Generated fixtures** must be reproducible. Commit the generating
     script (`generate.sh`). Never commit private-key material that serves
     only as generator input — the generator removes those before exit.

3. Produce the sidecar. Run `tools/compute-expected.sh` against the
   fixture to emit a draft sidecar; verify every computed field manually
   using an external tool (`openssl x509`, `ssh-keygen -lf`,
   `gpg --list-keys`, `sha256sum`); commit the pair.

4. Append one row to the category's `SOURCES.md`:
   `| filename | source URL | YYYY-MM-DD | sha256:<hex> |`.

5. Verify locally with `sbt "testOnly strategies.CertificatesSuite"` — the
   new test should go red (if pre-Phase 3) or green (if the strategy is
   implemented).

## Coverage floor

Phase 0 requires at least 200 paired `(fixture, sidecar)` pairs across
all categories combined. The
`CertificatesCorpusIntegritySuite.corpus contains at least 200 fixtures`
test enforces this. Phase 8's coverage suite further enforces that every
required `(algorithm, size/curve/params, artifact-type)` cell has at least
one representative.

## Private-key policy

No real private-key material may land here, ever. Test private keys
committed to `private-keys/` must carry the banner comment
`# GOAT RODEO TEST KEY - NOT A SECRET - DO NOT USE ANYWHERE ELSE`.
Generators that produce transient private keys as byproducts of fixture
creation must `rm` those keys before exit (see e.g. the ed25519 example
in `certificates-strategy/phases-3-4-x509-containers.md`).

The private-key leak guard lives in
`src/test/scala/strategies/CertificatesAssertions.assertNoForbiddenPatterns`
and Phase 8's dedicated `CertificatesLeakSuite` (not yet implemented).
Forbidden patterns are listed in
`../../certificates-strategy/appendices.md` Appendix C.

## Git LFS

Large binary fixtures (keystores, CRL binaries, DER certs above a few
KB) go through Git LFS. The project's sbt test setup already runs
`git lfs pull` — see `build.sbt`'s LFS hook.

## Regenerating generated fixtures

Each category directory that contains generated fixtures has a
`generate.sh`. Run it inside the Docker container published by
`tools/Dockerfile` so the openssl / ssh-keygen / gpg versions are pinned.
User/group IDs are preserved via `--user "$(id -u):$(id -g)"` so the
generated files are owned by the invoker, per project invariant #13.

## How expected sidecar values are computed

See `tools/compute-expected.sh` and its Docker wrapper. Preferred path
(plan sub-goal):
> Pre-compute using an external tool — openssl x509 -pubkey, sha256sum,
> ssh-keygen -lf — and record the expected value in the sidecar now.
> This decouples expected values from the implementation, so a buggy
> implementation can't accidentally pass.

Fallback: commit `"<computed>"` placeholders, run the eventual strategy
once to fill them in, manually verify each by re-running the external
tool, then lock in. Reviewer must see verification notes in the PR.
