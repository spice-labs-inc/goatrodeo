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

import io.spicelabs.goatrodeo.GoatRodeoBuilder
import munit.FunSuite
import org.json4s.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** T4.2 — End-to-end integration test for CycloneDX CBOM emission.
  *
  * Builds an ADG from a directory containing an X.509 certificate, an OpenSSL
  * configuration file, a Java `java.security` file, and a non-crypto file. With
  * `--emit-cbom-dir`, asserts that the emitted CBOMs are valid JSON, contain
  * certificate, OpenSSL, and Java security components, and do not represent the
  * non-crypto file as cryptographic material.
  */
class CbomIntegrationSuite extends FunSuite {

  private def text(jv: JValue, path: String*): String = {
    path.foldLeft(jv: JValue)(_ \ _) match {
      case JString(s) => s
      case _          => ""
    }
  }

  private val certSource =
    new File(
      "test_data/certificates/x509/leaves/github.com__github.com__9716d39441.der"
    )
  private val openSslSource =
    new File("test_data/openssl_configs/1.1.1w_test_sysdefault.cnf.cnf")

  private def copyTo(dir: File, source: File, name: String): File = {
    val f = new File(dir, name)
    Files.copy(source.toPath, f.toPath)
    f
  }

  private def writeString(dir: File, name: String, content: String): File = {
    val f = new File(dir, name)
    Files.write(f.toPath, content.getBytes(StandardCharsets.UTF_8))
    f
  }

  private def deleteRecursive(dir: File): Unit = {
    if (dir.exists()) {
      Files
        .walk(dir.toPath)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
      ()
    }
  }

  private def componentTypes(root: JValue): Set[String] = {
    (root \ "components") match {
      case JArray(comps) =>
        comps.flatMap { comp =>
          (comp \ "cryptoProperties" \ "assetType") match {
            case JString(a) => Some(a)
            case _          => None
          }
        }.toSet
      case _ => Set.empty
    }
  }

  test(
    "T4.2 mixed directory produces CBOMs with certificate, OpenSSL, and Java security components"
  ) {
    val inputDir = Files.createTempDirectory("cbom-int-input").toFile()
    val outputDir = Files.createTempDirectory("cbom-int-output").toFile()
    val cbomDir = Files.createTempDirectory("cbom-int-cbom").toFile()

    try {
      copyTo(inputDir, certSource, "cert.der")
      copyTo(inputDir, openSslSource, "openssl.cnf")
      writeString(
        inputDir,
        "java.security",
        """#
          |jdk.tls.disabledAlgorithms=SSLv3, RC4, DES, MD5withRSA
          |jdk.certpath.disabledAlgorithms=MD2, MD5, SHA1
          |jdk.tls.legacyAlgorithms=RC4, DES_CBC_SHA
          |jdk.tls.namedGroups=secp256r1, secp384r1
          |jdk.tls.ephemeralDHKeySize=2048
          |""".stripMargin
      )
      writeString(inputDir, "README.txt", "This is a non-crypto file.")

      new GoatRodeoBuilder()
        .withPayload(inputDir.getAbsolutePath)
        .withOutput(outputDir.getAbsolutePath)
        .withCbomDir(cbomDir.getAbsolutePath)
        .withCbomVersion("1.6")
        .withThreads(1)
        .run()

      val cbomFiles = cbomDir.listFiles()
      assert(cbomFiles != null, "CBOM output directory should contain files")
      assertEquals(
        cbomFiles.length,
        4,
        "Expected one CBOM file per top-level input file"
      )

      val allTypes = cbomFiles.flatMap { file =>
        val root = org.json4s.native.JsonMethods.parse(
          Files.readString(file.toPath())
        )
        assert(
          (root \ "bomFormat") != JNothing,
          s"${file.getName} should be a CycloneDX BOM"
        )
        assertEquals(text(root, "bomFormat"), "CycloneDX")
        componentTypes(root)
      }.toSet

      assert(
        allTypes.contains("certificate"),
        s"Expected a certificate component; found types: $allTypes"
      )
      assert(
        allTypes.contains("protocol"),
        s"Expected an OpenSSL protocol component; found types: $allTypes"
      )
      assert(
        allTypes.contains("related-crypto-material"),
        s"Expected a Java security related-crypto-material component; found types: $allTypes"
      )

      val allNames = cbomFiles
        .flatMap { file =>
          val root = org.json4s.native.JsonMethods.parse(
            Files.readString(file.toPath())
          )
          (root \ "components") match {
            case JArray(comps) => comps.map(c => text(c, "name"))
            case _             => Vector.empty
          }
        }
        .mkString(" ")
      assert(
        !allNames.toLowerCase.contains("readme"),
        s"Non-crypto file should not appear as a CBOM component; names: $allNames"
      )
    } finally {
      deleteRecursive(inputDir)
      deleteRecursive(outputDir)
      deleteRecursive(cbomDir)
    }
  }

  // T4.3 — end-to-end via GoatRodeoBuilder with tamper-evident logging: the
  // run writes a hash-chained log and a run-level checksum, and every CBOM
  // carries the run's correlation ID. THEORY: the builder routes through
  // Howdy.run, so withTamperEvidentLog must produce the tamper-evident
  // artifacts and correlate the CBOMs to the run.
  test(
    "T4.3 GoatRodeoBuilder tamper-evident run produces log, checksum, correlation"
  ) {
    val inputDir = Files.createTempDirectory("tel-int-input").toFile()
    val outputDir = Files.createTempDirectory("tel-int-output").toFile()
    val logFile = new File(outputDir, "run.log")
    try {
      writeString(inputDir, "cert.der", "not-a-real-cert")

      new GoatRodeoBuilder()
        .withPayload(inputDir.getAbsolutePath)
        .withOutput(outputDir.getAbsolutePath)
        .withCbomDir(outputDir.getAbsolutePath)
        .withTamperEvidentLog(logFile.getAbsolutePath)
        .withPrintFiles(true)
        .withThreads(1)
        .run()

      assert(logFile.exists(), "tamper-evident log file should exist")
      val logLines =
        Files.readAllLines(logFile.toPath()).toArray(new Array[String](0))
      assert(logLines.nonEmpty, "tamper-evident log should not be empty")
      assert(
        logLines.exists(_.contains("Correlation ID:")),
        "log should contain the correlation ID as the first line"
      )
      val checksums =
        outputDir.listFiles(f => f.getName.endsWith("_checksum.json"))
      assertEquals(checksums.length, 1, "exactly one checksum file")
      val checksum =
        org.json4s.native.JsonMethods
          .parse(Files.readString(checksums(0).toPath()))
      val corrId = text(checksum, "correlation_id")
      assert(corrId.nonEmpty, "checksum should carry a correlation ID")
      val cboms = outputDir.listFiles(f => f.getName.startsWith("cbom_"))
      assert(cboms != null && cboms.nonEmpty, "should emit CBOMs")
      val cbom =
        org.json4s.native.JsonMethods.parse(Files.readString(cboms(0).toPath()))
      val topProps = (cbom \ "properties") match {
        case JArray(ps) =>
          ps.collect { case o: JObject =>
            (text(o, "name") -> text(o, "value"))
          }.toMap
        case _ => Map.empty[String, String]
      }
      assertEquals(topProps.get("goatrodeo:correlation-id"), Some(corrId))
    } finally {
      deleteRecursive(inputDir)
      deleteRecursive(outputDir)
    }
  }
}
