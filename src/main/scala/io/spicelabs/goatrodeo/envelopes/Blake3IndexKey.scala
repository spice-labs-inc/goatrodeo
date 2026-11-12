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

package io.spicelabs.goatrodeo.envelopes

import io.spicelabs.goatrodeo.util.Blake3

/** The version 4 index key: `BLAKE3[0..16]` over the identifier's UTF-8 bytes.
  *
  * BigTent writes the algorithm into the file — the cluster envelope and every index
  * envelope carry `encoding: "BLAKE3[0..16]/Long/Long"` — and readers use the
  * algorithm the files declare rather than assuming one. This object is that
  * algorithm on the writing side, and the string it produces is the one written.
  *
  * The key is exactly 16 bytes. It is *not* an artifact identifier: the identifiers
  * stay gitoids, and BLAKE3 only decides where an index entry sits.
  */
object Blake3IndexKey {

  /** The declaration BigTent writes for this algorithm. */
  val encoding: String = "BLAKE3[0..16]/Long/Long"

  /** Key length: 128 bits. */
  val KeyBytes: Int = 16

  /** The index key for an identifier. */
  def key(identifier: String): Array[Byte] =
    Blake3.hashUtf8(identifier, KeyBytes)

  /** The key as the hexadecimal string BigTent's comparator uses. */
  def keyHex(identifier: String): String =
    Blake3.hex(key(identifier))
}
