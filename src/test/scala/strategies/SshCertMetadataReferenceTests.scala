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

/** Reference-fixture full-metadata assertions for OpenSSH certificates.
  *
  * ## What these tests test
  *
  * Phase-5 gap analysis G6: the materialized sidecars assert ≤2 of the
  * ~12 cert metadata fields the strategy emits. A regression in the
  * un-asserted fields (`SshCertCaFingerprint`, `SshCertSerial`, etc.)
  * would not fail any per-fixture test. These tests assert the *full*
  * cert-metadata table for two representative fixtures —
  * `user-cert-ed25519.pub` and `host-cert-rsa-signed-by-ed25519.pub` —
  * cross-checked against `ssh-keygen -L` output captured into the
  * test docstring at write time.
  *
  * ## Why this is independent ground truth
  *
  * The materializer fills sidecar values from the strategy itself
  * (tautological by construction). These tests bypass that loop: the
  * expected values were lifted from `ssh-keygen -L` output (the
  * reference OpenSSH tool) at fixture-creation time. A bug that flipped
  * `SshCertCaFingerprint` from the CA-key wire to the signed-key wire
  * (a real regression risk) would fail here.
  *
  * If the fixture bytes ever change, the expected values must be
  * regenerated from `ssh-keygen -L -f <fixture>` and committed
  * alongside the fixture.
  */
class SshCertMetadataReferenceTests extends FunSuite {

  private def wrap(path: String): FileWrapper =
    FileWrapper(new File(path), path, None)

  private def md(
      content: Certificates.SshCert,
      artifact: FileWrapper,
  ): Map[String, String] = {
    val state = new CertificatesState(artifact)
    val tm = state.invokeSshCertMetadata(artifact, content)
    tm.iterator.flatMap { case (k, vs) =>
      vs.headOption.map(v => k -> v.value)
    }.toMap
  }

  test("user-cert-ed25519: full metadata table matches ssh-keygen -L (G6)") {
    val w = wrap("test_data/certificates/ssh/synthetic/user-cert-ed25519.pub")
    val cert = Certificates.parseSshCert(w).get
    val m = md(cert, w)

    // Cross-checked against ssh-keygen -L output 2026-04-30:
    //   Type: ssh-ed25519-cert-v01@openssh.com user certificate
    //   Public key: ED25519-CERT SHA256:kVSaQnN01FoMK0pdLrpUff7WML+tVX0Rk8TaQacCq9U
    //   Signing CA: ED25519 SHA256:X+vjHTahwmg5oSKI82OVww82afSzeH+9j6F/XWW1jlQ (ssh-ed25519)
    //   Key ID: "goatrodeo-test-user"
    //   Serial: 0
    //   Valid: 2026-04-24T20:17:00 to 2027-04-23T20:18:21
    //   Principals: alice, bob
    //   Critical Options: (none)
    //   Extensions: permit-{X11-forwarding, agent-forwarding,
    //               port-forwarding, pty, user-rc}
    assertEquals(m.get("Certificates:KeyAlgorithm"), Some("ed25519"))
    assertEquals(
      m.get("Certificates:SshFingerprintSha256"),
      Some("SHA-256:kVSaQnN01FoMK0pdLrpUff7WML+tVX0Rk8TaQacCq9U"),
    )
    assertEquals(
      m.get("Certificates:SshCertCaFingerprint"),
      Some("SHA-256:X+vjHTahwmg5oSKI82OVww82afSzeH+9j6F/XWW1jlQ"),
    )
    assertEquals(m.get("Certificates:SshCertType"), Some("user"))
    assertEquals(m.get("Certificates:SshCertSerial"), Some("0"))
    assertEquals(m.get("Certificates:SshCertKeyId"), Some("goatrodeo-test-user"))
    assertEquals(m.get("Certificates:SshCertSigAlgorithm"), Some("ssh-ed25519"))
    assertEquals(m.get("Certificates:SshCertPrincipals"), Some("alice,bob"))
    assertEquals(
      m.get("Certificates:SshCertExtensions"),
      Some(
        "permit-X11-forwarding,permit-agent-forwarding," +
          "permit-port-forwarding,permit-pty,permit-user-rc"
      ),
    )
    // Critical Options: (none) → key not emitted
    assertEquals(m.get("Certificates:SshCertCriticalOptions"), None)
    // Validity timestamps — ISO-8601 UTC. ssh-keygen prints local time;
    // the cert wire stores UTC seconds. 2026-04-24T20:17:00 in the local
    // timezone where the fixture was generated converted to the wire's
    // epoch seconds gives the strategy's UTC rendering. Just assert
    // non-empty + ISO-8601 shape (no sentinel triggered for this cert).
    val va = m("Certificates:SshCertValidAfter")
    val vb = m("Certificates:SshCertValidBefore")
    assert(va.endsWith("Z") && va.contains("2026"),
           s"unexpected SshCertValidAfter: $va")
    assert(vb.endsWith("Z") && vb.contains("2027"),
           s"unexpected SshCertValidBefore: $vb")
    // Cert SHA-256 is the cert wire blob hash, not the signed-key fp
    val certHex = m("Certificates:SshCertSha256")
    assertEquals(certHex.length, 64)
    assert(certHex.matches("[0-9a-f]+"),
           s"SshCertSha256 must be lowercase hex: $certHex")
  }

  test("host-cert-rsa-signed-by-ed25519: cross-algorithm fields (G6)") {
    val w = wrap("test_data/certificates/ssh/synthetic/host-cert-rsa-signed-by-ed25519.pub")
    val cert = Certificates.parseSshCert(w).get
    val m = md(cert, w)

    // Cross-checked against ssh-keygen -L output 2026-04-30:
    //   Type: ssh-rsa-cert-v01@openssh.com host certificate
    //   Public key: RSA-CERT SHA256:5Sgk1psapPr7plh7GPsdPJPf6sF95ZmAzMOAMU/xloo
    //   Signing CA: ED25519 SHA256:X+vjHTahwmg5oSKI82OVww82afSzeH+9j6F/XWW1jlQ
    //   Key ID: "goatrodeo-test-host"
    //   Principals: host1.example, host2.example
    //   Extensions: (none)
    //   Critical Options: (none)
    assertEquals(m.get("Certificates:KeyAlgorithm"), Some("rsa"))
    assertEquals(m.get("Certificates:KeySize"), Some("2048"))
    assertEquals(
      m.get("Certificates:SshFingerprintSha256"),
      Some("SHA-256:5Sgk1psapPr7plh7GPsdPJPf6sF95ZmAzMOAMU/xloo"),
    )
    assertEquals(
      m.get("Certificates:SshCertCaFingerprint"),
      Some("SHA-256:X+vjHTahwmg5oSKI82OVww82afSzeH+9j6F/XWW1jlQ"),
    )
    assertEquals(m.get("Certificates:SshCertType"), Some("host"))
    assertEquals(m.get("Certificates:SshCertSerial"), Some("0"))
    assertEquals(m.get("Certificates:SshCertKeyId"), Some("goatrodeo-test-host"))
    assertEquals(m.get("Certificates:SshCertSigAlgorithm"), Some("ssh-ed25519"))
    assertEquals(
      m.get("Certificates:SshCertPrincipals"),
      Some("host1.example,host2.example"),
    )
    assertEquals(m.get("Certificates:SshCertCriticalOptions"), None)
    assertEquals(m.get("Certificates:SshCertExtensions"), None)
  }
}
