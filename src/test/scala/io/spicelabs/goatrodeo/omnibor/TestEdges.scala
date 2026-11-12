/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor

import scala.collection.immutable.{TreeMap, TreeSet}

/** Test-side helpers for building an `Item`'s connections.
  *
  * `Item.connections` is the version 4 shape — an ordered map of edge type to
  * target set — so a test that wants two `alias:from` edges writes one entry with a
  * two-element set rather than two pairs. `edges` takes the pair form that reads
  * naturally in a test and groups it, which keeps the call sites about what the test
  * means instead of about the map's shape.
  */
object TestEdges {

  /** Group `(edge type, target)` pairs into the map the model uses. */
  def edges(pairs: (String, String)*): TreeMap[String, TreeSet[String]] =
    pairs.foldLeft(TreeMap.empty[String, TreeSet[String]]) {
      case (acc, (edgeType, target)) =>
        acc.updated(
          edgeType,
          acc.getOrElse(edgeType, TreeSet.empty[String]) + target
        )
    }

  /** The same, from a collection. */
  def edgesOf(pairs: Iterable[(String, String)]): TreeMap[String, TreeSet[String]] =
    edges(pairs.toSeq*)
}
