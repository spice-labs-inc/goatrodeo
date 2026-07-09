/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Helper for writing pURL alias edges to the store.
  *
  * This object extracts the mechanical pURL alias-writing logic that was
  * previously duplicated in [[Maven.applyAccumulatedAugmentation]]. It keeps
  * Maven.scala focused on groupId/artifactId/version resolution and delegates
  * the store writes here.
  *
  * ==What it does==
  *
  * For each pURL string, `writeAlias` performs three operations:
  *
  *   1. Registers the pURL via `store.addPurl(purl)` 2. '''WRITE 1''': Updates
  *      the artifact's gitoid Item with `aliasFrom -> purl`, optionally merging
  *      extra metadata 3. '''WRITE 2''': Creates or updates the pURL Item node
  *      with `aliasTo -> gitoid`
  *
  * WRITE 1 and WRITE 2 write to different paths (gitoid vs pURL string), so
  * they acquire different row locks — no deadlock risk.
  *
  * ==Why it exists==
  *
  * Maven.scala is already ~2000 lines. Adding the multiple-pURL emission loop
  * (one pURL per embedded package) without extracting this helper would push it
  * past the 20000-token limit (CLAUDE.md invariant 9).
  */
object PurlAliasWriter {

  /** Write a single pURL's alias edges to the store, with optional metadata.
    *
    * @param purl
    *   The canonical pURL string (e.g., `pkg:maven/org.foo/bar@1.0`)
    * @param itemIdentifier
    *   The gitoid identifier of the artifact this pURL belongs to
    * @param store
    *   The storage to write to
    * @param extra
    *   Optional metadata to merge into the gitoid Item (e.g., manifest, pom,
    *   jar-structure). Only merged on WRITE 1 (gitoid item), not on the pURL
    *   item. Defaults to empty.
    */
  def writeAlias(
      purl: String,
      itemIdentifier: String,
      store: Storage,
      extra: TreeMap[String, TreeSet[StringOrPair]] = TreeMap.empty
  ): Unit = {
    // Register the pURL with the store's pURL index
    store.addPurl(purl)

    // WRITE 1: Update gitoid item — add aliasFrom -> pURL and optionally
    // merge extra metadata (manifest, pom, jar-structure, canonical pURL, etc.).
    // Uses store.write callback to ensure atomic read-modify-write under the
    // row-level lock. No prior store.read call; no nested store.write for same
    // path.
    store.write(
      itemIdentifier,
      {
        case Some(existing) =>
          val withAlias = existing.copy(
            connections = existing.connections + (EdgeType.aliasFrom -> purl)
          )
          val withMeta =
            if (extra.nonEmpty)
              withAlias.enhanceWithMetadata(
                extra = extra,
                filenames = Vector.empty,
                mimeTypes = Vector.empty
              )
            else withAlias
          Some(withMeta)
        case None =>
          val base = Item(
            itemIdentifier,
            TreeSet(EdgeType.aliasFrom -> purl),
            None,
            None
          )
          val withMeta =
            if (extra.nonEmpty)
              base.enhanceWithMetadata(
                extra = extra,
                filenames = Vector.empty,
                mimeTypes = Vector.empty
              )
            else base
          Some(withMeta)
      },
      _ => s"aliasFrom $purl" + (if (extra.nonEmpty) " + metadata" else "")
    )

    // WRITE 2: Create/update pURL item with aliasTo -> gitoid item.
    // Different path from WRITE 1, so different row lock — no deadlock risk.
    // If the pURL item already exists (another JAR with the same pURL), we
    // add another aliasTo entry.
    store.write(
      purl,
      {
        case Some(existingPurlItem) =>
          // pURL item already exists — add another aliasTo pointing to this
          // JAR item
          Some(
            existingPurlItem.copy(
              connections =
                existingPurlItem.connections + (EdgeType.aliasTo -> itemIdentifier)
            )
          )
        case None =>
          // First time we've seen this pURL — create the item
          Some(
            Item(
              purl,
              TreeSet(EdgeType.aliasTo -> itemIdentifier),
              Some(ItemMetaData.mimeType),
              Some(
                ItemMetaData(
                  fileNames = TreeSet(purl),
                  mimeType = TreeSet[String](),
                  fileSize = 0,
                  extra = TreeMap[String, TreeSet[StringOrPair]]()
                )
              )
            )
          )
      },
      _ => s"pURL item for $purl"
    )
  }
}
