# Plist Parsing (binary bplist00 and XML)

> **Navigation:** [Documentation Index](README.md)

Goat Rodeo parses Apple property lists through one facade —
`util/BinaryPlistParser` — producing `org.json4s.JValue` for both the
**binary** format (`bplist00`, used by real device IPAs and by the payload
inside `embedded.mobileprovision`) and **XML** plists. Consumers (the
MobileTls ATS capture, the Ipa strategy) write one code path.

## Format support

| Input | Path | Notes |
|---|---|---|
| `bplist00` magic | binary parser | header + object table + 32-byte trailer; objects: null/false/true, int (1/2/4/8-byte), real (float/double), date (→ ISO-8601 string), data (→ base64 string), ASCII string, UTF-16BE string (surrogate pairs preserved), UID, array, set, dict |
| XML plist | hardened DOM parser | `string/integer/real/true/false/date/data/array/dict`; a benign DOCUMENT (Apple's plist DTD) is stripped and re-parsed |
| anything else | → `None` | a failed parse is a value, never an exception |

## Boundaries (all enforced before allocation)

- **16 MiB blob cap** (`MaxPlistBytes`, mirroring the OCI metadata cap).
- **Object-count cap** `MaxObjectCount` = 100 000.
- **Depth cap** `MaxDepth` = 256.
- Trailer `numObjects`/`topObject`/`offsetTableOffset` bounds checked before
  the offset table is sized (hostile counts cannot drive memory
  amplification).
- Every object offset must land inside the buffer; refs must be in range;
  cyclic object graphs are rejected (resolved-set).
- The `0xf`-escaped length form (data/string/array/set/dict lengths) is
  checked against the buffer before any array is sized.
- The XML path reuses PomParser's hardened `DocumentBuilderFactory`
  (doctype disallowed, external entities off, no external DTD, no
  XInclude); a hostile DOCTYPE with entity expansion stays inert.

## Claims and tests

| # | Claim | Verified by |
|---|---|---|
| P1 | Golden binary plist (dict of scalars/array/double) parses to the expected JValue | `BinaryPlistParserSuite.Bplist-1.1` |
| P2 | UTF-16 strings, non-ASCII text, astral-plane surrogate pairs round-trip exactly | `Bplist-1.2` |
| P3 | Empty dict / minimal plist parses (legal empty containers) | `Bplist-1.3` |
| P4 | Hostile trailer (absurd counts, out-of-buffer offset table) rejected before allocation | `Bplist-1.4` |
| P5 | Out-of-buffer object offsets rejected; never throws | `Bplist-1.5` |
| P6 | Depth bomb beyond the cap and 0xf-escaped length bomb rejected | `Bplist-1.6` |
| P7 | Truncation at every prefix length: valid or `None`, never an exception | `Bplist-1.7` |
| P8 | XML plist (ATS shape) and its binary twin parse to identical JValues | `Bplist-1.8` |
| P9 | Property: random bounded JValues encode → parse round-trip | `Bplist-1.9` |
| PA | Garbage / other binary formats → `None`, never throws | `Bplist-1.a` |
| PB | Blob larger than the 16 MiB cap refused | `Bplist-1.b` |
| PC | Hostile XML DOCTYPE (XXE payload) stays inert — never expands to file content | `Bplist-1.c` |

The parser is additionally validated against a **real Apple-generated
binary plist** in the corpus: `Payload/Allsafe.app/Info.plist` from the
fetched `allsafe-ios.ipa` parses to `CFBundleIdentifier =
infosecadventures.allsafe` (see `IpaStrategySuite.Ipa-2.b`).

## Related

- `src/main/scala/io/spicelabs/goatrodeo/util/BinaryPlistParser.scala`
- `src/test/scala/io/spicelabs/goatrodeo/testsupport/BplistEncoder.scala`
  (test-only encoder; golden blobs and round-trip properties)
- `src/test/scala/io/spicelabs/goatrodeo/omnibor/BinaryPlistParserSuite.scala`
- [iOS (.ipa) strategy](ipa_integration.md) — the consumer