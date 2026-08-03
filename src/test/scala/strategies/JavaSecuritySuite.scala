/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.JavaSecurityDetector
import io.spicelabs.goatrodeo.util.JavaSecurityParser
import munit.FunSuite

import scala.collection.immutable.TreeSet

/** Phase 2 — Tests for the Java `java.security` capture strategy.
  *
  * These tests verify MIME-based and path-based claiming, bundling, metadata
  * emission, strategy-level `include` resolution, and coexistence with other
  * strategies. They also confirm that the strategy follows the
  * selection/processing boundary: `computeJavaSecurityFiles` is
  * content-agnostic and only claims by MIME type and path; parsing and
  * `include` resolution happen inside the strategy during processing.
  */
class JavaSecuritySuite extends FunSuite {

  private val adHoc = MKC.adHoc("java.security")

  private def securityArtifact(name: String, content: String): ByteWrapper = {
    ByteWrapper(content.getBytes("ISO-8859-1"), name, None)
  }

  private def basicSecurity: String =
    """jdk.tls.disabledAlgorithms=SSLv3, RC4, DES, MD5withRSA
      |jdk.certpath.disabledAlgorithms=MD2, MD5, SHA1
      |jdk.tls.legacyAlgorithms=K_NULL, M_NULL
      |jdk.tls.namedGroups=secp256r1, secp384r1
      |jdk.tls.ephemeralDHKeySize=2048
      |""".stripMargin

  // ==================== T2.1 / T2.10 Claiming by path and MIME type ====================

  test("strategy claims java.security in lib/security by path") {
    val sec = securityArtifact("lib/security/java.security", basicSecurity)
    val unrelated = securityArtifact("java.security", "foo=bar\n")

    val tp = JavaSecurityToProcess.computeJavaSecurityFiles(
      Map.empty,
      Map(
        "lib/security/java.security" -> Vector(sec),
        "java.security" -> Vector(unrelated)
      )
    )

    val strategies = tp._1
    assertEquals(strategies.size, 1)
    val bundle = strategies.head.asInstanceOf[JavaSecurityToProcess]
    assertEquals(bundle.files.size, 1)
    assertEquals(bundle.files.head.path(), "lib/security/java.security")
  }

  test("strategy claims java.security in conf/security by path") {
    val sec = securityArtifact("conf/security/java.security", basicSecurity)
    val tp = JavaSecurityToProcess.computeJavaSecurityFiles(
      Map.empty,
      Map("conf/security/java.security" -> Vector(sec))
    )
    assertEquals(tp._1.size, 1)
  }

  test("strategy claims java.security in jre/lib/security by path") {
    val sec = securityArtifact("jre/lib/security/java.security", basicSecurity)
    val tp = JavaSecurityToProcess.computeJavaSecurityFiles(
      Map.empty,
      Map("jre/lib/security/java.security" -> Vector(sec))
    )
    assertEquals(tp._1.size, 1)
  }

  test("strategy does not claim java.security at unrelated path by path") {
    val unrelated = securityArtifact("java.security", "foo=bar\n")
    val tp = JavaSecurityToProcess.computeJavaSecurityFiles(
      Map.empty,
      Map("java.security" -> Vector(unrelated))
    )
    assertEquals(tp._1.size, 0)
  }

  test("strategy claims files with Java security MIME type at unusual paths") {
    val sec = securityArtifact("extra/security.properties", basicSecurity)
    assert(sec.mimeType.contains(JavaSecurityDetector.JavaSecurityMimeType))

    val tp = JavaSecurityToProcess.computeJavaSecurityFiles(
      Map.empty,
      Map("extra/security.properties" -> Vector(sec))
    )
    assertEquals(tp._1.size, 1)
    val bundle = tp._1.head.asInstanceOf[JavaSecurityToProcess]
    assertEquals(bundle.files.size, 1)
  }

  // ==================== T2.2 / R2.5 Metadata ====================

  test("metadata contains parsed security values") {
    val artifact = securityArtifact("lib/security/java.security", basicSecurity)
    val state = JavaSecurityState(
      Map(
        "lib/security/java.security" -> JavaSecurityParser
          .parseString(basicSecurity)
          .get
      ),
      Map.empty
    )
    val (meta, _) =
      state.getMetadata(artifact, ItemTestHelper.testItem("x"), SingleMarker())

    assert(meta.contains(adHoc("disabled_algorithms")))
    assertEquals(
      meta(adHoc("disabled_algorithms")).map(_.value),
      TreeSet("DES", "MD5withRSA", "RC4", "SSLv3")
    )
    assert(meta.contains(adHoc("certpath_disabled_algorithms")))
    assert(meta.contains(adHoc("legacy_algorithms")))
    assert(meta.contains(adHoc("named_groups")))
    assert(meta.contains(adHoc("ephemeral_dh_key_size")))
  }

  // ==================== T2.11 Include directive ====================

  test("strategy resolves include directives within the selected set") {
    val main = securityArtifact(
      "lib/security/java.security",
      """jdk.tls.disabledAlgorithms=SSLv3
        |include extra.security
        |""".stripMargin
    )
    val extra = securityArtifact(
      "lib/security/extra.security",
      "jdk.tls.disabledAlgorithms=RC4\n"
    )

    val tp = JavaSecurityToProcess.computeJavaSecurityFiles(
      Map.empty,
      Map(
        "lib/security/java.security" -> Vector(main),
        "lib/security/extra.security" -> Vector(extra)
      )
    )

    assertEquals(tp._1.size, 1)
    val bundle = tp._1.head.asInstanceOf[JavaSecurityToProcess]
    val (elements, _) = bundle.getElementsToProcess()
    assertEquals(elements.size, 2)

    val store = MemStorage(None)
    val parentScope = ParentScope.forAndWith("root", None, Map())
    bundle.process(None, store, parentScope, None, Config())

    val mainItemOpt = store.keys().flatMap(store.read).find { item =>
      item.bodyAsItemMetaData
        .exists(_.fileNames.contains("lib/security/java.security"))
    }
    assert(mainItemOpt.isDefined)
    val extraMeta = mainItemOpt.get.bodyAsItemMetaData.get
    val disabled = extraMeta.extra.get(adHoc("disabled_algorithms"))
    assert(disabled.isDefined)
    assertEquals(disabled.get.map(_.value), TreeSet("RC4", "SSLv3"))
  }

  // ==================== T2.8 Nested archive discovery ====================

  test("java.security inside nested archive is discovered") {
    import org.apache.commons.compress.archivers.tar.TarArchiveEntry
    import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
    import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
    import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

    val secBytes = basicSecurity.getBytes("ISO-8859-1")

    // Build TAR containing the config.
    val tarBaos = new java.io.ByteArrayOutputStream()
    val tarOut = new TarArchiveOutputStream(tarBaos)
    val tarEntry = new TarArchiveEntry("lib/security/java.security")
    tarEntry.setSize(secBytes.length)
    tarOut.putArchiveEntry(tarEntry)
    tarOut.write(secBytes)
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
      ToProcess.buildGraphFromArtifactWrapper(zipWrapper, args = Config())

    val configItemOpt = store.keys().flatMap(store.read).find { item =>
      item.bodyAsItemMetaData
        .exists(_.fileNames.exists(_.endsWith("java.security")))
    }

    assert(
      configItemOpt.isDefined,
      "java.security item must be present in nested archive"
    )
    val extra = configItemOpt.get.bodyAsItemMetaData.get.extra
    assert(extra.contains(adHoc("disabled_algorithms")))
  }

  // ==================== T2.9 No false metadata ====================

  test("non-Java-security files do not receive java.security metadata") {
    val text = ByteWrapper("hello".getBytes("UTF-8"), "readme.txt", None)
    val store = ToProcess.buildGraphForToProcess(
      Vector(GenericFile(text)),
      args = Config()
    )
    val itemOpt = store.keys().flatMap(store.read).find { item =>
      item.bodyAsItemMetaData.exists(_.fileNames.contains("readme.txt"))
    }
    assert(itemOpt.isDefined)
    val extra = itemOpt.get.bodyAsItemMetaData.map(_.extra).getOrElse(Map.empty)
    assert(!extra.keys.exists(_.startsWith("java.security")))
  }
}
