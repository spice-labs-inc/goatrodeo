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

import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.io.ByteArrayInputStream
import java.security.Security
import java.security.cert.CertificateFactory
import scala.util.Try

/** Detects cryptographic file formats (X.509, keystores, PGP, SSH, CRLs,
  * private keys) by examining content, and augments the MIME type set
  * seen by the Certificates strategy.
  *
  * Purely additive: never removes or replaces existing MIME types.
  * Contrast with [[SaffronDetector]] which strips MIME types beginning
  * with `text/` for its own domain-specific reasons (VM/disk-image
  * classification).
  *
  * See `info/certificates_strategy.md` for the full detection
  * vocabulary, and `certificates-strategy/phases-1-2-foundation-detector.md`
  * for the per-format signature table this implementation honors.
  *
  * ## LLM-friendly summary
  *
  *   - Read no more than 4 KB from the artifact stream.
  *   - Add MIME types when a signature matches; never remove existing
  *     MIME types (including any beginning with `text/`).
  *   - When no signature matches, return `currentMimes` unchanged.
  *
  * The signatures fall into four families:
  *
  *   1. **PEM headers** — `-----BEGIN CERTIFICATE-----`,
  *      `-----BEGIN PRIVATE KEY-----`, etc. Detected by substring
  *      search inside the first 4 KB.
  *   2. **OpenSSH wire format** — first whitespace-delimited token
  *      matches `ssh-rsa`, `ssh-ed25519`, `ecdsa-sha2-nistp256`,
  *      `*-cert-v01@openssh.com`, `sk-*`, etc.
  *   3. **Magic bytes** — JKS (`0xfe 0xed 0xfe 0xed`), JCEKS
  *      (`0xce 0xce 0xce 0xce`), PGP binary packet tags
  *      (`0xC5/C6/95/98`).
  *   4. **DER (`0x30 0x82` prefix) disambiguation** — uses filename
  *      extension hints (`.p12` / `.pfx` → PKCS#12) and
  *      Bouncy-Castle parser probes (X.509 cert / CRL).
  *
  * Implementation note: `Security.addProvider(BouncyCastleProvider)`
  * runs once at object init so DER probes can use the BC X.509
  * factory which handles PQC algorithms beyond the JDK's built-in
  * provider.
  */
object CryptoDetector {

  /** Maximum bytes read from any artifact during HEADER / MAGIC /
    * SSH / PGP / PEM detection — the plan's 4 KB prefix budget.
    *
    * This is the cheap-fast path: a single 4 KB read covers every
    * signature whose detection only needs to see the file's
    * leading bytes (PEM headers, SSH first-token, JKS/JCEKS magic,
    * PGP packet tag, etc.). */
  val MAX_READ_BYTES: Int = 4096

  /** Extended read budget for DER X.509 / CRL parser probe.
    *
    * Phase-2 adversarial-review gap P1: BC's
    * `CertificateFactory.generateCertificate` rejects a truncated
    * 4 KB prefix on certs / CRLs whose full DER form is larger.
    * PQC certs (ML-DSA-87, SLH-DSA all sizes), composite hybrids,
    * and certs with large extensions can run 5–35 KB.
    *
    * Maintainer-approved fix: when the prefix has the `0x30 0x82`
    * DER prologue, read up to 1 MB and feed the full bytes to the
    * BC parser. This is bounded (no unbounded reads — adversarial
    * inputs can't exhaust memory) and large enough to admit every
    * cryptographic certificate format in current and foreseen
    * use. The 4 KB prefix budget still applies to all OTHER
    * signatures.
    */
  val MAX_DER_PROBE_BYTES: Int = 1024 * 1024

  // Register the BC provider once. Idempotent — Security.addProvider
  // is a no-op if BC is already registered.
  private val _bcInit: Unit = {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider())
    }
  }

  // SSH wire-format first tokens (plain pubkey).
  private val sshPubkeyTokens: Set[String] = Set(
    "ssh-rsa",
    "ssh-dss",
    "ssh-ed25519",
    "ssh-ed448",
    "ecdsa-sha2-nistp256",
    "ecdsa-sha2-nistp384",
    "ecdsa-sha2-nistp521",
    "sk-ssh-ed25519@openssh.com",
    "sk-ecdsa-sha2-nistp256@openssh.com",
  )

  // SSH wire-format first tokens (CA-issued certificate).
  private val sshCertTokens: Set[String] = Set(
    "ssh-rsa-cert-v01@openssh.com",
    "ssh-dss-cert-v01@openssh.com",
    "ssh-ed25519-cert-v01@openssh.com",
    "ecdsa-sha2-nistp256-cert-v01@openssh.com",
    "ecdsa-sha2-nistp384-cert-v01@openssh.com",
    "ecdsa-sha2-nistp521-cert-v01@openssh.com",
  )

  /** Augments the current MIME type set for the given artifact. Returns
    * `currentMimes` unchanged if no crypto signature matches.
    *
    * @param artifact     the file being classified
    * @param currentMimes MIME types already assigned by Tika plus prior
    *                     augmenters
    * @return             `currentMimes` union any detected crypto MIME
    *                     types
    */
  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    currentMimes ++ detect(artifact)
  }

  /** Inspect the artifact and return every MIME type that matches a
    * signature.
    *
    * Reads at most [[MAX_READ_BYTES]] (4 KB) to cover all PEM /
    * SSH / PGP / magic-byte / first-byte detection. If the prefix
    * starts with the DER `0x30 0x82` prologue AND no extension hint
    * has already classified the file as PKCS#12, additionally reads
    * up to [[MAX_DER_PROBE_BYTES]] (1 MB) to feed the full DER body
    * to BC's cert / CRL parser (gap P1 remediation: PQC and large
    * composite certs are 5–35 KB and can't be parsed from a 4 KB
    * truncation).
    *
    * Single `withStream` invocation per call.
    */
  def detect(artifact: ArtifactWrapper): Set[String] = {
    val (prefix, fullDerOpt) = readPrefixAndMaybeFullDer(artifact)
    if (prefix.isEmpty) Set.empty
    else detectFromBytes(prefix, artifact.path(), fullDerOpt)
  }

  /** Variant that takes already-read bytes — used by tests so they can
    * verify the read budgets via instrumented streams. */
  def detectFromBytes(
      prefix: Array[Byte],
      filename: String,
      fullDerOpt: Option[Array[Byte]] = None
  ): Set[String] = {
    if (prefix.isEmpty) return Set.empty
    val builder = Set.newBuilder[String]
    // For PEM and SSH wire-format searches, ISO-8859-1 gives a 1:1
    // byte-to-char map so substring search is safe even on binary
    // input. UTF-8 would replace invalid sequences but the searched
    // strings are ASCII so either works.
    val text = new String(prefix, "ISO-8859-1")
    detectPemHeaders(text, builder)
    detectSshWireFormat(text, builder)
    detectMagicBytes(prefix, builder)
    detectPgpBinary(prefix, builder)
    detectDerPrefix(prefix, filename, fullDerOpt.getOrElse(prefix), builder)
    detectDerPkcs7(prefix, builder)
    builder.result()
  }

  /** Read the 4 KB prefix; if it has the DER `0x30 0x82` prologue,
    * also read up to [[MAX_DER_PROBE_BYTES]] for the cert / CRL
    * parser probe.
    *
    * Returns `(prefix, fullDerBytesIfReadElseNone)`. Performs at
    * most one `withStream` call and reads no more bytes than
    * `max(MAX_READ_BYTES, MAX_DER_PROBE_BYTES)` total.
    */
  private def readPrefixAndMaybeFullDer(
      artifact: ArtifactWrapper
  ): (Array[Byte], Option[Array[Byte]]) = {
    artifact.withStream { stream =>
      val prefix = stream.readNBytes(MAX_READ_BYTES)
      if (
        prefix.length >= 2 &&
        (prefix(0) & 0xff) == 0x30 &&
        (prefix(1) & 0xff) == 0x82
      ) {
        // Continue reading up to the DER probe budget
        val rest = stream.readNBytes(MAX_DER_PROBE_BYTES - prefix.length)
        if (rest.length == 0) (prefix, Some(prefix))
        else {
          val full = new Array[Byte](prefix.length + rest.length)
          System.arraycopy(prefix, 0, full, 0, prefix.length)
          System.arraycopy(rest, 0, full, prefix.length, rest.length)
          (prefix, Some(full))
        }
      } else {
        (prefix, None)
      }
    }
  }

  // --- detection sub-routines ---

  private def detectPemHeaders(
      text: String,
      builder: scala.collection.mutable.Builder[String, Set[String]]
  ): Unit = {
    val begin = "-----BEGIN CERTIFICATE-----"
    val certCount = countOccurrences(text, begin)
    if (certCount > 0) {
      builder += "application/x-pem-file" += "application/x-x509-ca-cert"
      if (certCount > 1) builder += "application/x-pem-bundle"
    }
    if (text.contains("-----BEGIN CERTIFICATE REQUEST-----")) {
      builder += "application/x-pem-file" += "application/pkcs10"
    }
    if (text.contains("-----BEGIN PUBLIC KEY-----")) {
      builder += "application/x-pem-file" += "application/x-pem-public-key"
    }
    if (
      text.contains("-----BEGIN RSA PRIVATE KEY-----") ||
      text.contains("-----BEGIN EC PRIVATE KEY-----") ||
      text.contains("-----BEGIN DSA PRIVATE KEY-----") ||
      text.contains("-----BEGIN PRIVATE KEY-----")
    ) {
      builder += "application/x-pem-file" += "application/x-pem-private-key"
    }
    if (text.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----")) {
      builder += "application/x-pem-file" += "application/x-pem-encrypted-private-key"
    }
    if (text.contains("-----BEGIN OPENSSH PRIVATE KEY-----")) {
      builder += "application/x-openssh-private-key"
    }
    if (text.contains("-----BEGIN X509 CRL-----")) {
      builder += "application/x-pem-file" += "application/pkix-crl"
    }
    if (text.contains("-----BEGIN PKCS7-----")) {
      builder += "application/pkcs7-mime"
    }
    // PGP armored
    if (
      text.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----") ||
      text.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----")
    ) {
      builder += "application/pgp-keys"
    }
    if (text.contains("-----BEGIN PGP SIGNATURE-----")) {
      builder += "application/pgp-signature"
    }
    if (text.contains("-----BEGIN PGP MESSAGE-----")) {
      builder += "application/pgp-message"
    }
  }

  private def detectSshWireFormat(
      text: String,
      builder: scala.collection.mutable.Builder[String, Set[String]]
  ): Unit = {
    // SSH pubkey / authorized_keys files contain one or more lines of
    // the form `<token> <base64-blob> <comment>`.
    //
    //   - P7: tolerate UTF-8 BOM and leading whitespace before the
    //     first token.
    //   - P8: scan EVERY line in the prefix, not just the first; an
    //     authorized_keys file with `ssh-rsa ...\nssh-ed25519 ...\n`
    //     should claim once we find ANY recognized line.
    val lines = text.split("\\r?\\n")
    var sawPubkey = false
    var sawCert = false
    var idx = 0
    while (idx < lines.length && !(sawPubkey && sawCert)) {
      val raw = lines(idx)
      // Strip BOM if present at line start. We decoded the prefix as
      // ISO-8859-1 (1:1 byte-to-char), so the UTF-8 BOM bytes
      // 0xEF 0xBB 0xBF appear as the three-char sequence
      // "\u00EF\u00BB\u00BF". The Unicode BOM \uFEFF would only
      // appear if we'd decoded as UTF-8.
      val stripped =
        if (raw.startsWith("\u00EF\u00BB\u00BF")) raw.substring(3)
        else if (raw.startsWith("\uFEFF")) raw.substring(1)
        else raw
      // Skip leading whitespace and comment lines.
      val trimmed = stripped.dropWhile(c => c == ' ' || c == '\t')
      if (trimmed.nonEmpty && trimmed.charAt(0) != '#') {
        val token = trimmed.takeWhile(c =>
          c != ' ' && c != '\t' && c != '\r' && c != '\n')
        if (sshPubkeyTokens.contains(token)) sawPubkey = true
        if (sshCertTokens.contains(token)) sawCert = true
      }
      idx += 1
    }
    if (sawPubkey) builder += "application/x-openssh-public-key"
    if (sawCert) builder += "application/x-openssh-certificate"
  }

  private def detectMagicBytes(
      prefix: Array[Byte],
      builder: scala.collection.mutable.Builder[String, Set[String]]
  ): Unit = {
    if (prefix.length >= 4) {
      val (b0, b1, b2, b3) =
        (prefix(0) & 0xff, prefix(1) & 0xff,
         prefix(2) & 0xff, prefix(3) & 0xff)
      if (b0 == 0xfe && b1 == 0xed && b2 == 0xfe && b3 == 0xed) {
        builder += "application/x-java-keystore"
      } else if (b0 == 0xce && b1 == 0xce && b2 == 0xce && b3 == 0xce) {
        builder += "application/x-java-jce-keystore"
      }
    }
  }

  private def detectPgpBinary(
      prefix: Array[Byte],
      builder: scala.collection.mutable.Builder[String, Set[String]]
  ): Unit = {
    // OpenPGP packet-tag bytes for public/private key packets. From
    // RFC 4880 §4.2: old-format public-key (tag 6) → 0x98, private-key
    // (tag 5) → 0x95; new-format public-key → 0xC6, private-key → 0xC5.
    if (prefix.length >= 1) {
      val b0 = prefix(0) & 0xff
      if (b0 == 0xC6 || b0 == 0x98 || b0 == 0xC5 || b0 == 0x95) {
        builder += "application/pgp-keys"
      }
    }
  }

  private def detectDerPrefix(
      prefix: Array[Byte],
      filename: String,
      fullDer: Array[Byte],
      builder: scala.collection.mutable.Builder[String, Set[String]]
  ): Unit = {
    if (prefix.length < 4) return
    val (b0, b1) = (prefix(0) & 0xff, prefix(1) & 0xff)
    if (b0 != 0x30 || b1 != 0x82) return
    val ext = filenameExtension(filename)
    val isP12Ext = ext == "p12" || ext == "pfx"

    // P2 remediation: ASN.1 PKCS#12 structure probe. PKCS#12 PFX is
    //   PFX ::= SEQUENCE { version INTEGER, authSafe ContentInfo, ... }
    // Detect by parsing the outer SEQUENCE's first element as INTEGER
    // version (= 3 for current PKCS#12) followed by a ContentInfo
    // SEQUENCE.
    val isP12Struct = looksLikePkcs12(fullDer)

    if (isP12Ext || isP12Struct) {
      builder += "application/pkcs12"
      // P2 plan policy: "If extension and structure both disagree,
      // let Bouncy Castle's parsers decide at strategy time —
      // detection can emit both MIMEs and let the strategy pick."
      // If extension disagrees with structure, also emit the X.509
      // / CRL MIME so the strategy gets a chance.
      if (!isP12Ext) {
        // Structure says PKCS#12 but extension doesn't; that's
        // unusual — emit pkcs12 only (the structure probe is
        // authoritative when it succeeds because the SEQUENCE
        // shape is specific to PKCS#12).
      } else if (!isP12Struct) {
        // Extension says PKCS#12 but structure doesn't match —
        // try X.509 / CRL too (plan: dual emission).
        if (tryParseX509(fullDer)) builder += "application/pkix-cert"
        else if (tryParseCRL(fullDer)) builder += "application/pkix-crl"
      }
    } else {
      // Not PKCS#12 — try X.509 cert via BC on the FULL DER bytes
      // (gap P1 remediation: PQC certs and large composite hybrids
      // exceed 4 KB).
      if (tryParseX509(fullDer)) {
        builder += "application/pkix-cert"
      } else if (tryParseCRL(fullDer)) {
        builder += "application/pkix-crl"
      }
      // else: 0x30 0x82 prefix but neither cert nor CRL nor PKCS#12 →
      // no claim (plan negative test).
    }
  }

  /** P3 remediation: detect DER-encoded PKCS#7 / CMS by scanning the
    * prefix for the signedData OID `1.2.840.113549.1.7.2`, which DER-
    * encodes as the 11-byte sequence `06 09 2A 86 48 86 F7 0D 01 07 02`
    * (tag=06, length=09, value=2A 86 48 86 F7 0D 01 07 02). PKCS#7
    * ContentInfo always has signedData OID early in the SEQUENCE.
    */
  private def detectDerPkcs7(
      prefix: Array[Byte],
      builder: scala.collection.mutable.Builder[String, Set[String]]
  ): Unit = {
    if (prefix.length < 11) return
    if ((prefix(0) & 0xff) != 0x30 || (prefix(1) & 0xff) != 0x82) return
    // signedData OID DER-encoded: 06 09 2A 86 48 86 F7 0D 01 07 02
    val needle: Array[Byte] = Array(
      0x06.toByte, 0x09.toByte,
      0x2A.toByte, 0x86.toByte, 0x48.toByte, 0x86.toByte,
      0xF7.toByte, 0x0D.toByte, 0x01.toByte, 0x07.toByte, 0x02.toByte,
    )
    if (containsBytes(prefix, needle, fromIndex = 2, untilIndex = math.min(prefix.length, 64))) {
      builder += "application/pkcs7-mime"
    }
  }

  /** P2 remediation: lightweight ASN.1 probe — does this look like a
    * PKCS#12 PFX (outer SEQUENCE wrapping `INTEGER version` then
    * `ContentInfo` SEQUENCE)?
    *
    * Returns true if the structure matches; false otherwise (or on
    * any DER parse glitch). False positives are acceptable per the
    * plan's dual-emission policy.
    */
  private def looksLikePkcs12(bytes: Array[Byte]): Boolean = {
    Try {
      // Outer SEQUENCE
      if ((bytes(0) & 0xff) != 0x30) return false
      val (_, contentStart) = readDerLength(bytes, 1)
      // First inner element should be INTEGER (tag 0x02), version
      val verTag = bytes(contentStart) & 0xff
      if (verTag != 0x02) return false
      val (verLen, verContentStart) = readDerLength(bytes, contentStart + 1)
      // PKCS#12 version is 3 (single-byte integer). Accept any short
      // length to cover future bumps.
      if (verLen < 1 || verLen > 4) return false
      val nextTagOff = verContentStart + verLen
      // Second inner element should be SEQUENCE (ContentInfo), tag 0x30
      val niTag = bytes(nextTagOff) & 0xff
      niTag == 0x30
    }.getOrElse(false)
  }

  /** Decode a DER length field starting at `off`. Returns
    * `(length, content-start-offset)`. Throws on truncation. */
  private def readDerLength(bytes: Array[Byte], off: Int): (Int, Int) = {
    val first = bytes(off) & 0xff
    if (first < 0x80) (first, off + 1)
    else {
      val n = first & 0x7F
      if (n == 0 || off + 1 + n > bytes.length)
        throw new RuntimeException("bad DER length")
      var len = 0
      var i = 0
      while (i < n) {
        len = (len << 8) | (bytes(off + 1 + i) & 0xff)
        i += 1
      }
      (len, off + 1 + n)
    }
  }

  /** Boyer-Moore-light substring search over a byte range. */
  private def containsBytes(
      haystack: Array[Byte],
      needle: Array[Byte],
      fromIndex: Int,
      untilIndex: Int,
  ): Boolean = {
    val limit = math.min(untilIndex, haystack.length) - needle.length + 1
    var i = fromIndex
    while (i < limit) {
      var j = 0
      while (j < needle.length && haystack(i + j) == needle(j)) j += 1
      if (j == needle.length) return true
      i += 1
    }
    false
  }

  private def tryParseX509(bytes: Array[Byte]): Boolean = {
    Try {
      val cf = CertificateFactory.getInstance("X.509", "BC")
      cf.generateCertificate(new ByteArrayInputStream(bytes))
    }.isSuccess
  }

  private def tryParseCRL(bytes: Array[Byte]): Boolean = {
    Try {
      val cf = CertificateFactory.getInstance("X.509", "BC")
      cf.generateCRL(new ByteArrayInputStream(bytes))
    }.isSuccess
  }

  private def filenameExtension(path: String): String = {
    val name = path.substring(path.lastIndexOf('/') + 1)
    val dot = name.lastIndexOf('.')
    if (dot < 0) "" else name.substring(dot + 1).toLowerCase
  }

  private def countOccurrences(s: String, substr: String): Int = {
    if (substr.isEmpty) return 0
    var n = 0
    var i = 0
    while (i >= 0) {
      i = s.indexOf(substr, i)
      if (i >= 0) {
        n += 1
        i += substr.length
      }
    }
    n
  }
}
