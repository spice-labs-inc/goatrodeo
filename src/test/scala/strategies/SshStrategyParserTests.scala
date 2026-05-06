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

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File

/** Strategy-level SSH parser tests that cross-check fingerprints computed by
  * `Certificates.sshFingerprintB64` against the canonical `ssh-keygen -lf`
  * output.
  *
  * ## What these tests test
  *
  *   - `parseSshPubkey` succeeds on each canonical fixture
  *   - `sshFingerprintB64` produces the same SHA-256 fingerprint that
  *     `ssh-keygen -lf` produces (the "ground truth" anchor)
  *   - `parseSshCert` parses ED25519 and RSA cert wire formats and correctly
  *     identifies cert-type, principals, and extensions
  *
  * ## Why these tests matter
  *
  * The Phase-5 acceptance criterion is "All SSH fixtures pass sidecar
  * assertions". Sidecars were materialized from the strategy's own output
  * (tautological by design — same as Phase 4). These tests provide an
  * independent ground-truth anchor by comparing against `ssh-keygen` (Phase-0
  * corpus generator) output that's been hand- verified.
  */
class SshStrategyParserTests extends FunSuite {

  private def wrap(path: String): FileWrapper =
    FileWrapper(new File(path), path, None)

  test("parseSshPubkey: ed25519 fingerprint matches ssh-keygen output") {
    val w = wrap("test_data/certificates/ssh/synthetic/ed25519-openssh.pub")
    val pk = Certificates.parseSshPubkey(w)
    assert(pk.isDefined)
    val fp = Certificates.sshFingerprintB64(pk.get.wireBytes)
    assertEquals(fp, "Db31CxoP8DzjW/D7VJgyGO2ASZA/cxUQJBf7odnoEt0")
    assertEquals(pk.get.algName, "ssh-ed25519")
    assertEquals(pk.get.comment, Some("goatrodeo-ed25519@test"))
    assertEquals(pk.get.rsaModulusBits, None)
  }

  test("parseSshPubkey: rsa-4096 fingerprint and bit-length") {
    val w = wrap("test_data/certificates/ssh/synthetic/rsa-4096-openssh.pub")
    val pk = Certificates.parseSshPubkey(w)
    assert(pk.isDefined)
    val fp = Certificates.sshFingerprintB64(pk.get.wireBytes)
    assertEquals(fp, "9VAjeg9jcVjGFn2jX77k4h6DzFJf5UXz351tT1njVqo")
    assertEquals(pk.get.algName, "ssh-rsa")
    assertEquals(pk.get.rsaModulusBits, Some(4096))
  }

  test("parseSshPubkey: ecdsa-nistp256 detected with correct alg name") {
    val w =
      wrap("test_data/certificates/ssh/synthetic/ecdsa-nistp256-openssh.pub")
    val pk = Certificates.parseSshPubkey(w)
    assert(pk.isDefined)
    assertEquals(pk.get.algName, "ecdsa-sha2-nistp256")
    assertEquals(pk.get.rsaModulusBits, None)
  }

  test("parseSshCert: ed25519 user cert with principals and extensions") {
    val w = wrap("test_data/certificates/ssh/synthetic/user-cert-ed25519.pub")
    val c = Certificates.parseSshCert(w)
    assert(c.isDefined)
    val cert = c.get
    assertEquals(cert.signedKeyAlgName, "ssh-ed25519")
    assertEquals(cert.certType, 1L) // user
    assertEquals(cert.principals, Vector("alice", "bob"))
    assert(cert.extensions.contains("permit-pty"))
    assertEquals(cert.caSigAlgName, "ssh-ed25519")
  }

  test("parseSshCert: host RSA cert signed by Ed25519 CA") {
    val w = wrap(
      "test_data/certificates/ssh/synthetic/host-cert-rsa-signed-by-ed25519.pub"
    )
    val c = Certificates.parseSshCert(w)
    assert(c.isDefined)
    val cert = c.get
    assertEquals(cert.signedKeyAlgName, "ssh-rsa")
    assertEquals(cert.certType, 2L) // host
    assertEquals(cert.caSigAlgName, "ssh-ed25519")
    assert(cert.rsaModulusBits.isDefined)
  }

  // G1 — Plan §SshCertValidBefore / §SshCertValidAfter: OpenSSH uses
  // 0xFFFFFFFFFFFFFFFFL to mean "never expires" and 0L to mean "valid
  // always from the past". Both must render as their literal sentinel,
  // not as wrapped epoch dates (1970-01-01 / 1969-12-31).
  test("sshCertTimeLabel: 0xFFFFFFFFFFFFFFFF emits 'forever' (G1)") {
    assertEquals(
      Certificates.sshCertTimeLabel(-1L, "forever"),
      "forever"
    )
  }

  test("sshCertTimeLabel: 0L emits 'always' (G1)") {
    assertEquals(
      Certificates.sshCertTimeLabel(0L, "always"),
      "always"
    )
  }

  test("sshCertTimeLabel: ordinary epoch second renders ISO-8601 UTC") {
    // 1777680000 = 2026-05-02T00:00:00Z; sentinel parameter is ignored
    // for non-sentinel values
    val s = Certificates.sshCertTimeLabel(1777680000L, "forever")
    assertEquals(s, "2026-05-02T00:00:00Z")
  }

  // G11 — Plan §"Files that fail to parse return None": negative test
  // exercising the parser's None-on-bad-input contract. Required because
  // all 34 corpus fixtures parse successfully — an inadvertently-permissive
  // parser would pass every per-fixture test.
  test("parseSshPubkey: garbage input returns None (G11)") {
    val tmp = java.io.File.createTempFile("garbage", ".pub")
    tmp.deleteOnExit()
    java.nio.file.Files.write(
      tmp.toPath,
      "not-an-algo AAAA comment\n".getBytes("UTF-8")
    )
    val w = FileWrapper(tmp, tmp.getName, None)
    assertEquals(Certificates.parseSshPubkey(w), None)
  }

  test("parseSshPubkey: empty file returns None (G11)") {
    val tmp = java.io.File.createTempFile("empty", ".pub")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, "".getBytes("UTF-8"))
    val w = FileWrapper(tmp, tmp.getName, None)
    assertEquals(Certificates.parseSshPubkey(w), None)
  }

  test("parseSshPubkey: alg/wire mismatch returns None (G11)") {
    // Wire blob says "ssh-ed25519" but file's first token claims "ssh-rsa"
    // → `innerAlg == alg` sanity check rejects it.
    val ed25519WireB64 =
      "AAAAC3NzaC1lZDI1NTE5AAAAIC7ScYYTQq7gc3vqK4JyYx+7tHymW8rlqydjgU3etW+o"
    val tmp = java.io.File.createTempFile("mismatch", ".pub")
    tmp.deleteOnExit()
    java.nio.file.Files.write(
      tmp.toPath,
      s"ssh-rsa $ed25519WireB64 fake-comment\n".getBytes("UTF-8")
    )
    val w = FileWrapper(tmp, tmp.getName, None)
    assertEquals(Certificates.parseSshPubkey(w), None)
  }

  // G7 — Plan §"single-line OpenSSH wire format". `authorized_keys`
  // lines that have options before the algorithm token are explicitly
  // out-of-scope; they silently return None. This test pins that
  // contract so future refactors don't accidentally start claiming
  // option-prefixed lines (which could produce wrong fingerprints).
  test("parseSshPubkey: authorized_keys option-prefix line returns None (G7)") {
    val tmp = java.io.File.createTempFile("auth-keys", ".pub")
    tmp.deleteOnExit()
    val ed25519B64 =
      "AAAAC3NzaC1lZDI1NTE5AAAAIC7ScYYTQq7gc3vqK4JyYx+7tHymW8rlqydjgU3etW+o"
    java.nio.file.Files.write(
      tmp.toPath,
      s"from=\"1.2.3.4\",no-pty ssh-ed25519 $ed25519B64 user@host\n".getBytes(
        "UTF-8"
      )
    )
    val w = FileWrapper(tmp, tmp.getName, None)
    assertEquals(Certificates.parseSshPubkey(w), None)
  }

  test("parseSshCert: garbage input returns None (G11)") {
    val tmp = java.io.File.createTempFile("garbage", ".pub")
    tmp.deleteOnExit()
    java.nio.file.Files.write(
      tmp.toPath,
      "not-an-algo-cert-v01@openssh.com AAAA\n".getBytes("UTF-8")
    )
    val w = FileWrapper(tmp, tmp.getName, None)
    assertEquals(Certificates.parseSshCert(w), None)
  }

  test("signedKeyAlgFromCertName strips suffix") {
    assertEquals(
      Certificates.signedKeyAlgFromCertName("ssh-ed25519-cert-v01@openssh.com"),
      Some("ssh-ed25519")
    )
    assertEquals(
      Certificates.signedKeyAlgFromCertName("ssh-rsa-cert-v01@openssh.com"),
      Some("ssh-rsa")
    )
    assertEquals(
      Certificates.signedKeyAlgFromCertName("ssh-rsa"),
      None
    )
  }
}
