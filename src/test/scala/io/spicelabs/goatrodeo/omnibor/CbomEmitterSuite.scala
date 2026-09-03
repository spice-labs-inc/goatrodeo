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

import _root_.strategies.CertificatesPipelineRunner
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.ConfigurationParser
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite
import org.everit.json.schema.ValidationException
import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*
import scala.util.Try

class CbomEmitterSuite extends FunSuite {

  // T3.20 emits a 100,000-component CBOM; its runtime is workload- and
  // CPU-speed-bound, not correctness-bound, so the default 30s wall-clock cap
  // is not a meaningful gate on slower machines. A generous suite-wide
  // timeout is the safety net against hangs, not the acceptance criterion.
  override val munitTimeout =
    scala.concurrent.duration.Duration(5, "minutes")

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
      .schemaClient(client)
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

  private def parseConfig(args: String*): Option[Configuration] = {
    ConfigurationParser.parse(args.toArray)
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
        tag = None,
        fileListers = Vector((inputDir, () => Helpers.findFiles(inputDir))),
        ignorePathSet = Set(),
        excludeFileRegex = Vector(),
        finishedFile = _ => (),
        done = b => { finished = b; () },
        preWriteDB = Vector()
      )(using
        Configuration(
          threads = 1,
          blockList = None,
          maxRecords = 10000,
          tempDir = None,
          fsFilePaths = false
        )
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

  // ----------------------------------------------------------------------
  // Phase H — expanded hashing coverage (T3.29 .. T3.34)
  // ----------------------------------------------------------------------

  /** Build a crypto Item of the `CryptoAlgorithms:` family. */
  private def algoItem(id: String, algs: String*): Item =
    makeItem(
      id = id,
      connections = TreeSet(
        EdgeType.containedBy ->
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      ),
      fileNames = TreeSet("binary.so"),
      mimeTypes = TreeSet("application/x-sharedlib"),
      extra = TreeMap(
        "Name" -> TreeSet(StringOrPair("binary")),
        "CryptoAlgorithms:algorithm" -> TreeSet.from(
          algs.map(StringOrPair(_))
        )
      )
    )

  test("T3.29 new hash names classify as hash and validate in 1.6 and 1.7") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val item = algoItem(
        "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "sm3",
        "blake3",
        "sha3-384"
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> item.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, item)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          Vector("sm3", "blake3", "sha3-384").foreach { n =>
            val alg = findComponentByRef(json, s"alg:hash:$n").get
            assertEquals(
              getString(
                alg,
                "cryptoProperties",
                "algorithmProperties",
                "primitive"
              ),
              "hash"
            )
          }
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  test("T3.30 parameterSetIdentifier correctness for new hash names") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val item = algoItem(
        "gitoid:blob:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "sha512-224",
        "blake2b-512",
        "sha3-256",
        "argon2id"
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> item.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, item)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val alg224 = findComponentByRef(json, "alg:hash:sha512-224").get
          assertEquals(
            getString(
              alg224,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "224"
          )
          val alg512 = findComponentByRef(json, "alg:hash:blake2b-512").get
          assertEquals(
            getString(
              alg512,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "512"
          )
          val alg3 = findComponentByRef(json, "alg:hash:sha3-256").get
          assertEquals(
            getString(
              alg3,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "256"
          )
          val argon2 = findComponentByRef(json, "alg:hash:argon2id").get
          assertEquals(
            argon2 \ "cryptoProperties" \ "algorithmProperties" \ "parameterSetIdentifier",
            JNothing
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  test("T3.31 PasswordHash argon2id/nt-hash/apr1 flow into hash assets") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val shadow = makeItem(
        id =
          "gitoid:blob:sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("etc/shadow"),
        mimeTypes = TreeSet("text/plain"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("shadow")),
          "PasswordHash:Algorithm" -> TreeSet(
            StringOrPair("argon2id"),
            StringOrPair("nt-hash"),
            StringOrPair("apr1")
          ),
          "PasswordHash:Params" -> TreeSet(
            StringOrPair("m=65536,t=3,p=4")
          ),
          "PasswordHash:Salt" -> TreeSet(StringOrPair("Jh7M.9rR"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> shadow.identifier),
        fileNames = TreeSet("root.tar")
      )
      storeItem(storage, shadow)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          Vector("argon2id", "nt-hash", "apr1").foreach { n =>
            val alg = findComponentByRef(json, s"alg:hash:$n").get
            assertEquals(
              getString(
                alg,
                "cryptoProperties",
                "algorithmProperties",
                "primitive"
              ),
              "hash"
            )
          }
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  test("T3.32 ServiceCrypto blake2b/sha3 algorithms classify as hash assets") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val svc = makeItem(
        id =
          "gitoid:blob:sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("etc/ipsec.conf"),
        mimeTypes = TreeSet("text/plain"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("ipsec.conf")),
          "ServiceCrypto:service" -> TreeSet(StringOrPair("strongswan")),
          "ServiceCrypto:algorithms" -> TreeSet(
            StringOrPair("blake2b-512"),
            StringOrPair("sha3-256")
          )
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> svc.identifier),
        fileNames = TreeSet("root.tar")
      )
      storeItem(storage, svc)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val alg512 = findComponentByRef(json, "alg:hash:blake2b-512").get
          assertEquals(
            getString(
              alg512,
              "cryptoProperties",
              "algorithmProperties",
              "primitive"
            ),
            "hash"
          )
          assertEquals(
            getString(
              alg512,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "512"
          )
          val alg3 = findComponentByRef(json, "alg:hash:sha3-256").get
          assertEquals(
            getString(
              alg3,
              "cryptoProperties",
              "algorithmProperties",
              "primitive"
            ),
            "hash"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.33 golden byte-identity for pre-existing fixtures
  // ----------------------------------------------------------------------

  private val GoldenRootId =
    "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"

  private def goldenChild(
      id: String,
      name: String,
      extra: (String, Set[String])*
  ): Item =
    makeItem(
      id = id,
      connections = TreeSet(EdgeType.containedBy -> GoldenRootId),
      fileNames = TreeSet(name),
      mimeTypes = TreeSet("application/octet-stream"),
      extra = TreeMap.from(
        (("Name" -> Set(name)) +: extra.toVector).map { case (k, vs) =>
          k -> TreeSet.from(vs.map(StringOrPair(_)))
        }
      )
    )

  /** Fixed battery of pre-existing metadata families. Deliberately excludes the
    * approved §13 deltas (JWT context, sha3-256/512 and argon2 parameters,
    * `sha-3`, `EVP_sha512_224/256` dual emission) so the golden files pin
    * byte-identical output for everything else.
    */
  private def goldenStorage(): Storage = {
    val storage = MemStorage(None)
    val root = makeItem(
      id = GoldenRootId,
      connections = TreeSet(
        EdgeType.contains ->
          "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000001"
      ),
      fileNames = TreeSet("root")
    )
    storeItem(storage, root)

    val children = Vector(
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000001",
        "cert.pem",
        "Certificates:SubjectDN" -> Set("CN=test"),
        "Certificates:IssuerDN" -> Set("CN=issuer"),
        "Certificates:NotBefore" -> Set("2024-01-01T00:00:00Z"),
        "Certificates:NotAfter" -> Set("2025-01-01T00:00:00Z"),
        "Certificates:KeyAlgorithm" -> Set("RSA"),
        "Certificates:SigAlgorithm" -> Set("SHA256withRSA"),
        "Certificates:KeySize" -> Set("2048")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000002",
        "openssl.cnf",
        "openssl.cnf:cipher_string" -> Set("ECDHE-RSA-AES256-GCM-SHA384"),
        "openssl.cnf:min_protocol" -> Set("TLSv1.2"),
        "openssl.cnf:max_protocol" -> Set("TLSv1.3")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000003",
        "java.security",
        "java.security:disabled_algorithms" -> Set(
          "MD2, MD5, RSA keySize < 2048"
        )
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000004",
        "etc/shadow",
        "PasswordHash:Algorithm" -> Set("md5", "bcrypt"),
        "PasswordHash:Salt" -> Set("salt123")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000005",
        "usign.key",
        "Usign:KeyAlgorithm" -> Set("ed25519"),
        "Usign:KeySize" -> Set("256")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000006",
        "id_ed25519.pub",
        "SSH:MaterialType" -> Set("public-key"),
        "SSH:KeyAlgorithm" -> Set("ssh-ed25519"),
        "SSH:KeySize" -> Set("256")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000007",
        "nginx.conf",
        "TLSConfig:algorithms" -> Set("aes-128-gcm")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000008",
        "libmbedtls.so",
        "EmbeddedCertificates:count" -> Set("1"),
        "Certificates:SubjectDN" -> Set("CN=embedded")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000009",
        "inline.key",
        "EmbeddedKey:kind" -> Set("public-key"),
        "EmbeddedKey:key_algorithm" -> Set("rsa"),
        "EmbeddedKey:key_size" -> Set("2048")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000010",
        "ipsec.conf",
        "ServiceCrypto:service" -> Set("strongswan"),
        "ServiceCrypto:algorithms" -> Set("aes-128-gcm", "chacha20-poly1305")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000011",
        "krb5.conf",
        "Kerberos:algorithms" -> Set("aes256-cts-hmac-sha1-96")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000012",
        "jwk.json",
        "JWK:kty" -> Set("RSA"),
        "JWK:use" -> Set("sig"),
        "JWK:size" -> Set("2048")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000013",
        "binary.so",
        "CryptoAlgorithms:algorithm" -> Set(
          "aes-256-gcm",
          "sha-256",
          "curve25519"
        ),
        "CryptoAlgorithms:classifier" -> Set("evp"),
        "CryptoAlgorithms:confidence" -> Set("symbol")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000014",
        "Cargo.lock",
        "CryptoDependency:name" -> Set("ring"),
        "CryptoDependency:version" -> Set("0.17.14"),
        "CryptoDependency:algorithms" -> Set("aead", "signature")
      ),
      goldenChild(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000015",
        "network_security_config.xml",
        "MobileTls:algorithms" -> Set("aes-256-gcm")
      )
    )
    children.foreach(storeItem(storage, _))
    storage
  }

  /** Normalize the two build-dependent fields (emission timestamp, tool version
    * from BuildInfo) so goldens stay stable across builds.
    */
  private def normalizeTimestamp(jsonStr: String): String = {
    val noTs =
      jsonStr.replaceAll(
        "\"timestamp\"\\s*:\\s*\"[^\"]*\"",
        "\"timestamp\":\"<fixed>\""
      )
    noTs.replaceAll(
      "\"name\":\"goatrodeo\",\"version\":\"[^\"]*\"",
      "\"name\":\"goatrodeo\",\"version\":\"<fixed>\""
    )
  }

  private def goldenResourcePath(version: String): String =
    s"/cbom-golden/cbom-golden-$version.json"

  test("T3.33 pre-existing fixture families are byte-identical (golden)") {
    val dir = tempDir()
    try {
      val storage = goldenStorage()
      val capture = sys.env.get("CAPTURE_CBOM_GOLDEN").contains("1")
      Vector("1.6", "1.7").foreach { version =>
        val files = CbomEmitter.emitForStorage(storage, version, dir).get
        assertEquals(files.length, 1)
        val normalized =
          normalizeTimestamp(Files.readString(files.head.toPath()))
        val resource = goldenResourcePath(version)
        if (capture) {
          val out = new File(s"src/test/resources$resource")
          out.getParentFile().mkdirs()
          Files.writeString(out.toPath(), normalized)
          println(s"CAPTURED $resource")
        } else {
          val is = Option(getClass.getResourceAsStream(resource))
          assert(is.isDefined, s"golden resource missing: $resource")
          val expected =
            new String(is.get.readAllBytes(), StandardCharsets.UTF_8)
          assertEquals(
            normalized,
            expected,
            s"CBOM $version output diverged from the pre-phase golden"
          )
        }
      }
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.34 hostile JWT alg must not mint a hash asset
  // ----------------------------------------------------------------------
  test("T3.34 crafted JWT alg never mints a hash asset") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val jwt = makeItem(
        id =
          "gitoid:blob:sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("token.txt"),
        mimeTypes = TreeSet("text/plain"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("jwt")),
          "JWT:alg" -> TreeSet(StringOrPair("md4")),
          "JWT:signature_algorithm" -> TreeSet(StringOrPair("md4"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> jwt.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, jwt)

      val json = writeAndRead(storage, root, dir)
      assert(
        findComponentByRef(json, "alg:hash:md4").isEmpty,
        "attacker-controlled JWT alg must not mint a hash asset"
      )
      val sig = findComponentByRef(json, "alg:signature:md4")
      assert(sig.isDefined, "JWT alg is emitted with the signature context")
      assertEquals(
        getString(
          sig.get,
          "cryptoProperties",
          "algorithmProperties",
          "primitive"
        ),
        "signature"
      )
      assert(validate(compact(render(json)), schema16).isEmpty)
      assert(validate(compact(render(json)), schema17).isEmpty)
    } finally {
      cleanup(dir)
    }
  }

  // ----------------------------------------------------------------------
  // T3.35..T3.37 SWHID identifiers on artifact-backed components
  // ----------------------------------------------------------------------

  private val SwhidRootId =
    "gitoid:blob:sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

  /** An artifact-backed cert item; `sha1Alias` controls the `alias:from`
    * `gitoid:blob:sha1:` edge.
    */
  private def swhidCert(
      certId: String,
      sha1Alias: Option[String]
  ): Item = {
    val aliasEdges: Vector[(String, String)] = sha1Alias.toVector.map(
      EdgeType.aliasFrom -> _
    )
    makeItem(
      id = certId,
      connections = TreeSet(
        (EdgeType.containedBy -> SwhidRootId) +: aliasEdges*
      ),
      fileNames = TreeSet("cert.pem"),
      mimeTypes = TreeSet("application/x-pem-file"),
      extra = TreeMap(
        "Name" -> TreeSet(StringOrPair("cert")),
        "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=test"))
      )
    )
  }

  // T3.35 — the component's bom-ref is the sha256 GitOID, and the SWHID
  // (`swh:1:cnt:<sha1>`) is emitted as the `swhid:core` property derived
  // from the item's `alias:from` `gitoid:blob:sha1:<hex>` edge. `swhid:core`
  // is always paired with `omnibor:core` (the item's own `gitoid:blob:sha256`
  // OmniBOR id). THEORY: the SWHID content identifier is the same sha1 bytes
  // with a different prefix, so no extra hashing is needed — the pass only
  // translates the identifier the Item already carries. Output must stay
  // valid against CycloneDX 1.6 and 1.7.
  test("T3.35 artifact-backed component carries its SWHID and OmniBOR core") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val sha1hex = "4b71d999259c4f7b593a13df83c4f5d3bbf760a0"
      val certId =
        "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      val cert = swhidCert(certId, Some(s"gitoid:blob:sha1:${sha1hex}"))
      val root = makeItem(
        id = SwhidRootId,
        connections = TreeSet(EdgeType.contains -> cert.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, cert)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val comp = findComponentByRef(json, certId).get
          assertEquals(getString(comp, "bom-ref"), certId)
          assertEquals(
            propertyMap(comp).get("swhid:core"),
            Some(s"swh:1:cnt:${sha1hex}")
          )
          assertEquals(
            propertyMap(comp).get("omnibor:core"),
            Some(certId)
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.44 — `swhid:core` and `omnibor:core` are always emitted together, and
  // `swhid:core` always equals the final (leaf) node of `goatrodeo:swhid-path`
  // while `omnibor:core` equals the final node of `goatrodeo:omnibor-path`.
  // THEORY: the core identifiers must describe the item itself (never a third,
  // unrelated id), so each core must correspond to the item's own node in the
  // traversal path.
  test("T3.44 swhid:core/omnibor:core pair always agree with the path leaf") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val sha1hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b"
      val certId =
        "gitoid:blob:sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
      val cert = swhidCert(certId, Some(s"gitoid:blob:sha1:${sha1hex}"))
      val root = makeItem(
        id = SwhidRootId,
        connections = TreeSet(EdgeType.contains -> cert.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, cert)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val comp = findComponentByRef(json, certId).get
          val pm = propertyMap(comp)
          assert(pm.contains("swhid:core"), "swhid:core must be present")
          assert(pm.contains("omnibor:core"), "omnibor:core must be present")
          val swhidPath =
            pm.get("goatrodeo:swhid-path").get.split("\\|:\\|").toList
          val omniPath =
            pm.get("goatrodeo:omnibor-path").get.split("\\|:\\|").toList
          assertEquals(pm.get("swhid:core").get, swhidPath.last)
          assertEquals(pm.get("omnibor:core").get, omniPath.last)
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.36 — an item with no `gitoid:blob:sha1:` alias emits no `swhid:core`
  // property and stays schema-valid. THEORY: the property must be optional;
  // items built before the alias was captured (or without it) must not gain
  // a fabricated identifier.
  test("T3.36 no SWHID property without a sha1 alias") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val certId =
        "gitoid:blob:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      val cert = swhidCert(certId, None)
      val root = makeItem(
        id = SwhidRootId,
        connections = TreeSet(EdgeType.contains -> cert.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, cert)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val comp = findComponentByRef(json, certId).get
          assert(propertyMap(comp).get("swhid:core").isEmpty)
          assert(propertyMap(comp).get("omnibor:core").isEmpty)
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.37 — malformed sha1 aliases (non-hex, wrong length, uppercase) are
  // ignored rather than emitted as bogus SWHIDs. THEORY: alias values come
  // from a trusted internal pass, but the emitter must not mint an invalid
  // `swh:1:cnt:` identifier if a bad value ever arrives.
  test("T3.37 malformed sha1 aliases are ignored") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val certId =
        "gitoid:blob:sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
      val badAliases = Vector(
        "gitoid:blob:sha1:not-a-hex",
        "gitoid:blob:sha1:4B71D999259C4F7B593A13DF83C4F5D3BBF760A0",
        "gitoid:blob:sha1:abc123"
      )
      val cert = makeItem(
        id = certId,
        connections = TreeSet(
          (EdgeType.containedBy -> SwhidRootId) +:
            badAliases.map(EdgeType.aliasFrom -> _)*
        ),
        fileNames = TreeSet("cert.pem"),
        mimeTypes = TreeSet("application/x-pem-file"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("cert")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=test"))
        )
      )
      val root = makeItem(
        id = SwhidRootId,
        connections = TreeSet(EdgeType.contains -> cert.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, cert)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val comp = findComponentByRef(json, certId).get
          assert(propertyMap(comp).get("swhid:core").isEmpty)
          assert(propertyMap(comp).get("omnibor:core").isEmpty)
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.38 — keys detected inside a keystore become algorithm assets: every
  // `Certificates:Entry:<alias>:KeyAlgorithm` (plus KeySize/Curve) emitted by
  // the certificates strategy is registered as an `alg:` component and the
  // keystore component references it. THEORY: detecting keys is only useful
  // if the CBOM represents them; keystores previously emitted a `key`-typed
  // component with no algorithmRef at all.
  test("T3.38 keystore-detected keys emit algorithm assets") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val ksId =
        "gitoid:blob:sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
      val ks = makeItem(
        id = ksId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("store.jks"),
        mimeTypes = TreeSet("application/x-java-keystore"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("store.jks")),
          "Certificates:KeystoreType" -> TreeSet(StringOrPair("jks")),
          "Certificates:EntryCount" -> TreeSet(StringOrPair("1")),
          "Certificates:KeyEntryCount" -> TreeSet(StringOrPair("1")),
          "Certificates:Entry:mykey:Chain:0:SubjectDN" -> TreeSet(
            StringOrPair("CN=x")
          ),
          "Certificates:Entry:mykey:KeyAlgorithm" -> TreeSet(
            StringOrPair("rsa")
          ),
          "Certificates:Entry:mykey:KeySize" -> TreeSet(StringOrPair("2048"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> ks.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, ks)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val alg = findComponentByRef(json, "alg:pke:rsa")
          assert(
            alg.isDefined,
            "detected keystore key must emit an algorithm asset"
          )
          assertEquals(
            getString(
              alg.get,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "2048"
          )
          val comp = findComponentByRef(json, ksId).get
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "type"
            ),
            "key"
          )
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "algorithmRef"
            ),
            "alg:pke:rsa"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.39 — trusted-cert entries are certificates, not keys: their per-cert
  // `Entry:<alias>:KeyAlgorithm` metadata must NOT mint a key algorithm
  // asset. THEORY: key detection discriminates on the presence of `Chain:`
  // metadata, which only key entries carry.
  test("T3.39 trusted-cert entries never mint key assets") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val ksId =
        "gitoid:blob:sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
      val ks = makeItem(
        id = ksId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("trust.jks"),
        mimeTypes = TreeSet("application/x-java-keystore"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("trust.jks")),
          "Certificates:KeystoreType" -> TreeSet(StringOrPair("jks")),
          "Certificates:EntryCount" -> TreeSet(StringOrPair("1")),
          "Certificates:KeyEntryCount" -> TreeSet(StringOrPair("0")),
          "Certificates:Entry:trust1:KeyAlgorithm" -> TreeSet(
            StringOrPair("rsa")
          ),
          "Certificates:Entry:trust1:KeySize" -> TreeSet(StringOrPair("2048"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> ks.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, ks)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          assert(
            findComponentByRef(json, "alg:pke:rsa").isEmpty,
            "trusted-cert entries must not mint key algorithm assets"
          )
          val comp = findComponentByRef(json, ksId).get
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "type"
            ),
            "key"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.40 — end-to-end: a real JKS v1 corpus file runs the full pipeline
  // (MIME detection → Certificates strategy → Item metadata) and the emitted
  // CBOM contains the keystore component with its detected key as an
  // algorithm asset. THEORY: T3.38 pins the CBOM mapping with synthetic
  // metadata and K-C-* pins the parser with the corpus; this test proves the
  // two halves join — real file bytes in, keystore key in the CBOM out.
  test("T3.40 real JKS v1 corpus file flows into the CBOM") {
    val dir = tempDir()
    try {
      val fixture =
        new File(
          "test_data/certificates/keystores/synthetic/jks-v1/jks-v1-01-rsa-key-single.jks"
        )
      assert(fixture.exists(), s"corpus fixture missing: ${fixture.getPath}")
      val items = CertificatesPipelineRunner.runGoatRodeoOnSingleFile(fixture)
      assertEquals(items.size, 1)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      // the pipeline item is top-level (no containedBy), so wrap it under a
      // synthetic root or the emitter would treat it as a second root
      val ksItem = items.head
        .copy(connections =
          items.head.connections + (EdgeType.containedBy -> rootId)
        )

      val storage = MemStorage(None)
      storeItem(storage, ksItem)
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> ksItem.identifier),
        fileNames = TreeSet("root")
      )

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val comp = findComponentByRef(json, ksItem.identifier).get
          assertEquals(
            propertyMap(comp).get("Certificates:KeystoreType"),
            Some("jks")
          )
          assertEquals(
            propertyMap(comp).get("Certificates:KeyEntryCount"),
            Some("1")
          )
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "type"
            ),
            "key"
          )
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "algorithmRef"
            ),
            "alg:pke:rsa"
          )
          val alg = findComponentByRef(json, "alg:pke:rsa")
          assert(
            alg.isDefined,
            "detected keystore key must emit an algorithm asset"
          )
          assertEquals(
            getString(
              alg.get,
              "cryptoProperties",
              "algorithmProperties",
              "primitive"
            ),
            "pke"
          )
          assertEquals(
            getString(
              alg.get,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "2048"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.41 — end-to-end: a docker-built ELF containing a carved RSA-1024 DER
  // certificate flows through the full pipeline (MIME probe → carve strategy
  // → metadata) and the CBOM contains a certificate component with
  // KeySize 1024 plus an `alg:pke:rsa` asset parameterized 1024.
  test("T3.41 carved RSA-1024 cert in an ELF surfaces in the CBOM") {
    val dir = tempDir()
    try {
      val fixture =
        new File("test_data/carved-certs/elf-rsa1024-cert")
      assert(
        fixture.exists(),
        "carved corpus fixtures required — run gen_carved_elf_corpus.sh"
      )
      val items = CertificatesPipelineRunner.runGoatRodeoOnSingleFile(fixture)
      assert(items.nonEmpty, "pipeline should produce items for the ELF")
      val carved =
        items.filter(_.identifier != "gitoid:blob:sha256:" + ("0" * 64))
      assert(
        carved.exists { i =>
          i.bodyAsItemMetaData.exists(
            _.extra.keys.exists(_.startsWith("Certificates:Cert:0:"))
          )
        },
        "a carved-certificate item must exist"
      )

      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val storage = MemStorage(None)
      items.foreach(i =>
        storeItem(
          storage,
          i.copy(connections = i.connections + (EdgeType.containedBy -> rootId))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(
          items.map(i => EdgeType.contains -> i.identifier)*
        ),
        fileNames = TreeSet("root")
      )

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val certs = getComponents(json).filter { c =>
            (c \ "cryptoProperties" \ "assetType") == JString("certificate")
          }
          assert(
            certs.nonEmpty,
            "carved cert must emit a certificate component"
          )
          val cert1024 = certs.find { c =>
            propertyMap(c)
              .get("Certificates:Cert:0:KeySize")
              .contains("1024") ||
            propertyMap(c).get("Certificates:KeySize").contains("1024")
          }
          assert(
            cert1024.isDefined,
            s"no certificate component with KeySize 1024: ${certs.map(propertyMap)}"
          )
          val alg = findComponentByRef(json, "alg:pke:rsa")
          assert(alg.isDefined, "carved cert must emit an rsa algorithm asset")
          assertEquals(
            getString(
              alg.get,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "1024"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.42 — every item-backed component carries the traversal-derived paths
  // (`goatrodeo:path`, `goatrodeo:omnibor-path`, `goatrodeo:swhid-path`) built
  // from the ADG `contains` hierarchy from the root down to the item.
  test("T3.42 nested components carry traversal-derived paths") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val sha1Root = "aa" * 20
      val sha1Container = "bb" * 20
      val sha1Leaf = "cc" * 20
      val rootId = "gitoid:blob:sha256:" + ("0" * 64)
      val containerId = "gitoid:blob:sha256:" + ("1" * 64)
      val leafId = "gitoid:blob:sha256:" + ("2" * 64)
      val container = makeItem(
        id = containerId,
        connections = TreeSet(
          EdgeType.containedBy -> rootId,
          EdgeType.contains -> leafId,
          EdgeType.aliasFrom -> s"gitoid:blob:sha1:$sha1Container"
        ),
        fileNames = TreeSet("nested/cert.pem"),
        mimeTypes = TreeSet("application/x-pem-file"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("nested/cert.pem"))
        )
      )
      val leaf = makeItem(
        id = leafId,
        connections = TreeSet(
          EdgeType.containedBy -> containerId,
          EdgeType.aliasFrom -> s"gitoid:blob:sha1:$sha1Leaf"
        ),
        fileNames = TreeSet("nested/cert.pem"),
        mimeTypes = TreeSet("application/x-pem-file"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("nested/cert.pem")),
          "Certificates:SubjectDN" -> TreeSet(StringOrPair("CN=leaf")),
          "Certificates:KeyAlgorithm" -> TreeSet(StringOrPair("rsa")),
          "Certificates:KeySize" -> TreeSet(StringOrPair("2048")),
          "Certificates:Cert:0:SubjectDN" -> TreeSet(StringOrPair("CN=leaf"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(
          EdgeType.contains -> container.identifier,
          EdgeType.aliasFrom -> s"gitoid:blob:sha1:$sha1Root"
        ),
        fileNames = TreeSet("firmware.img"),
        extra = TreeMap("Name" -> TreeSet(StringOrPair("firmware.img")))
      )
      storeItem(storage, root)
      storeItem(storage, container)
      storeItem(storage, leaf)

      val json = writeAndRead(storage, root, dir)
      val comp = findComponentByRef(json, leafId).get
      val path = propertyMap(comp)
      assertEquals(
        path.get("goatrodeo:path"),
        Some("firmware.img|:|nested/cert.pem|:|nested/cert.pem")
      )
      assertEquals(
        path.get("goatrodeo:omnibor-path"),
        Some(s"$rootId|:|$containerId|:|$leafId")
      )
      assertEquals(
        path.get("goatrodeo:swhid-path"),
        Some(
          s"swh:1:cnt:$sha1Root|:|swh:1:cnt:$sha1Container|:|swh:1:cnt:$sha1Leaf"
        )
      )
    } finally {
      cleanup(dir)
    }
  }

  // T3.43 — end-to-end: an ArduPilot firmware ELF's AP_ROMFS is treated as a
  // container, so its embedded trust-store certs (RSA-1024) surface in the
  // CBOM with KeySize 1024 and the traversal-derived goatrodeo:path.
  test("T3.43 ArduPilot AP_ROMFS trust-store certs surface with KeySize 1024") {
    val dir = tempDir()
    try {
      val fixture =
        new File("test_data/firmware-images/ardupilot/arducopter")
      assert(fixture.exists(), "arducopter fixture required in firmware-images")
      val items = CertificatesPipelineRunner.runGoatRodeoOnSingleFile(fixture)
      val hasKeySize = (k: String) =>
        items.exists { i =>
          i.bodyAsItemMetaData.exists(
            _.extra.get(k).exists(_.map(_.value).toSet.contains("1024"))
          )
        }
      assert(
        hasKeySize("Certificates:KeySize") ||
          hasKeySize("Certificates:Cert:0:KeySize"),
        "arducopter must yield a certificate item with KeySize 1024"
      )

      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val storage = MemStorage(None)
      items.foreach(i =>
        storeItem(
          storage,
          i.copy(connections = i.connections + (EdgeType.containedBy -> rootId))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(
          items.map(i => EdgeType.contains -> i.identifier)*
        ),
        fileNames = TreeSet("root")
      )

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val certs = getComponents(json).filter { c =>
            (c \ "cryptoProperties" \ "assetType") == JString("certificate")
          }
          val rsa1024 = certs.find { c =>
            propertyMap(c).get("Certificates:KeySize").contains("1024") ||
            propertyMap(c).get("Certificates:Cert:0:KeySize").contains("1024")
          }
          assert(
            rsa1024.isDefined,
            s"no certificate component with KeySize 1024: ${certs.map(propertyMap)}"
          )
          assert(
            propertyMap(rsa1024.get).get("goatrodeo:path").isDefined,
            "certificate component should carry a goatrodeo:path"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.38 — keys detected inside a keystore become algorithm assets: every
  // `Certificates:Entry:<alias>:KeyAlgorithm` (plus KeySize/Curve) emitted by
  // the certificates strategy is registered as an `alg:` component and the
  // keystore component references it. THEORY: detecting keys is only useful
  // if the CBOM represents them; keystores previously emitted a `key`-typed
  // component with no algorithmRef at all.
  test("T3.38 keystore-detected keys emit algorithm assets") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val ksId =
        "gitoid:blob:sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
      val ks = makeItem(
        id = ksId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("store.jks"),
        mimeTypes = TreeSet("application/x-java-keystore"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("store.jks")),
          "Certificates:KeystoreType" -> TreeSet(StringOrPair("jks")),
          "Certificates:EntryCount" -> TreeSet(StringOrPair("1")),
          "Certificates:KeyEntryCount" -> TreeSet(StringOrPair("1")),
          "Certificates:Entry:mykey:Chain:0:SubjectDN" -> TreeSet(
            StringOrPair("CN=x")
          ),
          "Certificates:Entry:mykey:KeyAlgorithm" -> TreeSet(
            StringOrPair("rsa")
          ),
          "Certificates:Entry:mykey:KeySize" -> TreeSet(StringOrPair("2048"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> ks.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, ks)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val alg = findComponentByRef(json, "alg:pke:rsa")
          assert(
            alg.isDefined,
            "detected keystore key must emit an algorithm asset"
          )
          assertEquals(
            getString(
              alg.get,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "2048"
          )
          val comp = findComponentByRef(json, ksId).get
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "type"
            ),
            "key"
          )
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "algorithmRef"
            ),
            "alg:pke:rsa"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.39 — trusted-cert entries are certificates, not keys: their per-cert
  // `Entry:<alias>:KeyAlgorithm` metadata must NOT mint a key algorithm
  // asset. THEORY: key detection discriminates on the presence of `Chain:`
  // metadata, which only key entries carry.
  test("T3.39 trusted-cert entries never mint key assets") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      val ksId =
        "gitoid:blob:sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
      val ks = makeItem(
        id = ksId,
        connections = TreeSet(EdgeType.containedBy -> rootId),
        fileNames = TreeSet("trust.jks"),
        mimeTypes = TreeSet("application/x-java-keystore"),
        extra = TreeMap(
          "Name" -> TreeSet(StringOrPair("trust.jks")),
          "Certificates:KeystoreType" -> TreeSet(StringOrPair("jks")),
          "Certificates:EntryCount" -> TreeSet(StringOrPair("1")),
          "Certificates:KeyEntryCount" -> TreeSet(StringOrPair("0")),
          "Certificates:Entry:trust1:KeyAlgorithm" -> TreeSet(
            StringOrPair("rsa")
          ),
          "Certificates:Entry:trust1:KeySize" -> TreeSet(StringOrPair("2048"))
        )
      )
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> ks.identifier),
        fileNames = TreeSet("root")
      )
      storeItem(storage, ks)

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          assert(
            findComponentByRef(json, "alg:pke:rsa").isEmpty,
            "trusted-cert entries must not mint key algorithm assets"
          )
          val comp = findComponentByRef(json, ksId).get
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "type"
            ),
            "key"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }

  // T3.40 — end-to-end: a real JKS v1 corpus file runs the full pipeline
  // (MIME detection → Certificates strategy → Item metadata) and the emitted
  // CBOM contains the keystore component with its detected key as an
  // algorithm asset. THEORY: T3.38 pins the CBOM mapping with synthetic
  // metadata and K-C-* pins the parser with the corpus; this test proves the
  // two halves join — real file bytes in, keystore key in the CBOM out.
  test("T3.40 real JKS v1 corpus file flows into the CBOM") {
    val dir = tempDir()
    try {
      val fixture =
        new File(
          "test_data/certificates/keystores/synthetic/jks-v1/jks-v1-01-rsa-key-single.jks"
        )
      assert(fixture.exists(), s"corpus fixture missing: ${fixture.getPath}")
      val items = CertificatesPipelineRunner.runGoatRodeoOnSingleFile(fixture)
      assertEquals(items.size, 1)
      val rootId =
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000"
      // the pipeline item is top-level (no containedBy), so wrap it under a
      // synthetic root or the emitter would treat it as a second root
      val ksItem = items.head
        .copy(connections =
          items.head.connections + (EdgeType.containedBy -> rootId)
        )

      val storage = MemStorage(None)
      storeItem(storage, ksItem)
      val root = makeItem(
        id = rootId,
        connections = TreeSet(EdgeType.contains -> ksItem.identifier),
        fileNames = TreeSet("root")
      )

      Vector("1.6" -> schema16, "1.7" -> schema17).foreach {
        case (version, schema) =>
          val json = writeAndRead(storage, root, dir, version)
          val comp = findComponentByRef(json, ksItem.identifier).get
          assertEquals(
            propertyMap(comp).get("Certificates:KeystoreType"),
            Some("jks")
          )
          assertEquals(
            propertyMap(comp).get("Certificates:KeyEntryCount"),
            Some("1")
          )
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "type"
            ),
            "key"
          )
          assertEquals(
            getString(
              comp,
              "cryptoProperties",
              "relatedCryptoMaterialProperties",
              "algorithmRef"
            ),
            "alg:pke:rsa"
          )
          val alg = findComponentByRef(json, "alg:pke:rsa")
          assert(
            alg.isDefined,
            "detected keystore key must emit an algorithm asset"
          )
          assertEquals(
            getString(
              alg.get,
              "cryptoProperties",
              "algorithmProperties",
              "primitive"
            ),
            "pke"
          )
          assertEquals(
            getString(
              alg.get,
              "cryptoProperties",
              "algorithmProperties",
              "parameterSetIdentifier"
            ),
            "2048"
          )
          assert(validate(compact(render(json)), schema).isEmpty)
      }
    } finally {
      cleanup(dir)
    }
  }
}
