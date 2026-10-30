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

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper

import java.nio.charset.StandardCharsets

/** Tests for the byte ceiling on JSON the Docker strategy parses.
  *
  * WHAT: every JSON read in the Docker strategy — the docker-save arm's
  * `manifest.json`, its candidate blob scan and its config blobs, and the OCI
  * arm's index, manifests and config — goes through `readJsonCapped` with
  * `MaxOciJsonBytes` (16 MiB). A blob over the ceiling is refused, not
  * truncated and not parsed.
  *
  * WHY: a batch can contain a file named `manifest.json` that is not a
  * docker-save manifest at all — a browser extension package carries one at its
  * root, for instance. Entering the arm is cheap and failing the shape test is
  * cheap, but parsing an arbitrarily large JSON document to discover that is
  * not. The ceiling makes that worst case bounded. It never fires on a real
  * image: an image manifest is a few KB, and a registry will not accept one
  * above 4 MiB.
  *
  * THEORY: the cap is a byte ceiling on the blob, checked by reading one byte
  * past it, so a document that does not fit is rejected outright rather than
  * parsed in part. That matters because a truncated JSON document is not a
  * smaller document — it is a different, invalid one, and parsing it would
  * produce either a parse failure or, worse, a plausible-looking partial
  * structure.
  *
  * LLM note: test ids are DJ-n. `DJ-1` pins the refusal, `DJ-2` pins that a
  * document inside the ceiling is parsed normally, `DJ-3` pins that the ceiling
  * does not disturb the real corpus fixture.
  */
class DockerJsonCapSuite extends GoatRodeoFunSuite {

  private def bytes(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.ISO_8859_1)

  // DJ-1 — a blob larger than the ceiling is refused, not parsed.
  test("DJ-1 a JSON blob over the ceiling is refused") {
    val over =
      ByteWrapper(bytes("[" + "1," * 100 + "1]"), "manifest.json", None)
    assertEquals(DockerToProcess.readJsonCapped(over, 64), None)
    // exactly at the ceiling is still refused once it exceeds it by one byte
    assertEquals(
      DockerToProcess.readJsonCapped(over, over.size().toInt - 1),
      None
    )
  }

  // DJ-2 — a document that fits is parsed, so the ceiling cannot silently disable
  // ordinary detection.
  test("DJ-2 a JSON blob inside the ceiling is parsed") {
    val doc =
      ByteWrapper(bytes("""{"schemaVersion":2}"""), "manifest.json", None)
    assert(
      DockerToProcess.readJsonCapped(doc, 1024).isDefined,
      "a small document must parse"
    )
    assert(
      DockerToProcess.readJsonCapped(doc, 16 * 1024 * 1024).isDefined,
      "the production ceiling must admit a small document"
    )
  }

  // DJ-3 — the ceiling is above anything the corpus contains. The tar itself is
  // 1.1 GB because it holds layer tarballs; the JSON inside it is what matters,
  // so the manifest member is extracted and measured rather than the archive.
  test("DJ-3 the corpus manifest fits well under the ceiling") {
    val f = new java.io.File(
      "test_data/download/docker_tests/grinder_bt_pg_docker.tar"
    )
    assert(f.exists(), s"corpus fixture missing: ${f.getAbsolutePath}")
    val tar =
      new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
        new java.io.BufferedInputStream(new java.io.FileInputStream(f))
      )
    var manifestBytes: Array[Byte] = Array.emptyByteArray
    try {
      var entry = tar.getNextEntry
      while (entry != null && manifestBytes.isEmpty) {
        if (entry.getName == "manifest.json") manifestBytes = tar.readAllBytes()
        entry = tar.getNextEntry
      }
    } finally tar.close()

    assert(manifestBytes.nonEmpty, "the corpus must contain a manifest.json")
    assert(
      manifestBytes.length < DockerToProcess.MaxOciJsonBytes,
      s"the corpus manifest is ${manifestBytes.length} bytes, below the ceiling"
    )
    val doc = ByteWrapper(manifestBytes, "manifest.json", None)
    assert(
      DockerToProcess
        .readJsonCapped(doc, DockerToProcess.MaxOciJsonBytes)
        .isDefined,
      "the corpus manifest must parse under the production ceiling"
    )
  }
}
