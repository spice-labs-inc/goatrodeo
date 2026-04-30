# Certificates corpus-authoring tools

Runs inside the Docker image built from `Dockerfile` in this directory.
Project invariant #13 (CLAUDE.md): any script requiring more than
git / docker / JVM must run in a Docker container; volumes preserve the
invoker's UID/GID via `--user "$(id -u):$(id -g)"`.

## Build the image

From the repo root:

```
docker build -t goatrodeo-certcorpus-tools test_data/certificates/tools/
```

## Generate a draft sidecar

```
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v "$(pwd)":/work \
  -w /work \
  goatrodeo-certcorpus-tools \
  test_data/certificates/tools/compute-expected.sh \
    test_data/certificates/x509/rsa2048-isrg-root-x1.pem \
  > test_data/certificates/x509/rsa2048-isrg-root-x1.pem.expected.json
```

The emitted JSON is a **draft** — every field tagged `<review>` or
`<compute>` must be filled in or verified by a human before the sidecar
is committed. The script never guesses a pURL; it only computes raw
cryptographic fingerprints.

## What the tool produces

For supported kinds (PEM X.509, DER X.509, SSH public key) the tool
computes the actual cryptographic fields (SPKI SHA-256, cert SHA-256,
SSH fingerprint) and fills them into the draft sidecar directly.
Algorithm, curve, size, and sig-alg qualifiers are left as `<review>`
because the canonical form depends on the plan's Appendix A vocabulary
and should be eyeballed against the strategy plan.

For unsupported or ambiguous kinds (keystores, CRLs, PGP, private keys,
keystore formats) the tool emits a skeleton and labels everything
`<review>`. Humans fill those in by hand using the per-phase metadata
tables in `certificates-strategy/phases-*.md`.

## Safety notes

- The script never writes any file other than stdout (the JSON sidecar).
  It never modifies the fixture. Safe to run against any file.
- GPG operations use an ephemeral `$GNUPGHOME` pointing at
  `/tmp/gnupg-scratch` inside the container so the invoker's real
  keyring is never touched.
- The container has no network access by default. Sidecars are
  computed from local fixture bytes only.

## Testing the tool

A round-trip smoke test will land in Phase 3 once real X.509 fixtures
exist: run `compute-expected.sh` against each fixture, diff the
emitted draft against the committed sidecar, confirm the computed
fields (SHA-256 hashes) agree exactly. This catches tool regressions
or accidental sidecar drift.
