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

import java.io.File

/** Tests for the ArduPilot `AP_ROMFS` embedded file store reader.
  *
  * WHAT: `ApRomfs.read` parses the generated `embedded_file` table out of an
  * ArduPilot firmware ELF and decompresses each ROMFS file; `FileWalker`
  * treats the ROMFS as an archive.
  *
  * WHY: ArduPilot firmware embeds its ROMFS (including TLS trust-store
  * certificates) as compressed structs — not a self-describing container —
  * so nothing could reach the certs. The corpus fixture
  * `test_data/firmware-images/ardupilot/arducopter` is a Surveyor-OT-Demo
  * build carrying `etc/ssl/certs/root-ca.crt` (RSA-1024).
  *
  * THEORY: the AP_ROMFS struct layout is fixed by ArduPilot (raw-DEFLATE
  * contents; pointer width from the ELF class). Anchoring on a known ROMFS
  * filename locates the table; decompressing each `contents` yields the
  * files.
  *
  * LLM note: AR-x = test id.
  */
class ApRomfsSuite extends FunSuite {

  private val arducopter =
    new File("test_data/firmware-images/ardupilot/arducopter")

  test("AR-1 ApRomfs decodes the Surveyor OT Demo trust-store certs") {
    assume(arducopter.exists(), "arducopter fixture required")
    val w = FileWrapper(arducopter, arducopter.getPath, None)
    val files = ApRomfs.read(w)
    assert(files.isDefined, "arducopter should be an AP_ROMFS ELF")
    val byName = files.get.toMap
    val rootCa = byName.get("etc/ssl/certs/root-ca.crt")
    val signer = byName.get("etc/ssl/certs/update-signer.crt")
    assert(rootCa.isDefined, "root-ca.crt should be present in ROMFS")
    assert(signer.isDefined, "update-signer.crt should be present in ROMFS")
    assert(new String(rootCa.get, "UTF-8").contains("-----BEGIN CERTIFICATE-----"))
    assert(new String(signer.get, "UTF-8").contains("-----BEGIN CERTIFICATE-----"))
  }

  test("AR-2 FileWalker treats the firmware as an archive") {
    assume(arducopter.exists(), "arducopter fixture required")
    val w = FileWrapper(arducopter, arducopter.getPath, None)
    val result = FileWalker.withinArchiveStream(w) { artifacts =>
      artifacts.map(_.path())
    }
    assert(result.isDefined, "arducopter should be walkable as an AP_ROMFS archive")
    val paths = result.get
    assert(
      paths.contains("etc/ssl/certs/root-ca.crt"),
      s"expected root-ca.crt in ROMFS entries, got ${paths.take(8)}"
    )
    assert(
      paths.contains("etc/ssl/certs/update-signer.crt"),
      s"expected update-signer.crt in ROMFS entries"
    )
  }

  test("AR-3 non-ROMFS binaries yield nothing") {
    val w = ByteWrapper("not an elf or romfs".getBytes("UTF-8"), "x.bin", None)
    assertEquals(ApRomfs.read(w), None)
  }
}