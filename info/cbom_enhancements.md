# CBOM Emission from an ADG — Enhancements & Conventions

> **Navigation:** [Documentation Index](README.md)

This document describes the CycloneDX Cryptographic Bill-of-Materials (CBOM)
emission conventions implemented in Goat Rodeo, so that **other generators
that build CBOMs from an Artifact Dependency Graph (ADG)** can match the
output. The emitter walks the ADG (a DAG of `Item`s connected by `contains`
edges) from each root and emits one CBOM per root.

## 1. Inputs: the ADG traversal

- Each **CBOM is rooted in one top-level ADG `Item`** (the file that was
  submitted to the tool, e.g. `firmware.img`, `app.jar`, a Docker image).
- The emitter walks **`contains` edges** from the root to every reachable
  `Item`. Intermediate container `Item`s (a JAR, a TAR, a ROMFS, a disk
  image, an ELF section) are **part of the traversal** even though they are
  not themselves crypto components — they become the path hierarchy.
- The graph is a DAG: an `Item` reachable by more than one path is visited
  once, and the **first path that reaches it** is the one reported. A
  traversal depth cap bounds the walk.
- A crypto `Item` is one whose metadata carries a recognized strategy prefix
  (certificates, keystores, SSH, PGP, password hashes, embedded certs,
  binary crypto footprints, JWT/JWK, TLS config, service crypto,
  dependencies, mobile TLS, …).

## 2. Component identity

Each crypto `Item` becomes a CycloneDX `cryptographic-asset` component
keyed by its OmniBOR identifier:

- `bom-ref` = the item's **`gitoid:blob:sha256:<hex>`** (the item identifier).
- `swhid:core` property = the Software Heritage content identifier
  **`swh:1:cnt:<sha1>`**, derived from the item's `alias:from`
  `gitoid:blob:sha1:<hex>` edge (same bytes, different prefix — no re-hash).

## 3. Traversal-derived paths (the hierarchy)

Every component that maps to an ADG `Item` carries **three parallel path
properties** describing where in the submitted file the material lives. The
path is computed from the `contains`-edge chain **root → … → item** during
traversal — it is *not* taken from per-strategy metadata.

| Property | Value | Example |
|---|---|---|
| `goatrodeo:path` | the chain of container names (each node's file name, i.e. its path within its parent) joined by the separator | `firmware.img|:|romfs|:|etc/ssl/certs/root-ca.crt` |
| `goatrodeo:omnibor-path` | the same chain as `gitoid:blob:sha256:` identifiers | `gitoid:blob:sha256:aa…|:|gitoid:blob:sha256:bb…|:|gitoid:blob:sha256:cc…` |
| `goatrodeo:swhid-path` | the same chain as `swh:1:cnt:` identifiers | `swh:1:cnt:aa…|:|swh:1:cnt:bb…|:|swh:1:cnt:cc…` |

**Separator:** the chain is joined with **`|:|`**. This is deliberately
different from `/`, which is used *within* a single node's own logical path
(e.g. `etc/ssl/certs/root-ca.crt` is one node's path). `|:|` is chosen
because it is visually distinct and essentially absent from real file names.

**Node name** for `goatrodeo:path` = the `Item`'s first `fileNames` entry
(the artifact's path within its parent); if an `Item` has none, its
`gitoid:blob:sha256` identifier is used instead.

**SWHID availability:** every real artifact `Item` carries the sha1 alias
edge, so the swhid path is complete. A node without that edge is **omitted**
from the swhid path (best effort).

## 4. Derived (synthetic) components

Algorithm assets (`alg:<primitive>:<name>` bom-refs) and lockfile
`library` components are synthetic — they are not ADG `Item`s. They carry
the **same three path properties as their containing `Item`** (the path of
the item that produced them). Because algorithm assets are deduplicated by
`bom-ref` across a root, a shared algorithm component keeps the path of the
first item that referenced it.

## 5. Output shape

- CycloneDX **1.6 and 1.7** (validated against the official schemas).
- One file per root, named `cbom_<root-gitoid>.json` (`:` and `/` in the
  gitoid replaced by `_`).
- Components: `cryptographic-asset` (certificate / algorithm /
  related-crypto-material / protocol), plus `library` for lockfile
  dependencies.

## 6. Guarantees

- A generated path lets a consumer start at the submitted root file and
  locate the material within it, by file name, by OmniBOR identifier, or by
  SWHID — the three paths are parallel.
- Adding the three path properties changes existing output byte-identity;
  regenerating golden fixtures is part of any emitter change.

## References

- `src/main/scala/io/spicelabs/goatrodeo/omnibor/CbomEmitter.scala`
- `info/cbom_emitter.md`