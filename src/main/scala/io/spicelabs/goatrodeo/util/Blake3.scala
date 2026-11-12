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

package io.spicelabs.goatrodeo.util

import java.nio.charset.StandardCharsets
import org.bouncycastle.crypto.digests.Blake3Digest

/** BLAKE3, as BigTent's version 4 format uses it.
  *
  * BouncyCastle is already a dependency of this project and its `Blake3Digest` is an
  * extensible-output function, so the truncated 128-bit key the format asks for is a
  * single `doFinal(out, 0, 16)` rather than a digest followed by a copy. The
  * implementation is pure Java with no SIMD — fine for the short identifier strings
  * this hashes, which is the only use here.
  */
object Blake3 {

  /** BLAKE3 of these bytes, truncated to `outLen`. */
  def hash(bytes: Array[Byte], outLen: Int = 16): Array[Byte] = {
    val digest = new Blake3Digest()
    digest.update(bytes, 0, bytes.length)
    val out = new Array[Byte](outLen)
    digest.doFinal(out, 0, outLen)
    out
  }

  /** BLAKE3 of this string's UTF-8 bytes, truncated to `outLen`. */
  def hashUtf8(value: String, outLen: Int = 16): Array[Byte] =
    hash(value.getBytes(StandardCharsets.UTF_8), outLen)

  /** The hexadecimal form, used wherever an order or a name is needed. */
  def hex(bytes: Array[Byte]): String =
    Helpers.toHex(bytes)
}
