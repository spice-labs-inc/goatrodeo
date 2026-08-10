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

import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite
import org.everit.json.schema.ValidationException
import org.json4s.*
import org.json4s.native.JsonMethods.*
import scopt.OParser

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*
import scala.util.Try

class CbomEmitterSuite extends FunSuite {

  private lazy val schema16 = loadEveritSchema("bom-1.6.schema.json")
  private lazy val schema17 = loadEveritSchema("bom-1.7.schema.json")

  private implicit val formats: Formats = DefaultFormats

  /** Read a CycloneDX schema resource as text. */
  private def schemaResource(name: String): String = {
    val is = Option(getClass.getResourceAsStream("/cyclonedx/" + name))
    assert(is.isDefined, s"Schema resource not found: $name")
    new String(is.get.readAllBytes(), StandardCharsets.UTF_8)
  }

  /** Build a JSON-Schema validator from the CycloneDX schema files, serving the
    * external `$ref`s the bom schemas use (`spdx.schema.json`,
    * `jsf-0.82.schema.json`, `cryptography-defs.schema.json`) from the test
    * classpath via a `SchemaClient`, so the schema resolves without a network.
    */
  private def loadEveritSchema(name: String): org.everit.json.schema.Schema = {
    val client = new org.everit.json.schema.loader.SchemaClient {
      override def get(url: String): java.io.InputStream = {
        val base = "http://cyclonedx.org/schema/"
        val resource =
          if (url.startsWith(base)) {
            "/cyclonedx/" + url.stripPrefix(base)
          } else {
            "/cyclonedx/" + url.substring(url.lastIndexOf('/') + 1)
          }
        val is = getClass.getResourceAsStream(resource)
        assert(is != null, s"external schema resource not found: $url")
        is
      }
    }
    org.everit.json.schema.loader.SchemaLoader
      .builder()
      .schemaJson(new org.json.JSONObject(schemaResource(name)))
      .httpClient(client)
      .build()
      .load()
      .build()
  }

  /** Validate CBOM JSON against a CycloneDX schema; returns the violation
    * messages (empty = valid).
    */
  private def validate(
      json: String,
      schema: org.everit.json.schema.Schema
  ): Set[String] = {
    try {
      schema.validate(new org.json.JSONObject(json))
      Set.empty
    } catch {
      case e: ValidationException =>
        e.getAllMessages().asScala.toSet
    }
  }

  private def parseConfig(args: String*): Option[Config] = {
    OParser.parse(Config.parser1, args, Config())
  }

  private def tempDir(): File = {
    Files.createTempDirectory("cbom-test").toFile()
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
    storage.write(item.identifier, _ => Some(item), _ => "test")
    ()
  }

  private def writeAndRead(
      storage: Storage,
      root: Item,
      dir: File,
      version: String = "1.6"
  ): JValue = {
    storeItem(storage, root)
    val files = CbomEmitter.emitForStorage(storage, version, dir).get
    assertEquals(files.size, 1)
    parse(Files.readString(files.head.toPath()))
  }

  private def getString(jv: JValue, path: String*): String = {
    path.foldLeft(jv: JValue)(_ \ _) match {
      case JString(s) => s
      case other =>
        fail(s"Expected string at ${path.mkString("/")}, got $other")
    }
  }

  private def getInt(jv: JValue, path: String*): Int = {
    path.foldLeft(jv: JValue)(_ \ _) match {
      case JInt(n)    => n.toInt
      case JLong(n)   => n.toInt
      case JString(s) => Try(s.toInt).getOrElse(fail(s"Not an integer: $s"))
      case other =>
        fail(s"Expected integer at ${path.mkString("/")}, got $other")
    }
  }

  private def getComponents(json: JValue): List[JValue] = {
    (json \ "components") match { case JArray(arr) => arr; case _ => Nil }
  }

  private def getProperties(json: JValue): List[JValue] = {
    (json \ "properties") match { case JArray(arr) => arr; case _ => Nil }
  }

  private def propertyMap(component: JValue): Map[String, String] = {
    (component \ "properties") match {
      case JArray(arr) =>
        arr
          .collect { case JObject(fields) =>
            val name = fields.find(_._1 == "name").flatMap {
              case (_, JString(s)) => Some(s)
              case _               => None
            }
            val value = fields.find(_._1 == "value").flatMap {
              case (_, JString(s)) => Some(s)
              case _               => None
            }
            name.flatMap(n => value.map(n -> _))
          }
          .flatten
          .toMap
      case _ => Map()
    }
  }

  private def findComponentByRef(json: JValue, ref: String): Option[JValue] = {
    getComponents(json).find { c =>
      (c \ "bom-ref") match {
        case JString(s) => s == ref
        case _          => false
      }
    }
  }

  private def cryptoProperties(component: JValue): JValue = {
    component \ "cryptoProperties"
  }

  // ----------------------------------------------------------------------
  // T3.1 / T3.17 CLI parsing
  // ----------------------------------------------------------------------
  test("T3.1 CLI flags parse correctly") {
    val c1 =
      parseConfig("--emit-cbom-dir", "/tmp/cbom", "--cbom-version", "1.7").get
    assertEquals(c1.cbomDir, Some(new File("/tmp/cbom")))
    assertEquals(c1.cbomVersion, "1.7")

    val c2 = parseConfig("--emit-cbom-dir", "/tmp/cbom").get
    assertEquals(c2.cbomDir, Some(new File("/tmp/cbom")))
    assertEquals(c2.cbomVersion, "1.6")

    val c3 = parseConfig().get
    assertEquals(c3.cbomDir, None)
  }

  test("T3.17 invalid --cbom-version rejected") {
    assert(parseConfig("--cbom-version", "1.5").isEmpty)
    assert(parseConfig("--cbom-version", "2.0").isEmpty)
  }

  // ----------------------------------------------------------------------
  // T3.2 / T3.11 / T3.12 empty CBOM
  // ----------------------------------------------------------------------
  test("T3.2 empty CBOM emitted for root with no crypto material") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val root = makeItem(
        id =
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000",
        fileNames = TreeSet("plain.txt"),
        mimeTypes = TreeSet("text/plain")
      )
      val json = writeAndRead(storage, root, dir)

      assertEquals(getString(json, "bomFormat"), "CycloneDX")
      assertEquals(getString(json, "specVersion"), "1.6")
      assert(getString(json, "serialNumber").startsWith("urn:uuid:"))
      assertEquals(getInt(json, "version"), 1)
      assert((json \ "metadata") != JNothing)

      val components = getComponents(json)
      assertEquals(components.length, 0)

      val jsonStr = compact(render(json))
      assert(validate(jsonStr, schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.3 / T3.13 single-certificate CBOM
  // ----------------------------------------------------------------------
  test("T3.3 single certificate produces a valid CBOM component") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val certId =
        "gitoid:blob:sha256:1111111111111111111111111111111111111111111111111111111111111111"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val cert = makeItem(
        id = certId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("test-cert.pem"),
        mimeTypes = TreeSet("application/x-pem-file"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("test-cert")),
          "Description" -> TreeSet(StringOrPair("X.509 v3 certificate")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=test")),
          "Certificates:IssuerDN" -> TreeSet(StringOrPair("CN=issuer")),
          "Certificates:NotBefore" -> TreeSet(
            StringOrPair("2024-01-01T00:00:00Z")
          ),
          "Certificates:NotAfter" -> TreeSet(
            StringOrPair("2025-01-01T00:00:00Z")
          ),
          "Certificates:KeyAlgorithm" -> TreeSet(StringOrPair("RSA")),
          "Certificates:SigAlgorithm" -> TreeSet(StringOrPair("SHA256withRSA")),
          "Certificates:KeySize" -> TreeSet(StringOrPair("2048"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> certId),
        fileNames = TreeSet("root.jar"),
        mimeTypes = TreeSet("application/java-archive")
      )
      storeItem(storage, cert)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 3)
      val component = components.head

      assertEquals(getString(component, "type"), "cryptographic-asset")
      assertEquals(getString(component, "name"), "test-cert")

      val cp = component \ "cryptoProperties"
      assertEquals(getString(cp, "assetType"), "certificate")
      assertEquals(
        getString(cp, "certificateProperties", "subjectName"),
        "CN=test"
      )
      assertEquals(
        getString(cp, "certificateProperties", "issuerName"),
        "CN=issuer"
      )
      assertEquals(
        getString(cp, "certificateProperties", "certificateFormat"),
        "X.509"
      )
      assertEquals(
        getString(cp, "certificateProperties", "subjectPublicKeyRef"),
        "alg:pke:rsa"
      )
      assertEquals(
        getString(cp, "certificateProperties", "signatureAlgorithmRef"),
        "alg:signature:sha256withrsa"
      )

      val props = propertyMap(component)
      assertEquals(props("Certificates:KeyAlgorithm"), "RSA")
      assertEquals(props("Certificates:SigAlgorithm"), "SHA256withRSA")
      assertEquals(props("Certificates:KeySize"), "2048")

      val keyAlg = findComponentByRef(json, "alg:pke:rsa").get
      assertEquals(getString(keyAlg, "type"), "cryptographic-asset")
      assertEquals(getString(keyAlg, "name"), "RSA")
      assertEquals(
        getString(keyAlg, "cryptoProperties", "assetType"),
        "algorithm"
      )
      assertEquals(
        getString(
          keyAlg,
          "cryptoProperties",
          "algorithmProperties",
          "primitive"
        ),
        "pke"
      )
      assertEquals(
        getInt(
          keyAlg,
          "cryptoProperties",
          "algorithmProperties",
          "parameterSetIdentifier"
        ),
        2048
      )

      val sigAlg = findComponentByRef(json, "alg:signature:sha256withrsa").get
      assertEquals(
        getString(
          sigAlg,
          "cryptoProperties",
          "algorithmProperties",
          "primitive"
        ),
        "signature"
      )

      assert(validate(compact(render(json)), schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.4 OpenSSL config CBOM
  // ----------------------------------------------------------------------
  test("T3.4 OpenSSL config produces a protocol component") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val configId =
        "gitoid:blob:sha256:2222222222222222222222222222222222222222222222222222222222222222"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val config = makeItem(
        id = configId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("openssl.cnf"),
        mimeTypes = TreeSet("application/x-openssl-config"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("openssl.cnf")),
          "openssl.cnf:sections" -> TreeSet(StringOrPair("ssl_conf")),
          "openssl.cnf:cipher_string" -> TreeSet(
            StringOrPair("ECDHE-RSA-AES256-GCM-SHA384")
          ),
          "openssl.cnf:min_protocol" -> TreeSet(StringOrPair("TLSv1.2")),
          "openssl.cnf:max_protocol" -> TreeSet(StringOrPair("TLSv1.3"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> configId),
        fileNames = TreeSet("root.tar"),
        mimeTypes = TreeSet("application/x-tar")
      )
      storeItem(storage, config)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 1)
      val component = components.head

      val cp = component \ "cryptoProperties"
      assertEquals(getString(cp, "assetType"), "protocol")
      assertEquals(getString(cp, "protocolProperties", "type"), "tls")
      assert(
        getString(cp, "protocolProperties", "version").contains("TLSv1.2")
      )
      assert(
        getString(cp, "protocolProperties", "version").contains("TLSv1.3")
      )

      val suites = (cp \ "protocolProperties" \ "cipherSuites") match {
        case JArray(arr) => arr
        case _           => Nil
      }
      assert(suites.nonEmpty)

      val props = propertyMap(component)
      assertEquals(
        props("openssl.cnf:cipher_string"),
        "ECDHE-RSA-AES256-GCM-SHA384"
      )
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.5 Java security CBOM
  // ----------------------------------------------------------------------
  test("T3.5 Java security produces a component with disabled algorithms") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val secId =
        "gitoid:blob:sha256:3333333333333333333333333333333333333333333333333333333333333333"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val sec = makeItem(
        id = secId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("java.security"),
        mimeTypes = TreeSet("application/x-java-security-properties"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("java.security")),
          "java.security:disabled_algorithms" -> TreeSet(
            StringOrPair("MD2, MD5, RSA keySize < 2048")
          ),
          "java.security:named_groups" -> TreeSet(
            StringOrPair("secp256r1, secp384r1")
          )
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> secId),
        fileNames = TreeSet("root.zip"),
        mimeTypes = TreeSet("application/zip")
      )
      storeItem(storage, sec)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 1)
      val component = components.head

      assertEquals(
        getString(component, "cryptoProperties", "assetType"),
        "related-crypto-material"
      )
      val props = propertyMap(component)
      assert(props("java.security:disabled_algorithms").contains("MD2"))
      assert(props("java.security:named_groups").contains("secp256r1"))
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.6 CycloneDX 1.7 emission
  // ----------------------------------------------------------------------
  test("T3.6 --cbom-version 1.7 emits a valid 1.7 CBOM") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val certId =
        "gitoid:blob:sha256:4444444444444444444444444444444444444444444444444444444444444444"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val cert = makeItem(
        id = certId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("cert.pem"),
        mimeTypes = TreeSet("application/x-pem-file"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("cert")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=test")),
          "Certificates:KeyAlgorithm" -> TreeSet(StringOrPair("RSA"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> certId),
        fileNames = TreeSet("root"),
        mimeTypes = TreeSet("application/octet-stream")
      )
      storeItem(storage, cert)
      val json = writeAndRead(storage, root, dir, "1.7")

      assertEquals(getString(json, "specVersion"), "1.7")
      val components = getComponents(json)
      assertEquals(components.length, 2)
      assert(validate(compact(render(json)), schema17).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.7 nested archive traversal
  // ----------------------------------------------------------------------
  test("T3.7 crypto material inside nested archives appears in root CBOM") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val zipId =
        "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      val tarId =
        "gitoid:blob:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      val certId =
        "gitoid:blob:sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> zipId),
        fileNames = TreeSet("outer.zip")
      )
      val zip = makeItem(
        id = zipId,
        connections =
          TreeSet(EdgeType.containedBy -> rootId, EdgeType.contains -> tarId),
        fileNames = TreeSet("inner.tar")
      )
      val tar = makeItem(
        id = tarId,
        connections =
          TreeSet(EdgeType.containedBy -> zipId, EdgeType.contains -> certId),
        fileNames = TreeSet("cert.pem")
      )
      val cert = makeItem(
        id = certId,
        connections = TreeSet(EdgeType.containedBy -> tarId),
        fileNames = TreeSet("cert.pem"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("nested-cert")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=nested"))
        )
      )
      storeItem(storage, zip)
      storeItem(storage, tar)
      storeItem(storage, cert)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 1)
      assertEquals(getString(components.head, "name"), "nested-cert")
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.8 filename stability
  // ----------------------------------------------------------------------
  test("T3.8 CBOM filenames are stable across runs") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val root = makeItem(
        id =
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000",
        fileNames = TreeSet("root")
      )
      storeItem(storage, root)

      val run1 = CbomEmitter
        .emitForStorage(storage, "1.6", dir)
        .get
        .map(_.getName())
        .toSet
      val run2 = CbomEmitter
        .emitForStorage(storage, "1.6", dir)
        .get
        .map(_.getName())
        .toSet
      assertEquals(run1, run2)
      assertEquals(run1.size, 1)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.10 I/O failure handling
  // ----------------------------------------------------------------------
  test("T3.10 CBOM write failure is captured in Try") {
    val dir = tempDir()
    try {
      dir.setReadOnly()
      val storage = MemStorage(None)
      val root = makeItem(
        id =
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000",
        fileNames = TreeSet("root")
      )
      storeItem(storage, root)
      val result = CbomEmitter.emitForStorage(storage, "1.6", dir)
      assert(result.isFailure)
    } finally {
      dir.setWritable(true)
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.14 multi-root CBOM
  // ----------------------------------------------------------------------
  test("T3.14 two roots produce two CBOM files") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val root1 = makeItem(
        id =
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000001",
        fileNames = TreeSet("root1")
      )
      val root2 = makeItem(
        id =
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000002",
        fileNames = TreeSet("root2")
      )
      storeItem(storage, root1)
      storeItem(storage, root2)

      val files = CbomEmitter.emitForStorage(storage, "1.6", dir).get
      assertEquals(files.length, 2)
      assertEquals(files.map(_.getName()).toSet.size, 2)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.15 cyclic contains graph
  // ----------------------------------------------------------------------
  test("T3.15 cyclic contains graph does not hang the emitter") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val a =
        "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      val b =
        "gitoid:blob:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      val c =
        "gitoid:blob:sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

      val itemA = makeItem(
        id = a,
        connections = TreeSet(EdgeType.contains -> b),
        fileNames = TreeSet("A"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("A")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=A"))
        )
      )
      val itemB = makeItem(
        id = b,
        connections =
          TreeSet(EdgeType.containedBy -> a, EdgeType.contains -> c),
        fileNames = TreeSet("B")
      )
      val itemC = makeItem(
        id = c,
        connections =
          TreeSet(EdgeType.containedBy -> b, EdgeType.contains -> a),
        fileNames = TreeSet("C")
      )
      storeItem(storage, itemA)
      storeItem(storage, itemB)
      storeItem(storage, itemC)

      val json = writeAndRead(storage, itemA, dir)
      val components = getComponents(json)
      assertEquals(components.length, 1)
      assert(validate(compact(render(json)), schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.16 duplicate GitOID component
  // ----------------------------------------------------------------------
  test("T3.16 duplicate GitOID reached via multiple paths appears once") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val certId =
        "gitoid:blob:sha256:1111111111111111111111111111111111111111111111111111111111111111"
      val viaA =
        "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      val viaB =
        "gitoid:blob:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

      val root = makeItem(
        id = rootId,
        connections =
          TreeSet(EdgeType.contains -> viaA, EdgeType.contains -> viaB),
        fileNames = TreeSet("root")
      )
      val a = makeItem(
        id = viaA,
        connections =
          TreeSet(EdgeType.containedBy -> rootId, EdgeType.contains -> certId),
        fileNames = TreeSet("A")
      )
      val b = makeItem(
        id = viaB,
        connections =
          TreeSet(EdgeType.containedBy -> rootId, EdgeType.contains -> certId),
        fileNames = TreeSet("B")
      )
      val cert = makeItem(
        id = certId,
        connections = TreeSet(EdgeType.containedBy -> viaA),
        fileNames = TreeSet("cert.pem"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("shared-cert")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=shared"))
        )
      )
      storeItem(storage, a)
      storeItem(storage, b)
      storeItem(storage, cert)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 1)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.18 output directory auto-creation
  // ----------------------------------------------------------------------
  test("T3.18 non-existent CBOM output directory is created") {
    val parent = Files.createTempDirectory("cbom-parent").toFile()
    val dir = new File(new File(parent, "nested"), "output")
    try {
      val storage = MemStorage(None)
      val root = makeItem(
        id =
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000",
        fileNames = TreeSet("root")
      )
      storeItem(storage, root)
      val files = CbomEmitter.emitForStorage(storage, "1.6", dir).get
      assert(dir.isDirectory())
      assertEquals(files.length, 1)
    } finally {
      cleanup(parent)
    }
  }

  // ----------------------------------------------------------------------
  // T3.19 private-key-marker Items are emitted faithfully (no redaction)
  // ----------------------------------------------------------------------
  test("T3.19 private-key-marker Items are emitted faithfully") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val certId =
        "gitoid:blob:sha256:1111111111111111111111111111111111111111111111111111111111111111"
      val keyId =
        "gitoid:blob:sha256:2222222222222222222222222222222222222222222222222222222222222222"

      val cert = makeItem(
        id = certId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("cert.pem"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("cert")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=test"))
        )
      )
      val key = makeItem(
        id = keyId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("key.pem"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("key.pem")),
          "Description" -> TreeSet(
            StringOrPair("Unencrypted private key (public key derived)")
          ),
          "Certificates:DerivedFromPrivateKey" -> TreeSet(StringOrPair("true"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections =
          TreeSet(EdgeType.contains -> certId, EdgeType.contains -> keyId),
        fileNames = TreeSet("root")
      )
      storeItem(storage, cert)
      storeItem(storage, key)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 2)
      val byName = components
        .groupBy(getString(_, "name"))
        .view
        .mapValues(_.head)
        .toMap
      assertEquals(getString(byName("cert"), "type"), "cryptographic-asset")
      assertEquals(
        getString(
          byName("key.pem"),
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "type"
        ),
        "public-key"
      )
      assert(
        propertyMap(byName("key.pem"))
          .contains("Certificates:DerivedFromPrivateKey"),
        "private-key marker must surface as a property"
      )
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.20 CBOM size-limit
  // ----------------------------------------------------------------------
  test("T3.20 oversized CBOM is truncated to 100,000 components") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val root = makeItem(
        id = rootId,
        connections = TreeSet(),
        fileNames = TreeSet("root")
      )
      storeItem(storage, root)

      val ids = (0 until 100001).map { idx =>
        val id =
          f"gitoid:blob:sha256:111111111111111111111111111111111111111111111111111111111111${idx}%05d"
        val item = makeItem(
          id = id,
          connections = TreeSet(EdgeType.containedBy -> rootId),
          fileNames = TreeSet(s"cert$idx.pem"),
          extra = TreeMap(
            "Name" -> TreeSet(StringOrPair(s"cert$idx")),
            "Certificates:SubjectDN" -> TreeSet(StringOrPair(s"CN=cert$idx"))
          )
        )
        storeItem(storage, item)
        id
      }
      val rootWithChildren =
        root.copy(connections = TreeSet(ids.map(EdgeType.contains -> _)*))
      storeItem(storage, rootWithChildren)

      val json = writeAndRead(storage, rootWithChildren, dir)
      val components = getComponents(json)
      assert(components.length <= 100000)
      val props = getProperties(json)
      assert(props.exists {
        case JObject(fields) =>
          fields.find(_._1 == "name").exists {
            case (_, JString("cbom:truncated")) => true
            case _                              => false
          }
        case _ => false
      })
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.21 symlink rejection
  // ----------------------------------------------------------------------
  test("T3.21 symlink in CBOM output path is rejected") {
    val parent = Files.createTempDirectory("cbom-symlink").toFile()
    val realDir = new File(parent, "real")
    val linkDir = new File(parent, "link")
    try {
      realDir.mkdirs()
      Files.createSymbolicLink(linkDir.toPath(), realDir.toPath())
      val storage = MemStorage(None)
      val root = makeItem(
        id =
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000",
        fileNames = TreeSet("root")
      )
      storeItem(storage, root)
      val result = CbomEmitter.emitForStorage(storage, "1.6", linkDir)
      assert(result.isFailure)
    } finally {
      cleanup(parent)
    }
  }

  // ----------------------------------------------------------------------
  // T3.22 atomic write and no leftover temp files
  // ----------------------------------------------------------------------
  test("T3.22 CBOM write is atomic and leaves no temp files") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val root = makeItem(
        id =
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000",
        fileNames = TreeSet("root")
      )
      storeItem(storage, root)
      val files = CbomEmitter.emitForStorage(storage, "1.6", dir).get
      assertEquals(files.length, 1)
      assert(files.head.exists())
      val tempFiles = dir.listFiles().filter(_.getName().endsWith(".tmp"))
      assertEquals(tempFiles.length, 0)

      import java.nio.file.attribute.PosixFilePermission
      val perms =
        Try(Files.getPosixFilePermissions(files.head.toPath())).toOption
      perms.foreach { p =>
        assert(!p.contains(PosixFilePermission.OWNER_EXECUTE))
        assert(!p.contains(PosixFilePermission.GROUP_EXECUTE))
        assert(!p.contains(PosixFilePermission.OTHERS_EXECUTE))
        assert(!p.contains(PosixFilePermission.OTHERS_READ))
        assert(!p.contains(PosixFilePermission.OTHERS_WRITE))
      }
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.23 related-crypto-material public key references its algorithm
  // ----------------------------------------------------------------------
  test("T3.23 public key material emits algorithmRef and size") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val keyId =
        "gitoid:blob:sha256:5555555555555555555555555555555555555555555555555555555555555555"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val key = makeItem(
        id = keyId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("test-key.pub"),
        mimeTypes = TreeSet("application/x-openssh-public-key"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("test-key")),
          "Certificates:KeyAlgorithm" -> TreeSet(StringOrPair("ed25519")),
          "Certificates:KeySize" -> TreeSet(StringOrPair("256"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> keyId),
        fileNames = TreeSet("root.tar"),
        mimeTypes = TreeSet("application/x-tar")
      )
      storeItem(storage, key)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 2)
      val keyComponent = components.find { c =>
        (c \ "cryptoProperties" \ "assetType") == JString(
          "related-crypto-material"
        )
      }.get
      assertEquals(
        getString(
          keyComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "type"
        ),
        "public-key"
      )
      assertEquals(
        getString(
          keyComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "algorithmRef"
        ),
        "alg:pke:ed25519"
      )
      assertEquals(
        getInt(
          keyComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "size"
        ),
        256
      )

      val alg = findComponentByRef(json, "alg:pke:ed25519").get
      assertEquals(getString(alg, "name"), "ed25519")
      assertEquals(
        getString(alg, "cryptoProperties", "algorithmProperties", "primitive"),
        "pke"
      )
      assertEquals(
        getInt(
          alg,
          "cryptoProperties",
          "algorithmProperties",
          "parameterSetIdentifier"
        ),
        256
      )
      assert(validate(compact(render(json)), schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.24 CRL references its signature algorithm
  // ----------------------------------------------------------------------
  test("T3.24 CRL emits signature algorithmRef and algorithm component") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val crlId =
        "gitoid:blob:sha256:6666666666666666666666666666666666666666666666666666666666666666"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val crl = makeItem(
        id = crlId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("test.crl"),
        mimeTypes = TreeSet("application/pkix-crl"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("test")),
          "Certificates:CrlSha256" -> TreeSet(StringOrPair("abc123")),
          "Certificates:SigAlgorithm" -> TreeSet(StringOrPair("SHA256withRSA")),
          "Certificates:IssuerDN" -> TreeSet(StringOrPair("CN=issuer"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> crlId),
        fileNames = TreeSet("root.zip"),
        mimeTypes = TreeSet("application/zip")
      )
      storeItem(storage, crl)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 2)
      val crlComponent = components.find { c =>
        (c \ "cryptoProperties" \ "assetType") == JString(
          "related-crypto-material"
        )
      }.get
      assertEquals(
        getString(
          crlComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "algorithmRef"
        ),
        "alg:signature:sha256withrsa"
      )

      val alg = findComponentByRef(json, "alg:signature:sha256withrsa").get
      assertEquals(
        getString(alg, "cryptoProperties", "algorithmProperties", "primitive"),
        "signature"
      )
      assert(validate(compact(render(json)), schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.25 certificate with EC public key exposes curve in algorithm component
  // ----------------------------------------------------------------------
  test("T3.25 EC certificate promotes curve into algorithmProperties") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val certId =
        "gitoid:blob:sha256:7777777777777777777777777777777777777777777777777777777777777777"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val cert = makeItem(
        id = certId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("ec-cert.pem"),
        mimeTypes = TreeSet("application/x-pem-file"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("ec-cert")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=test")),
          "Certificates:KeyAlgorithm" -> TreeSet(StringOrPair("ec")),
          "Certificates:Curve" -> TreeSet(StringOrPair("p-256")),
          "Certificates:SigAlgorithm" -> TreeSet(
            StringOrPair("ECDSAwithSHA256")
          )
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> certId),
        fileNames = TreeSet("root.jar"),
        mimeTypes = TreeSet("application/java-archive")
      )
      storeItem(storage, cert)
      val json = writeAndRead(storage, root, dir)

      val alg = findComponentByRef(json, "alg:pke:ec").get
      assertEquals(
        getString(alg, "cryptoProperties", "algorithmProperties", "primitive"),
        "pke"
      )
      assertEquals(
        getString(alg, "cryptoProperties", "algorithmProperties", "curve"),
        "p-256"
      )
      assertEquals(
        getString(
          alg,
          "cryptoProperties",
          "algorithmProperties",
          "parameterSetIdentifier"
        ),
        "p-256"
      )
      assert(validate(compact(render(json)), schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.26 password hash file references hash algorithm component
  // ----------------------------------------------------------------------
  test("T3.26 password hash file emits algorithmRef for hash family") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val hashId =
        "gitoid:blob:sha256:8888888888888888888888888888888888888888888888888888888888888888"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val hashFile = makeItem(
        id = hashId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("shadow"),
        mimeTypes = TreeSet("text/plain"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("shadow")),
          "PasswordHash:Algorithm" -> TreeSet(StringOrPair("bcrypt"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> hashId),
        fileNames = TreeSet("root.tar"),
        mimeTypes = TreeSet("application/x-tar")
      )
      storeItem(storage, hashFile)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 2)
      val hashComponent = components.find { c =>
        (c \ "cryptoProperties" \ "assetType") == JString(
          "related-crypto-material"
        )
      }.get
      assertEquals(
        getString(
          hashComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "type"
        ),
        "password"
      )
      assertEquals(
        getString(
          hashComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "algorithmRef"
        ),
        "alg:hash:bcrypt"
      )

      val alg = findComponentByRef(json, "alg:hash:bcrypt").get
      assertEquals(
        getString(alg, "cryptoProperties", "algorithmProperties", "primitive"),
        "hash"
      )
      assert(validate(compact(render(json)), schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.27 usign key emits ed25519 algorithmRef and key size
  // ----------------------------------------------------------------------
  test("T3.27 usign key emits ed25519 algorithmRef and size") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val keyId =
        "gitoid:blob:sha256:9999999999999999999999999999999999999999999999999999999999999999"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val key = makeItem(
        id = keyId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("1035ac73cc4e59e3"),
        mimeTypes = TreeSet("text/plain"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("1035ac73cc4e59e3")),
          "Usign:KeyAlgorithm" -> TreeSet(StringOrPair("ed25519")),
          "Usign:KeySize" -> TreeSet(StringOrPair("256"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> keyId),
        fileNames = TreeSet("root.tar"),
        mimeTypes = TreeSet("application/x-tar")
      )
      storeItem(storage, key)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 2)
      val keyComponent = components.find { c =>
        (c \ "cryptoProperties" \ "assetType") == JString(
          "related-crypto-material"
        )
      }.get
      assertEquals(
        getString(
          keyComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "type"
        ),
        "public-key"
      )
      assertEquals(
        getString(
          keyComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "algorithmRef"
        ),
        "alg:pke:ed25519"
      )
      assertEquals(
        getInt(
          keyComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "size"
        ),
        256
      )

      val alg = findComponentByRef(json, "alg:pke:ed25519").get
      assertEquals(
        getString(alg, "cryptoProperties", "algorithmProperties", "primitive"),
        "pke"
      )
      assertEquals(
        getInt(
          alg,
          "cryptoProperties",
          "algorithmProperties",
          "parameterSetIdentifier"
        ),
        256
      )
      assert(validate(compact(render(json)), schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.28 md5 password hash emits hash algorithmRef
  // ----------------------------------------------------------------------
  test("T3.28 md5 password hash emits hash algorithmRef") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val hashId =
        "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

      val hashFile = makeItem(
        id = hashId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("shadow"),
        mimeTypes = TreeSet("text/plain"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("shadow")),
          "PasswordHash:Algorithm" -> TreeSet(StringOrPair("md5")),
          "PasswordHash:Salt" -> TreeSet(StringOrPair("salt123"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> hashId),
        fileNames = TreeSet("root.tar"),
        mimeTypes = TreeSet("application/x-tar")
      )
      storeItem(storage, hashFile)
      val json = writeAndRead(storage, root, dir)

      val components = getComponents(json)
      assertEquals(components.length, 2)
      val hashComponent = components.find { c =>
        (c \ "cryptoProperties" \ "assetType") == JString(
          "related-crypto-material"
        )
      }.get
      assertEquals(
        getString(
          hashComponent,
          "cryptoProperties",
          "relatedCryptoMaterialProperties",
          "algorithmRef"
        ),
        "alg:hash:md5"
      )

      val alg = findComponentByRef(json, "alg:hash:md5").get
      assertEquals(
        getString(alg, "cryptoProperties", "algorithmProperties", "primitive"),
        "hash"
      )
      assert(validate(compact(render(json)), schema16).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.9 no CBOM without flag
  // ----------------------------------------------------------------------
  test("T3.9 no CBOM files are written without --emit-cbom-dir") {
    val inputDir = Files.createTempDirectory("cbom-input").toFile()
    val outputDir = Files.createTempDirectory("cbom-output").toFile()
    try {
      val certSource = new File(
        "test_data/certificates/pem-bundles/synthetic/goatrodeo-test-chain.pem"
      )
      val certDest = new File(inputDir, "cert.pem")
      Files.copy(certSource.toPath(), certDest.toPath())

      var finished = false
      Builder.buildDB(
        dest = outputDir,
        threadCnt = 1,
        blockList = None,
        maxRecords = 10000,
        tag = None,
        tempDir = None,
        args = Config(),
        fileListers = Vector((inputDir, () => Helpers.findFiles(inputDir))),
        ignorePathSet = Set(),
        excludeFileRegex = Vector(),
        finishedFile = _ => (),
        done = b => { finished = b; () },
        preWriteDB = Vector(),
        fsFilePaths = false
      )
      assert(finished)

      val jsonFiles =
        Helpers.findFiles(outputDir).filter(_.getName().endsWith(".json"))
      assertEquals(jsonFiles.length, 0)
    } finally {
      cleanup(inputDir)
      cleanup(outputDir)
    }
  }
}
