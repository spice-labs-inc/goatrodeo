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

import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import munit.FunSuite

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Unit tests for [[CertificatesAssertions]] — each helper gets a positive and
  * a negative test. Synthetic Items are constructed directly; no pipeline, no
  * fixtures.
  *
  * Traces to: `certificates-strategy/appendices.md` Appendix B (assertion
  * contract) and Appendix C (forbidden-pattern leak guard).
  *
  * ## LLM-friendly summary
  *
  * Each helper is independently verified:
  *   - `assertMimeTypesContain` / `assertMimeTypesAbsent`
  *   - `assertPurlsContain` / `assertPurlsAbsent` (including the `<computed>`
  *     token path)
  *   - `assertMetadataContains` (exact + `<computed>` placeholder)
  *   - `assertMetadataRanges` (inside + outside bounds, unparseable bounds)
  *   - `assertMetadataKeysAbsent`
  *   - `assertNoForbiddenPatterns` — the private-key leak guard
  */
class CertificatesAssertionsTests extends FunSuite {

  private def mkItem(
      mimeTypes: Set[String] = Set.empty,
      purls: Set[String] = Set.empty,
      extra: Map[String, Set[String]] = Map.empty
  ): Item = {
    val extraTree: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap.from(extra.view.mapValues { vs =>
        TreeSet.from(vs.map(v => StringOrPair(v)))
      })
    val connections =
      TreeSet.from(purls.map(p => EdgeType.aliasFrom -> p))
    Item(
      identifier = "gitoid:blob:sha256:test",
      connections = connections,
      bodyMimeType = Some(ItemMetaData.mimeType),
      body = Some(
        ItemMetaData(
          fileNames = TreeSet.empty,
          mimeType = TreeSet.from(mimeTypes),
          fileSize = 0L,
          extra = extraTree
        )
      )
    )
  }

  // ---------- MIME helpers ----------

  test("assertMimeTypesContain passes when all required are present") {
    val item = mkItem(mimeTypes = Set("a/b", "c/d"))
    CertificatesAssertions.assertMimeTypesContain(item, List("a/b"), "t")
    CertificatesAssertions.assertMimeTypesContain(item, List("a/b", "c/d"), "t")
  }

  test("assertMimeTypesContain throws when any required is missing") {
    val item = mkItem(mimeTypes = Set("a/b"))
    val ex = intercept[AssertionError] {
      CertificatesAssertions.assertMimeTypesContain(
        item,
        List("a/b", "missing"),
        "t"
      )
    }
    assert(ex.getMessage.contains("missing"))
  }

  test("assertMimeTypesAbsent passes when none of the forbidden are present") {
    val item = mkItem(mimeTypes = Set("a/b"))
    CertificatesAssertions.assertMimeTypesAbsent(item, List("x/y", "z/w"), "t")
  }

  test("assertMimeTypesAbsent throws when any forbidden is present") {
    val item = mkItem(mimeTypes = Set("a/b"))
    intercept[AssertionError] {
      CertificatesAssertions.assertMimeTypesAbsent(item, List("a/b"), "t")
    }
  }

  // ---------- pURL helpers ----------

  test("assertPurlsContain passes when all required pURLs are present") {
    val item =
      mkItem(purls =
        Set("pkg:generic/x509/spki-sha256@abc", "pkg:generic/ssh/sha256@xyz")
      )
    CertificatesAssertions.assertPurlsContain(
      item,
      List("pkg:generic/x509/spki-sha256@abc"),
      "t"
    )
  }

  test("assertPurlsContain throws when a required pURL is missing") {
    val item = mkItem(purls = Set("pkg:generic/x509/spki-sha256@abc"))
    intercept[AssertionError] {
      CertificatesAssertions.assertPurlsContain(
        item,
        List("pkg:generic/pgp/fingerprint@deadbeef"),
        "t"
      )
    }
  }

  test(
    "assertPurlsContain accepts <computed> placeholder when some pURL matches the surrounding segments"
  ) {
    val item =
      mkItem(purls = Set("pkg:generic/x509/spki-sha256@abc123?alg=rsa"))
    CertificatesAssertions.assertPurlsContain(
      item,
      List("pkg:generic/x509/spki-sha256@<computed>?alg=rsa"),
      "t"
    )
  }

  test(
    "assertPurlsContain with <computed> still throws when no pURL matches prefix or suffix"
  ) {
    val item = mkItem(purls = Set("pkg:generic/ssh/sha256@xxx"))
    intercept[AssertionError] {
      CertificatesAssertions.assertPurlsContain(
        item,
        List("pkg:generic/x509/spki-sha256@<computed>?alg=rsa"),
        "t"
      )
    }
  }

  test("assertPurlsAbsent throws when a forbidden pURL is present") {
    val item = mkItem(purls = Set("pkg:generic/x509/spki-sha256@abc"))
    intercept[AssertionError] {
      CertificatesAssertions.assertPurlsAbsent(
        item,
        List("pkg:generic/x509/spki-sha256@abc"),
        "t"
      )
    }
  }

  test("purlsOf does not include non-pkg: edges") {
    // alias:from edges may legitimately carry gitoid: hashes too.
    val item = Item(
      identifier = "gitoid:blob:sha256:test",
      connections = TreeSet(
        EdgeType.aliasFrom -> "gitoid:blob:sha1:hashA",
        EdgeType.aliasFrom -> "pkg:generic/x509/spki-sha256@abc"
      ),
      bodyMimeType = Some(ItemMetaData.mimeType),
      body = Some(
        ItemMetaData(
          fileNames = TreeSet.empty,
          mimeType = TreeSet.empty,
          fileSize = 0L,
          extra = TreeMap.empty
        )
      )
    )
    val purls = CertificatesAssertions.purlsOf(item)
    assertEquals(purls, Set("pkg:generic/x509/spki-sha256@abc"))
  }

  // ---------- metadata helpers ----------

  test("assertMetadataContains passes on exact match") {
    val item = mkItem(extra = Map("Certificates:Version" -> Set("3")))
    CertificatesAssertions.assertMetadataContains(
      item,
      Map("Certificates:Version" -> "3"),
      "t"
    )
  }

  test(
    "assertMetadataContains accepts <computed> when key is present and non-empty"
  ) {
    val item = mkItem(extra = Map("Certificates:SpkiSha256" -> Set("abc123")))
    CertificatesAssertions.assertMetadataContains(
      item,
      Map("Certificates:SpkiSha256" -> "<computed>"),
      "t"
    )
  }

  test("assertMetadataContains throws when a key is missing") {
    val item = mkItem()
    intercept[AssertionError] {
      CertificatesAssertions.assertMetadataContains(
        item,
        Map("Certificates:Version" -> "3"),
        "t"
      )
    }
  }

  test("assertMetadataContains throws when value mismatches") {
    val item = mkItem(extra = Map("Certificates:Version" -> Set("3")))
    intercept[AssertionError] {
      CertificatesAssertions.assertMetadataContains(
        item,
        Map("Certificates:Version" -> "2"),
        "t"
      )
    }
  }

  test(
    "assertMetadataRanges passes when a value is inside the inclusive range"
  ) {
    val item = mkItem(extra = Map("Certificates:EntryCount" -> Set("150")))
    CertificatesAssertions.assertMetadataRanges(
      item,
      Map("Certificates:EntryCount" -> NumericRange("100", "200")),
      "t"
    )
  }

  test("assertMetadataRanges throws when no value lies in range") {
    val item = mkItem(extra = Map("Certificates:EntryCount" -> Set("250")))
    intercept[AssertionError] {
      CertificatesAssertions.assertMetadataRanges(
        item,
        Map("Certificates:EntryCount" -> NumericRange("100", "200")),
        "t"
      )
    }
  }

  test("assertMetadataRanges throws for unparseable bounds") {
    val item = mkItem(extra = Map("k" -> Set("100")))
    intercept[AssertionError] {
      CertificatesAssertions.assertMetadataRanges(
        item,
        Map("k" -> NumericRange("not-a-number", "200")),
        "t"
      )
    }
  }

  test("assertMetadataKeysAbsent throws when a forbidden key is present") {
    val item =
      mkItem(extra = Map("Certificates:PrivateKeyMaterial" -> Set("leak")))
    intercept[AssertionError] {
      CertificatesAssertions.assertMetadataKeysAbsent(
        item,
        List("Certificates:PrivateKeyMaterial"),
        "t"
      )
    }
  }

  // ---------- leak guard ----------

  test("assertNoForbiddenPatterns passes when no value matches any pattern") {
    val item =
      mkItem(extra = Map("Certificates:SubjectDN" -> Set("CN=Example")))
    CertificatesAssertions.assertNoForbiddenPatterns(
      item,
      List(
        "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
        "openssh-key-v1"
      ),
      "t"
    )
  }

  test(
    "assertNoForbiddenPatterns catches a PEM private-key header in any metadata value"
  ) {
    val item =
      mkItem(extra = Map("SomeKey" -> Set("-----BEGIN RSA PRIVATE KEY-----")))
    val ex = intercept[AssertionError] {
      CertificatesAssertions.assertNoForbiddenPatterns(
        item,
        List("-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"),
        "t"
      )
    }
    assert(ex.getMessage.contains("PRIVATE KEY"))
  }

  test("assertNoForbiddenPatterns catches the openssh-key-v1 magic string") {
    val item = mkItem(extra =
      Map(
        "Certificates:SomeField" -> Set(
          "blob contains openssh-key-v1 somewhere"
        )
      )
    )
    intercept[AssertionError] {
      CertificatesAssertions.assertNoForbiddenPatterns(
        item,
        List("openssh-key-v1"),
        "t"
      )
    }
  }

  test("assertNoForbiddenPatterns reports the specific key that matched") {
    val item = mkItem(extra =
      Map(
        "Benign" -> Set("safe value"),
        "Leaky" -> Set("-----BEGIN PGP PRIVATE KEY BLOCK-----")
      )
    )
    val ex = intercept[AssertionError] {
      CertificatesAssertions.assertNoForbiddenPatterns(
        item,
        List("-----BEGIN PGP PRIVATE KEY BLOCK-----"),
        "t"
      )
    }
    assert(
      ex.getMessage.contains("Leaky"),
      s"expected message to name the offending key 'Leaky'; got: ${ex.getMessage}"
    )
  }

  // --- extended leak-pattern coverage per Appendix C ---
  //
  // The Appendix C list includes three PKCS#8 base64 prefixes and a
  // full PEM-body regex that guard against the strategy accidentally
  // serializing private-key DER bytes (either as base64 text or as
  // embedded full PEM blocks). Each gets its own test so a reviewer
  // challenging "does your leak guard catch X" has a named test to
  // point at.

  test("assertNoForbiddenPatterns catches PKCS#8 base64 prefix MIIEvQIBADAN") {
    val item =
      mkItem(extra = Map("SomeKey" -> Set("MIIEvQIBADAN..." + "A" * 100)))
    intercept[AssertionError] {
      CertificatesAssertions.assertNoForbiddenPatterns(
        item,
        List("MIIEvQIBADAN"),
        "t"
      )
    }
  }

  test(
    "assertNoForbiddenPatterns catches PKCS#8 base64 prefix MIIEpAIBAAKCAQEA"
  ) {
    val item =
      mkItem(extra = Map("SomeKey" -> Set("MIIEpAIBAAKCAQEA" + "X" * 200)))
    intercept[AssertionError] {
      CertificatesAssertions.assertNoForbiddenPatterns(
        item,
        List("MIIEpAIBAAKCAQEA"),
        "t"
      )
    }
  }

  test(
    "assertNoForbiddenPatterns catches MIIB...QIB... regex (broader PKCS#8 families)"
  ) {
    // Matches `MIIB{8 chars}QIB{any}` — the generic PKCS#8
    // short-key prefix pattern.
    val leak = "MIIBVAIBADANQIBsomethingmorehere"
    val item = mkItem(extra = Map("K" -> Set(leak)))
    intercept[AssertionError] {
      CertificatesAssertions.assertNoForbiddenPatterns(
        item,
        List("MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+"),
        "t"
      )
    }
  }

  test(
    "assertNoForbiddenPatterns catches full PEM private-key body, not just header"
  ) {
    val leak =
      "-----BEGIN RSA PRIVATE KEY-----\n" +
        "MIIEpAIBAAKCAQEA1234\n" +
        "abcd5678\n" +
        "-----END RSA PRIVATE KEY-----"
    val item = mkItem(extra = Map("K" -> Set(leak)))
    intercept[AssertionError] {
      CertificatesAssertions.assertNoForbiddenPatterns(
        item,
        List(
          "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----"
        ),
        "t"
      )
    }
  }

  test(
    "assertNoForbiddenPatterns: safe values that look similar to prefixes do not false-positive"
  ) {
    // `MIIBQ` is legitimately the first 5 chars of many public-key DER
    // base64 encodings (subject-public-key-info). Without the `QIB`
    // anchoring requirement, we'd false-positive on every SPKI.
    val item = mkItem(extra =
      Map("Certificates:SpkiSha256" -> Set("MIIBQsomethingelse"))
    )
    CertificatesAssertions.assertNoForbiddenPatterns(
      item,
      List("MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+"),
      "t"
    )
  }
}
