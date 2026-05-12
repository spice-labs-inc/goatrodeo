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

import io.bullet.borer.Decoder
import io.bullet.borer.Encoder

import scala.annotation.targetName
import scala.collection.immutable.ArraySeq

object Opaques {

  /** A Gitoid — a content-addressable identifier with an object type and hash
    * algorithm.
    *
    * Internally a single `ArraySeq.ofByte` of length `1 + algorithm.byteLength`:
    *   - byte 0: header — high nibble = `ObjectType.ordinal`, low nibble = `HashAlgorithm.ordinal`
    *   - bytes 1..n: raw hash bytes
    *
    * `ArraySeq.ofByte` is backed by a single `Array[Byte]` (no per-element
    * boxing) and provides structural equality / hashCode, which is required
    * for `Set[Gitoid]` and `Map[Gitoid, _]` semantics.
    */
  opaque type Gitoid = ArraySeq.ofByte

  object Gitoid {

    enum ObjectType {
      case Blob, Tree, Commit, Tag

      def name: String = this match {
        case Blob   => "blob"
        case Tree   => "tree"
        case Commit => "commit"
        case Tag    => "tag"
      }
    }

    enum HashAlgorithm {
      case Sha1, Sha256

      def name: String = this match {
        case Sha1   => "sha1"
        case Sha256 => "sha256"
      }

      def byteLength: Int = this match {
        case Sha1   => 20
        case Sha256 => 32
      }
    }

    private inline def headerByte(t: ObjectType, a: HashAlgorithm): Byte =
      (((t.ordinal & 0xf) << 4) | (a.ordinal & 0xf)).toByte

    /** Internal byte accessor — returns the backing array directly (zero-copy).
      * Do not mutate. */
    private[Opaques] inline def bytes(g: Gitoid): Array[Byte] = g.unsafeArray

    private inline def hexNibble(c: Char): Int = c match {
      case '0'       => 0
      case '1'       => 1
      case '2'       => 2
      case '3'       => 3
      case '4'       => 4
      case '5'       => 5
      case '6'       => 6
      case '7'       => 7
      case '8'       => 8
      case '9'       => 9
      case 'a' | 'A' => 10
      case 'b' | 'B' => 11
      case 'c' | 'C' => 12
      case 'd' | 'D' => 13
      case 'e' | 'E' => 14
      case 'f' | 'F' => 15
      case _ =>
        throw new IllegalArgumentException(s"Invalid hex character: '$c'")
    }

    private inline def hexChar(b: Int): Char = (b & 0xf) match {
      case 0  => '0'
      case 1  => '1'
      case 2  => '2'
      case 3  => '3'
      case 4  => '4'
      case 5  => '5'
      case 6  => '6'
      case 7  => '7'
      case 8  => '8'
      case 9  => '9'
      case 10 => 'a'
      case 11 => 'b'
      case 12 => 'c'
      case 13 => 'd'
      case 14 => 'e'
      case 15 => 'f'
    }

    /** Build a Gitoid directly from binary hash bytes — no String materialised. */
    @targetName("fromIArray")
    def apply(
        objectType: ObjectType,
        algorithm: HashAlgorithm,
        hash: IArray[Byte]
    ): Gitoid =
      apply(objectType, algorithm, hash.asInstanceOf[Array[Byte]])

    /** Build a Gitoid from a mutable `Array[Byte]` (e.g. straight from
      * `MessageDigest.digest()`). Defensive copy. */
    @targetName("fromArray")
    def apply(
        objectType: ObjectType,
        algorithm: HashAlgorithm,
        hash: Array[Byte]
    ): Gitoid = {
      val expected = algorithm.byteLength
      if (hash.length != expected)
        throw new IllegalArgumentException(
          s"Hash length ${hash.length} does not match ${algorithm.name} byte length $expected"
        )
      val out = new Array[Byte](1 + expected)
      out(0) = headerByte(objectType, algorithm)
      System.arraycopy(hash, 0, out, 1, expected)
      new ArraySeq.ofByte(out)
    }

    /** Parse a `gitoid:<type>:<algo>:<hex>` URL into a Gitoid. Walks the input
      * character by character; one final `Array[Byte]` allocation. Throws
      * `IllegalArgumentException` on malformed input. */
    def apply(s: String): Gitoid = {
      val len = s.length
      val prefix = "gitoid:"
      if (len < prefix.length || !s.startsWith(prefix))
        throw new IllegalArgumentException(s"Not a gitoid URL: '$s'")
      var i = prefix.length

      val tStart = i
      while (i < len && s.charAt(i) != ':') i += 1
      if (i >= len)
        throw new IllegalArgumentException(s"Missing object type separator in: '$s'")
      val objectType = parseObjectType(s, tStart, i)
      i += 1

      val aStart = i
      while (i < len && s.charAt(i) != ':') i += 1
      if (i >= len)
        throw new IllegalArgumentException(s"Missing hash algorithm separator in: '$s'")
      val algorithm = parseHashAlgorithm(s, aStart, i)
      i += 1

      val expectedHexLen = algorithm.byteLength * 2
      if (len - i != expectedHexLen)
        throw new IllegalArgumentException(
          s"Expected $expectedHexLen hex characters for ${algorithm.name}, got ${len - i} in: '$s'"
        )
      val out = new Array[Byte](1 + algorithm.byteLength)
      out(0) = headerByte(objectType, algorithm)
      var j = 0
      while (j < algorithm.byteLength) {
        val hi = hexNibble(s.charAt(i + j * 2))
        val lo = hexNibble(s.charAt(i + j * 2 + 1))
        out(1 + j) = ((hi << 4) | lo).toByte
        j += 1
      }
      new ArraySeq.ofByte(out)
    }

    private def parseObjectType(s: String, from: Int, until: Int): ObjectType = {
      val tokenLen = until - from
      if (tokenLen == 4) {
        if (s.charAt(from) == 'b' && s.charAt(from + 1) == 'l' &&
          s.charAt(from + 2) == 'o' && s.charAt(from + 3) == 'b')
          ObjectType.Blob
        else if (s.charAt(from) == 't' && s.charAt(from + 1) == 'r' &&
          s.charAt(from + 2) == 'e' && s.charAt(from + 3) == 'e')
          ObjectType.Tree
        else throw new IllegalArgumentException(s"Unknown object type token in '$s'")
      } else if (tokenLen == 6) {
        if (s.charAt(from) == 'c' && s.charAt(from + 1) == 'o' &&
          s.charAt(from + 2) == 'm' && s.charAt(from + 3) == 'm' &&
          s.charAt(from + 4) == 'i' && s.charAt(from + 5) == 't')
          ObjectType.Commit
        else throw new IllegalArgumentException(s"Unknown object type token in '$s'")
      } else if (tokenLen == 3) {
        if (s.charAt(from) == 't' && s.charAt(from + 1) == 'a' &&
          s.charAt(from + 2) == 'g')
          ObjectType.Tag
        else throw new IllegalArgumentException(s"Unknown object type token in '$s'")
      } else throw new IllegalArgumentException(s"Unknown object type token in '$s'")
    }

    private def parseHashAlgorithm(
        s: String,
        from: Int,
        until: Int
    ): HashAlgorithm = {
      val tokenLen = until - from
      if (tokenLen == 4) {
        if (s.charAt(from) == 's' && s.charAt(from + 1) == 'h' &&
          s.charAt(from + 2) == 'a' && s.charAt(from + 3) == '1')
          HashAlgorithm.Sha1
        else throw new IllegalArgumentException(s"Unknown hash algorithm token in '$s'")
      } else if (tokenLen == 6) {
        if (s.charAt(from) == 's' && s.charAt(from + 1) == 'h' &&
          s.charAt(from + 2) == 'a' && s.charAt(from + 3) == '2' &&
          s.charAt(from + 4) == '5' && s.charAt(from + 5) == '6')
          HashAlgorithm.Sha256
        else throw new IllegalArgumentException(s"Unknown hash algorithm token in '$s'")
      } else throw new IllegalArgumentException(s"Unknown hash algorithm token in '$s'")
    }

    /** Pattern-match accessor. Returns `(objectType, algorithm, hash)`. */
    def unapply(g: Gitoid): Some[(ObjectType, HashAlgorithm, IArray[Byte])] = {
      val gb = bytes(g)
      val header = gb(0) & 0xff
      val objectType = ObjectType.fromOrdinal((header >>> 4) & 0xf)
      val algorithm = HashAlgorithm.fromOrdinal(header & 0xf)
      val n = algorithm.byteLength
      val hashCopy = new Array[Byte](n)
      var i = 0
      while (i < n) {
        hashCopy(i) = gb(1 + i)
        i += 1
      }
      Some((objectType, algorithm, IArray.unsafeFromArray(hashCopy)))
    }

    /** URL-form-equivalent ordering: by type name, then algo name, then hash
      * bytes (unsigned). Matches the prior `Ordering.String` over URL strings
      * for any two well-formed gitoids, keeping merkle outputs stable. */
    given Ordering[Gitoid] = (a: Gitoid, b: Gitoid) => {
      val ab = bytes(a)
      val bb = bytes(b)
      val aType = ObjectType.fromOrdinal((ab(0) & 0xff) >>> 4 & 0xf)
      val bType = ObjectType.fromOrdinal((bb(0) & 0xff) >>> 4 & 0xf)
      val typeCmp = aType.name.compareTo(bType.name)
      if (typeCmp != 0) typeCmp
      else {
        val aAlgo = HashAlgorithm.fromOrdinal(ab(0) & 0xf)
        val bAlgo = HashAlgorithm.fromOrdinal(bb(0) & 0xf)
        val algoCmp = aAlgo.name.compareTo(bAlgo.name)
        if (algoCmp != 0) algoCmp
        else {
          val n = math.min(ab.length, bb.length)
          var i = 1
          var result = 0
          while (i < n && result == 0) {
            result = (ab(i) & 0xff) - (bb(i) & 0xff)
            i += 1
          }
          if (result != 0) result else ab.length - bb.length
        }
      }
    }

    /** Borer wire format: encode/decode as the URL string. Preserves on-disk
      * compatibility with existing ADGs. */
    given Encoder[Gitoid] =
      Encoder.forString.contramap[Gitoid](toUrlString)
    given Decoder[Gitoid] =
      Decoder.forString.map(Gitoid(_))

    /** Reconstruct the `gitoid:<type>:<algo>:<hex>` URL. One `StringBuilder`
      * allocation sized exactly. */
    private[Opaques] def toUrlString(g: Gitoid): String = {
      val gb = bytes(g)
      val header = gb(0) & 0xff
      val objectType = ObjectType.fromOrdinal((header >>> 4) & 0xf)
      val algorithm = HashAlgorithm.fromOrdinal(header & 0xf)
      val typeName = objectType.name
      val algoName = algorithm.name
      val n = algorithm.byteLength
      val totalLen =
        "gitoid:".length + typeName.length + 1 + algoName.length + 1 + n * 2
      val sb = new java.lang.StringBuilder(totalLen)
      sb.append("gitoid:").append(typeName).append(':').append(algoName).append(':')
      var i = 0
      while (i < n) {
        val b = gb(1 + i) & 0xff
        sb.append(hexChar(b >>> 4))
        sb.append(hexChar(b & 0xf))
        i += 1
      }
      sb.toString
    }
  }

  extension (g: Gitoid) {
    /** Reconstruct the URL form. Allocates a fresh String. */
    def apply(): String = Gitoid.toUrlString(g)

    def objectType: Gitoid.ObjectType =
      Gitoid.ObjectType.fromOrdinal((Gitoid.bytes(g)(0) & 0xff) >>> 4 & 0xf)

    def hashAlgorithm: Gitoid.HashAlgorithm =
      Gitoid.HashAlgorithm.fromOrdinal(Gitoid.bytes(g)(0) & 0xf)

    /** A copy of the hash bytes (without the header). */
    def hash: IArray[Byte] = {
      val gb = Gitoid.bytes(g)
      val n = gb.length - 1
      val out = new Array[Byte](n)
      var i = 0
      while (i < n) {
        out(i) = gb(1 + i)
        i += 1
      }
      IArray.unsafeFromArray(out)
    }

    def isBlobSha256: Boolean = Gitoid.bytes(g)(0) == ((0 << 4) | 1).toByte

    /** Does the URL form start with the given prefix? Materialises the URL
      * string — callers in performance-sensitive paths should prefer
      * `objectType` / `hashAlgorithm` checks. */
    def startsWith(prefix: String): Boolean =
      Gitoid.toUrlString(g).startsWith(prefix)
  }
}

export Opaques.Gitoid
