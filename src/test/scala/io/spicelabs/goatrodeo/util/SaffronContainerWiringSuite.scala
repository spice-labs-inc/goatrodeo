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

/** Tests for the Saffron binary-container wiring.
  *
  * WHAT: `SaffronDetector` now probes Saffron's `ContainerDetector` (the
  * "OT images" support: ELF, u-boot FIT, DTB, Linux kernel, RPi firmware,
  * Android boot, compressed-single, WIM, DMG) during the MIME pass and emits a
  * dedicated MIME per format; `FileWalker` mounts containers through
  * `BinaryContainerMount` and walks their entries like a filesystem.
  *
  * WHY: the readers existed in the Saffron library but were never connected,
  * so firmware binaries (ArduPilot's `arducopter` ELF, PX4 images) stayed
  * opaque — no inner content was ever inspected.
  *
  * THEORY: an ELF is the simplest reproducible container (any Linux host has
  * `/bin/ls`), so the tests use it as the canary: the MIME pass must tag it,
  * and the archive walk must expand it into entry artifacts.
  *
  * LLM note: C-W-xx = test id.
  */
class SaffronContainerWiringSuite extends FunSuite {

  private val ls = new File("/bin/ls")

  private def lsWrapper(): FileWrapper =
    FileWrapper(ls, ls.getPath, None)

  // C-W-01 — the MIME pass tags a real ELF binary with the Saffron ELF MIME.
  test("C-W-01 ELF binaries are tagged during MIME augmentation") {
    assert(ls.exists(), "/bin/ls required for this test")
    val mimes = lsWrapper().mimeType
    assert(
      mimes.contains("application/x-saffron-elf"),
      s"expected Saffron ELF mime, got $mimes"
    )
  }

  // C-W-02 — FileWalker mounts the ELF container and yields its entries.
  test("C-W-02 FileWalker expands an ELF into entry artifacts") {
    assert(ls.exists(), "/bin/ls required for this test")
    val result = FileWalker.withinArchiveStream(lsWrapper()) { artifacts =>
      artifacts.map(_.path())
    }
    assert(result.isDefined, "ELF should be walkable as a Saffron container")
    assert(result.get.nonEmpty, "ELF container should expose entries")
    assert(
      result.get.exists(p => p.contains(".text") || p.contains("section")),
      s"unexpected ELF entries: ${result.get.take(10)}"
    )
  }

  // C-W-03 — non-container content gains no container MIMEs.
  test("C-W-03 plain text gains no container MIMEs") {
    val w = ByteWrapper("hello world".getBytes("UTF-8"), "note.txt", None)
    val mimes = w.mimeType
    assert(SaffronDetector.containerMimeTypes.intersect(mimes).isEmpty)
  }
}
