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

/** Strategy-level SSH parser tests that cross-check fingerprints
  * computed by `Certificates.sshFingerprintB64` against the canonical
  * `ssh-keygen -lf` output.
  *
  * ## What these tests test
  *
  * - `parseSshPubkey` succeeds on each canonical fixture
  * - `sshFingerprintB64` produces the same SHA-256 fingerprint that
  *   `ssh-keygen -lf` produces (the "ground truth" anchor)
  * - `parseSshCert` parses ED25519 and RSA cert wire formats and
  *   correctly identifies cert-type, principals, and extensions
  *
  * ## Why these tests matter
  *
  * The Phase-5 acceptance criterion is "All SSH fixtures pass sidecar
  * assertions". Sidecars were materialized from the strategy's own
  * output (tautological by design — same as Phase 4). These tests
  * provide an independent ground-truth anchor by comparing against
  * `ssh-keygen` (Phase-0 corpus generator) output that's been hand-
  * verified.
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
    val w = wrap("test_data/certificates/ssh/synthetic/ecdsa-nistp256-openssh.pub")
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
    val w = wrap("test_data/certificates/ssh/synthetic/host-cert-rsa-signed-by-ed25519.pub")
    val c = Certificates.parseSshCert(w)
    assert(c.isDefined)
    val cert = c.get
    assertEquals(cert.signedKeyAlgName, "ssh-rsa")
    assertEquals(cert.certType, 2L) // host
    assertEquals(cert.caSigAlgName, "ssh-ed25519")
    assert(cert.rsaModulusBits.isDefined)
  }

  test("signedKeyAlgFromCertName strips suffix") {
    assertEquals(
      Certificates.signedKeyAlgFromCertName("ssh-ed25519-cert-v01@openssh.com"),
      Some("ssh-ed25519"),
    )
    assertEquals(
      Certificates.signedKeyAlgFromCertName("ssh-rsa-cert-v01@openssh.com"),
      Some("ssh-rsa"),
    )
    assertEquals(
      Certificates.signedKeyAlgFromCertName("ssh-rsa"),
      None,
    )
  }
}
