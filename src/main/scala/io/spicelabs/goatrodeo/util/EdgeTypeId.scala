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

package io.spicelabs.goatrodeo.util

import io.spicelabs.goatrodeo.omnibor.EdgeType

/** Compact `uint8` IDs for each `EdgeType` string, used in the v2 on-disk edge
  * encoding.
  *
  * Reserved IDs are stable and append-only. Never renumber an existing ID —
  * doing so would silently corrupt previously-written clusters. Unknown IDs at
  * decode time are an error.
  */
object EdgeTypeId {
  val ContainedBy: Byte = 0 // EdgeType.containedBy ("contained:up")
  val Contains: Byte = 1 // EdgeType.contains    ("contained:down")
  val AliasFrom: Byte = 2 // EdgeType.aliasFrom   ("alias:from")
  val AliasTo: Byte = 3 // EdgeType.aliasTo     ("alias:to")
  val BuildsTo: Byte = 4 // EdgeType.buildsTo    ("build:up")
  val BuiltFrom: Byte = 5 // EdgeType.builtFrom   ("build:down")
  val TagFrom: Byte = 6 // EdgeType.tagFrom     ("tag:from")
  val TagTo: Byte = 7 // EdgeType.tagTo       ("tag:to")

  /** Sentinel for edge-type strings the codec doesn't recognise. They round-trip
    * via `unknownTypes` in the `WriteContext` / `ReadContext`. */
  val Unknown: Byte = -1

  def fromString(edgeType: String): Byte = edgeType match {
    case EdgeType.containedBy => ContainedBy
    case EdgeType.contains    => Contains
    case EdgeType.aliasFrom   => AliasFrom
    case EdgeType.aliasTo     => AliasTo
    case EdgeType.buildsTo    => BuildsTo
    case EdgeType.builtFrom   => BuiltFrom
    case EdgeType.tagFrom     => TagFrom
    case EdgeType.tagTo       => TagTo
    case _                    => Unknown
  }

  def toStringOpt(id: Byte): Option[String] = id match {
    case ContainedBy => Some(EdgeType.containedBy)
    case Contains    => Some(EdgeType.contains)
    case AliasFrom   => Some(EdgeType.aliasFrom)
    case AliasTo     => Some(EdgeType.aliasTo)
    case BuildsTo    => Some(EdgeType.buildsTo)
    case BuiltFrom   => Some(EdgeType.builtFrom)
    case TagFrom     => Some(EdgeType.tagFrom)
    case TagTo       => Some(EdgeType.tagTo)
    case _           => None
  }

  /** Predicate: does the edge form a content-DAG relationship (`contained:*` or
    * `build:*`)? Those are the edges the topological sort follows to decide
    * write order. Inverse direction is included on purpose — we need to know
    * about both halves to choose the canonical projection. */
  def isContentEdge(edgeType: String): Boolean =
    edgeType == EdgeType.contains ||
      edgeType == EdgeType.containedBy ||
      edgeType == EdgeType.builtFrom ||
      edgeType == EdgeType.buildsTo

  /** The "forward" half of a content-edge pair — the direction we orient the
    * topological sort along. An Item that has a `forwardContentEdge` to X
    * means X should be written **before** this Item (X is a child/dependency).
    *
    * The convention chosen: `contained:up` (containedBy) and `build:down`
    * (builtFrom) are the forward edges. Their inverses (`contained:down` /
    * `build:up`) are written first via the items they point at. */
  def isForwardContentEdge(edgeType: String): Boolean =
    edgeType == EdgeType.containedBy ||
      edgeType == EdgeType.builtFrom
}
