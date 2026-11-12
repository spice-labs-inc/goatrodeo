# BigTent cluster format (LLM notes)

Version 4 only. Constants, from BigTent's code at commit `0ee0636f`:

```
cluster envelope: version = 4, encoding = "BLAKE3[0..16]/Long/Long"   (cluster.rs)
index envelope:   version = 1, encoding = same                        (writer.rs:434)
data envelope:    version = 2; previous = 0; depends_on = {}          (writer.rs:548)
index key:        BLAKE3(identifier.as_utf8())[0..16]                 (util.rs:138)
item.connections: BTreeMap<String, BTreeSet<String>>                  (item.rs:332)
file names:       sha256(file)[0..8] as 16 hex chars
envelope length:  2 bytes, big endian, after the 4-byte magic
```

Scala side: `envelopes/Blake3IndexKey` (`encoding`, `key`, `keyHex`),
`util/Blake3` (BouncyCastle `Blake3Digest`, `doFinal(out, 0, 16)`),
`envelopes/Envelope.scala` (the three envelopes and `ClusterFileEnvelope.validateForRead`),
`omnibor/Item` (`connections: TreeMap[String, TreeSet[String]]`, `edges`, `targetsOf`,
`connectionsPlus`, `cmpKey`), `omnibor/GraphManager` (index key at the write site),
`omnibor/Cluster` (`open`, index and data readers, all validating), `util/Helpers`
(`readShortAndCBOR`).

Fixtures: `test_data/bigtent_v4/cluster_{0,1}` (BigTent-written, pinned, hashed in
`SOURCES.md`). Tests: `BigTentV4FixtureSuite` (V4F-*), `V4ReaderRefusalSuite` (VR-*),
`V4RoundTripSuite` (RT-*), `ItemMergeConnectionsSuite` (MC-*).

Two defects this work fixed, both in the read path and both previously untested:

1. `GoatRodeoCluster.open` read a 4-byte envelope length while the writer (and BigTent)
   write 2 bytes — `Helpers.readShortAndCBOR` is the fix.
2. `Helpers.readCBOR` passed an unflipped `ByteBuffer` to `Cbor.decode`, so every
   envelope read failed with "End of Input".

Gotchas worth remembering:

- `TreeMap.++` replaces on a key collision; per-key union is a fold, not `++`.
- Borer's derived decoder for a case class reads the CBOR map **in declaration order**,
  so the Scala field order must match the order the Rust writer emits.
- An `Option[String]` field in these envelopes decodes as a 0/1-length array in this
  project's borer setup, not as text — hence `encoding` is a plain `String`.
- Every `.grd` ends with a 10-byte trailer: `write_short_signed(-1)` (the 2-byte
  `0xFFFF` end-of-file marker) followed by `write_usize_sync(previous_position)` (the
  8-byte back-pointer to the last record). Goat Rodeo writes both already
  (`GraphManager`), so the trailers match BigTent's byte for byte; consumers that read
  by index offset ignore the trailer, while a sequential scan must stop at the marker.
