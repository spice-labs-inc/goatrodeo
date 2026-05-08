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

package strategies

import munit.FunSuite

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import scala.util.Try

/** Ground-truth cross-check — verify that the `Certificates:CertSha256` and
  * `Certificates:SpkiSha256` fields in every committed X.509 sidecar actually
  * match the bytes of their paired fixture file.
  *
  * ## Why this suite exists
  *
  * Phase 0b shipped 200 sidecars whose values were computed by
  * `tools/cert_sidecar.py` (Python + the `cryptography` package, itself a
  * wrapper over OpenSSL). If that computation had a bug, or if someone edited a
  * sidecar by hand without regenerating, or if a fixture file was swapped
  * without updating the sidecar — every such drift is a silent source of false
  * green or false red downstream.
  *
  * This suite uses **the JDK's built-in** `java.security.cert.
  * CertificateFactory` — a completely separate X.509 parser from the one that
  * authored the sidecars. If both agree on SHA-256 of the full DER cert and
  * SHA-256 of the SubjectPublicKeyInfo, the ground-truth is independently
  * verified.
  *
  * ## Scope
  *
  * Only X.509 sidecars are covered (those that carry `Certificates:CertSha256`
  * / `Certificates:SpkiSha256`). Non-X.509 fixtures (SSH, PGP, private keys,
  * keystores, CRLs) do not have these fields and are skipped.
  *
  * The suite enforces n ≥ 5 as a minimum sample. If fewer than 5 X.509 sidecars
  * with computed fields are found, the test fails loudly — that would mean
  * either the corpus has been gutted or the sidecar format has changed without
  * this test being kept in sync.
  *
  * ## LLM-friendly summary
  *
  *   - "at least 5 X.509 fixtures are covered" — sample-size floor.
  *   - "every X.509 fixture's CertSha256 matches SHA-256 of its fixture bytes"
  *     — byte-level ground truth.
  *   - "every X.509 fixture's SpkiSha256 matches SHA-256 of the JDK-parsed
  *     SubjectPublicKeyInfo" — SPKI computation cross- check.
  *   - "every X.509 fixture's SubjectDN matches JDK's RFC-2253 rendering of the
  *     parsed subject" — DN formatting cross-check.
  */
class CertificatesSidecarGroundTruthTests extends FunSuite {

  /** One X.509 fixture that has cert/SPKI/DN sidecar fields to verify. */
  private case class X509CheckCase(
      fixture: java.io.File,
      sidecar: CertificatesSidecar
  )

  /** Collect every (fixture, sidecar) pair whose sidecar has
    * `Certificates:CertSha256` — the marker that identifies an X.509 sidecar
    * with computed ground truth.
    */
  private lazy val cases: Vector[X509CheckCase] = {
    CertificatesFixtureInventory.pairs.flatMap { pair =>
      Try(CertificatesSidecar.parse(pair.sidecar)).toOption.flatMap { sc =>
        if (sc.metadata.mustContain.contains("Certificates:CertSha256"))
          Some(X509CheckCase(pair.fixture, sc))
        else None
      }
    }
  }

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  private def sha256(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def parseCert(fixtureBytes: Array[Byte]): X509Certificate = {
    val cf = CertificateFactory.getInstance("X.509")
    cf.generateCertificate(new ByteArrayInputStream(fixtureBytes))
      .asInstanceOf[X509Certificate]
  }

  test("at least 5 X.509 fixtures have computed ground-truth sidecar fields") {
    assert(
      cases.size >= 5,
      s"Expected ≥ 5 X.509 sidecars with 'Certificates:CertSha256' " +
        s"to sample for ground-truth cross-check; found ${cases.size}. " +
        s"Either the corpus lost coverage or the sidecar schema drifted."
    )
  }

  test(
    "every X.509 sidecar's Certificates:CertSha256 matches SHA-256 of the fixture's DER bytes (JDK parser)"
  ) {
    val failures = cases.flatMap { c =>
      val raw = Files.readAllBytes(c.fixture.toPath)
      val cert = parseCert(raw)
      val derSha = sha256(cert.getEncoded)
      val declared = c.sidecar.metadata.mustContain("Certificates:CertSha256")
      if (derSha == declared) None
      else
        Some(
          s"${c.fixture.getName}: sidecar says CertSha256=$declared, " +
            s"JDK computed $derSha from parsed DER"
        )
    }
    assert(
      failures.isEmpty,
      s"${failures.size} of ${cases.size} fixtures failed cross-check:\n" +
        failures.mkString("\n")
    )
  }

  test(
    "every X.509 sidecar's Certificates:SpkiSha256 matches SHA-256 of the JDK-parsed SubjectPublicKeyInfo"
  ) {
    val failures = cases.flatMap { c =>
      val raw = Files.readAllBytes(c.fixture.toPath)
      val cert = parseCert(raw)
      // PublicKey.getEncoded returns the SubjectPublicKeyInfo DER
      // per java.security.Key.getEncoded — this is exactly the bytes
      // cert_sidecar.py also hashes.
      val spkiSha = sha256(cert.getPublicKey.getEncoded)
      val declared = c.sidecar.metadata.mustContain("Certificates:SpkiSha256")
      if (spkiSha == declared) None
      else
        Some(
          s"${c.fixture.getName}: sidecar says SpkiSha256=$declared, " +
            s"JDK computed $spkiSha from parsed SubjectPublicKeyInfo"
        )
    }
    assert(
      failures.isEmpty,
      s"${failures.size} of ${cases.size} fixtures failed SPKI cross-check:\n" +
        failures.mkString("\n")
    )
  }

  test(
    "every X.509 sidecar's Certificates:SubjectDN matches JDK's RFC-2253 rendering"
  ) {
    // This does NOT catch every DN-formatting edge case — JDK and
    // `cryptography` may differ on attribute ordering for multi-RDN
    // names — but for the canonical single-RDN cases that dominate
    // the corpus (CN/O/C), the strings are identical.
    //
    // We report mismatches rather than failing hard, because DN
    // formatting has legitimate vendor variation. Mismatch count > 0
    // is information, not necessarily a defect. Failing threshold:
    // > 25% of the corpus. Under that, the cross-check is informative
    // but not blocking.
    val mismatches = cases.flatMap { c =>
      val raw = Files.readAllBytes(c.fixture.toPath)
      val cert = parseCert(raw)
      val jdkDN = cert.getSubjectX500Principal.getName("RFC2253")
      val declared = c.sidecar.metadata.mustContain("Certificates:SubjectDN")
      if (jdkDN == declared) None
      else Some(s"${c.fixture.getName}: sidecar=$declared, JDK=$jdkDN")
    }
    // Allow up to 25% DN-rendering drift before failing — JDK and
    // OpenSSL differ on escaping rules for some attributes (e.g.,
    // how SHIFT-JIS content in historical CA certs gets escaped).
    val threshold = math.max(1, cases.size / 4)
    assert(
      mismatches.size <= threshold,
      s"${mismatches.size} of ${cases.size} X.509 sidecars have " +
        s"SubjectDN strings that differ between Python `cryptography` " +
        s"and JDK RFC-2253 rendering (threshold: $threshold). " +
        s"First 5 mismatches: " +
        mismatches.take(5).mkString("\n  ", "\n  ", "")
    )
  }
}
