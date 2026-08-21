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
  val ProbeBytes: Int = 256 * 1024

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

  /** Applicability rule: block-list shaped — text/XML/JSON/class files can
    * never carry carved DER certs; binaries and unknown fragments (ELF sections
    * are octet-stream) stay probed.
    */
  private[goatrodeo] def mimeRule(mimes: Set[String]): Boolean =
    ArtifactWrapper.noneOf(
      "text/",
      "application/xml",
      "application/json",
      "application/java-vm"
    )(mimes)

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
    val seen = scala.collection.mutable.HashSet[String]()
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

  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    val found = Try {
      artifact.withStream { s =>
        val buf = new Array[Byte](ProbeBytes)
        val n = s.read(buf, 0, ProbeBytes)
        val bytes =
          if (n <= 0) Array.emptyByteArray else Arrays.copyOf(buf, n)
        hasDerCandidate(bytes, ProbeBytes)
      }
    }.getOrElse(false)
    if (found) currentMimes + CarvedMime else currentMimes
  }
}
