/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import munit.FunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Phase 0.2 — Certificates.parseSshCertBlob returns None instead of throwing
  * for cert-type mismatch and unsupported key algorithms.
  *
  * REQUIREMENT: No exceptions for flow control. Previously, cert-type mismatch
  * triggered `require` (throws IllegalArgumentException) and unsupported key
  * algorithms triggered `throw new IllegalArgumentException`. Both are now
  * `None` returns.
  *
  * ==LLM-Readable Section==
  *
  * This suite tests that parseSshCertBlob handles expected failures gracefully
  * by returning None instead of throwing:
  *   - Cert-type mismatch: inner wire type != declared certTypeName → None
  *   - Unsupported key algorithm: signedAlg not in known set → None
  *
  * SSH wire format helper: strings are encoded as 4-byte big-endian length
  * followed by UTF-8 bytes.
  */
class CertificatesSshCertBlobSuite extends FunSuite {

  private def sshString(s: String): Array[Byte] = {
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val len = bytes.length
    val header = Array(
      ((len >> 24) & 0xff).toByte,
      ((len >> 16) & 0xff).toByte,
      ((len >> 8) & 0xff).toByte,
      (len & 0xff).toByte
    )
    header ++ bytes
  }

  private def buildWire(strings: String*): Array[Byte] = {
    val buf = new ByteArrayOutputStream()
    strings.foreach { s => buf.write(sshString(s)) }
    buf.toByteArray
  }

  /** Test: unsupported SSH cert key algorithm returns None.
    *
    * WHAT: parseSshCertBlob with an unknown signedAlg (e.g. "ssh-unknown")
    * returns None. WHAT NOT: Does not throw IllegalArgumentException.
    *
    * WHY: Previously, the `case other =>` branch threw
    * IllegalArgumentException. This is exceptions-as-flow-control for an
    * expected failure case (encountering an algorithm the strategy doesn't
    * handle). The correct response is None.
    *
    * REQUIREMENT: Unknown algorithm returns None.
    */
  test(
    "Certificates - unsupported SSH cert key alg returns None"
  ) {
    val certTypeName = "ssh-unknown-cert-v01@openssh.com"
    val signedAlg = "ssh-unknown"
    val wire = buildWire(certTypeName)
    val result = Certificates.parseSshCertBlob(
      certTypeName,
      signedAlg,
      wire,
      None
    )
    assert(
      result.isEmpty,
      "Unsupported key algorithm must return None, not throw"
    )
  }

  /** Test: SSH cert type mismatch returns None.
    *
    * WHAT: parseSshCertBlob where the inner wire type string differs from the
    * certTypeName parameter returns None. WHAT NOT: Does not throw via
    * require().
    *
    * WHY: Previously, `require(innerType == certTypeName, ...)` threw
    * IllegalArgumentException on mismatch. This is exceptions-as-flow-control
    * for an expected failure case (corrupt or mismatched cert data). The
    * correct response is None.
    *
    * REQUIREMENT: Type mismatch returns None.
    */
  test(
    "Certificates - SSH cert type mismatch returns None"
  ) {
    val certTypeName = "ssh-rsa-cert-v01@openssh.com"
    val signedAlg = "ssh-rsa"
    val wire = buildWire("ssh-dsa-cert-v01@openssh.com")
    val result = Certificates.parseSshCertBlob(
      certTypeName,
      signedAlg,
      wire,
      None
    )
    assert(
      result.isEmpty,
      "Cert-type mismatch must return None, not throw"
    )
  }

  /** Test: parseSshCertBlob does not throw for any input combination.
    *
    * WHAT: Calling parseSshCertBlob with various malformed inputs never throws,
    * always returns Option.
    *
    * WHY: The central invariant of Phase 0 — no exceptions for flow control.
    * This is a meta-test that ensures the method is provably non-throwing for
    * the cases we control (cert-type mismatch, unsupported alg).
    *
    * REQUIREMENT: No exceptions for flow control.
    */
  /** Test: short wire data returns None without throwing.
    *
    * WHAT: parseSshCertBlob with truncated wire data (fewer bytes than needed)
    * returns None. WHAT NOT: Does not throw on short reads.
    *
    * WHY: SSH wire data from untrusted sources may be truncated; the reader
    * methods return None on short reads, and parseSshCertBlob must propagate
    * that as None.
    *
    * REQUIREMENT: Short wire data returns None.
    */
  test(
    "Certificates - SSH cert parsing handles short wire data gracefully"
  ) {
    val certTypeName = "ssh-rsa-cert-v01@openssh.com"
    val signedAlg = "ssh-rsa"
    // Wire is too short to contain a valid SSH cert structure.
    val shortWire =
      Array(0x00.toByte, 0x00.toByte, 0x00.toByte, 0x05.toByte) ++ "ssh-r"
        .getBytes(StandardCharsets.UTF_8)
    val result = Certificates.parseSshCertBlob(
      certTypeName,
      signedAlg,
      shortWire,
      None
    )
    assert(
      result.isEmpty,
      "Short wire data must return None, not throw"
    )
  }

  test(
    "Certificates - parseSshCertBlob never throws for controlled failures"
  ) {
    val cases = Seq(
      (
        "ssh-rsa-cert-v01@openssh.com",
        "ssh-rsa",
        buildWire("ssh-dsa-cert-v01@openssh.com")
      ),
      (
        "ssh-unknown-cert-v01@openssh.com",
        "ssh-unknown",
        buildWire("ssh-unknown-cert-v01@openssh.com")
      ),
      ("ssh-rsa-cert-v01@openssh.com", "ssh-rsa", Array.emptyByteArray)
    )
    cases.foreach { case (certType, signedAlg, wire) =>
      val result = scala.util.Try(
        Certificates.parseSshCertBlob(certType, signedAlg, wire, None)
      )
      assert(
        result.isSuccess,
        s"parseSshCertBlob must not throw for certType=$certType " +
          s"signedAlg=$signedAlg; got ${result}"
      )
    }
  }
}
