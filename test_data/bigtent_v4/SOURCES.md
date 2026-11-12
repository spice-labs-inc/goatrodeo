# BigTent V4 cluster fixtures

Two complete BigTent **version 4** clusters, copied verbatim from the BigTent
repository so that the read-path tests are checked against bytes Goat Rodeo did not
write. Without them, a writer and a reader that agree with each other and disagree
with the format would pass every round-trip test.

| | |
|---|---|
| Upstream | https://github.com/spice-labs-inc/bigtent |
| Path | `test_data/v4/cluster_0`, `test_data/v4/cluster_1` |
| Commit | `0ee0636fdab0f2ddf1bf68b2a8acea3eed48a66b` |
| Licence | Apache License 2.0 (`LICENSE.txt` upstream) |
| Total size | 29,588 bytes |

Shapes, decoded from the copied bytes rather than from documentation:

- `cluster_0` cluster file: magic `0xba4a4a`, envelope `{version: 4, magic: 0xba4a4a,
  data_files: 4, index_files: 4, info: {}, encoding: "BLAKE3[0..16]/Long/Long"}`,
  163 bytes.
- `cluster_0/7901b49733745301.gri`: magic `0x54154170`, envelope `{version: 1,
  magic: 0x54154170, size: 2, data_files: 1, encoding: "BLAKE3[0..16]/Long/Long",
  info: {}}`, 157 bytes. The index envelope version is **1** — unchanged from
  version 3, with the encoding string naming the key algorithm.
- `*.grd` files declare data envelope version 2 and hold map-shaped `connections`.

## Files

| File | Bytes | SHA-256 |
|---|---|---|
| `test_data/bigtent_v4/cluster_0/0804669ec70b2903.gri` | 605 | `0804669ec70b29032c4a991fd089ff372cc3d03f15e4b1fc8c44f9d0132bf3b4` |
| `test_data/bigtent_v4/cluster_0/16cc3ac6088f16d7.grd` | 4173 | `96cc3ac6088f16d7498b4da0297c30990ea9e5954ef73efb63eb1f53d70e98b9` |
| `test_data/bigtent_v4/cluster_0/2026_09_22_20_08_19_59ad84729756493f.grc` | 163 | `59ad84729756493fa0c700f29aad53cbb0571b936149df5449234c11750b6586` |
| `test_data/bigtent_v4/cluster_0/293bca8ab1715fd3.grd` | 609 | `a93bca8ab1715fd3a99f676d737caeb1d51c2366914f7d3cd307eb42f5b42404` |
| `test_data/bigtent_v4/cluster_0/412962ad8d6ec974.gri` | 637 | `412962ad8d6ec9749b8f6118758865373beeb38fdb8c295c65b18ac69be7017c` |
| `test_data/bigtent_v4/cluster_0/5116ef4580c59b3d.gri` | 573 | `d116ef4580c59b3db7ea81db2082448e7e435df544a7645b5a5334735ebd9d0b` |
| `test_data/bigtent_v4/cluster_0/6c06c7d7d011a275.grd` | 4015 | `ec06c7d7d011a27516c3115f1c4f8458d14e86adcef6dd90ec33ba8168cde992` |
| `test_data/bigtent_v4/cluster_0/788473206599c62b.grd` | 4080 | `788473206599c62b796501b106d97bd1a557e5fbb8b99de2ed557b6ffb4aae42` |
| `test_data/bigtent_v4/cluster_0/7901b49733745301.gri` | 157 | `7901b4973374530176919575956d045adea8332d830d7756aae53faccb1f50f6` |
| `test_data/bigtent_v4/cluster_0/history.jsonl` | 3 | `ca3d163bab055381827226140568f3bef7eaac187cebd76878e0b63e9e442356` |
| `test_data/bigtent_v4/cluster_1/105771ecaee7ec94.gri` | 637 | `905771ecaee7ec94bf0726b3ff274cc5e4930b12775b88e4df1aba3d86446e85` |
| `test_data/bigtent_v4/cluster_1/2026_09_22_20_08_19_0430172581c92b57.grc` | 145 | `0430172581c92b57f130e6b27f3c362a5b99aeb09730399b059badc82f6c075d` |
| `test_data/bigtent_v4/cluster_1/5f1c86dc84d3d708.grd` | 4189 | `5f1c86dc84d3d708df18d8898c1d7cf4aa02242d668f0fe0a85082c318a2038e` |
| `test_data/bigtent_v4/cluster_1/69ef8811964c918c.gri` | 605 | `e9ef8811964c918c4bbce2ee1136f9acab9687d2f128025b53cd1748ef347c79` |
| `test_data/bigtent_v4/cluster_1/6f832d7c183f22f7.grd` | 4191 | `6f832d7c183f22f7aafb7e7fc7bf19fdeb28b30f8f6af2adf9536737ca700ac6` |
| `test_data/bigtent_v4/cluster_1/77f9ec17c847f951.gri` | 637 | `77f9ec17c847f9511c00c804c225a99df0cc8e367f43f2c94b66d245453021a3` |
| `test_data/bigtent_v4/cluster_1/7dafecd4394409bb.grd` | 4166 | `7dafecd4394409bb31d550f8397ff44e6e16d38980c4fcf6f9c5dec0095455ea` |
| `test_data/bigtent_v4/cluster_1/history.jsonl` | 3 | `ca3d163bab055381827226140568f3bef7eaac187cebd76878e0b63e9e442356` |
