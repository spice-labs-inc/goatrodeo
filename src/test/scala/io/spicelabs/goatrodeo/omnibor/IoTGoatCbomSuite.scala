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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.spicelabs.goatrodeo.GoatRodeoBuilder
import munit.FunSuite

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

/** T4.5 — Discovery-driven CBOM regression test for the IoTGoat x86 firmware.
  *
  * The firmware image was opened with native tools and traversed; the static
  * cryptographic material found was:
  *
  *   - `/etc/shadow` containing MD5-crypt password hashes for `root` and
  *     `iotgoatuser`.
  *   - `/etc/opkg/keys/` containing 11 OpenWrt/LEDE `usign` package signing
  *     public keys.
  *   - `/etc/dropbear/dropbear_rsa_host_key`, an empty placeholder for the SSH
  *     host key that is generated at first boot.
  *   - `/etc/config/uhttpd`, which configures HTTPS to use `/etc/uhttpd.crt`
  *     and `/etc/uhttpd.key` (the files are absent from the image; they are
  *     generated at first boot).
  *   - `/usr/lib/libmbedx509.so.2.14.1`, the mbed TLS x509 library that
  *     contains embedded certificate delimiters.
  *
  * The GitHub source repository for IoTGoat was also checked and contains no
  * hardcoded certificates or private keys; it contains the same password hashes
  * and default OpenWrt configs.
  *
  * This test runs the full Goat Rodeo CBOM pipeline against the image and
  * asserts that every one of those items is represented as a CycloneDX
  * cryptographic-asset component in the emitted CBOM.
  */
class IoTGoatCbomSuite extends FunSuite {

  private val mapper = new ObjectMapper()

  private val fixture = new File("test_data/IoTGoat-x86.img.gz")

  override val munitTimeout: Duration = Duration(5, "minutes")

  private def deleteRecursive(dir: File): Unit = {
    if (dir.exists()) {
      Files
        .walk(dir.toPath)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
      ()
    }
  }

  private def allComponents(root: JsonNode): Vector[JsonNode] = {
    val components = root.path("components")
    if (components.isMissingNode || !components.isArray) {
      Vector.empty
    } else {
      components.elements().asScala.toVector
    }
  }

  private def componentText(component: JsonNode): String = {
    val parts = Vector.newBuilder[String]
    parts += component.path("name").asText("")
    parts += component.path("bom-ref").asText("")
    val props = component.path("properties")
    if (!props.isMissingNode && props.isArray) {
      props.elements().asScala.foreach { p =>
        parts += p.path("name").asText("")
        parts += p.path("value").asText("")
      }
    }
    parts.result().mkString(" ")
  }

  test(
    "T4.5 IoTGoat x86 CBOM contains all discovered static cryptographic material"
  ) {
    assume(fixture.exists(), s"IoTGoat x86 fixture required: ${fixture}")

    val outputDir = Files.createTempDirectory("iotgoat-cbom-output").toFile()
    val cbomDir = Files.createTempDirectory("iotgoat-cbom-cbom").toFile()

    try {
      new GoatRodeoBuilder()
        .withPayload(fixture.getAbsolutePath)
        .withOutput(outputDir.getAbsolutePath)
        .withCbomDir(cbomDir.getAbsolutePath)
        .withCbomVersion("1.6")
        .withThreads(4)
        .withMaxRecords(10000)
        .run()

      val cbomFiles = cbomDir.listFiles().filter(_.getName.endsWith(".json"))
      assert(
        cbomFiles.nonEmpty,
        "CBOM output directory should contain at least one CBOM file"
      )

      val components =
        cbomFiles.flatMap(f => allComponents(mapper.readTree(f))).toVector
      val componentTexts = components.map(componentText)

      assert(
        componentTexts.exists(_.contains("/etc/shadow")),
        s"CBOM should contain a component for /etc/shadow (password hashes). " +
          s"Found components: ${componentTexts.mkString(", ")}"
      )

      assert(
        componentTexts.exists(
          _.contains("/etc/dropbear/dropbear_rsa_host_key")
        ),
        s"CBOM should contain a component for the dropbear host key placeholder. " +
          s"Found components: ${componentTexts.mkString(", ")}"
      )

      assert(
        componentTexts.exists(_.contains("/etc/config/uhttpd")),
        s"CBOM should contain a component for the uhttpd TLS configuration. " +
          s"Found components: ${componentTexts.mkString(", ")}"
      )

      assert(
        componentTexts.exists(_.contains("/usr/lib/libmbedx509.so.2.14.1")),
        s"CBOM should contain a component for the mbed TLS x509 library. " +
          s"Found components: ${componentTexts.mkString(", ")}"
      )

      assert(
        componentTexts.count(_.contains("/etc/opkg/keys")) >= 1,
        s"CBOM should contain at least one component for the opkg signing keys. " +
          s"Found components: ${componentTexts.mkString(", ")}"
      )

      // Guardrail: the wireless AP is intentionally unencrypted, so the CBOM
      // must not invent a PSK or WPA key.
      assert(
        !componentTexts.exists(t =>
          t.toLowerCase.contains("wpa") || t.toLowerCase.contains("psk")
        ),
        "CBOM should not invent a WiFi PSK for the open AP"
      )
    } finally {
      deleteRecursive(outputDir)
      deleteRecursive(cbomDir)
    }
  }
}
