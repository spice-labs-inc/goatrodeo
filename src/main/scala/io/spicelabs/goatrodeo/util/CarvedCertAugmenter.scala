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

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Arrays
import scala.collection.mutable.HashSet
import scala.util.Try

/** MIME augmentation and carving for DER X.509 certificates embedded at
  * arbitrary offsets inside binary artifacts (firmware ELF sections, raw
  * blobs). Firmware certs are DER byte arrays — not PEM text — so the existing
  * PEM-marker detectors never see them.
  *
  * Two-phase, like the other content detectors:
  *   - the MIME pass probes a bounded window (256 KB) for candidate DER
  *     structures containing an SPKI OID needle and emits
  *     `application/x-goatrodeo-carved-x509`;
  *   - the claiming strategy calls [[carveCertificates]] during processing to
  *     fully parse (and dedupe) the certificates within a larger bounded scan.
  *
  * Safety: only full DER parses are ever returned — a length-forged candidate
  * never yields a certificate — and the byte caps bound hostile inputs.
  */
object CarvedCertAugmenter {

  val CarvedMime = "application/x-goatrodeo-carved-x509"

  /** Bytes read during the MIME-pass probe. */
  val ProbeBytes: Int = MimeProbe.MaxBytes

  /** Bytes scanned during processing-time carving. */
  val MaxScanBytes: Int = 16 * 1024 * 1024

  /** Maximum parsed certificates per artifact. */
  val MaxCerts: Int = 1024

  // OID needles: rsaEncryption (1.2.840.113549.1.1.1) and id-ecPublicKey
  // (1.2.840.10045.2.1) as they appear inside an X.509 subjectPublicKeyInfo.
  private val RsaSpkiOid: Array[Byte] = Array[Byte](
    0x06,
    0x09,
    0x2a,
    0x86.toByte,
    0x48,
    0x86.toByte,
    0xf7.toByte,
    0x0d,
    0x01,
    0x01,
    0x01
  )
  private val EcSpkiOid: Array[Byte] =
    Array[Byte](
      0x06,
      0x07,
      0x2a,
      0x86.toByte,
      0x48,
      0xce.toByte,
      0x3d,
      0x02,
      0x01
    )

  /** Both sides come from [[MimeFamilies]], the same pair the consumer of this
    * augmenter's MIME type uses, so a change to what this augmenter can handle
    * changes its consumer at the same moment.
    */
  private[goatrodeo] def mimeRule(
      mimes: Set[String],
      name: String = ""
  ): Boolean =
    !MimeFamilies.contradicts(mimes, MimeFamilies.cannotBeCarvedCarrier) &&
      MimeFamilies.couldBe(mimes, MimeFamilies.couldBeCarvedCarrier)

  /** Parse the DER length header at `headerIdx` (pointing at the `0x30`
    * SEQUENCE byte). Returns the total DER object size (header + content), or
    * None when the header is malformed or the declared length is out of bounds.
    */
  private[goatrodeo] def derObjectLength(
      bytes: Array[Byte],
      headerIdx: Int
  ): Option[Int] = {
    if (headerIdx < 0 || headerIdx + 1 >= bytes.length) return None
    if ((bytes(headerIdx) & 0xff) != 0x30) return None
    val b1 = bytes(headerIdx + 1) & 0xff
    if ((b1 & 0x80) == 0) {
      val total = 2 + b1
      if (b1 >= 4 && headerIdx + total <= bytes.length) Some(total) else None
    } else if (b1 == 0x81 && headerIdx + 2 < bytes.length) {
      val contentLen = bytes(headerIdx + 2) & 0xff
      val total = 3 + contentLen
      if (contentLen >= 4 && headerIdx + total <= bytes.length) Some(total)
      else None
    } else if (b1 == 0x82 && headerIdx + 3 < bytes.length) {
      val contentLen =
        ((bytes(headerIdx + 2) & 0xff) << 8) | (bytes(headerIdx + 3) & 0xff)
      val total = 4 + contentLen
      if (contentLen >= 4 && headerIdx + total <= bytes.length) Some(total)
      else None
    } else {
      None
    }
  }

  private def containsSpkiNeedle(
      hay: Array[Byte],
      off: Int,
      len: Int
  ): Boolean = {
    val end = math.min(off + len, hay.length)
    var i = off
    while (i < end) {
      if ((hay(i) & 0xff) == 0x06) {
        if (startsWith(hay, i, RsaSpkiOid) || startsWith(hay, i, EcSpkiOid))
          return true
      }
      i += 1
    }
    false
  }

  private def startsWith(
      hay: Array[Byte],
      at: Int,
      needle: Array[Byte]
  ): Boolean = {
    if (at + needle.length > hay.length) return false
    var j = 0
    while (j < needle.length) {
      if (hay(at + j) != needle(j)) return false
      j += 1
    }
    true
  }

  private def parseCert(bytes: Array[Byte]): Option[X509Certificate] = {
    Try {
      CertificateFactory
        .getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(bytes))
    }.toOption.collect { case x: X509Certificate => x }
  }

  /** True when a DER candidate containing an SPKI needle exists within the
    * first `maxBytes` bytes. Cheap probe for the MIME pass.
    */
  private[goatrodeo] def hasDerCandidate(
      bytes: Array[Byte],
      maxBytes: Int
  ): Boolean = {
    val limit = math.min(bytes.length - 2, maxBytes)
    var i = 0
    while (i <= limit) {
      if ((bytes(i) & 0xff) == 0x30) {
        val b1 = bytes(i + 1) & 0xff
        if (b1 == 0x81 || b1 == 0x82) {
          derObjectLength(bytes, i) match {
            case Some(total) if containsSpkiNeedle(bytes, i, total) =>
              return true
            case _ =>
          }
        }
      }
      i += 1
    }
    false
  }

  /** Scan `bytes` for embedded DER X.509 certificates (deduped by DER SHA-256).
    * Returns the parsed certificates and whether more candidates existed beyond
    * `maxCerts`.
    */
  private[goatrodeo] def carveCertificates(
      bytes: Array[Byte],
      maxBytes: Int,
      maxCerts: Int
  ): (Vector[X509Certificate], Boolean) = {
    val limit = math.min(bytes.length - 2, maxBytes)
    val seen = HashSet[String]()
    val out = Vector.newBuilder[X509Certificate]
    var capExceeded = false
    var count = 0
    var i = 0
    while (i <= limit && !capExceeded) {
      if ((bytes(i) & 0xff) == 0x30) {
        val b1 = bytes(i + 1) & 0xff
        if (b1 == 0x81 || b1 == 0x82) {
          derObjectLength(bytes, i) match {
            case Some(total) if containsSpkiNeedle(bytes, i, total) =>
              val slice = Arrays.copyOfRange(bytes, i, i + total)
              val hash = Helpers.sha256Hex(slice)
              if (!seen.contains(hash)) {
                parseCert(slice).foreach { cert =>
                  seen += hash
                  if (count >= maxCerts) {
                    capExceeded = true
                  } else {
                    out += cert
                    count += 1
                  }
                }
              }
            case _ =>
          }
        }
      }
      i += 1
    }
    (out.result(), capExceeded)
  }

  /** Evidence about a possible carved-certificate carrier.
    *
    * No MIME type is added: whether this artifact *contains* a certificate is
    * not known from a bounded prefix, and a MIME type is only added when it is
    * true. The record says what the carrier is, so the carving strategy can
    * decide whether a deeper look is worth it — and only for artifacts nothing
    * else claimed.
    */
  final case class Carrier(
      isElf: Boolean,
      isPe: Boolean,
      derPrefixSeen: Boolean,
      probedBytes: Int,
      sectionTableOffset: Long = 0L,
      sectionEntrySize: Int = 0,
      sectionCount: Int = 0,
      symbolTableOffset: Long = 0L,
      symbolCount: Int = 0
  ) extends AugmentationEvidence {
    def augmenter: String = "CarvedCertAugmenter"

    /** True when the carrier's link-time tables can be walked: without a
      * section table there is no way to find `.symtab`, and without a symbol
      * table there is no name to aim the carve at.
      */
    def tablesKnown: Boolean = sectionTableOffset > 0 && sectionCount > 0
  }

  /** Reads the ELF header fields that point at the section table. Both the
    * header and the program header table sit in the first block of every sane
    * ELF, so this costs no extra I/O.
    *
    * `e_shoff` is at the *end* of the file in practice, and the section data
    * precedes it; the strategy reaches both by opening fresh streams and
    * skipping, which is an lseek and therefore free in bytes read.
    */
  private def elfTables(bytes: Array[Byte]): (Long, Int, Int) = {
    if (bytes.length < 64) (0L, 0, 0)
    else {
      val is64 = bytes(4) == 2
      if (is64) {
        val eShoff = readLongLE(bytes, 40)
        val eShentsize = readShortLE(bytes, 58)
        val eShnum = readShortLE(bytes, 60)
        // e_shnum == 0 means the real count lives in section 0's sh_size, which
        // would need a second read; treat it as unknown rather than guessing.
        if (eShnum == 0) (0L, 0, 0) else (eShoff, eShentsize, eShnum)
      } else {
        val eShoff = readIntLE(bytes, 32).toLong & 0xffffffffL
        val eShentsize = readShortLE(bytes, 46)
        val eShnum = readShortLE(bytes, 48)
        if (eShnum == 0) (0L, 0, 0) else (eShoff, eShentsize, eShnum)
      }
    }
  }

  /** Reads the PE COFF header fields: where the section table is and where the
    * COFF symbol table is. Both are in the first block for any PE.
    */
  private def peTables(bytes: Array[Byte]): (Long, Int, Int, Long, Int) = {
    if (bytes.length < 64) (0L, 0, 0, 0L, 0)
    else {
      val eLfanew = readIntLE(bytes, 60)
      if (eLfanew <= 0 || eLfanew + 24 > bytes.length) (0L, 0, 0, 0L, 0)
      else {
        val symbolPointer = readIntLE(bytes, eLfanew + 12).toLong & 0xffffffffL
        val numberOfSymbols = readIntLE(bytes, eLfanew + 16)
        val sizeOfOptionalHeader = readShortLE(bytes, eLfanew + 20)
        val numberOfSections = readShortLE(bytes, eLfanew + 6)
        val sectionTable = eLfanew + 24 + sizeOfOptionalHeader
        (
          sectionTable.toLong,
          40,
          numberOfSections,
          symbolPointer,
          numberOfSymbols
        )
      }
    }
  }

  private def readShortLE(b: Array[Byte], at: Int): Int =
    (b(at) & 0xff) | ((b(at + 1) & 0xff) << 8)

  private def readIntLE(b: Array[Byte], at: Int): Int =
    (b(at) & 0xff) | ((b(at + 1) & 0xff) << 8) |
      ((b(at + 2) & 0xff) << 16) | ((b(at + 3) & 0xff) << 24)

  private def readLongLE(b: Array[Byte], at: Int): Long = {
    var v = 0L
    var i = 7
    while (i >= 0) { v = (v << 8) | (b(at + i) & 0xffL); i -= 1 }
    v
  }

  val augmenter: MimeAugmenter = MimeAugmenter(
    id = "CarvedCertAugmenter",
    rule = mimeRule(_, _),
    budget = ProbeBudget(),
    fn = (artifact, currentMimes, evidence, probe) => {
      val bytes = probe.bytes
      val isElf = bytes.length >= 4 && bytes(0) == 0x7f.toByte &&
        bytes(1) == 'E'.toByte && bytes(2) == 'L'.toByte && bytes(
          3
        ) == 'F'.toByte
      val isPe = bytes.length >= 2 && bytes(0) == 'M'.toByte &&
        bytes(1) == 'Z'.toByte
      val elf = if (isElf) elfTables(bytes) else (0L, 0, 0)
      val pe = if (isPe) peTables(bytes) else (0L, 0, 0, 0L, 0)
      val record = Carrier(
        isElf = isElf,
        isPe = isPe,
        derPrefixSeen = hasDerCandidate(bytes, bytes.length),
        probedBytes = bytes.length,
        sectionTableOffset = if (isElf) elf._1 else pe._1,
        sectionEntrySize = if (isElf) elf._2 else pe._2,
        sectionCount = if (isElf) elf._3 else pe._3,
        symbolTableOffset = pe._4,
        symbolCount = pe._5
      )
      // A DER object carrying an SPKI needle in the first block *is* carved
      // certificate material — a known fact, not a possibility — so the MIME is
      // asserted here and the strategy claims it without probing again.
      // The ELF magic in the first four bytes is proof, so the ELF container type
      // is claimed here rather than left to Saffron's probe: that probe needs the
      // whole file to classify an ELF, and a binary larger than the probe prefix
      // therefore came back unclassified. A known type belongs in the MIME set
      // before the probes that would otherwise guess at it.
      val afterElf =
        if (record.isElf && !currentMimes.contains(SaffronDetector.ElfMime))
          currentMimes + SaffronDetector.ElfMime
        else currentMimes
      val mimes =
        if (record.derPrefixSeen && !afterElf.contains(CarvedMime))
          afterElf + CarvedMime
        else afterElf
      (
        mimes,
        evidence.updated(
          CarvedMime,
          evidence.getOrElse(CarvedMime, Vector.empty) :+ record
        )
      )
    }
  )
}
