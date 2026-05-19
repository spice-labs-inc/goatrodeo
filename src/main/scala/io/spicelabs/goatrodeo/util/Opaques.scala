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

import java.security.MessageDigest
import scala.annotation.targetName
import scala.collection.immutable.ArraySeq

object Opaques {

  /** Shared header-byte layout for both `Gitoid` and `Identifier`.
    *
    * byte 0:
    *   high nibble (bits 7..4) — IdentifierKind ordinal
    *     0 = Gitoid
    *     1 = RawHash
    *     2 = PackageUrl
    *     3 = Sentinel
    *   low nibble (bits 3..0) — kind-specific sub-encoding
    *     Gitoid:     (ObjectType.ordinal << 2) | HashAlgorithm.ordinal
    *     RawHash:    RawHashAlgorithm.ordinal
    *     PackageUrl: 0 (reserved)
    *     Sentinel:   0 (reserved)
    *
    * bytes 1..n:
    *   Gitoid / RawHash: raw hash bytes (length determined by algorithm)
    *   PackageUrl / Sentinel: UTF-8 bytes of the canonical text form
    */
  private inline val KindGitoid = 0
  private inline val KindRawHash = 1
  private inline val KindPackageUrl = 2
  private inline val KindSentinel = 3

  // ============================================================================
  // Gitoid
  // ============================================================================

  /** A Gitoid — a content-addressable git-style identifier with an object type
    * and hash algorithm.
    *
    * Internally `ArraySeq.ofByte` of length `1 + algorithm.byteLength`:
    *   - byte 0: high nibble = 0 (Identifier.Kind.Gitoid), low nibble =
    *     `(ObjectType.ordinal << 2) | HashAlgorithm.ordinal`
    *   - bytes 1..n: raw hash bytes
    *
    * Identical wire layout to an `Identifier` whose `kind == Kind.Gitoid`, so
    * conversion to/from `Identifier` is zero-copy.
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
      ((KindGitoid << 4) | ((t.ordinal & 0x3) << 2) | (a.ordinal & 0x3)).toByte

    private[Opaques] inline def bytes(g: Gitoid): Array[Byte] = g.unsafeArray

    @targetName("fromIArray")
    def apply(
        objectType: ObjectType,
        algorithm: HashAlgorithm,
        hash: IArray[Byte]
    ): Gitoid =
      apply(objectType, algorithm, hash.asInstanceOf[Array[Byte]])

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

    /** Parse a `gitoid:<type>:<algo>:<hex>` URL into a Gitoid. */
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
        val hi = HexUtil.nibble(s.charAt(i + j * 2))
        val lo = HexUtil.nibble(s.charAt(i + j * 2 + 1))
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

    def unapply(g: Gitoid): Some[(ObjectType, HashAlgorithm, IArray[Byte])] = {
      val gb = bytes(g)
      val sub = gb(0) & 0xf
      val objectType = ObjectType.fromOrdinal((sub >>> 2) & 0x3)
      val algorithm = HashAlgorithm.fromOrdinal(sub & 0x3)
      val n = algorithm.byteLength
      val hashCopy = new Array[Byte](n)
      System.arraycopy(gb, 1, hashCopy, 0, n)
      Some((objectType, algorithm, IArray.unsafeFromArray(hashCopy)))
    }

    given Ordering[Gitoid] = (a: Gitoid, b: Gitoid) => {
      val ab = bytes(a)
      val bb = bytes(b)
      val aSub = ab(0) & 0xf
      val bSub = bb(0) & 0xf
      val aType = ObjectType.fromOrdinal((aSub >>> 2) & 0x3)
      val bType = ObjectType.fromOrdinal((bSub >>> 2) & 0x3)
      val typeCmp = aType.name.compareTo(bType.name)
      if (typeCmp != 0) typeCmp
      else {
        val aAlgo = HashAlgorithm.fromOrdinal(aSub & 0x3)
        val bAlgo = HashAlgorithm.fromOrdinal(bSub & 0x3)
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

    given Encoder[Gitoid] = new Encoder[Gitoid] {
      def write(w: io.bullet.borer.Writer, g: Gitoid): w.type =
        w.writeBytes(bytes(g))
    }
    given Decoder[Gitoid] = new Decoder[Gitoid] {
      def read(r: io.bullet.borer.Reader): Gitoid = {
        val raw = r.readByteArray()
        if (raw.length < 1) throw new IllegalArgumentException("Empty gitoid bytes")
        val kind = (raw(0) & 0xff) >>> 4
        if (kind != KindGitoid)
          throw new IllegalArgumentException(s"Bytes are not a Gitoid (kind=$kind)")
        val sub = raw(0) & 0xf
        val algo = HashAlgorithm.fromOrdinal(sub & 0x3)
        if (raw.length != 1 + algo.byteLength)
          throw new IllegalArgumentException(
            s"Gitoid bytes length ${raw.length} does not match header (expects ${1 + algo.byteLength})"
          )
        new ArraySeq.ofByte(raw)
      }
    }

    /** Reconstruct the `gitoid:<type>:<algo>:<hex>` URL. */
    private[Opaques] def toUrlString(g: Gitoid): String = {
      val gb = bytes(g)
      val sub = gb(0) & 0xf
      val objectType = ObjectType.fromOrdinal((sub >>> 2) & 0x3)
      val algorithm = HashAlgorithm.fromOrdinal(sub & 0x3)
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
        sb.append(HexUtil.char(b >>> 4))
        sb.append(HexUtil.char(b & 0xf))
        i += 1
      }
      sb.toString
    }
  }

  extension (g: Gitoid) {
    def apply(): String = Gitoid.toUrlString(g)

    def objectType: Gitoid.ObjectType =
      Gitoid.ObjectType.fromOrdinal(((Gitoid.bytes(g)(0) & 0xf) >>> 2) & 0x3)

    def hashAlgorithm: Gitoid.HashAlgorithm =
      Gitoid.HashAlgorithm.fromOrdinal(Gitoid.bytes(g)(0) & 0x3)

    def hash: IArray[Byte] = {
      val gb = Gitoid.bytes(g)
      val n = gb.length - 1
      val out = new Array[Byte](n)
      System.arraycopy(gb, 1, out, 0, n)
      IArray.unsafeFromArray(out)
    }

    /** A Gitoid is "blob+sha256" iff sub-encoding bits are `(Blob.ordinal << 2)
      * | Sha256.ordinal` = `(0 << 2) | 1` = 1. */
    def isBlobSha256: Boolean =
      Gitoid.bytes(g)(0) == ((KindGitoid << 4) | 1).toByte

    def startsWith(prefix: String): Boolean =
      Gitoid.toUrlString(g).startsWith(prefix)
  }

  // ============================================================================
  // Identifier
  // ============================================================================

  /** A general-purpose `Item` identifier — Gitoid, raw hash alias, package URL,
    * or sentinel string. Byte-encoded as a single `ArraySeq.ofByte` whose first
    * byte tags the kind (see top-of-file layout comment); the remaining bytes
    * are kind-dependent.
    *
    * Sharing the wire layout with `Gitoid` means converting between them is
    * zero-copy when the underlying bytes are valid for both. */
  opaque type Identifier = ArraySeq.ofByte

  object Identifier {

    enum Kind {
      case Gitoid, RawHash, PackageUrl, Sentinel
    }

    enum RawHashAlgorithm {
      case Md5, Sha1, Sha256, Sha512

      def byteLength: Int = this match {
        case Md5    => 16
        case Sha1   => 20
        case Sha256 => 32
        case Sha512 => 64
      }

      def name: String = this match {
        case Md5    => "md5"
        case Sha1   => "sha1"
        case Sha256 => "sha256"
        case Sha512 => "sha512"
      }
    }

    private[Opaques] inline def bytes(id: Identifier): Array[Byte] = id.unsafeArray

    private inline def headerByte(kind: Int, sub: Int): Byte =
      (((kind & 0xf) << 4) | (sub & 0xf)).toByte

    // ---- Construction from typed sources (cheap, no parsing) ---------------

    /** Zero-copy: the underlying `ArraySeq.ofByte` is already valid as both
      * `Gitoid` and `Identifier` because they share the byte layout. */
    def fromGitoid(g: Gitoid): Identifier = g.asInstanceOf[Identifier]

    def fromPackageUrl(p: PackageUrl): Identifier = {
      val utf8 = p.apply().getBytes("UTF-8")
      val out = new Array[Byte](1 + utf8.length)
      out(0) = headerByte(KindPackageUrl, 0)
      System.arraycopy(utf8, 0, out, 1, utf8.length)
      new ArraySeq.ofByte(out)
    }

    def sentinel(text: String): Identifier = {
      val utf8 = text.getBytes("UTF-8")
      val out = new Array[Byte](1 + utf8.length)
      out(0) = headerByte(KindSentinel, 0)
      System.arraycopy(utf8, 0, out, 1, utf8.length)
      new ArraySeq.ofByte(out)
    }

    def rawHash(algo: RawHashAlgorithm, hash: Array[Byte]): Identifier = {
      if (hash.length != algo.byteLength)
        throw new IllegalArgumentException(
          s"Hash length ${hash.length} does not match ${algo.name} byte length ${algo.byteLength}"
        )
      val out = new Array[Byte](1 + algo.byteLength)
      out(0) = headerByte(KindRawHash, algo.ordinal & 0xf)
      System.arraycopy(hash, 0, out, 1, algo.byteLength)
      new ArraySeq.ofByte(out)
    }

    // ---- Construction from canonical String (dispatches on prefix) ---------

    /** Parse a canonical identifier string into the appropriate kind:
      *
      *   "gitoid:…"   → Gitoid
      *   "md5:…" / "sha1:…" / "sha256:…" / "sha512:…" → RawHash
      *   "pkg:…"      → PackageUrl
      *   otherwise    → Sentinel (free-form text, tolerates "tags" et al.)
      */
    def apply(canonical: String): Identifier = {
      if (canonical.startsWith("gitoid:")) {
        fromGitoid(Gitoid(canonical))
      } else if (canonical.startsWith("pkg:")) {
        fromPackageUrl(PackageUrl(canonical))
      } else {
        // Possible raw-hash prefixes.
        val algoOpt: Option[RawHashAlgorithm] =
          if (canonical.startsWith("md5:")) Some(RawHashAlgorithm.Md5)
          else if (canonical.startsWith("sha1:")) Some(RawHashAlgorithm.Sha1)
          else if (canonical.startsWith("sha256:")) Some(RawHashAlgorithm.Sha256)
          else if (canonical.startsWith("sha512:")) Some(RawHashAlgorithm.Sha512)
          else None
        algoOpt match {
          case Some(algo) =>
            val colonAt = canonical.indexOf(':')
            val hexStart = colonAt + 1
            val expectedHex = algo.byteLength * 2
            if (canonical.length - hexStart != expectedHex) {
              // Wrong hex length — preserve as sentinel rather than reject.
              // This keeps short test fixtures and unusual identifier strings
              // working without burdening every caller with a try/catch.
              sentinel(canonical)
            } else {
              val hash = new Array[Byte](algo.byteLength)
              var j = 0
              var fallToSentinel = false
              while (j < algo.byteLength && !fallToSentinel) {
                try {
                  val hi = HexUtil.nibble(canonical.charAt(hexStart + j * 2))
                  val lo = HexUtil.nibble(canonical.charAt(hexStart + j * 2 + 1))
                  hash(j) = ((hi << 4) | lo).toByte
                  j += 1
                } catch {
                  case _: IllegalArgumentException =>
                    fallToSentinel = true
                }
              }
              if (fallToSentinel) sentinel(canonical) else rawHash(algo, hash)
            }
          case None =>
            sentinel(canonical)
        }
      }
    }

    given Ordering[Identifier] = (a: Identifier, b: Identifier) => {
      val ab = bytes(a)
      val bb = bytes(b)
      val n = math.min(ab.length, bb.length)
      var i = 0
      var result = 0
      while (i < n && result == 0) {
        result = (ab(i) & 0xff) - (bb(i) & 0xff)
        i += 1
      }
      if (result != 0) result else ab.length - bb.length
    }

    given Encoder[Identifier] = new Encoder[Identifier] {
      def write(w: io.bullet.borer.Writer, id: Identifier): w.type =
        w.writeBytes(bytes(id))
    }
    given Decoder[Identifier] = new Decoder[Identifier] {
      def read(r: io.bullet.borer.Reader): Identifier =
        new ArraySeq.ofByte(r.readByteArray())
    }

    /** Materialise the canonical String form. Used by the lazy cache on
      * `Item` so each Item pays at most once. */
    private[Opaques] def toCanonicalString(id: Identifier): String = {
      val ib = bytes(id)
      if (ib.length == 0)
        throw new IllegalStateException("Identifier with no bytes")
      val kind = (ib(0) & 0xff) >>> 4
      kind match {
        case KindGitoid     => Gitoid.toUrlString(id.asInstanceOf[Gitoid])
        case KindRawHash    => rawHashString(ib)
        case KindPackageUrl => new String(ib, 1, ib.length - 1, "UTF-8")
        case KindSentinel   => new String(ib, 1, ib.length - 1, "UTF-8")
        case other =>
          throw new IllegalStateException(s"Unknown Identifier kind: $other")
      }
    }

    private def rawHashString(ib: Array[Byte]): String = {
      val algo = RawHashAlgorithm.fromOrdinal(ib(0) & 0xf)
      val n = algo.byteLength
      val name = algo.name
      val totalLen = name.length + 1 + n * 2
      val sb = new java.lang.StringBuilder(totalLen)
      sb.append(name).append(':')
      var i = 0
      while (i < n) {
        val b = ib(1 + i) & 0xff
        sb.append(HexUtil.char(b >>> 4))
        sb.append(HexUtil.char(b & 0xf))
        i += 1
      }
      sb.toString
    }
  }

  extension (id: Identifier) {
    /** Canonical text form. */
    @targetName("identifierApply")
    def apply(): String = Identifier.toCanonicalString(id)

    @targetName("identifierKind")
    def kind: Identifier.Kind = {
      val k = (Identifier.bytes(id)(0) & 0xff) >>> 4
      Identifier.Kind.fromOrdinal(k)
    }

    @targetName("identifierIsGitoid")
    def isGitoid: Boolean = ((Identifier.bytes(id)(0) & 0xff) >>> 4) == KindGitoid

    /** Zero-copy conversion to `Gitoid` when this identifier IS a gitoid. */
    @targetName("identifierAsGitoid")
    def asGitoid: Option[Gitoid] =
      if (id.isGitoid) Some(id.asInstanceOf[Gitoid]) else None

    /** MD5 of the raw identifier bytes — no UTF-8 round-trip through a
      * canonical String. */
    @targetName("identifierMd5")
    def md5: Array[Byte] = {
      val md = MessageDigest.getInstance("MD5")
      md.digest(Identifier.bytes(id))
    }

    @targetName("identifierMd5Hex")
    def md5Hex: String = bytesToHex(id.md5)

    @targetName("identifierStartsWith")
    def startsWith(prefix: String): Boolean =
      Identifier.toCanonicalString(id).startsWith(prefix)
  }

  // ============================================================================
  // PackageUrl
  // ============================================================================

  opaque type PackageUrl = String

  object PackageUrl {

    def apply(canonical: String): PackageUrl = {
      val parsed = new com.github.packageurl.PackageURL(canonical)
      val again = parsed.canonicalize()
      if (again != canonical)
        throw new IllegalArgumentException(
          s"Not a canonical PackageURL: '$canonical' canonicalises to '$again'"
        )
      canonical
    }

    def apply(purl: com.github.packageurl.PackageURL): PackageUrl =
      purl.canonicalize()

    given Ordering[PackageUrl] = Ordering.String
    given Encoder[PackageUrl] =
      Encoder.forString.asInstanceOf[Encoder[PackageUrl]]
    given Decoder[PackageUrl] =
      Decoder.forString.asInstanceOf[Decoder[PackageUrl]]
  }

  extension (p: PackageUrl) {
    def apply(): String = p

    def parsed: com.github.packageurl.PackageURL =
      new com.github.packageurl.PackageURL(p)

    def startsWith(prefix: String): Boolean = (p: String).startsWith(prefix)
  }

  // ============================================================================
  // Shared hex utilities (also used by Helpers but kept private to Opaques
  // so the encoders/decoders here have no external dependencies). The public
  // hex helpers in Helpers.scala are unchanged.
  // ============================================================================

  private object HexUtil {
    inline def nibble(c: Char): Int = c match {
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

    inline def char(b: Int): Char = (b & 0xf) match {
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
  }

  private def bytesToHex(arr: Array[Byte]): String = {
    val sb = new java.lang.StringBuilder(arr.length * 2)
    var i = 0
    while (i < arr.length) {
      val b = arr(i) & 0xff
      sb.append(HexUtil.char(b >>> 4))
      sb.append(HexUtil.char(b & 0xf))
      i += 1
    }
    sb.toString
  }
}

export Opaques.Gitoid
export Opaques.Identifier
export Opaques.PackageUrl
