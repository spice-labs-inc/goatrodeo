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

package io.spicelabs.goatrodeo.docs

import munit.FunSuite

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** T4.5 — ADR existence and structure test.
  *
  * Verifies that every ADR required by R4.7 exists in `docs/adr/`, contains a
  * human-friendly section, and contains an LLM-friendly section.
  */
class AdrExistenceSuite extends FunSuite {

  private val expectedAdrs = Vector(
    "docs/adr/0001-openssl-config-mime-augmenter.md",
    "docs/adr/0002-openssl-config-strategy.md",
    "docs/adr/0004-java-security-strategy.md",
    "docs/adr/0005-cbom-output-format.md"
  )

  private def readAdr(path: String): String = {
    new String(
      Files.readAllBytes(new File(path).toPath),
      StandardCharsets.UTF_8
    )
  }

  expectedAdrs.foreach { adrPath =>
    test(s"T4.5 $adrPath exists with human and LLM sections") {
      val f = new File(adrPath)
      assert(f.exists(), s"ADR file must exist: $adrPath")
      assert(f.isFile, s"ADR path must be a file: $adrPath")
      val content = readAdr(adrPath)
      assert(
        content.contains("## Context") || content.contains("## Decision"),
        s"$adrPath must contain a human-friendly section (Context/Decision)"
      )
      assert(
        content.contains("## LLM-Friendly Summary"),
        s"$adrPath must contain an LLM-friendly section"
      )
    }
  }
}
