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

import java.io.File
import java.nio.file.Files

/** Unit tests for [[CertificatesSidecar.parse]].
  *
  * Traces to: `certificates-strategy/phase-0-corpus.md` sub-goal #1 ("Define
  * the complete sidecar JSON schema") and `certificates-strategy/
  * appendices.md` Appendix B ("Required fields... `description`, `source`,
  * `retrievedAt`, `itemCount`, `mimeTypes.mustContain`, `purls.mustContain`,
  * `metadata.mustContain`, `forbiddenMetadataPatterns`").
  *
  * ## LLM-friendly summary of each test
  *
  *   - "valid minimal sidecar parses" — confirms the parser accepts a
  *     sidecar that supplies only the required fields and no optional
  *     ones.
  *   - "valid full sidecar parses" — confirms optional fields
  *     (`mustNotContain`, `mustContainRanges`, `forbiddenMetadataKeys`)
  *     round-trip.
  *   - "missing X throws" — one test per required field. Confirms the
  *     parser fails loudly with a message naming the missing field,
  *     rather than silently defaulting.
  *   - "malformed JSON throws" — confirms syntactic errors are surfaced
  *     with a "not valid JSON" message rather than a cryptic NPE.
  *   - "wrong type on required field throws" — e.g., `itemCount: "one"`
  *     (string) must be rejected, not coerced.
  */
class CertificatesSidecarTests extends FunSuite {

  private def writeSidecar(json: String): File = {
    val f = File.createTempFile("sidecar-", ".expected.json")
    Files.writeString(f.toPath, json)
    f.deleteOnExit()
    f
  }

  private val minimalSidecar =
    """{
      |  "description": "test",
      |  "source": "test",
      |  "retrievedAt": "2026-04-24",
      |  "itemCount": 1,
      |  "mimeTypes": {
      |    "mustContain": []
      |  },
      |  "purls": {
      |    "mustContain": []
      |  },
      |  "metadata": {
      |    "mustContain": {}
      |  },
      |  "forbiddenMetadataPatterns": []
      |}""".stripMargin

  test("valid minimal sidecar parses") {
    val f = writeSidecar(minimalSidecar)
    val sc = CertificatesSidecar.parse(f)
    assertEquals(sc.description, "test")
    assertEquals(sc.source, "test")
    assertEquals(sc.retrievedAt, "2026-04-24")
    assertEquals(sc.itemCount, 1)
    assertEquals(sc.mimeTypes.mustContain, List.empty[String])
    assertEquals(sc.mimeTypes.mustNotContain, List.empty[String])
    assertEquals(sc.purls.mustContain, List.empty[String])
    assertEquals(sc.purls.mustNotContain, List.empty[String])
    assertEquals(sc.metadata.mustContain, Map.empty[String, String])
    assertEquals(sc.metadata.mustContainRanges, Map.empty[String, NumericRange])
    assertEquals(sc.forbiddenMetadataKeys, List.empty[String])
    assertEquals(sc.forbiddenMetadataPatterns, List.empty[String])
  }

  test("valid full sidecar parses including optional fields") {
    val full =
      """{
        |  "description": "Full example",
        |  "source": "https://example.com/cert.pem",
        |  "retrievedAt": "2026-04-24",
        |  "itemCount": 1,
        |  "mimeTypes": {
        |    "mustContain": ["application/x-pem-file"],
        |    "mustNotContain": ["text/plain"]
        |  },
        |  "purls": {
        |    "mustContain": ["pkg:x509/spki-sha256@abc?alg=rsa"],
        |    "mustNotContain": ["pkg:ssh/sha256@xyz"]
        |  },
        |  "metadata": {
        |    "mustContain": { "Name": "Example", "Certificates:Version": "3" },
        |    "mustContainRanges": { "Certificates:EntryCount": { "min": "100", "max": "10000" } }
        |  },
        |  "forbiddenMetadataKeys": ["Certificates:PrivateKeyMaterial"],
        |  "forbiddenMetadataPatterns": [
        |    "-----BEGIN RSA PRIVATE KEY-----",
        |    "openssh-key-v1"
        |  ]
        |}""".stripMargin
    val sc = CertificatesSidecar.parse(writeSidecar(full))
    assertEquals(sc.mimeTypes.mustContain, List("application/x-pem-file"))
    assertEquals(sc.mimeTypes.mustNotContain, List("text/plain"))
    assertEquals(
      sc.purls.mustContain,
      List("pkg:x509/spki-sha256@abc?alg=rsa")
    )
    assertEquals(sc.purls.mustNotContain, List("pkg:ssh/sha256@xyz"))
    assertEquals(
      sc.metadata.mustContain,
      Map("Name" -> "Example", "Certificates:Version" -> "3")
    )
    assertEquals(
      sc.metadata.mustContainRanges,
      Map("Certificates:EntryCount" -> NumericRange("100", "10000"))
    )
    assertEquals(sc.forbiddenMetadataKeys, List("Certificates:PrivateKeyMaterial"))
    assertEquals(sc.forbiddenMetadataPatterns.size, 2)
  }

  // Parameterized negative tests — each test removes one required field
  // from the minimal sidecar and confirms parse throws with a helpful message.
  private val requiredFields =
    List(
      "description",
      "source",
      "retrievedAt",
      "itemCount",
      "mimeTypes.mustContain",
      "purls.mustContain",
      "metadata.mustContain",
      "forbiddenMetadataPatterns"
    )

  requiredFields.foreach { field =>
    test(s"missing required field '$field' throws SidecarParseError") {
      // Build a broken sidecar by removing the given field via JSON surgery
      val broken = removeField(minimalSidecar, field)
      val f = writeSidecar(broken)
      val ex = intercept[CertificatesSidecar.SidecarParseError] {
        CertificatesSidecar.parse(f)
      }
      assert(
        ex.getMessage.contains(field) ||
          ex.getMessage.contains(field.split('.').last),
        s"Error message '${ex.getMessage}' should mention missing field '$field'"
      )
    }
  }

  test("malformed JSON throws SidecarParseError with 'not valid JSON'") {
    val f = writeSidecar("this is not { valid } JSON at all")
    val ex = intercept[CertificatesSidecar.SidecarParseError] {
      CertificatesSidecar.parse(f)
    }
    assert(
      ex.getMessage.contains("not valid JSON"),
      s"Error message should be informative; got: ${ex.getMessage}"
    )
  }

  test("wrong type on required field throws SidecarParseError") {
    // itemCount as string instead of number
    val broken = minimalSidecar.replace("\"itemCount\": 1", "\"itemCount\": \"one\"")
    val ex = intercept[CertificatesSidecar.SidecarParseError] {
      CertificatesSidecar.parse(writeSidecar(broken))
    }
    assert(
      ex.getMessage.contains("itemCount"),
      s"Error message should name the offending field; got: ${ex.getMessage}"
    )
  }

  test("ranges with non-numeric bounds are accepted by the parser (validation happens at assertion time)") {
    // Parser does not validate that range bounds are numeric; that check
    // happens in `CertificatesAssertions.assertMetadataRanges`. This test
    // documents that contract — the parser is lenient about values, strict
    // about structure.
    val withBadRange =
      minimalSidecar.replace(
        "\"mustContain\": {}",
        "\"mustContain\": {}, \"mustContainRanges\": { \"k\": { \"min\": \"abc\", \"max\": \"xyz\" } }"
      )
    val sc = CertificatesSidecar.parse(writeSidecar(withBadRange))
    assertEquals(
      sc.metadata.mustContainRanges,
      Map("k" -> NumericRange("abc", "xyz"))
    )
  }

  // -- helpers --

  /** Remove a field from a JSON string by naive string surgery — suitable
    * only for the minimal well-formed sidecar used in this file's tests. */
  private def removeField(json: String, field: String): String = {
    val segs = field.split('.').toList
    segs match {
      case List(top) =>
        // Top-level field: remove the "top": ... line
        stripLine(json, s"\"$top\"")
      case List(parent, child) =>
        // Nested field: strip the child inside the parent object
        val lines = json.split('\n').toList
        val out = collection.mutable.ArrayBuffer[String]()
        var inParent = false
        lines.foreach { line =>
          val t = line.trim
          if (t.startsWith(s"\"$parent\"")) inParent = true
          if (inParent && t.startsWith(s"\"$child\"")) {
            // skip this line
          } else out += line
          if (inParent && (t == "}," || t == "}")) inParent = false
        }
        out.mkString("\n")
      case _ => json
    }
  }

  private def stripLine(json: String, needle: String): String = {
    val lines = json.split('\n').toList
    val kept = lines.filterNot(_.trim.startsWith(needle))
    // Fix trailing comma if we removed the last entry
    kept.mkString("\n").replaceAll(",(\\s*})", "$1")
  }
}
