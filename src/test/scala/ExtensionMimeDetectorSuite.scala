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

import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.ExtensionMimeDetector

class ExtensionMimeDetectorSuite extends munit.FunSuite {

  // ==================== .class magic-byte tests ====================

  test("detect - .class with CAFEBABE magic returns application/java-vm") {

    /** What: A file named `Foo.class` whose first 4 bytes are 0xCAFEBABE is
      * detected as `application/java-vm` without invoking Tika.
      *
      * Why: `.class` files are the single most common file type inside JARs
      * (60-80%). Bypassing Tika for them eliminates the majority of Tika calls.
      *
      * Requirement: Extension-based short-circuit for .class files with magic
      * byte verification.
      */
    val bytes = Array[Byte](
      0xca.toByte,
      0xfe.toByte,
      0xba.toByte,
      0xbe.toByte,
      0x00,
      0x00,
      0x00,
      0x34
    )
    val wrapper = ByteWrapper(bytes, "com/example/Foo.class", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, Some("application/java-vm"))
  }

  test(
    "detect - .class with wrong magic returns None (falls through to Tika)"
  ) {

    /** What: A file named `Foo.class` whose first 4 bytes are NOT 0xCAFEBABE
      * returns `None`, causing the caller to fall through to Tika.
      *
      * Why: A `.class` extension without matching magic bytes may indicate a
      * corrupted file, a file with a misleading extension, or a non-Java file
      * that happens to use `.class`. In these cases Tika's content-based
      * detection is still needed.
      */
    val bytes = "not a class file".getBytes("UTF-8")
    val wrapper = ByteWrapper(bytes, "Foo.class", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, None)
  }

  test("detect - .class with fewer than 4 bytes returns None") {

    /** What: A file named `Foo.class` with only 2 bytes returns `None`.
      *
      * Why: A valid `.class` file must be at least 4 bytes (the magic header).
      * Files shorter than the magic cannot be valid class files.
      */
    val bytes = Array[Byte](0xca.toByte, 0xfe.toByte)
    val wrapper = ByteWrapper(bytes, "Foo.class", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, None)
  }

  test("detect - .CLASS (uppercase extension) is matched case-insensitively") {

    /** What: A file named `FOO.CLASS` (uppercase extension) with CAFEBABE magic
      * is detected as `application/java-vm`.
      *
      * Why: File extensions should be matched case-insensitively, as some build
      * systems or filesystems produce uppercase extensions.
      */
    val bytes = Array[Byte](0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte)
    val wrapper = ByteWrapper(bytes, "FOO.CLASS", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, Some("application/java-vm"))
  }

  // ==================== Java archive magic-byte tests ====================

  test("detect - .jar with PK magic returns application/java-archive") {

    /** What: A file named `lib.jar` whose first 4 bytes are PK\x03\x04 is
      * detected as `application/java-archive` without invoking Tika.
      *
      * Why: JAR files are ZIP-format archives. The PK\x03\x04 magic is the
      * standard ZIP local file header signature. Bypassing Tika for verified
      * JARs saves the expensive Tika content-based detection.
      */
    val bytes = Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
    val wrapper = ByteWrapper(bytes, "lib.jar", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, Some("application/java-archive"))
  }

  test("detect - .war with PK magic returns application/java-archive") {

    /** What: A file named `webapp.war` with PK\x03\x04 magic is detected as
      * `application/java-archive`.
      *
      * Why: WAR files are Java Web Application Archives, also ZIP-format.
      */
    val bytes = Array[Byte](0x50, 0x4b, 0x03, 0x04)
    val wrapper = ByteWrapper(bytes, "webapp.war", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, Some("application/java-archive"))
  }

  test("detect - .ear with PK magic returns application/java-archive") {

    /** What: A file named `app.ear` with PK\x03\x04 magic is detected as
      * `application/java-archive`.
      *
      * Why: EAR files are Java Enterprise Application Archives.
      */
    val bytes = Array[Byte](0x50, 0x4b, 0x03, 0x04)
    val wrapper = ByteWrapper(bytes, "app.ear", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, Some("application/java-archive"))
  }

  test("detect - .jpi with PK magic returns application/java-archive") {

    /** What: A Jenkins plugin (.jpi) with PK\x03\x04 magic is detected as
      * `application/java-archive`.
      *
      * Why: Jenkins plugins are Java archives with a non-standard extension.
      */
    val bytes = Array[Byte](0x50, 0x4b, 0x03, 0x04)
    val wrapper = ByteWrapper(bytes, "plugin.jpi", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, Some("application/java-archive"))
  }

  test("detect - .jar with wrong magic returns None (falls through to Tika)") {

    /** What: A file named `lib.jar` whose first 4 bytes are NOT PK\x03\x04
      * returns `None`, causing the caller to fall through to Tika.
      *
      * Why: A `.jar` extension without ZIP magic bytes may indicate a corrupted
      * file, an empty file, or a non-Java file with a misleading extension.
      * Tika's content-based detection is still needed.
      */
    val bytes = "not a zip file".getBytes("UTF-8")
    val wrapper = ByteWrapper(bytes, "lib.jar", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, None)
  }

  test("detect - .jar with fewer than 4 bytes returns None") {

    /** What: A file named `lib.jar` with only 2 bytes returns `None`.
      *
      * Why: A valid ZIP/JAR file must be at least 4 bytes (the PK magic
      * header).
      */
    val bytes = Array[Byte](0x50, 0x4b)
    val wrapper = ByteWrapper(bytes, "lib.jar", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, None)
  }

  // ==================== Non-matching extension tests ====================

  test("detect - .txt returns None") {

    /** What: A file named `readme.txt` returns `None` regardless of content.
      *
      * Why: `.txt` is not in the extension short-circuit set. Tika should be
      * used for content-based detection.
      */
    val bytes = "hello world".getBytes("UTF-8")
    val wrapper = ByteWrapper(bytes, "readme.txt", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, None)
  }

  test("detect - .json returns None") {

    /** What: A file named `config.json` returns `None`.
      *
      * Why: `.json` is not currently in the extension short-circuit set. Only
      * `.class` and Java archive extensions are handled.
      */
    val bytes = """{"key":"value"}""".getBytes("UTF-8")
    val wrapper = ByteWrapper(bytes, "config.json", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, None)
  }

  test("detect - file with no extension returns None") {

    /** What: A file named `Makefile` (no extension) returns `None`.
      *
      * Why: Without an extension, there's nothing to short-circuit on. Tika
      * should be used.
      */
    val bytes = "all: build".getBytes("UTF-8")
    val wrapper = ByteWrapper(bytes, "Makefile", None)
    val result = ExtensionMimeDetector.detect(wrapper)
    assertEquals(result, None)
  }

  // ==================== Integration tests via mimeType ====================

  test(
    "mimeType - .class with CAFEBABE bypasses Tika and returns application/java-vm"
  ) {

    /** What: When `mimeType` is accessed on a ByteWrapper with `.class`
      * extension and CAFEBABE magic, the result is `application/java-vm`.
      *
      * Why: This verifies the wiring of `ExtensionMimeDetector` into the
      * `_mimeType` lazy val. The MIME type should be determined without
      * invoking Tika, and the augmenter chain should still run (but
      * `augmentationCannotApply` returns true for `application/java-vm`, so
      * augmenters are skipped — same as the Tika path).
      */
    val bytes = Array[Byte](
      0xca.toByte,
      0xfe.toByte,
      0xba.toByte,
      0xbe.toByte,
      0x00,
      0x00,
      0x00,
      0x34
    )
    val wrapper = ByteWrapper(bytes, "com/example/Foo.class", None)
    val mimes = wrapper.mimeType
    assert(
      mimes.contains("application/java-vm"),
      s"Expected application/java-vm in $mimes"
    )
  }

  test(
    "mimeType - .jar with PK magic bypasses Tika and returns application/java-archive"
  ) {

    /** What: When `mimeType` is accessed on a ByteWrapper with `.jar` extension
      * and PK\x03\x04 magic, the result includes `application/java-archive`.
      *
      * Why: This verifies the wiring of `ExtensionMimeDetector` into the
      * `_mimeType` lazy val for Java archives. The MIME type should be
      * determined without invoking Tika, and the augmenter chain should still
      * run (but `augmentationCannotApply` returns true for
      * `application/java-archive`, so augmenters are skipped — same as the Tika
      * path).
      */
    val bytes = Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
    val wrapper = ByteWrapper(bytes, "lib.jar", None)
    val mimes = wrapper.mimeType
    assert(
      mimes.contains("application/java-archive"),
      s"Expected application/java-archive in $mimes"
    )
  }

  test("mimeType - .class with wrong magic falls through to Tika") {

    /** What: When `mimeType` is accessed on a ByteWrapper with `.class`
      * extension but wrong magic bytes, the result is NOT `application/java-vm`
      * — Tika determines the type instead.
      *
      * Why: This verifies the fallback path. When magic bytes don't match,
      * `ExtensionMimeDetector.detect` returns `None`, and the original
      * Tika-based detection runs.
      */
    val bytes = "not a class file".getBytes("UTF-8")
    val wrapper = ByteWrapper(bytes, "Foo.class", None)
    val mimes = wrapper.mimeType
    assert(
      !mimes.contains("application/java-vm"),
      s"Should not contain application/java-vm: $mimes"
    )
  }

  test("mimeType - .jar with wrong magic falls through to Tika") {

    /** What: When `mimeType` is accessed on a ByteWrapper with `.jar` extension
      * but wrong magic bytes, the result does NOT contain
      * `application/java-archive` — Tika determines the type instead.
      *
      * Why: This verifies the fallback path for Java archives.
      */
    val bytes = "not a zip file".getBytes("UTF-8")
    val wrapper = ByteWrapper(bytes, "lib.jar", None)
    val mimes = wrapper.mimeType
    assert(
      !mimes.contains("application/java-archive"),
      s"Should not contain application/java-archive: $mimes"
    )
  }
}
