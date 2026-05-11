/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

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
import io.spicelabs.goatrodeo.util.Config

import java.util.Date

/** Phase 1 TDD Tests: PackageTagInfo data structure and CLI parsing
  *
  * These tests verify:
  *   - PackageTagInfo case class with Option types
  *   - json4s serialization omits None fields
  *   - Config CLI options for --package-tags and --package-tags-short-name
  *   - GoatRodeoBuilder API methods
  *
  * Requirement Traceability:
  *   - R1: --package-tags CLI option
  *   - R2: --package-tags-short-name CLI option
  *   - R3: Per-package tag JSON structure with optional version
  */
class PackageTagInfoSuite extends munit.FunSuite {

  // ==================== PackageTagInfo Construction Tests ====================

  test("PackageTagInfo construction with Options") {
    // RED: Compilation error - PackageTagInfo doesn't exist
    // Theory: Basic case class should be instantiable with Option types
    // Requirement: R3
    val info = PackageTagInfo(
      name = "org.example:test-artifact",
      version = Some("1.0.0"),
      date = Some(new Date())
    )
    assertEquals(info.name, "org.example:test-artifact")
    assertEquals(info.version, Some("1.0.0"))
    assert(info.date.isDefined)
  }

  test("PackageTagInfo construction with None version") {
    // RED: Compilation error
    // Theory: Version should be optional
    // Requirement: R3
    val info = PackageTagInfo(
      name = "test-package",
      version = None,
      date = Some(new Date())
    )
    assertEquals(info.name, "test-package")
    assertEquals(info.version, None)
  }

  test("PackageTagInfo construction with None date") {
    // RED: Compilation error
    // Theory: Date should be optional (falls back to current)
    // Requirement: R3
    val info = PackageTagInfo(
      name = "test-package",
      version = Some("1.0.0"),
      date = None
    )
    assertEquals(info.name, "test-package")
    assertEquals(info.date, None)
  }

  // ==================== JSON Serialization Tests ====================

  test("PackageTagInfo JSON omits None version") {
    // RED: No json4s serializer
    // Theory: json4s should omit None fields from output
    // Requirement: R3
    val info = PackageTagInfo("test-package", None, Some(new Date()))
    val json = PackageTagInfo.toJson(info)

    // Should not contain version field
    assert(
      !json.contains("\"version\""),
      s"JSON should not contain version field: $json"
    )
    assert(json.contains("\"tag\""), s"JSON should contain tag field: $json")
    assert(json.contains("\"date\""), s"JSON should contain date field: $json")
  }

  test("PackageTagInfo JSON includes Some version") {
    // RED: No json4s serializer
    // Theory: json4s should include Some fields
    // Requirement: R3
    val info = PackageTagInfo("test-package", Some("1.0.0"), Some(new Date()))
    val json = PackageTagInfo.toJson(info)

    assert(
      json.contains("\"version\""),
      s"JSON should contain version field: $json"
    )
    assert(
      json.contains("\"version\":\"1.0.0\""),
      s"JSON should have version value: $json"
    )
  }

  test("PackageTagInfo date converts to ISO 8601") {
    // RED: No date formatter
    // Theory: Date should be formatted as ISO 8601 UTC
    // Requirement: R3
    val date = new Date(1609459200000L) // 2021-01-01 00:00:00 UTC
    val info = PackageTagInfo("test", Some("1.0"), Some(date))
    val json = PackageTagInfo.toJson(info)

    assert(json.contains("2021-01-01"), s"JSON should contain ISO date: $json")
    assert(json.contains("T"), s"ISO date should have T separator: $json")
    assert(json.contains("Z"), s"ISO date should end with Z: $json")
  }

  // ==================== Config Tests ====================

  test("Config.packageTags defaults false") {
    // RED: Field doesn't exist
    // Theory: Boolean should default to false for backward compatibility
    // Requirement: R1
    val config = Config()
    assertEquals(config.packageTags, false)
  }

  test("Config.packageTagsShortName defaults false") {
    // RED: Field doesn't exist
    // Theory: Boolean should default to false
    // Requirement: R2
    val config = Config()
    assertEquals(config.packageTagsShortName, false)
  }

  // Note: CLI parsing tests require scopt integration
  // These are integration tests that will be covered by the actual CLI

  // ==================== GoatRodeoBuilder Tests ====================

  test("GoatRodeoBuilder.withPackageTags") {
    // RED: Method doesn't exist
    // Theory: Builder API should have method to enable package tags
    // Requirement: R1
    val builder = new GoatRodeoBuilder()
    val result = builder.withPackageTags()
    // Just verify it compiles and returns builder for chaining
    assert(result.isInstanceOf[GoatRodeoBuilder])
  }

  test("GoatRodeoBuilder.withPackageTagsShortName") {
    // RED: Method doesn't exist
    // Theory: Builder API should have method for short names
    // Requirement: R2
    val builder = new GoatRodeoBuilder()
    val result = builder.withPackageTagsShortName()
    assert(result.isInstanceOf[GoatRodeoBuilder])
  }

}
