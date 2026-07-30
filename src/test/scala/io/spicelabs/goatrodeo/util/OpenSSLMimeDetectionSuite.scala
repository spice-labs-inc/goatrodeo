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

package io.spicelabs.goatrodeo.util

import munit.FunSuite
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties

import java.io.File

/** Phase 0 — Evaluate MIME detection for the collected OpenSSL configuration
  * corpus.
  *
  * Apache Tika classifies `.cnf` files as `text/plain`. The custom
  * `OpenSSLConfigDetector` augmenter adds `application/x-openssl-config` when
  * the content looks like an OpenSSL config. These tests verify that the corpus
  * is detected correctly.
  *
  * The augmenter is intentionally conservative: it detects the main OpenSSL
  * config files and most test/demo configs, but specialized files such as OID
  * tables or certificate-transparency log lists may fall through to
  * `text/plain`. That is acceptable because Phase 1 only needs to capture files
  * with TLS/security configuration semantics.
  */
class OpenSSLMimeDetectionSuite extends FunSuite {

  private val corpusDir = new File("test_data/openssl_configs")

  private val expectedMime = OpenSSLConfigDetector.OpenSSLConfigMimeType

  private val MinimumDetectionRate = 0.85

  private def corpusFiles(): Seq[File] = {
    if (!corpusDir.exists() || !corpusDir.isDirectory) {
      throw new RuntimeException(
        s"OpenSSL config corpus not found at ${corpusDir.getAbsolutePath}. " +
          "Run workspace/collect_openssl_configs.py first."
      )
    }
    corpusDir
      .listFiles()
      .filter(_.isFile)
      .filter(_.getName.endsWith(".cnf"))
      .toSeq
  }

  private def detectedMimes(file: File): Set[String] = {
    val wrapper = FileWrapper(file, file.getName, None)
    wrapper.mimeType
  }

  test("OpenSSL config corpus is detected with the custom MIME type") {
    val files = corpusFiles()
    assert(files.nonEmpty, "corpus must contain at least one .cnf file")

    val detectionResults = files.map { file =>
      detectedMimes(file).contains(expectedMime)
    }
    val detectedCount = detectionResults.count(identity)
    val rate = detectedCount.toDouble / files.size

    assert(
      rate >= MinimumDetectionRate,
      s"Expected at least ${(MinimumDetectionRate * 100).toInt}% detection, " +
        s"got ${(rate * 100).toInt}% ($detectedCount/${files.size})"
    )
  }

  test("Tika classifies a representative OpenSSL config as text/plain") {
    val file = new File(corpusDir, "3.2.1_apps_openssl.cnf.cnf")
    assert(file.exists(), s"Representative fixture must exist: ${file.getName}")

    val tika = new TikaConfig()
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName)
    val detected = tika.getDetector.detect(
      TikaInputStream.get(file.toPath),
      metadata
    )
    assertEquals(detected.toString, "text/plain")
  }

  test("Main OpenSSL config files from each release are detected") {
    val mainConfigs = Seq(
      "0.9.8zh_apps_openssl.cnf.cnf",
      "1.0.0t_apps_openssl.cnf.cnf",
      "1.0.2u_apps_openssl.cnf.cnf",
      "1.1.0l_apps_openssl.cnf.cnf",
      "1.1.1w_apps_openssl.cnf.cnf",
      "3.0.13_apps_openssl.cnf.cnf",
      "3.1.5_apps_openssl.cnf.cnf",
      "3.2.1_apps_openssl.cnf.cnf"
    )

    val existing = mainConfigs.filter { name =>
      new File(corpusDir, name).exists()
    }

    val missing = existing.filter { name =>
      !detectedMimes(new File(corpusDir, name)).contains(expectedMime)
    }

    assert(
      missing.isEmpty,
      s"Main OpenSSL configs not detected: ${missing.mkString(", ")}"
    )
  }
}
