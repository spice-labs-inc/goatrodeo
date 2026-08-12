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

package io.spicelabs.goatrodeo.omnibor

import munit.FunSuite
import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** CBOM emission for the Phase A–G extended-capture families.
  *
  * ## LLM-friendly summary
  *
  * Covers the extended-capture families in `CbomEmitter` and locks their
  * emitted CBOM shape:
  *
  *   - ServiceCrypto/Kerberos/MobileTls → related-crypto-material `other` +
  *     algorithm refs
  *   - JWT → related-crypto-material `other` + signature algorithm (the `none`
  *     fallback is never turned into an algorithm)
  *   - JWK → related-crypto-material `public-key`/`private-key` (driven by
  *     `JWK:private_present`)
  *   - EmbeddedKey → related-crypto-material from `EmbeddedKey:kind` + derived
  *     algorithm
  *   - CryptoAlgorithms → pure (deduped) algorithm assets, no material
  *   - CryptoDependency → `library` components with crypto-family properties
  *
  * Private-key markers are emitted faithfully as properties (no redaction).
  */
class ExtendedCaptureCbomSuite extends FunSuite {

  private implicit val formats: Formats = DefaultFormats

  private def makeItem(
      id: String,
      connections: TreeSet[Edge] = TreeSet(),
      extra: TreeMap[String, TreeSet[StringOrPair]] = TreeMap(),
      fileNames: TreeSet[String] = TreeSet(),
      mimeTypes: TreeSet[String] = TreeSet()
  ): Item = {
    Item(
      identifier = id,
      connections = connections,
      bodyMimeType = Some(ItemMetaData.mimeType),
      body = Some(
        ItemMetaData(
          fileNames = fileNames,
          mimeType = mimeTypes,
          fileSize = 0,
          extra = extra
        )
      )
    )
  }

  private def storeItem(storage: Storage, item: Item): Unit = {
    storage.write(item.identifier, _ => Some(item), _ => "ext-capture-test")
    ()
  }

  private def tempDir(): File = {
    Files.createTempDirectory("ext-cbom-test").toFile()
  }

  private def cleanup(dir: File): Unit = {
    if (dir != null && dir.exists()) {
      Files
        .walk(dir.toPath())
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete(_))
      ()
    }
  }

  private def emitCbom(items: List[Item]): (JValue, File) = {
    val dir = tempDir()
    val storage = MemStorage(None)
    val rootId = "gitoid:blob:sha256:" + "0" * 64
    items.foreach { it =>
      storeItem(
        storage,
        it.copy(connections = TreeSet(EdgeType.containedBy -> rootId))
      )
    }
    val root = makeItem(
      rootId,
      connections =
        TreeSet.from(items.map(it => EdgeType.contains -> it.identifier))
    )
    storeItem(storage, root)
    val files = CbomEmitter.emitForStorage(storage, "1.6", dir).get
    assertEquals(files.size, 1)
    (parse(Files.readString(files.head.toPath())), dir)
  }

  private def components(json: JValue): List[JValue] = {
    (json \ "components") match {
      case JArray(arr) => arr
      case _           => Nil
    }
  }

  private def byBomRef(json: JValue, ref: String): Option[JValue] = {
    components(json).find(c =>
      (c \ "bom-ref") match {
        case JString(s) => s == ref
        case _          => false
      }
    )
  }

  private def materialType(c: JValue): String = {
    getString(c, "cryptoProperties", "relatedCryptoMaterialProperties", "type")
  }

  private def getString(jv: JValue, path: String*): String = {
    path.foldLeft(jv: JValue)(_ \ _) match {
      case JString(s) => s
      case other =>
        fail(s"Expected string at ${path.mkString("/")}, got $other")
    }
  }

  private def stringOpt(jv: JValue, path: String*): Option[String] = {
    path.foldLeft(jv: JValue)(_ \ _) match {
      case JString(s) => Some(s)
      case _          => None
    }
  }

  private def propValues(c: JValue, name: String): List[String] = {
    (c \ "properties") match {
      case JArray(arr) =>
        arr.collect {
          case o: JObject
              if (o \ "name") == JString(name) && (o \ "value")
                .isInstanceOf[JString] =>
            getString(o, "value")
        }
      case _ => Nil
    }
  }

  test("ServiceCrypto → related-crypto-material other + algorithm asset") {
    val id = "gitoid:blob:sha256:" + "a" * 64
    val item = makeItem(
      id,
      extra = TreeMap(
        "ServiceCrypto:service" -> TreeSet(StringOrPair("openvpn")),
        "ServiceCrypto:algorithms" -> TreeSet(StringOrPair("aes-128-gcm"))
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      val c = byBomRef(json, id).get
      assertEquals(materialType(c), "other")
      val algRef = getString(
        c,
        "cryptoProperties",
        "relatedCryptoMaterialProperties",
        "algorithmRef"
      )
      assert(byBomRef(json, algRef).isDefined, s"dangling service alg $algRef")
      assert(
        propValues(c, "ServiceCrypto:algorithms").contains("aes-128-gcm"),
        "captured algorithm key must surface as a property"
      )
    } finally cleanup(dir)
  }

  test("Kerberos → related-crypto-material other + algorithm asset") {
    val id = "gitoid:blob:sha256:" + "b" * 64
    val item = makeItem(
      id,
      extra = TreeMap(
        "Kerberos:algorithms" -> TreeSet(
          StringOrPair("aes256-cts-hmac-sha1-96")
        )
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      val c = byBomRef(json, id).get
      assertEquals(materialType(c), "other")
      val algRef = getString(
        c,
        "cryptoProperties",
        "relatedCryptoMaterialProperties",
        "algorithmRef"
      )
      assert(
        byBomRef(json, algRef).isDefined,
        s"dangling kerberos alg $algRef"
      )
    } finally cleanup(dir)
  }

  test(
    "JWT → related-crypto-material other; `none` never becomes an algorithm"
  ) {
    val id = "gitoid:blob:sha256:" + "c" * 64
    val item = makeItem(
      id,
      extra = TreeMap(
        "JWT:alg" -> TreeSet(StringOrPair("HS256")),
        "JWT:signature_algorithm" -> TreeSet(StringOrPair("hmac-sha-256"))
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      val c = byBomRef(json, id).get
      assertEquals(materialType(c), "other")
      val algRef = getString(
        c,
        "cryptoProperties",
        "relatedCryptoMaterialProperties",
        "algorithmRef"
      )
      assert(byBomRef(json, algRef).isDefined, s"dangling jwt alg $algRef")
    } finally cleanup(dir)

    val noneId = "gitoid:blob:sha256:" + "d" * 64
    val noneItem = makeItem(
      noneId,
      extra = TreeMap(
        "JWT:alg" -> TreeSet(StringOrPair("none")),
        "JWT:signature_algorithm" -> TreeSet(StringOrPair("none"))
      )
    )
    val (noneJson, noneDir) = emitCbom(List(noneItem))
    try {
      val c = byBomRef(noneJson, noneId).get
      assert(
        stringOpt(
          c,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "algorithmRef"
        ).isEmpty,
        "JWT `none` must not produce an algorithm ref"
      )
      assert(
        propValues(c, "JWT:alg").contains("none"),
        "JWT `none` finding rides as a property"
      )
    } finally cleanup(noneDir)
  }

  test("JWK public/private → material type and presence property") {
    val pubId = "gitoid:blob:sha256:" + "e" * 64
    val pub = makeItem(
      pubId,
      extra = TreeMap(
        "JWK:kty" -> TreeSet(StringOrPair("RSA")),
        "JWK:use" -> TreeSet(StringOrPair("sig")),
        "JWK:size" -> TreeSet(StringOrPair("2048"))
      )
    )
    val (json, dir) = emitCbom(List(pub))
    try {
      val c = byBomRef(json, pubId).get
      assertEquals(materialType(c), "public-key")
      val algRef = getString(
        c,
        "cryptoProperties",
        "relatedCryptoMaterialProperties",
        "algorithmRef"
      )
      assert(byBomRef(json, algRef).isDefined, s"dangling JWK alg $algRef")
      assertEquals(
        propValues(c, "JWK:kty"),
        List("RSA"),
        "JWK kty must surface as a property"
      )
    } finally cleanup(dir)

    val privId = "gitoid:blob:sha256:" + "f" * 64
    val priv = makeItem(
      privId,
      extra = TreeMap(
        "JWK:kty" -> TreeSet(StringOrPair("EC")),
        "JWK:private_present" -> TreeSet(StringOrPair("true"))
      )
    )
    val (privJson, privDir) = emitCbom(List(priv))
    try {
      val c = byBomRef(privJson, privId).get
      assertEquals(materialType(c), "private-key")
      assert(
        propValues(c, "JWK:private_present").contains("true"),
        "JWK private presence must surface as a property"
      )
    } finally cleanup(privDir)
  }

  test("EmbeddedKey → material type from kind + derived algorithm") {
    val id = "gitoid:blob:sha256:" + "12" * 32
    val item = makeItem(
      id,
      extra = TreeMap(
        "EmbeddedKey:source" -> TreeSet(StringOrPair("kubeconfig")),
        "EmbeddedKey:kind" -> TreeSet(StringOrPair("private-key")),
        "EmbeddedKey:key_algorithm" -> TreeSet(StringOrPair("rsa")),
        "EmbeddedKey:key_size" -> TreeSet(StringOrPair("2048"))
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      val c = byBomRef(json, id).get
      assertEquals(materialType(c), "private-key")
      assert(
        propValues(c, "EmbeddedKey:kind").contains("private-key"),
        "EmbeddedKey kind must surface as a property"
      )
      val algRef = stringOpt(
        c,
        "cryptoProperties",
        "relatedCryptoMaterialProperties",
        "algorithmRef"
      )
      assert(algRef.isDefined, "embedded key must link its derived algorithm")
      assert(byBomRef(json, algRef.get).isDefined, "dangling embedded key alg")
    } finally cleanup(dir)
  }

  test("CryptoAlgorithms → pure algorithm assets (no material component)") {
    val id = "gitoid:blob:sha256:" + "34" * 32
    val item = makeItem(
      id,
      extra = TreeMap(
        "CryptoAlgorithms:algorithm" -> TreeSet(
          StringOrPair("aes-128-gcm"),
          StringOrPair("sha-256")
        )
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      assert(
        byBomRef(json, id).isEmpty,
        "footprint item must not emit a material"
      )
      val compNames = components(json).flatMap(c => stringOpt(c, "name"))
      assert(
        compNames.contains("aes-128-gcm") && compNames.contains("sha-256"),
        s"footprint must emit both algorithm assets; got $compNames"
      )
    } finally cleanup(dir)
  }

  test("CryptoDependency → library components with crypto-family properties") {
    val id = "gitoid:blob:sha256:" + "56" * 32
    val item = makeItem(
      id,
      extra = TreeMap(
        "CryptoDependency:name" -> TreeSet(StringOrPair("ring")),
        "CryptoDependency:version" -> TreeSet(StringOrPair("0.17.8")),
        "CryptoDependency:algorithms" -> TreeSet(
          StringOrPair("aead"),
          StringOrPair("signature")
        )
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      val lib = byBomRef(json, "dep-ring").get
      assertEquals(getString(lib, "type"), "library")
      assertEquals(getString(lib, "name"), "ring")
      assertEquals(getString(lib, "version"), "0.17.8")
      val families = propValues(lib, "crypto-family")
      assert(
        families.contains("aead") && families.contains("signature"),
        s"library must carry crypto-family properties; got $families"
      )
    } finally cleanup(dir)
  }

  test("MobileTls → related-crypto-material other + algorithm asset") {
    val id = "gitoid:blob:sha256:" + "78" * 32
    val item = makeItem(
      id,
      extra = TreeMap(
        "MobileTls:policy" -> TreeSet(StringOrPair("network_security_config")),
        "MobileTls:algorithms" -> TreeSet(StringOrPair("aes-128-gcm"))
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      val c = byBomRef(json, id).get
      assertEquals(materialType(c), "other")
      val algRef = getString(
        c,
        "cryptoProperties",
        "relatedCryptoMaterialProperties",
        "algorithmRef"
      )
      assert(
        byBomRef(json, algRef).isDefined,
        s"dangling mobiletls alg $algRef"
      )
    } finally cleanup(dir)
  }

  test("SSH private-key-placeholder → schema-legal private-key type") {
    val id = "gitoid:blob:sha256:" + "9a" * 32
    val item = makeItem(
      id,
      extra = TreeMap(
        "SSH:MaterialType" -> TreeSet(StringOrPair("private-key-placeholder")),
        "SSH:FilePath" -> TreeSet(StringOrPair("/etc/dropbear/host_key"))
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      val c = byBomRef(json, id).get
      assertEquals(
        materialType(c),
        "private-key",
        "placeholder must map to the schema-legal private-key type"
      )
      assert(
        propValues(c, "SSH:MaterialType").contains("private-key-placeholder"),
        "the raw placeholder value must ride in the SSH:MaterialType property"
      )
    } finally cleanup(dir)
  }

  test("signature OIDs are canonicalized to gallery names (ml-dsa-*)") {
    val id = "gitoid:blob:sha256:" + "bc" * 32
    val item = makeItem(
      id,
      extra = TreeMap(
        "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=t")),
        "Certificates:SigAlgorithm" -> TreeSet(
          StringOrPair(
            "<unknown-sig-oid-2.16.840.1.101.3.4.3.40>"
          )
        )
      )
    )
    val (json, dir) = emitCbom(List(item))
    try {
      val names = components(json).flatMap(c => stringOpt(c, "name"))
      assert(
        names.contains("ml-dsa-65"),
        s"unknown-sig-oid 2.16.840.1.101.3.4.3.40 must canonicalize to ml-dsa-65; got $names"
      )
      assert(
        !names.contains("<unknown-sig-oid-2.16.840.1.101.3.4.3.40>"),
        "the raw signature-OID name must not be emitted"
      )
      val alg = components(json)
        .find(c => stringOpt(c, "name").contains("ml-dsa-65"))
        .get
      assertEquals(getString(alg, "cryptoProperties", "assetType"), "algorithm")
      assertEquals(
        getString(alg, "cryptoProperties", "algorithmProperties", "primitive"),
        "signature"
      )
    } finally cleanup(dir)
  }
}
