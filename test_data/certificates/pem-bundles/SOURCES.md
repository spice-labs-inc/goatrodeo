# Sources — `pem-bundles/`

Provenance ledger for multi-PEM bundle fixtures.

See `../README.md` for corpus-wide policies.

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| _placeholder_ | _e.g., `https://curl.se/ca/cacert.pem`_ | _YYYY-MM-DD_ | _sha256:…_ |

## Target list for Phase 4

- [ ] `mozilla-ca-bundle.pem` — `https://curl.se/ca/cacert.pem`
- [ ] `letsencrypt-fullchain.pem` — ISRG Root X1 + R3 intermediate
      concatenated

## Category floor

Phase 0 coverage guidance: 8 PEM bundles.

## Mozilla CA bundle as a PEM-bundle fixture

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| mozilla-ca-bundle.pem | https://curl.se/ca/cacert.pem | 2026-04-24 | sha256:b6e66569cc3d438dd5abe514d0df50005d570bfc96c14dca8f768d020cb96171 |

## Synthesized PEM bundles

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| synthetic/isrg-x1-r3-chain.pem | synthetic concat of 2 committed PEMs | 2026-04-28 | sha256:3595d2f89a7b3912612c171a6db206c75314e416ae550d3bcb00384cdd016d60 |
| synthetic/isrg-multi-chain.pem | synthetic concat of 3 committed PEMs | 2026-04-28 | sha256:3fb68da2b5db783117073233020730e0c1717748da6de5255e25fae8c1610139 |
| synthetic/letsencrypt-all-roots-and-ints.pem | synthetic concat of 5 committed PEMs | 2026-04-28 | sha256:3279c53f4930ebc1ca5eb072d51f25f2b54298ae67901fd651a84bac3bdf7c86 |
| synthetic/historical-roots-bundle.pem | synthetic concat of historical roots | 2026-04-28 | sha256:c1c4c80b71419d9c76797003396fc6cbd1d8c74c10ea456ede7e931d325de668 |
| synthetic/mixed-roots-6-cert.pem | synthetic 6-cert mixed bundle | 2026-04-28 | sha256:7a3ac659d973ece03bb2e8b85e6ab0381554c77bbd1efa32ba008ee8d68f78f5 |

## Topup PEM bundle

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| synthetic/all-canonical-plus-historical.pem | synthetic concat (16 certs) | 2026-04-28 | sha256:8d7ed2f7ae964f42c325e91377951317d838146b5dd4f67149d663e598af055b |
