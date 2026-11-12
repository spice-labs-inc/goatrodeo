# BigTent cluster format: version 4 with BLAKE3 index keys

Goat Rodeo writes **version 4** BigTent clusters and reads **version 4 only**. Version 3
support was dropped deliberately: a version 3 cluster is MD5-keyed and carries
pair-shaped items, which this reader would misinterpret rather than reject.

The authority for the format is BigTent's code, not its prose documentation; where the
two disagreed, the code won. The relevant facts, taken from
`spice-labs-inc/bigtent` at commit `0ee0636f`:

| Layer | Value |
|---|---|
| Cluster envelope `version` | `4` (`src/rodeo/cluster.rs`) |
| Cluster envelope `encoding` | `"BLAKE3[0..16]/Long/Long"`, always present |
| Index envelope `version` | `1` — unchanged from version 3; the `encoding` string names the algorithm |
| Index key | `BLAKE3[0..16]` over the identifier's UTF-8 bytes |
| Data envelope `version` | `2`; `previous` and `depends_on` inert |
| `Item.connections` | ordered map, edge type to target set |
| File names | SHA-256 of the file, first eight bytes, hex |
| End of a data file | 2-byte `0xFFFF` marker, then an 8-byte back-pointer to the last record |

## Evidence, not documentation

`test_data/bigtent_v4/` holds two complete version 4 clusters copied verbatim from the
BigTent repository, with provenance and per-file hashes in `SOURCES.md`. They are the
only bytes in the test suite that Goat Rodeo did not write, so they are what proves the
format is understood rather than merely self-consistent:
`BigTentV4FixtureSuite.V4F-1` (versions and declaration), `V4F-2` (index and data
envelopes), `V4F-3` (every index key is `BLAKE3[0..16]` of the identifier it indexes).

## Reading

`GoatRodeoCluster.open` validates the cluster envelope by version and declared
encoding, and both failures name the offending value
(`V4ReaderRefusalSuite.VR-1`, `VR-2`, with `VR-2b` proving a valid envelope passes).
Index and data envelopes are validated the same way. A written cluster reads back
through the same path with every item compared field by field
(`V4RoundTripSuite.RT-1`), and an index entry is asserted to be keyed by the
identifier's key (`RT-2`).

## Writing

The index key is computed where the index entry is written and nowhere else. No hash is
stored on an item: the identifier is already in memory and BLAKE3 over a short string
is cheap, so storing a copy would cost sixteen bytes and a header per item for a value
used once. Ordering helpers (`Item.cmpKey`, `Storage.pathsSortedWithMD5`) use the same
key, so the tree has exactly one identifier hash.

## Merging items

Merging two items unions the target sets **per edge type**. This is the one place the
map shape can silently lose data, because `TreeMap.++` replaces on a key collision:
merging two items that alias the same edge type would keep only one side's targets.
`ItemMergeConnectionsSuite.MC-1`, `MC-3` and `MC-6` fail on that implementation and
pass on the union; `MC-2`, `MC-4` and `MC-5` cover the dedupe, disjoint and empty
cases.

## Compatibility

There is no compatibility shim and no fallback. A cluster whose version is not 4, whose
declaration is not the BLAKE3 declaration, or whose data file declares an envelope
version other than 2 is refused with an error naming the value it found.
