/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.OpenSSLConfigDetector
import io.spicelabs.goatrodeo.util.OpenSSLConfigParser
import munit.FunSuite

import scala.collection.immutable.TreeSet

/** Phase 1 — Tests for the OpenSSL config capture strategy.
  *
  * These tests verify MIME-based claiming, bundling, dependency ordering,
  * metadata emission, cross-file reference tracking, and coexistence with other
  * strategies.
  */
class OpenSSLConfigSuite extends FunSuite {

  /** The default configuration for these tests; calls needing different
    * settings pass an explicit `(using ...)`.
    */
  private given Configuration = Configuration()

  private val adHoc = MKC.adHoc("openssl.cnf")

  private def configArtifact(
      name: String,
      content: String
  ): ByteWrapper = {
    ByteWrapper(content.getBytes("UTF-8"), name, None)
  }

  private def basicConfig: String =
    """[ssl_default]
      |CipherString = DEFAULT@SECLEVEL=2
      |MinProtocol = TLSv1.2
      |""".stripMargin

  // ==================== T1.1 MIME-based claim ====================

  test("strategy claims OpenSSL configs by MIME type") {
    val openssl = configArtifact("openssl.cnf", basicConfig)
    val genericIni = configArtifact("config.ini", "[section]\nkey=value\n")
    val binary = ByteWrapper(Array[Byte](0x00, 0x01), "data.bin", None)

    assert(
      openssl.mimeType.contains(OpenSSLConfigDetector.OpenSSLConfigMimeType)
    )
    assert(
      !genericIni.mimeType.contains(OpenSSLConfigDetector.OpenSSLConfigMimeType)
    )

    val strategies = ToProcess.strategiesForArtifacts(
      Vector(openssl, genericIni, binary),
      _ => (),
      false
    )

    val openSslStrategies = strategies.collect {
      case tp: OpenSSLConfigToProcess => tp
    }
    assertEquals(openSslStrategies.size, 1)
    assertEquals(openSslStrategies.head.files.size, 1)
    assertEquals(openSslStrategies.head.files.head.path(), "openssl.cnf")
  }

  test("strategy does not claim files without OpenSSL MIME type") {
    val text = ByteWrapper("hello".getBytes("UTF-8"), "readme.txt", None)
    val strategies = ToProcess.strategiesForArtifacts(
      Vector(text),
      _ => (),
      false
    )
    assert(strategies.forall(!_.isInstanceOf[OpenSSLConfigToProcess]))
  }

  // ==================== T1.2 Bundle grouping ====================

  test("multiple OpenSSL files at a layer are bundled into one ToProcess") {
    val a = configArtifact("a.cnf", basicConfig)
    val b = configArtifact("b.cnf", "[ssl_default]\nMaxProtocol = TLSv1.3\n")
    val c = configArtifact("c.cnf", "[req]\nOptions = BanCamellia\n")

    val strategies = ToProcess.strategiesForArtifacts(
      Vector(a, b, c),
      _ => (),
      false
    )

    val openSslStrategies = strategies.collect {
      case tp: OpenSSLConfigToProcess => tp
    }
    assertEquals(openSslStrategies.size, 1)
    assertEquals(openSslStrategies.head.files.size, 3)
  }

  // ==================== T1.3 Dependency ordering ====================

  test("files are ordered with dependencies first") {
    val a = configArtifact("a.cnf", basicConfig)
    val b = configArtifact(
      "b.cnf",
      """[ssl_default]
        |.include a.cnf
        |MaxProtocol = TLSv1.3
        |""".stripMargin
    )

    val tp = OpenSSLConfigToProcess.computeOpenSSLConfigFiles(
      Map.empty,
      Map(
        "a.cnf" -> Vector(a),
        "b.cnf" -> Vector(b)
      )
    )

    val bundle = tp._1.head.asInstanceOf[OpenSSLConfigToProcess]
    val (elements, _) = bundle.getElementsToProcess()
    val paths = elements.map(_._1.path())
    assertEquals(paths, Vector("a.cnf", "b.cnf"))
  }

  test("standalone files are ordered alphabetically") {
    val c = configArtifact("c.cnf", basicConfig)
    val a = configArtifact("a.cnf", basicConfig)
    val b = configArtifact("b.cnf", basicConfig)

    val tp = OpenSSLConfigToProcess.computeOpenSSLConfigFiles(
      Map.empty,
      Map(
        "c.cnf" -> Vector(c),
        "a.cnf" -> Vector(a),
        "b.cnf" -> Vector(b)
      )
    )

    val bundle = tp._1.head.asInstanceOf[OpenSSLConfigToProcess]
    val (elements, _) = bundle.getElementsToProcess()
    assertEquals(elements.map(_._1.path()), Vector("a.cnf", "b.cnf", "c.cnf"))
  }

  // ==================== T1.4 / T1.5 Metadata ====================

  test("metadata contains parsed security values") {
    val artifact = configArtifact("openssl.cnf", basicConfig)
    val state = OpenSSLConfigState(
      Map("openssl.cnf" -> OpenSSLConfigParser.parseString(basicConfig).get),
      Map.empty
    )
    val (meta, _) =
      state.getMetadata(artifact, ItemTestHelper.testItem("x"), SingleMarker())

    assert(
      meta.contains(adHoc("cipher_string")),
      "metadata must contain cipher_string"
    )
    assertEquals(
      meta(adHoc("cipher_string")).head.value,
      "DEFAULT@SECLEVEL=2"
    )
    assert(
      meta.contains(adHoc("min_protocol")),
      "metadata must contain min_protocol"
    )
    assertEquals(meta(adHoc("min_protocol")).head.value, "TLSv1.2")
    assert(meta.contains(adHoc("sections")), "metadata must contain sections")
  }

  test("ssl_conf indirection values are captured in metadata") {
    val text =
      """[openssl_init]
        |ssl_conf = ssl_sect
        |
        |[ssl_sect]
        |system_default = system_default_sect
        |
        |[system_default_sect]
        |CipherString = DEFAULT
        |""".stripMargin
    val data = OpenSSLConfigParser.parseString(text).get
    assertEquals(data.cipherString, Some("DEFAULT"))
    assert(data.sections.contains("system_default_sect"))
  }

  // ==================== T1.6 Cross-file references ====================

  test("cross-file references include container and file GitOIDs") {
    val a = configArtifact("a.cnf", basicConfig)
    val b = configArtifact(
      "b.cnf",
      """[ssl_default]
        |.include a.cnf
        |MaxProtocol = TLSv1.3
        |""".stripMargin
    )

    val tp = OpenSSLConfigToProcess.computeOpenSSLConfigFiles(
      Map.empty,
      Map("a.cnf" -> Vector(a), "b.cnf" -> Vector(b))
    )

    val bundle = tp._1.head.asInstanceOf[OpenSSLConfigToProcess]
    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())
    val containerGitOID = "gitoid:sha256:container123"

    bundle.process(
      Some(containerGitOID),
      store,
      parentScope,
      None
    )

    val aItemOpt = store.keys().flatMap(store.read).find { item =>
      item.bodyAsItemMetaData.exists(_.fileNames.contains("a.cnf"))
    }
    val bItemOpt = store.keys().flatMap(store.read).find { item =>
      item.bodyAsItemMetaData.exists(_.fileNames.contains("b.cnf"))
    }

    assert(aItemOpt.isDefined)
    assert(bItemOpt.isDefined)

    val aGitOID = aItemOpt.get.identifier
    val bMeta = bItemOpt.get.bodyAsItemMetaData.get
    val associated = bMeta.extra.get(adHoc("associated_files"))
    assert(associated.isDefined)
    val encoded = associated.get.head.value
    assertEquals(encoded, s"$containerGitOID:$aGitOID")
  }

  // ==================== T1.10 No false metadata ====================

  test("non-OpenSSL files do not receive openssl.cnf metadata") {
    val text = ByteWrapper("hello".getBytes("UTF-8"), "readme.txt", None)
    val store = ToProcess.buildGraphForToProcess(
      Vector(GenericFile(text))
    )
    val itemOpt = store.keys().flatMap(store.read).find { item =>
      item.bodyAsItemMetaData.exists(_.fileNames.contains("readme.txt"))
    }
    assert(itemOpt.isDefined)
    val extra = itemOpt.get.bodyAsItemMetaData.map(_.extra).getOrElse(Map.empty)
    assert(!extra.keys.exists(_.startsWith("openssl.cnf")))
  }

  // ==================== T1.11 Cycles ====================

  test("self-reference cycle does not cause infinite processing") {
    val a = configArtifact(
      "a.cnf",
      """[ssl_default]
        |.include a.cnf
        |CipherString = DEFAULT
        |""".stripMargin
    )

    val tp = OpenSSLConfigToProcess.computeOpenSSLConfigFiles(
      Map.empty,
      Map("a.cnf" -> Vector(a))
    )

    val bundle = tp._1.head.asInstanceOf[OpenSSLConfigToProcess]
    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())

    bundle.process(None, store, parentScope, None)

    assert(store.size() > 0)
  }

  test("mutual-reference cycle terminates without cyclic metadata") {
    val a = configArtifact(
      "a.cnf",
      """[ssl_default]
        |.include b.cnf
        |CipherString = DEFAULT
        |""".stripMargin
    )
    val b = configArtifact(
      "b.cnf",
      """[ssl_default]
        |.include a.cnf
        |MaxProtocol = TLSv1.3
        |""".stripMargin
    )

    val tp = OpenSSLConfigToProcess.computeOpenSSLConfigFiles(
      Map.empty,
      Map("a.cnf" -> Vector(a), "b.cnf" -> Vector(b))
    )

    val bundle = tp._1.head.asInstanceOf[OpenSSLConfigToProcess]
    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())

    bundle.process(None, store, parentScope, None)

    val items = store.keys().flatMap(store.read).toVector
    val aItem =
      items.find(_.bodyAsItemMetaData.exists(_.fileNames.contains("a.cnf"))).get
    val bItem =
      items.find(_.bodyAsItemMetaData.exists(_.fileNames.contains("b.cnf"))).get

    // The order is deterministic: for this cycle the result is b, a because a
    // depends on b. The first-processed file cannot reference the second, so
    // no cyclic reference metadata is emitted.
    val (elements, _) = bundle.getElementsToProcess()
    val order = elements.map(_._1.path())
    assert(order == Vector("b.cnf", "a.cnf"), s"unexpected order: $order")

    val firstItem = if (order.head == "a.cnf") aItem else bItem
    val secondItem = if (order.last == "b.cnf") bItem else aItem
    val firstAssociated =
      firstItem.bodyAsItemMetaData.get.extra.get(adHoc("associated_files"))
    assert(
      firstAssociated.isEmpty || !firstAssociated.get.head.value
        .contains(secondItem.identifier),
      "first-processed file must not reference the second (cycle broken)"
    )
  }

  // ==================== T1.14 Strategy coexistence ====================

  test("OpenSSL config inside nested archive is discovered") {
    import org.apache.commons.compress.archivers.tar.TarArchiveEntry
    import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
    import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
    import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

    val cnfBytes = basicConfig.getBytes("UTF-8")

    // Build TAR containing the config.
    val tarBaos = new java.io.ByteArrayOutputStream()
    val tarOut = new TarArchiveOutputStream(tarBaos)
    val tarEntry = new TarArchiveEntry("etc/ssl/openssl.cnf")
    tarEntry.setSize(cnfBytes.length)
    tarOut.putArchiveEntry(tarEntry)
    tarOut.write(cnfBytes)
    tarOut.closeArchiveEntry()
    tarOut.close()

    // Build ZIP containing the TAR.
    val zipBaos = new java.io.ByteArrayOutputStream()
    val zipOut = new ZipArchiveOutputStream(zipBaos)
    val zipEntry = new ZipArchiveEntry("nested.tar")
    zipEntry.setSize(tarBaos.size())
    zipOut.putArchiveEntry(zipEntry)
    zipOut.write(tarBaos.toByteArray)
    zipOut.closeArchiveEntry()
    zipOut.close()

    val zipWrapper = ByteWrapper(
      zipBaos.toByteArray,
      "nested.zip",
      None
    )

    val store =
      ToProcess.buildGraphFromArtifactWrapper(zipWrapper)

    val configItemOpt = store.keys().flatMap(store.read).find { item =>
      item.bodyAsItemMetaData
        .exists(_.fileNames.exists(_.endsWith("openssl.cnf")))
    }

    assert(
      configItemOpt.isDefined,
      "OpenSSL config item must be present in nested archive"
    )
    val extra = configItemOpt.get.bodyAsItemMetaData.get.extra
    assert(extra.contains(adHoc("cipher_string")))
  }

  test("strategy does not steal PEM certificates") {
    val certFile = new java.io.File(
      "test_data/certificates/x509/synthetic/rsa-2048-selfsigned.pem"
    )
    assert(certFile.exists(), "test certificate fixture must exist")
    val cert =
      io.spicelabs.goatrodeo.util.FileWrapper(certFile, "cert.pem", None)
    val cnf = configArtifact("openssl.cnf", basicConfig)

    assert(cert.mimeType.contains("application/x-pem-file"))

    val strategies = ToProcess.strategiesForArtifacts(
      Vector(cert, cnf),
      _ => (),
      false
    )

    val hasOpenSSL = strategies.exists(_.isInstanceOf[OpenSSLConfigToProcess])
    val hasCerts = strategies.exists(_.isInstanceOf[Certificates])
    assert(hasOpenSSL)
    assert(hasCerts)
  }

  // ==================== Phase A — cipher-suite decomposition ====================

  test("Phase A metadata contains resolved algorithms and per-suite entries") {
    val text =
      """[ssl_default]
        |CipherString = ECDHE-RSA-AES128-GCM-SHA256:!aNULL
        |Ciphersuites = TLS_AES_256_GCM_SHA384
        |""".stripMargin
    val artifact = configArtifact("openssl.cnf", text)
    val state = OpenSSLConfigState(
      Map("openssl.cnf" -> OpenSSLConfigParser.parseString(text).get),
      Map.empty
    )
    val (meta, _) =
      state.getMetadata(artifact, ItemTestHelper.testItem("x"), SingleMarker())

    val algorithms = meta(adHoc("algorithms")).toVector.map(_.value).sorted
    assertEquals(
      algorithms,
      Vector(
        "aes-256-gcm",
        "ecdh",
        "rsa",
        "aes-128-gcm",
        "sha-256",
        "sha-384"
      ).sorted
    )

    assertEquals(
      meta(adHoc("suite:0:name")).head.value,
      "ECDHE-RSA-AES128-GCM-SHA256"
    )
    assertEquals(
      meta(adHoc("suite:0:algorithms")).head.value,
      "ecdh,rsa,aes-128-gcm,sha-256"
    )
    assertEquals(meta(adHoc("suite:1:name")).head.value, "!aNULL")
    assert(
      !meta.contains(adHoc("suite:1:algorithms")),
      "excluded token resolves to no algorithms"
    )
    assertEquals(
      meta(adHoc("suite:2:name")).head.value,
      "TLS_AES_256_GCM_SHA384"
    )
    assertEquals(
      meta(adHoc("suite:2:algorithms")).head.value,
      "aes-256-gcm,sha-384"
    )
  }

  test("Phase A decomposition metadata is deterministic across calls") {
    val text =
      """[ssl_default]
        |CipherString = DEFAULT@SECLEVEL=2
        |""".stripMargin
    val artifact = configArtifact("openssl.cnf", text)
    val state = OpenSSLConfigState(
      Map("openssl.cnf" -> OpenSSLConfigParser.parseString(text).get),
      Map.empty
    )
    val (m1, _) =
      state.getMetadata(artifact, ItemTestHelper.testItem("x"), SingleMarker())
    val (m2, _) =
      state.getMetadata(artifact, ItemTestHelper.testItem("x"), SingleMarker())
    assertEquals(m1, m2)
    // `DEFAULT@SECLEVEL=2` is name-only; no algorithms are invented.
    assertEquals(
      m1.get(adHoc("suite:0:name")).map(_.head.value),
      Some("DEFAULT@SECLEVEL=2")
    )
    assert(!m1.contains(adHoc("algorithms")))
  }
}

/** Helper for creating minimal Items in tests. */
object ItemTestHelper {
  def testItem(id: String): io.spicelabs.goatrodeo.omnibor.Item = {
    io.spicelabs.goatrodeo.omnibor.Item(
      id,
      TreeSet.empty,
      None,
      None
    )
  }
}
