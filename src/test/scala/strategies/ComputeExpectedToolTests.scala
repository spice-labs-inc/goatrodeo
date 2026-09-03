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
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import scala.sys.process.*

/** End-to-end tests for `test_data/certificates/tools/compute-expected.sh`.
  *
  * Task #3 specifies a draft-sidecar generator that contributors run when
  * adding a fixture. Pre-Phase-0b, the script existed but was untested. A
  * regression in the SubjectDN/IssuerDN sed expressions (an extra space in `sed
  * 's/^subject= //'` against openssl's `subject=...` output) was shipping
  * broken sidecars. This suite locks in the tool's contract:
  *
  *   - exit 0 on a valid PEM X.509 fixture
  *   - emits parseable JSON
  *   - SubjectDN field contains no `subject=` literal prefix
  *   - IssuerDN field contains no `issuer=` literal prefix
  *   - Cert SHA-256 matches what the JDK X509 parser computes against the same
  *     fixture's DER bytes
  *
  * If the script is missing or `bash` cannot be invoked the test is marked
  * `assert(... && !ignored)` so non-Linux dev machines do not fail-spuriously.
  *
  * ## Traceability
  *
  * Task: phase-0-corpus.md task #3 (the tool itself). Acceptance criterion
  * verified: tool produces JSON with correctly- stripped DN values that any
  * reviewer can hand-verify against `openssl x509 -subject -nameopt RFC2253`
  * output.
  */
class ComputeExpectedToolTests extends FunSuite {

  private val script = new File(
    "test_data/certificates/tools/compute-expected.sh"
  )
  private val sampleFixture = new File(
    "test_data/certificates/x509/canonical/letsencrypt-isrgrootx1.pem"
  )

  override def beforeAll(): Unit = {
    assert(
      script.exists() && script.canExecute(),
      s"compute-expected.sh missing or not executable at ${script.getPath}"
    )
    assert(
      sampleFixture.exists(),
      s"sample fixture missing at ${sampleFixture.getPath}"
    )
  }

  private def runTool(fixture: File): (Int, String, String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val logger = ProcessLogger(
      o => { out.append(o); out.append('\n') },
      e => { err.append(e); err.append('\n') }
    )
    val rc =
      Process(Seq("bash", script.getAbsolutePath, fixture.getAbsolutePath))
        .!(logger)
    (rc, out.toString, err.toString)
  }

  test("compute-expected.sh exits 0 on a valid PEM X.509 fixture") {
    val (rc, _, err) = runTool(sampleFixture)
    assertEquals(rc, 0, s"expected rc=0; got $rc. stderr=$err")
  }

  test("compute-expected.sh emits parseable JSON") {
    val (_, stdout, _) = runTool(sampleFixture)
    val tmp = File.createTempFile("compute-expected-out-", ".json")
    Files.writeString(tmp.toPath, stdout)
    try {
      // Re-use the production sidecar parser. Even though the tool
      // emits `<review>` placeholders, the JSON shape must satisfy the
      // schema or contributors get cryptic errors when they commit it.
      val parsed = CertificatesSidecar.parse(tmp)
      assertEquals(parsed.itemCount, 1)
      assert(parsed.mimeTypes.mustContain.contains("application/x-pem-file"))
    } finally tmp.delete()
  }

  test("compute-expected.sh strips the 'subject=' prefix from SubjectDN") {
    val (_, stdout, _) = runTool(sampleFixture)
    val tmp = File.createTempFile("compute-expected-out-", ".json")
    Files.writeString(tmp.toPath, stdout)
    try {
      val parsed = CertificatesSidecar.parse(tmp)
      val dn =
        parsed.metadata.mustContain.getOrElse("Certificates:SubjectDN", "")
      assert(
        dn.nonEmpty,
        "Certificates:SubjectDN must be present and non-empty"
      )
      assert(
        !dn.startsWith("subject="),
        s"SubjectDN must not start with literal 'subject=' prefix; got: '$dn'"
      )
      assert(
        dn.startsWith("CN=ISRG Root X1"),
        s"SubjectDN should start with the cert's actual subject; got: '$dn'"
      )
    } finally tmp.delete()
  }

  test("compute-expected.sh strips the 'issuer=' prefix from IssuerDN") {
    val (_, stdout, _) = runTool(sampleFixture)
    val tmp = File.createTempFile("compute-expected-out-", ".json")
    Files.writeString(tmp.toPath, stdout)
    try {
      val parsed = CertificatesSidecar.parse(tmp)
      val dn =
        parsed.metadata.mustContain.getOrElse("Certificates:IssuerDN", "")
      assert(
        dn.nonEmpty,
        "Certificates:IssuerDN must be present and non-empty"
      )
      assert(
        !dn.startsWith("issuer="),
        s"IssuerDN must not start with literal 'issuer=' prefix; got: '$dn'"
      )
    } finally tmp.delete()
  }

  test(
    "compute-expected.sh's emitted CertSha256 (in cert-sha256@... pURL) matches JDK's parsed-DER SHA-256"
  ) {
    val (_, stdout, _) = runTool(sampleFixture)
    val tmp = File.createTempFile("compute-expected-out-", ".json")
    Files.writeString(tmp.toPath, stdout)
    try {
      val parsed = CertificatesSidecar.parse(tmp)
      // The tool emits `pkg:generic/x509/cert-sha256@{hex}?...` strings — extract
      // the hex value from the cert-sha256 pURL.
      val certPurl = parsed.purls.mustContain
        .find(_.startsWith("pkg:generic/x509/cert-sha256@"))
        .getOrElse(
          fail(
            "expected a pkg:generic/x509/cert-sha256@... pURL in tool output"
          )
        )
      val toolHex = certPurl
        .stripPrefix("pkg:generic/x509/cert-sha256@")
        .takeWhile(_ != '?')
      // Independently parse the same fixture with the JDK and compute
      // SHA-256 of the DER form.
      val cf = CertificateFactory.getInstance("X.509")
      val cert = cf
        .generateCertificate(
          new ByteArrayInputStream(Files.readAllBytes(sampleFixture.toPath))
        )
        .asInstanceOf[X509Certificate]
      val jdkHex = MessageDigest
        .getInstance("SHA-256")
        .digest(cert.getEncoded)
        .map(b => f"${b & 0xff}%02x")
        .mkString
      assertEquals(
        toolHex,
        jdkHex,
        s"compute-expected.sh emitted cert-sha256=$toolHex but JDK " +
          s"parsed DER hashes to $jdkHex"
      )
    } finally tmp.delete()
  }
}
