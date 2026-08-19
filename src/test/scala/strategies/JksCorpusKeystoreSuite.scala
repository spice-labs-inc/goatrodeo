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

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Corpus-driven tests for JKS keystore parsing and key detection.
  *
  * WHAT: the 20-pair JKS v1/v2 corpus
  * (`test_data/certificates/keystores/synthetic/jks-v{1,2}/`, generated in a
  * JDK 8 container — see the README there) is parsed through the real
  * `Certificates.parseKeystore` and the real metadata pipeline, and the
  * detected keys are asserted.
  *
  * WHY: BouncyCastle has no JKS implementation, so before the SUN-provider
  * fallback every JKS artifact degraded to envelope-only (`ks = None`) and no
  * key was ever detected. These tests pin the fix end to end: both format
  * versions must load without a password, v1 and v2 of the same store must
  * yield identical results, and each key entry must surface its detected key
  * algorithm/size/curve.
  *
  * THEORY: JKS v1 and v2 differ only in the per-certificate type fields, so a
  * faithful parser must produce identical outcomes for a matched pair. Key
  * detection uses the entry's public key (the chain head certificate) — never
  * the protected private key blob, which is unreadable without the key
  * password.
  *
  * LLM note: K-C-xx = test id.
  */
class JksCorpusKeystoreSuite extends FunSuite {

  private val v1Dir = "test_data/certificates/keystores/synthetic/jks-v1"
  private val v2Dir = "test_data/certificates/keystores/synthetic/jks-v2"

  private val expectedEntries: Map[String, Int] = Map(
    "jks-v1-01-rsa-key-single.jks" -> 1,
    "jks-v1-02-rsa-key-chain2.jks" -> 2,
    "jks-v1-03-rsa-key-chain3.jks" -> 3,
    "jks-v1-04-ec-p256-key.jks" -> 1,
    "jks-v1-05-dsa-key.jks" -> 1,
    "jks-v1-06-trusted-single.jks" -> 1,
    "jks-v1-07-trusted-five.jks" -> 5,
    "jks-v1-08-mixed-1key-2trusted.jks" -> 3,
    "jks-v1-09-mixed-2key-3trusted.jks" -> 5,
    "jks-v1-10-empty.jks" -> 0,
    "jks-v1-11-two-aliases-same-cert.jks" -> 2,
    "jks-v1-12-mixedcase-aliases.jks" -> 3,
    "jks-v1-13-long-alias.jks" -> 1,
    "jks-v1-14-rsa-4096-key.jks" -> 1,
    "jks-v1-15-ec-p384-key.jks" -> 1,
    "jks-v1-16-custom-storepass.jks" -> 1,
    "jks-v1-17-diff-keypass.jks" -> 1,
    "jks-v1-18-ten-keys.jks" -> 10,
    "jks-v1-19-expired-cert.jks" -> 1,
    "jks-v1-20-future-cert.jks" -> 1
  )

  private def wrap(path: String): FileWrapper =
    FileWrapper(new File(path), path, None)

  private def testItem(id: String): Item = {
    Item(
      id,
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(id),
          mimeType = TreeSet("application/octet-stream"),
          fileSize = 100,
          extra = TreeMap()
        )
      )
    )
  }

  private def metadataFor(
      path: String,
      format: String
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val wrapper = wrap(path)
    val claim = Certificates.parseKeystore(wrapper, format).get
    val state = new CertificatesState(wrapper, Some(claim))
    val (md, _) =
      state.getMetadata(wrapper, testItem("gitoid-test"), new SingleMarker())
    md
  }

  private def mdString(
      md: TreeMap[String, TreeSet[StringOrPair]],
      key: String
  ): Option[String] = {
    md.get(key).flatMap(_.headOption).collect { case StringOf(s) => s }
  }

  // K-C-01 — every v1 corpus file loads without a password via the real
  // parse path, with the expected entry count. THEORY: null-password loading
  // skips only the integrity digest; entries are fully readable.
  test("K-C-01 all 20 JKS v1 files load with the expected entry count") {
    expectedEntries.foreach { case (name, expected) =>
      val parsed = Certificates.parseKeystore(wrap(s"$v1Dir/$name"), "JKS")
      assert(parsed.isDefined, s"$name: parseKeystore returned None")
      val k = parsed.get
      assertEquals(k.format, "jks", name)
      assert(k.ks.isDefined, s"$name: keystore did not load (envelope-only)")
      assertEquals(k.entryCount, expected, name)
    }
  }

  // K-C-02 — v1 and v2 of every pair parse identically. THEORY: the two
  // format versions differ only in per-certificate type fields; a faithful
  // parser must not treat them differently.
  test("K-C-02 v1 and v2 of every pair parse identically") {
    expectedEntries.keys.foreach { name =>
      val v2Name = name.replace("jks-v1-", "jks-v2-")
      val v1 = Certificates.parseKeystore(wrap(s"$v1Dir/$name"), "JKS")
      val v2 = Certificates.parseKeystore(wrap(s"$v2Dir/$v2Name"), "JKS")
      assert(v1.isDefined && v2.isDefined, s"$name: one side failed to parse")
      assert(
        v1.get.ks.isDefined && v2.get.ks.isDefined,
        s"$name: one side is envelope-only"
      )
      assertEquals(v1.get.entryCount, v2.get.entryCount, name)
      assertEquals(v1.get.format, v2.get.format, name)
    }
  }

  // K-C-03 — key entries are detected: each key entry emits its key
  // algorithm/size/curve derived from the entry's public key (chain head
  // cert), never from the protected private key.
  test("K-C-03 key entries are detected with algorithm, size, and curve") {
    val rsa = metadataFor(s"$v1Dir/jks-v1-01-rsa-key-single.jks", "JKS")
    assertEquals(
      mdString(rsa, "Certificates:Entry:entry:KeyAlgorithm"),
      Some("rsa")
    )
    assertEquals(
      mdString(rsa, "Certificates:Entry:entry:KeySize"),
      Some("2048")
    )
    assertEquals(mdString(rsa, "Certificates:KeyEntryCount"), Some("1"))

    val rsa4096 = metadataFor(s"$v1Dir/jks-v1-14-rsa-4096-key.jks", "JKS")
    assertEquals(
      mdString(rsa4096, "Certificates:Entry:bigkey:KeySize"),
      Some("4096")
    )

    val ec = metadataFor(s"$v1Dir/jks-v1-04-ec-p256-key.jks", "JKS")
    assertEquals(
      mdString(ec, "Certificates:Entry:eckey:KeyAlgorithm"),
      Some("ec")
    )
    assertEquals(mdString(ec, "Certificates:Entry:eckey:Curve"), Some("p-256"))

    val ec384 = metadataFor(s"$v1Dir/jks-v1-15-ec-p384-key.jks", "JKS")
    assertEquals(
      mdString(ec384, "Certificates:Entry:eckey384:Curve"),
      Some("p-384")
    )

    val dsa = metadataFor(s"$v1Dir/jks-v1-05-dsa-key.jks", "JKS")
    assertEquals(
      mdString(dsa, "Certificates:Entry:dsakey:KeyAlgorithm"),
      Some("dsa")
    )
    // keyAlgAndQualifier sizes DSA from Y.bitLength (not P), so a 1024-bit
    // DSA key reports 1023; this matches the Chain:0:KeySize the same cert
    // emits — consistent, not a regression.
    assertEquals(
      mdString(dsa, "Certificates:Entry:dsakey:KeySize"),
      Some("1023")
    )
  }

  // K-C-04 — trusted-cert-only stores have no detected keys. THEORY: key
  // detection must only fire for key entries; a trusted cert is a cert, not
  // a key. Key entries are distinguishable by their `Chain:` metadata (only
  // key entries carry a certificate chain); trusted entries do not.
  test("K-C-04 trusted-cert-only store has no detected keys") {
    val md = metadataFor(s"$v1Dir/jks-v1-06-trusted-single.jks", "JKS")
    assertEquals(mdString(md, "Certificates:KeyEntryCount"), Some("0"))
    assertEquals(mdString(md, "Certificates:CertCount"), Some("1"))
    assert(
      !md.keys.exists(k => k.contains(":Chain:")),
      s"trusted-cert store must not emit chain (key-entry) metadata, got ${md.keys}"
    )
  }

  // K-C-05 — the empty store loads and reports zero entries.
  test("K-C-05 empty store loads with zero entries") {
    val md = metadataFor(s"$v1Dir/jks-v1-10-empty.jks", "JKS")
    assertEquals(mdString(md, "Certificates:EntryCount"), Some("0"))
    assertEquals(mdString(md, "Certificates:KeyEntryCount"), Some("0"))
  }

  // K-C-06 — JCEKS loads via the SunJCE provider fallback (BC has no JCEKS
  // implementation either) and its key entry is detected.
  test("K-C-06 JCEKS loads and detects its key entry") {
    val parsed = Certificates.parseKeystore(
      wrap("test_data/certificates/keystores/synthetic/encrypted-jceks.jceks"),
      "JCEKS"
    )
    assert(parsed.isDefined)
    assert(parsed.get.ks.isDefined, "JCEKS must not be envelope-only")
    assertEquals(parsed.get.entryCount, 1)
    val md = metadataFor(
      "test_data/certificates/keystores/synthetic/encrypted-jceks.jceks",
      "JCEKS"
    )
    assertEquals(mdString(md, "Certificates:KeyEntryCount"), Some("1"))
  }
}
