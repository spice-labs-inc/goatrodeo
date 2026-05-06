# Phase 0 — Claims and test traceability

**Parent plan:** [`../../certificates-strategy-plan.md`](../../certificates-strategy-plan.md)
**Phase doc:** [`../../certificates-strategy/phase-0-corpus.md`](../../certificates-strategy/phase-0-corpus.md)
**LLM-friendly parallel copy:** [`phase-0-claims_llm.md`](phase-0-claims_llm.md)

Per invariant #12, every factual claim about what Phase 0 delivers is paired
with a test that verifies it. A hostile reviewer can walk this table, run
the named test, read the test source, and confirm it actually exercises the
claim.

## What Phase 0 delivered

Phase 0 was split, with maintainer approval (HS-1), into:

- **Phase 0a — infrastructure:** harness, schema, tools, documentation,
  directory scaffold. **Complete.**
- **Phase 0b — corpus population:** 200+ real-world fixtures with
  sidecars. **Complete.** The corpus now contains exactly 200 paired
  `(fixture, sidecar)` pairs, split as:

  | Category | Count | Source |
  |---|---|---|
  | `x509/mozilla/` | 145 | Fanned out from `https://curl.se/ca/cacert.pem` |
  | `x509/canonical/` | 6 | Let's Encrypt (X1/X2/R3/E1/E2) + DigiCert G2, individually pinned |
  | `x509/synthetic/` | 6 | Generated (Ed25519, ECDSA P-256/P-384, RSA-2048, SHA-1-legacy, intermediate) + DER re-encoding |
  | `x509/pqc/ml-dsa/` | 6 | ML-DSA-44/65/87 pure + pre-hash variants (IETF Hackathon BC r5) |
  | `x509/pqc/slh-dsa/` | 8 | SLH-DSA SHA-2 (128/192/256 × s/f) + SHAKE-128s + SHAKE-256f |
  | `x509/pqc/falcon/` | 2 | Falcon-512 + Falcon-1024 (round-3 alternate) |
  | `x509/pqc/composite/` | 5 | ML-DSA hybrid: + RSA-PSS, + Ed25519, + ECDSA-P256, + ECDSA-P384, + RSA-3072-PSS |
  | `pem-bundles/` | 2 | Mozilla bundle + synthetic 2-cert chain |
  | `keystores/synthetic/` | 3 | JKS, JCEKS, PKCS#12 (encrypted, envelope-only coverage) |
  | `crls/synthetic/` | 4 | Empty, small, SHA-1, DER |
  | `ssh/synthetic/` | 6 | RSA-4096, Ed25519, ECDSA P-256/P-384 + user cert + host cert |
  | `ssh/github/` | 7 | Real-world `github.com/{user}.keys` for stable maintainer accounts |
  | `pgp/synthetic/` | 2 | v4 RSA, v4 Ed25519 (armored public keys) |
  | `private-keys/synthetic/` | 8 | 4 unencrypted (RSA-2048, Ed25519, OpenSSH Ed25519, OpenSSH RSA-4096) + 4 encrypted (PKCS#8 PBKDF2, PKCS#8 scrypt, OpenSSH bcrypt, PEM-legacy AES) |
  | `edge-cases/` | 10 | Truncated, empty, whitespace, typo-header, wrong-magic, DER-prefix-not-X509, plain-text, blank-lines, only-begin-marker, random-noise |
  | `x509/historical/` | 1 | Distrusted/expired root from crt.sh archive |
| `keystores/real/` | 1 | Pinned-JDK OpenJDK-21 cacerts (encrypted-envelope path) |
| `keystores/synthetic/` | +1 | BKS via BC 1.79 (`tools/GenerateBks.java`) |
| `crls/real/` | 6 | DigiCert + GlobalSign + Sectigo CRLs |
| `pgp/real/` | 9 | Real maintainer keys via keys.openpgp.org |
| `pgp/synthetic/` | +1 | v6 Ed25519 (RFC 9580) via BC 1.80 (`tools/GeneratePgpV6.java`) |
| `ssh/github/` | +8 | More maintainer accounts (15 total) |
| **Total** | **249** | |

  Coverage-matrix state:
    - **PQC** — substantial coverage. ML-DSA at all three NIST levels
      (44/65/87) pure + pre-hash; SLH-DSA at all three levels ×
      {fast, small} for SHA-2 + SHAKE samples; Falcon 512/1024;
      composite (hybrid) ML-DSA + classical. From IETF Hackathon BC r5.
    - **Null-password-readable trust store** — `keystores/synthetic/
      trust-only-null-password.p12` via `GenerateTrustOnlyNullPassword
      .java`.
    - **PGP v6** — `pgp/synthetic/v6-ed25519-pub.asc` via BC 1.80's
      `OpenPGPV6KeyGenerator` (`tools/GeneratePgpV6.java`).
    - **BKS keystore** — `keystores/synthetic/trust-bks.bks` via BC
      1.79 provider (`tools/GenerateBks.java`).
    - **Real CA-published CRLs** — 6 from DigiCert / GlobalSign /
      Sectigo at `crls/real/`.
    - **Real maintainer PGP keys** — 9 from keys.openpgp.org at
      `pgp/real/`.
    - **Pinned-JDK cacerts** — `keystores/real/openjdk-21-cacerts.jks`
      copied from the running OpenJDK install.
    - **More real-world SSH** — 15 GitHub maintainer accounts.
    - **Historical/distrusted X.509** — 1 from crt.sh archive at
      `x509/historical/` (URLs for Symantec/Entrust 403'd; further
      backfill needs alternate sources).
    - **`compute-expected.sh` reviewer tool** — fixed (SubjectDN/
      IssuerDN prefix bug eliminated) and now covered by 5 tests in
      `ComputeExpectedToolTests`.
    - **`generate.sh` per category** — wrappers added for x509,
      ssh, pgp, crls, private-keys, edge-cases, pem-bundles
      (delegating to `bootstrap_synthetic.py --category`); plus the
      pre-existing keystores wrapper.
    - ML-KEM (key-only — encryption not signing) deferred to Phase 7.

## Claim → test matrix

| # | Claim | Verified by |
|---|---|---|
| 1 | Sidecar schema is defined end-to-end with required and optional fields | `strategies.CertificatesSidecarTests.valid minimal sidecar parses`, `strategies.CertificatesSidecarTests.valid full sidecar parses including optional fields` |
| 2 | Sidecar parser rejects missing required fields with a message naming the field | `strategies.CertificatesSidecarTests.missing required field 'description' throws SidecarParseError` (and 7 more, one per required field) |
| 3 | Sidecar parser rejects malformed JSON with a "not valid JSON" message | `strategies.CertificatesSidecarTests.malformed JSON throws SidecarParseError with 'not valid JSON'` |
| 4 | Sidecar parser rejects wrong types on required fields | `strategies.CertificatesSidecarTests.wrong type on required field throws SidecarParseError` |
| 5 | Fixture discovery pairs `foo.pem` with `foo.pem.expected.json` in the same directory | `strategies.CertificatesFixtureInventoryTests.pairs fixture and sidecar sharing a stem` |
| 6 | Fixture discovery handles extension-less fixtures (e.g., `openssh-ed25519-unencrypted`) | `strategies.CertificatesFixtureInventoryTests.handles fixtures without file extensions` |
| 7 | Orphan sidecars are detected (sidecar without matching fixture) | `strategies.CertificatesFixtureInventoryTests.orphan sidecar is detected when no matching fixture exists` |
| 8 | Orphan fixtures are detected (fixture without matching sidecar) | `strategies.CertificatesFixtureInventoryTests.orphan fixture is detected when no matching sidecar exists` |
| 9 | Infrastructure files (README, SOURCES.md, `.gitkeep`, `generate.sh`) are excluded from fixture candidates | `strategies.CertificatesFixtureInventoryTests.excludes SOURCES.md, README.md, README_llm.md, .gitkeep, generate.sh from fixture candidates` |
| 10 | Files under `tools/` are excluded from fixture candidates | `strategies.CertificatesFixtureInventoryTests.excludes files under tools/ from fixture candidates` |
| 11 | `countByCategory` aggregates by immediate subdirectory | `strategies.CertificatesFixtureInventoryTests.countByCategory aggregates pairs by immediate subdirectory` |
| 12 | Empty corpus root yields zero fixtures and zero orphans | `strategies.CertificatesFixtureInventoryTests.empty root reports zero fixtures and zero orphans` |
| 13 | Nonexistent corpus root yields zero fixtures without throwing | `strategies.CertificatesFixtureInventoryTests.nonexistent root reports zero fixtures (does not throw)` |
| 14 | Same-stem sidecar and fixture in different directories do NOT pair | `strategies.CertificatesFixtureInventoryTests.sidecar in one directory does not pair with fixture in a different directory` |
| 15 | Hidden files (except `.gitkeep`, which is excluded anyway) are not fixture candidates | `strategies.CertificatesFixtureInventoryTests.hidden files (starting with .) other than .gitkeep are not fixture candidates` |
| 16 | `assertMimeTypesContain` passes on subset-presence, throws on missing | `strategies.CertificatesAssertionsTests.assertMimeTypesContain passes when all required are present`, `strategies.CertificatesAssertionsTests.assertMimeTypesContain throws when any required is missing` |
| 17 | `assertMimeTypesAbsent` passes when none of the forbidden are present, throws otherwise | `strategies.CertificatesAssertionsTests.assertMimeTypesAbsent passes when none of the forbidden are present`, `strategies.CertificatesAssertionsTests.assertMimeTypesAbsent throws when any forbidden is present` |
| 18 | `assertPurlsContain` passes on subset-presence, throws on missing | `strategies.CertificatesAssertionsTests.assertPurlsContain passes when all required pURLs are present`, `strategies.CertificatesAssertionsTests.assertPurlsContain throws when a required pURL is missing` |
| 19 | `<computed>` token matches any pURL sharing the non-placeholder segments | `strategies.CertificatesAssertionsTests.assertPurlsContain accepts <computed> placeholder when some pURL matches the surrounding segments` |
| 20 | `<computed>` still fails when no pURL shares prefix or suffix | `strategies.CertificatesAssertionsTests.assertPurlsContain with <computed> still throws when no pURL matches prefix or suffix` |
| 21 | `assertPurlsAbsent` throws when any forbidden pURL is present | `strategies.CertificatesAssertionsTests.assertPurlsAbsent throws when a forbidden pURL is present` |
| 22 | `purlsOf` excludes `gitoid:` aliases, includes only `pkg:` strings | `strategies.CertificatesAssertionsTests.purlsOf does not include non-pkg: edges` |
| 23 | `assertMetadataContains` passes on exact match | `strategies.CertificatesAssertionsTests.assertMetadataContains passes on exact match` |
| 24 | `<computed>` metadata placeholder checks presence + non-empty | `strategies.CertificatesAssertionsTests.assertMetadataContains accepts <computed> when key is present and non-empty` |
| 25 | `assertMetadataContains` throws on missing key | `strategies.CertificatesAssertionsTests.assertMetadataContains throws when a key is missing` |
| 26 | `assertMetadataContains` throws on value mismatch | `strategies.CertificatesAssertionsTests.assertMetadataContains throws when value mismatches` |
| 27 | `assertMetadataRanges` accepts values inside an inclusive range | `strategies.CertificatesAssertionsTests.assertMetadataRanges passes when a value is inside the inclusive range` |
| 28 | `assertMetadataRanges` rejects values outside bounds | `strategies.CertificatesAssertionsTests.assertMetadataRanges throws when no value lies in range` |
| 29 | `assertMetadataRanges` rejects unparseable bounds | `strategies.CertificatesAssertionsTests.assertMetadataRanges throws for unparseable bounds` |
| 30 | `assertMetadataKeysAbsent` throws when any forbidden key is present | `strategies.CertificatesAssertionsTests.assertMetadataKeysAbsent throws when a forbidden key is present` |
| 31 | Forbidden-pattern leak sweep catches PEM private-key markers | `strategies.CertificatesAssertionsTests.assertNoForbiddenPatterns catches a PEM private-key header in any metadata value` |
| 32 | Leak sweep catches the `openssh-key-v1` magic string | `strategies.CertificatesAssertionsTests.assertNoForbiddenPatterns catches the openssh-key-v1 magic string` |
| 33 | Leak sweep reports the offending metadata key in its message | `strategies.CertificatesAssertionsTests.assertNoForbiddenPatterns reports the specific key that matched` |
| 34 | Leak sweep is silent on clean metadata | `strategies.CertificatesAssertionsTests.assertNoForbiddenPatterns passes when no value matches any pattern` |
| 35 | Corpus root directory exists on disk | `strategies.CertificatesCorpusIntegritySuite.corpus root exists` |
| 36 | Every committed sidecar parses and declares required fields | `strategies.CertificatesCorpusIntegritySuite.every sidecar parses and declares required fields` |
| 37 | No orphan sidecars in committed corpus | `strategies.CertificatesCorpusIntegritySuite.no orphan sidecars` |
| 38 | No orphan fixtures in committed corpus | `strategies.CertificatesCorpusIntegritySuite.no orphan fixtures` |
| 39 | Per-fixture pipeline assertions iterate the full corpus | `strategies.CertificatesSuite` (parameterized — one test per `(fixture, sidecar)` pair; emits zero tests when corpus is empty, which is Phase 0a's state) |
| 40 | Corpus contains at least 200 paired fixtures | `strategies.CertificatesCorpusIntegritySuite.corpus contains at least 200 fixtures` (**green**, 200 of 200) |
| 41 | All sidecar field values for X.509 fixtures are ground-truth (cert SHA-256, SPKI SHA-256, subject DN, dates, key alg, sig alg) and not strategy-output-derived | Computed by `tools/cert_sidecar.py` using the `cryptography` library; cross-checked against `openssl` output for one canonical fixture (ISRG Root X1) in the session transcript |
| 42 | Synthetic fixture generators are reproducible | Scripts live in `test_data/certificates/tools/bootstrap_{mozilla,synthetic,canonical_roots}.py`; each runs to completion deterministically given the pinned inputs |
| 43 | Binary fixture types go through Git LFS | `test_data/certificates/.gitattributes` tracks `*.der`, `*.jks`, `*.jceks`, `*.bks`, `*.p12`, `*.pfx`, `*.gpg`, `*.pgp`, `*.bin`, `*.crl` |
| 44 | Per-fixture assertion tests discover all paired fixtures | `strategies.CertificatesSuite` — parameterized, emits one test per pair. Pending state until the `Certificates` strategy class exists (detected via `Class.forName`); all per-fixture tests are `.ignore`d uniformly, satisfying the plan's "no partial mix" rule. |
| 45 | Leak-guard pattern list matches Appendix C in full (8 patterns, not 4) | `strategies.CertificatesAssertionsTests.assertNoForbiddenPatterns catches PKCS#8 base64 prefix MIIEvQIBADAN`, `.* MIIEpAIBAAKCAQEA`, `.* MIIB...QIB... regex`, `.* full PEM private-key body, not just header`, `.* safe values ... do not false-positive` |
| 46 | Pipeline-runner alias-stub filter removes non-primary Items | `strategies.CertificatesPipelineRunnerTests.filterPrimaryItems drops Items with body=None (alias stubs)`, `.filterPrimaryItems drops Items with ItemTagData body`, `.runGoatRodeoOnSingleFile on a simple file returns exactly one primary Item` (plus 4 more positive/ordering cases) |
| 47 | Every committed X.509 sidecar's `CertSha256` / `SpkiSha256` / `SubjectDN` is cross-validated by the JDK's built-in parser (≥157 fixtures) | `strategies.CertificatesSidecarGroundTruthTests` — 4 tests, all green |
| 48 | Corpus has at least one null-password-readable keystore (Phase 8 coverage matrix cell "Keystore encryption state: unencrypted") | `test_data/certificates/keystores/synthetic/trust-only-null-password.p12` + sidecar; paired generator `keystores/synthetic/generate.sh` + `tools/GenerateTrustOnlyNullPassword.java` |
| 49 | Per-fixture test state is uniform (no partial mix) | `strategies.CertificatesSuite` detects `Class.forName("io.spicelabs.goatrodeo.omnibor.strategies.Certificates")` and uniformly skips or runs |
| 50 | `compute-expected.sh` strips the OpenSSL `subject=` / `issuer=` prefix correctly | `strategies.ComputeExpectedToolTests.compute-expected.sh strips the 'subject=' prefix from SubjectDN`, `.* strips the 'issuer=' prefix from IssuerDN` |
| 51 | `compute-expected.sh` exits 0 and emits parseable JSON with valid sidecar shape | `strategies.ComputeExpectedToolTests.compute-expected.sh exits 0 ...`, `.* emits parseable JSON` |
| 52 | `compute-expected.sh` cert-sha256 matches the JDK X509 parser's DER hash | `strategies.ComputeExpectedToolTests.compute-expected.sh's emitted CertSha256 ... matches JDK's parsed-DER SHA-256` |
| 53 | Each synthetic-fixture category has a `generate.sh` per plan acceptance | wrappers exist at `test_data/certificates/{x509,ssh,pgp,crls,private-keys,pem-bundles}/synthetic/generate.sh` and `test_data/certificates/edge-cases/generate.sh` and `test_data/certificates/keystores/synthetic/generate.sh` (BC) — each delegates to `bootstrap_synthetic.py --category` |
| 54 | Coverage-matrix cell: PGP v6 is satisfied | `test_data/certificates/pgp/synthetic/v6-ed25519-pub.asc` (primary version=6 verified by BC) |
| 55 | Coverage-matrix cell: BKS keystore is satisfied | `test_data/certificates/keystores/synthetic/trust-bks.bks` (round-trip verified by `GenerateBks.java`) |

## Claims NOT verified by Phase 0 tests (tracked for later phases)

- **Per-sidecar ground-truth correctness.** The sidecar-parse and
  orphan-detection tests are structural; they do not assert "the
  `Certificates:SubjectDN` field in sidecar X matches what `openssl
  x509 -subject` says for fixture X." The sidecar values WERE
  computed with `tools/cert_sidecar.py` using the `cryptography`
  library (independent from the Certificates strategy), and
  cross-checked against `openssl` for the prototype. A Phase 3 smoke
  test should re-run `compute-expected.sh` against each fixture and
  diff the output against the committed sidecar to catch drift.
- **Per-fixture pipeline output correctness.** `CertificatesSuite`
  emits 200 tests; the assertions fire only when the Certificates
  strategy is implemented. Pre-Phase-3 runs show expected failures
  (strategy not yet emitting the specified Items). See Phase 3
  acceptance.
- **Coverage-matrix completeness.** The Phase 8 coverage suite
  (`CertificatesCoverageSuite`) does not exist yet. Current corpus
  has known gaps for PQC (ml-dsa), PGP v6, and BKS — see the fixtures
  table above.

## Hostile-reviewer self-check (HS-3)

Answering the five HS-3 questions for Phase 0a:

1. **Did I read the requirement?** Yes — `phase-0-corpus.md` top to
   bottom.
2. **Did I read the implementation?** Yes — every file in
   `src/test/scala/strategies/Certificates*.scala`.
3. **Did I read the test?** Yes — every test in the four
   `Certificates*Tests.scala` / `...Suite.scala` files.
4. **Does each test exercise the actual requirement?** Per-helper unit
   tests (sidecar parser, inventory, assertions) exercise the exact
   contract each helper advertises. The integrity suite exercises the
   end-to-end corpus shape. The claim marked "Phase 0b" is explicitly
   not yet verified.
5. **Would a crusty engineer agree it works?** For Phase 0a:
   infrastructure is present, tested, documented, red-to-green in shape.
   For Phase 0b: "not yet" — which is what the intentional red gate says.
