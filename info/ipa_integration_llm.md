# iOS app bundle (.ipa) integration — LLM notes

> Companion to `ipa_integration.md`. Fast orientation for LLM/agent readers.

## What changed

New `IpaStrategy` (claim + accumulate + cert emission + provisional pURL);
`.ipa` identification in `ArtifactWrapper` (massage to `application/zip`);
`BinaryPlistParser` (binary bplist00 + XML → JValue, bounded, never
throws); MobileTls ATS now parses binary plists via the shared facade.

## Key facts

- `.ipa` = ZIP with `Payload/<App>.app/`; children: Mach-O exe (opaque),
  `Info.plist` (often **bplist00**), `embedded.mobileprovision` (CMS
  SignedData whose payload is a plist and whose certs are the signing
  chain).
- Identification: `ArtifactWrapper.isIpa` — `.ipa` extension (lowercase) +
  4-byte magic `PK\x03\x04` (local header) or `PK\x05\x06` (empty EOCD) →
  `application/zip`. Keys on magic, never MIME (Android APKs/plain ZIPs
  untouched). Tika detects `.ipa` as `application/x-itunes-ipa` otherwise.
- Claim (`computeIpaFiles`): `.ipa` zip containers + bare bundle members
  (`Payload/**/*.app/Info.plist`, `embedded.mobileprovision`) — containers
  take precedence; removed from byUUID/byName.
- Accumulation: parent-scope pattern. `generateParentScope` on `IpaState`
  closes over `IpaState.this`; `accumulateInfo` guards `parentId ==
  scopeFor()`; **only children whose parent component ends in `.app`**
  count (framework plists can't clobber). Bare members self-accumulate in
  `beginProcessing` (no children). `applyAccumulatedAugmentation` does the
  single `store.write` merge; `postChildProcessing` returns `this` (Dotnet
  pattern) so the accumulator survives.
- **Reads must drain the stream fully**: a single `s.read` can return
  partial chunks on real file streams; truncated plists then coincidentally
  parse. `readBounded`/`contentBytes` loop to EOF, and **return EMPTY on
  overflow** (never truncated-parse).
- Certs: `CMSSignedData.getCertificates()` → `X509CertificateHolder`, chain
  capped at 32 (`MaxCertChain`); payload plist via `getSignedContent` →
  `BinaryPlistParser`; cert Items emitted with `Certificates.perCertMetadata`
  shape (SubjectDN/CertSha256/...) under `gitoid:blob:sha256:<gitoid-of-DER>`
  ids, `EdgeType.contains` child of the .ipa item.
- pURL: provisional `pkg:apple/ios/<sanitized bundle-id>@<version>`
  (owner decision 2026-09-25: option (b); provisional until purl-spec
  ratifies an app-store type; arch qualifier omitted until fat-slice
  enumeration exists). Emitted in `applyAccumulatedAugmentation` via
  `store.addPurl` + `CANONICAL_PURL` metadata.
- MobileTls ATS: `buildMetadata` for `apple-ats` tries
  `BinaryPlistParser.parse(contentBytes)` → `parseAtsFromJValue`
  (`NSAppTransportSecurity` dict → `FileType=apple-ats`,
  `ats_arbitrary_loads` when `NSAllowsArbitraryLoads`==true,
  `ats_exceptions` when `NSExceptionDomains` is a dict,
  `ats_local_networking` when `NSAllowsLocalNetworking`==true); falls back
  to text only when bytes are non-empty (an oversized/truncated blob is
  never text-interpreted — its object table contains ASCII key strings
  that false-positive).

## Test IDs

- `IpaDetectionSuite.Ipa-0.1..0.8` (detection/walk; 0.8 = real corpus)
- `IpaStrategySuite.Ipa-2.1..2.9, 2.a, 2.b` (strategy; 2.b = real corpus
  end-to-end incl. certs + pURL)
- `BinaryPlistParserSuite.Bplist-1.1..1.9, 1.a..1.c` (parser bounds/round-trip)
- `MobileTlsSuite.Mtl-3.1..3.7` (ATS on binary plists)

## Gotchas for future agents

- **Never single-read** plist/CMS bytes from a wrapper stream — drain with
  64 KiB chunks; empty-on-overflow.
- The corpus `allsafe-ios.ipa` (`test_data/download/ipa_tests/`, fetched by
  build.sbt Tests.Setup, sha256-pinned in `IpaCorpus`) validates the parser
  against Apple's real encoder (`Ipa-2.b`).
- json4s `\` needs `MonadicJValue` import in consumers.
- Strategy order in `ToProcess.dynamicToProcess` matters: IpaStrategy runs
  after MobileTls. MobileTls claims bare `Info.plist` files first; Ipa
  claims containers and Payload-pathed members.
- `bodyAsItemMetaData` is on Item (not on the body).