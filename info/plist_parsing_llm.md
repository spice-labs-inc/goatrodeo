# Plist Parsing (binary bplist00 and XML) — LLM notes

> Companion to `plist_parsing.md`. Fast orientation for LLM/agent readers.

## What changed

New `util/BinaryPlistParser` — one facade for **binary** (`bplist00`) and
**XML** plists → `org.json4s.JValue`. Foundation for iOS `.ipa` support
(Info.plist and the mobileprovision payload are often binary plists).

## Key facts

- Entry: `BinaryPlistParser.parse(bytes: Array[Byte]): Option[JValue]`;
  `bplist00` magic (8-byte header) → binary path, otherwise XML path.
  Never throws; malformed → `None`.
- Binary layout: header + object stream + 32-byte trailer at EOF:
  `6 unused bytes, offsetSize, objectRefSize, numObjects (8 BE),
  topObject (8 BE), offsetTableOffset (8 BE)`; offset table at
  `offsetTableOffset`, `numObjects` entries of `offsetSize` bytes.
- Object marker: high nibble = kind, low nibble = info.
  kinds: `0x0` null/false/true/fill · `0x1` int · `0x2` real · `0x3` date ·
  `0x4` data · `0x5` ASCII string · `0x6` UTF-16BE string · `0x8` UID ·
  `0xa` array · `0xc` set · `0xd` dict.
- Lengths: `info < 0xf` → length = info; `info == 0xf` → following integer
  object gives the length (0x1n marker + n bytes).
- Date (0x33): 8-byte BE double, seconds since 2001-01-01; emits ISO-8601
  string (parity with XML `<date>` text). Data emits base64 string (parity
  with XML `<data>` text). Dict keys must be strings; unknown types fail
  the parse.
- Bounds BEFORE allocation: `MaxPlistBytes` 16 MiB, `MaxObjectCount`
  100 000, `MaxDepth` 256; offsets/refs range-checked; cycles rejected via
  resolved-set (remove-on-exit so shared subtrees are legal).
- XML path: hardened `DocumentBuilderFactory` (doctype off, external
  entities off, no external DTD, no XInclude); benign DOCTYPE stripped and
  retried (PomParser pattern); root must be `<plist>`; convert
  `string/integer/real/true/false/date/data/array/dict`.

## Encoder (test-only)

`testsupport/BplistEncoder` encodes JValue → bplist00 for fixtures and
round-trip properties. Fixed widths: refSize 2, offsetSize 4 (fine under
65535 objects / 4 GiB). **Offset table entries are absolute file offsets
(header + running)** and **dict count = number of pairs** (refs = keys
then values) — two easy-to-regress details, covered by the round-trip
properties.

## Gotchas for future agents

- Real device Info.plists are binary; MobileTls and Ipa must parse, never
  regex raw bytes (the old MobileTls ATS regex path was XML-only).
- The corpus `allsafe-ios.ipa` `Info.plist` is a real Apple bplist —
  `Ipa-2.b` asserts its parsed values, so parser changes are validated
  against Apple's encoder, not just ours.
- `jv \ "key"` needs `org.json4s.MonadicJValue`/JsonDSL imports in
  consumer code.