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

import io.spicelabs.goatrodeo.GoatRodeoBuilder
import munit.FunSuite
import scopt.OParser

import java.io.File
import java.nio.file.Paths

/** T4.4 — README / CLI sync test for the CycloneDX CBOM flags.
  *
  * Verifies that the CLI flags documented in `README.md` (`--emit-cbom-dir` and
  * `--cbom-version`) parse correctly via `Config.parser1`, reject invalid
  * input, and are accessible from the programmatic builder API.
  */
class ConfigCbomFlagsSuite extends FunSuite {

  private def parse(args: String*): Option[Config] = {
    OParser.parse(Config.parser1, args, Config())
  }

  private def builderConfig(b: GoatRodeoBuilder): Config = {
    val field = classOf[GoatRodeoBuilder].getDeclaredField("config")
    field.setAccessible(true)
    field.get(b).asInstanceOf[Config]
  }

  test("T4.4 --emit-cbom-dir and --cbom-version parse correctly") {
    val parsed = parse(
      "-b",
      "/tmp/in",
      "--emit-cbom-dir",
      "/tmp/cbom",
      "--cbom-version",
      "1.7"
    )
    assert(parsed.isDefined, "Valid CBOM flags should parse")
    val config = parsed.get
    assertEquals(config.cbomDir, Some(new File("/tmp/cbom")))
    assertEquals(config.cbomVersion, "1.7")
  }

  test("T4.4 --cbom-version defaults to 1.6 when omitted") {
    val parsed = parse("-b", "/tmp/in", "--emit-cbom-dir", "/tmp/cbom")
    assert(parsed.isDefined)
    assertEquals(parsed.get.cbomVersion, "1.6")
  }

  test("T4.4 invalid --cbom-version is rejected") {
    val parsed = parse(
      "-b",
      "/tmp/in",
      "--emit-cbom-dir",
      "/tmp/cbom",
      "--cbom-version",
      "1.8"
    )
    assert(parsed.isEmpty, "Invalid CBOM version should be rejected")
  }

  test("T4.4 GoatRodeoBuilder exposes CBOM methods") {
    val builder = new GoatRodeoBuilder()
      .withPayload("/tmp/in")
      .withCbomDir("/tmp/cbom")
      .withCbomVersion("1.7")
    val config = builderConfig(builder)
    assertEquals(config.cbomDir, Some(Paths.get("/tmp/cbom").toFile()))
    assertEquals(config.cbomVersion, "1.7")
  }

  test("T4.4 GoatRodeoBuilder withExtraArg supports CBOM keys") {
    val builder = new GoatRodeoBuilder()
      .withPayload("/tmp/in")
      .withExtraArg("emitCbomDir", "/tmp/cbom")
      .withExtraArg("cbomVersion", "1.7")
    val config = builderConfig(builder)
    assertEquals(config.cbomDir, Some(Paths.get("/tmp/cbom").toFile()))
    assertEquals(config.cbomVersion, "1.7")
  }
}
