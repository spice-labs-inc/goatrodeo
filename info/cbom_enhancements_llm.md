# CBOM Emission from an ADG — LLM Digest

> **Navigation:** [Documentation Index](README.md) · Human twin: `cbom_enhancements.md`

## One paragraph

Goat Rodeo emits one CycloneDX CBOM per ADG root. The emitter walks
`contains` edges from the root; intermediate container Items (JAR/TAR/ROMFS/
ELF-section/disk-image) form the path hierarchy even though they aren't crypto
components. Each crypto Item becomes a `cryptographic-asset` component whose
`bom-ref` is its `gitoid:blob:sha256` identifier and which carries a
`swhid:core` (`swh:1:cnt:<sha1>`) property. Every item-backed component also
carries three traversal-derived path properties —
`goatrodeo:path` (container-name chain), `goatrodeo:omnibor-path`
(`gitoid:blob:sha256` chain), `goatrodeo:swhid-path` (`swh:1:cnt` chain) —
joined by the `|:|` separator (deliberately not `/`, which is used within a
single node's own logical path). These are computed from the ADG traversal,
not from strategy metadata.

## Key facts for a re-implementer

- **Traversal:** BFS over `contains` edges from root; DAG dedup by `visited`
  (first path wins); depth cap. The chain `root → … → item` is threaded and
  used verbatim (intermediate non-crypto containers included).
- **Identity:** `bom-ref` = `item.identifier` (`gitoid:blob:sha256:<hex>`);
  `swhid:core` from the `alias:from` `gitoid:blob:sha1:<hex>` edge.
- **Three path props** (all components backed by an Item):
  - `goatrodeo:path` = chain of each node's first `fileNames` (fallback: its
    gitoid), joined by `|:|`.
  - `goatrodeo:omnibor-path` = chain of `item.identifier`, joined by `|:|`.
  - `goatrodeo:swhid-path` = chain of swhids (`swh:1:cnt:<sha1>`), joined by
    `|:|`; nodes lacking the sha1 alias are omitted.
- **Synthetic components** (algorithm assets `alg:<primitive>:<name>`,
  lockfile `library`) are not ADG Items but carry the same three path props
  as the Item that produced them; algorithm assets dedup by `bom-ref` keeping
  the first item's path.
- **Output:** CycloneDX 1.6/1.7, validated against official schemas; one file
  per root `cbom_<root-gitoid>.json`.
- **Cost:** adding these paths changes output byte-identity — golden fixtures
  must be regenerated with any emitter change.

## Tests

- `CbomEmitterSuite.T3.42` — nested container chain produces the three paths
  with the `|:|` separator (file, omnibor, swhid).
- `CbomEmitterSuite.T3.33` — golden byte-identity (regenerated; verified the
  delta is exactly the three added props per component).
- Full suite `sbt test` green (2,340).

## Gotchas

- `|:|` is the container separator; `/` is used only within a node's own
  path (e.g. `etc/ssl/certs/root-ca.crt` is one node).
- The chain includes the root (submitted file) as the first segment.
- `swhid-path` omits nodes without a sha1 alias (best-effort).