# OCI image parity in the Docker strategy

> **Navigation:** [Documentation Index](README.md)

The Docker strategy ingests both transports of an image: the docker-save tar
(`manifest.json` + `blobs/sha256/…`) and the pure OCI image layout
(`oci-layout` + `index.json` + `blobs/sha256/…`, as produced by
`oras copy --to-oci-layout`). No separate strategy exists for OCI — one
strategy, two transports.

## How it works

```
computeDockerFiles
  ├── docker-save claim (manifest.json) — unchanged; wins when both present
  └── ociLayoutClaim (only when the docker-save claim found nothing)
        ├── requires `oci-layout` + JSON `index.json`
        ├── index.json = manifest list  → expand (nested indexes, depth ≤ 8,
        │       ≤ 128 entries), prefer linux/amd64, else lexicographic
        │       (os, arch, variant); attestations (no platform) skipped
        ├── index.json = bare manifest  → used directly (OCI or docker media type)
        ├── every digest validated ^sha256:[0-9a-f]{64}$; blob path
        │       blobs/sha256/<hex> looked up in byName only (never resolved
        │       against the filesystem — a hostile index.json cannot read
        │       host files)
        ├── JSON read capped at 16 MiB; ≤ 512 layers per image
        └── RepoTags from the wild org.opencontainers.image.ref.name
              annotation (≤ 256 chars, no control chars); absent → no tags
```

## Parity definition

Parity is **maximum information extraction per format**, not byte-equal
output. The two transports genuinely differ:

| Aspect | docker-save tar | OCI layout |
|---|---|---|
| Layer blobs | re-compressed locally (uncompressed `tar`) | registry `tar+gzip` |
| RepoTags | `manifest.json` entry | only if `ref.name` annotation present (rare) |
| pURLs | RepoTags pURL | usually none |
| OCI manifest fields | only if a manifest blob survived the save (alpine's does) | always |

What must be identical: the **config blob digest** (both transports store the
config byte-for-byte), and therefore every config-derived metadata field
(Env/Cmd/Entrypoint/LayerCount/History/Labels/Platform), `rootfs.diff_ids`,
the layer graph (counts + contains edges), and the layer-content pURLs
(e.g. carved certs).

## Fixtures and fetching

The parity fixtures are public images **pinned by digest** and fetched from
the registries only when missing — nothing is hosted on public-test-data for
this feature (a digest-pinned artifact is immutable, so the registry is the
canonical location).

- `alpine:3.20.6` (index `sha256:de4fe706…`), `postgres:16.4` (index
  `sha256:e62fbf9d…`) — see `ociPins` in `build.sbt`.
- At test setup, `build.sbt` runs, per image, when the cache entry is
  missing: `docker pull @digest` + `docker tag` + `docker save` (the
  docker-save tar), and ORAS inside a locked-down docker container (pinned
  ORAS image, `--user $(id -u):$(id -g)`, `--cap-drop ALL`,
  no-new-privileges) with `oras copy --recursive --platform linux/amd64
  --to-oci-layout`. A `.config-digest` sentinel marks a complete cache.
- The fetch is skipped when docker is unavailable or
  `GOATRODEO_SKIP_OCI_FETCH=1`; the parity tests gate on fixture presence.
- Fetched image content is **data, never executed** — parsing is JVM-side
  only, pinned by a source-scan test.

## Claims and their tests

| # | Claim | Verified by |
|---|---|---|
| C1 | Both transports of a pinned image carry the same config blob | `OciDockerParitySuite.P-01` |
| C2 | Config-derived metadata is identical across transports | `P-02` |
| C3 | Layer graph parity (counts, contains edges) despite differing blob digests | `P-03` |
| C4 | docker-save adds the RepoTags pURL; wild OCI emits no docker pURL; layer-content pURLs agree | `P-04` |
| C5 | OCI manifest fields (ConfigMediaType, SchemaVersion) extracted; agree where both sides carry them | `P-05` |
| C6 | `rootfs.diff_ids` identical across transports | `P-06` |
| C7 | The build never executes fetched image content (only pinned ORAS + digest-pinned pull/tag/save) | `P-07` |
| C8 | OCI layout directories are claimed via relative paths | `P-08` |
| C9 | Single-manifest index.json claimed, empty wild RepoTags | `DockerSuite.O-01` |
| C10 | Manifest list prefers linux/amd64, skips attestations | `O-02` |
| C11 | Nested indexes resolve | `O-03` |
| C12 | `ref.name` annotation (descriptor or manifest level) becomes RepoTags | `O-04` |
| C13 | Hostile digests (traversal, bad hex, wrong algorithm, truncated) are never resolved | `O-05` |
| C14 | No claim without `oci-layout`; missing blobs claim nothing | `O-06`, `O-07` |
| C15 | docker-save wins when both formats present | `O-08` |
| C16 | Empty manifests, non-JSON index, garbage ref.name, oversized index, excessive nesting, hostile layer digests handled | `O-09`…`O-14` |

## Related

- `docs/adrs/adr_2026_08_24_oci_docker_parity.md`
- `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Docker.scala`
- `src/test/scala/io/spicelabs/goatrodeo/omnibor/OciDockerParitySuite.scala`
- `src/test/scala/DockerSuite.scala` (O-xx tests)
