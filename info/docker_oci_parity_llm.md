# OCI image parity in the Docker strategy (LLM notes)

> Companion to `docker_oci_parity.md`. Fast orientation for LLM/agent readers.

## What changed

`DockerToProcess.computeDockerFiles` now claims pure OCI image layouts in
addition to docker-save tars. No new strategy.

## Key facts

- Flow: docker-save claim runs first; if it returns empty, `ociLayoutClaim`
  runs (deterministic precedence — a corpus with both formats leaves the
  stray `index.json` unclaimed).
- `ociLayoutClaim` requires `oci-layout` present and `index.json` JSON-mimed.
- `index.json` handling: `manifests[]` array (non-empty) → manifest list;
  else the JSON itself is treated as a bare manifest (media type must be
  `application/vnd.oci.image.manifest.v1+json` or
  `application/vnd.docker.distribution.manifest.v2+json`).
- Manifest lists: `expandIndexEntries` flattens nested indexes (depth ≤
  `MaxOciIndexDepth` 8), `selectPlatformEntries` prefers linux/amd64, else
  lexicographic (os, arch, variant); entries without a platform
  (attestations) are candidates only when nothing has a platform.
- Digest validation: `^sha256:[0-9a-f]{64}$` via regex; blob path is always
  `blobs/sha256/<hex>` (hex from substring(7)), looked up in byName — never
  filesystem-resolved, never from raw descriptor text.
- `readJsonCapped`: bounded stream read; > `MaxOciJsonBytes` (16 MiB) →
  None → skipped. `MaxOciManifestEntries` 128, `MaxOciLayers` 512.
- `ManifestInfo.repoTags: Option[Vector[String]] = None`: `None` derives
  RepoTags from `manifestConfig` (docker-save path unchanged, so the four
  existing RepoTags tests pass untouched); `Some(tags)` is the OCI-derived
  value (usually empty in the wild). `effectiveRepoTags` feeds
  `computePurls` and `maybePackageTag`.
- ref.name source: descriptor `annotations` for list entries; manifest-level
  `annotations` for bare manifests; sanitized (`safeRefName`: ≤256 chars, no
  C0/DEL) — garbage → no tags, no error.
- The OCI claim synthesizes `manifestConfig` as
  `JObject("RepoTags" -> JArray(tags))`; layers/config resolved via byName;
  `DockerToProcess(indexArtifact, infos, layerMap)`; claimed names
  (`index.json`, `oci-layout`, config + layer blob paths) removed from
  byName/byUUID.
- `DockerMetadataExtractor`: `SchemaVersion` accepts JInt/JLong (wild
  manifests have `"schemaVersion": 2` as an integer).

## Fixtures (build.sbt `ociPins` + `orasImage`)

- alpine:3.20.6 — index `sha256:de4fe7064d8f98419ea6b49190df1abbf43450c1702eeb864fe9ced453c1cc5f`, config `sha256:ff221270…`
- postgres:16.4 — index `sha256:e62fbf9d3e2b49816a32c400ed2dba83e3b361e6833e624024309c35d334b412`, config `sha256:6b14e73a…`
- ORAS image pinned: `ghcr.io/oras-project/oras:v1.3.0@sha256:6ce045ce…`
- Fetch (Tests.Setup, cache-on-missing): docker pull/tag/save →
  `test_data/download/docker_tests/<image>_<short>_docker.tar`; ORAS in
  docker (`--user $(id -u):$(id -g)`, cap-drop ALL, no-new-privileges,
  tmpfs /tmp, mount `oci_images/` at `/data`) `oras copy --recursive
  --platform linux/amd64 --to-oci-layout docker.io/library/<image>@<digest>
  /data/<image>` → `test_data/download/oci_images/<image>/` + sentinel
  `.config-digest`.
- `GOATRODEO_SKIP_OCI_FETCH=1` skips; docker missing → warn + parity tests
  `assume`-skip.

## Gotchas for future agents

- **Layer blob digests differ between transports** (`docker save`
  re-compresses). Never assert layer blob-path equality; assert `diff_ids`
  (config content) and graph structure (P-03/P-06).
- **Wild OCI layouts usually have no RepoTags** → no docker pURL. P-04 pins
  the exact wild behavior; do not "fix" it by injecting annotations during
  fetch (explicitly rejected by the project owner).
- The docker-save tar of alpine **preserves an OCI manifest blob** (buildkit
  artifact), so the docker side can legitimately carry ConfigMediaType —
  P-05 asserts agreement, not absence.
- ORAS `copy --to-oci-layout` argument order is `<source> <dest-dir>`;
  `--retry-times` is NOT a `copy` flag.
- Layer contents get recursively processed (ca-certificates → certs, etc.):
  parity on "layer-content pURLs" is part of P-04.
- OCI layout directory ingestion needs full relative paths (fsFilePaths or
  a tarred layout); `strategyForDirectory` (basenames only) cannot resolve
  `blobs/sha256/<hex>` — P-08 uses relative-path wrappers.
- Adding images: extend `ociPins` (4-tuples) — the fetch and tests use the
  same pins; update `OciDockerParitySuite.pinnedConfigDigests` and P-07's
  digest-literal assertions.
