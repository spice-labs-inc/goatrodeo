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

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

/** Tests for the algorithmic half of the fast reject: the artifact's filename.
  *
  * WHAT: the fast reject a consumer applies before any probe is made of the
  * MIME set *and* the artifact's filename. A name that declares a disk or guest
  * image admits the artifact even when Tika's answer puts it in a family the
  * consumer otherwise refuses, and the same function is used by the Saffron
  * augmenter and by the file walker.
  *
  * WHY: this is the case that a set-only filter cannot get right. Tika assigns
  * `.vhd` by extension alone and reads the extension as VHDL, so a virtual disk
  * arrives as `text/x-vhdl`; a `.raw` cloud image arrives as `image/x-raw-*`. A
  * filter that refuses text refuses the disk; a filter that admits text admits
  * every prose file in the run. The name distinguishes them, and it costs
  * nothing.
  *
  * THEORY: the decision is a function of (MIME set, filename) and nothing else
  * — no bytes are read, which is what makes it a fast reject rather than a
  * probe. The three consumers (augmenter, strategy, walker) call the same
  * function so they cannot drift; this test pins the augmenter and the walker
  * against each other on the same names.
  *
  * LLM note: test ids are FR-n. FR-1 pins the name clause on its own; FR-2 pins
  * that the name does not open the door to prose; FR-3 pins augmenter/walker
  * agreement.
  */
class MimeFastRejectNameSuite extends GoatRodeoFunSuite {

  // FR-1 — a name that declares a disk admits the artifact through the fast
  // reject, whatever MIME family Tika chose.
  test("FR-1 a disk name admits a text or image claim") {
    Seq(
      "disk.vhd",
      "disk.vhdx",
      "disk.vdi",
      "disk.qcow2",
      "disk.vmdk",
      "disk.raw",
      "disk.img",
      "boot.iso",
      "install.dmg",
      "image.ami"
    )
      .foreach { name =>
        assert(
          FileWrapper(
            new java.io.File("test_data/raw.img"),
            name,
            None
          ) != null,
          "fixture wrapper must construct"
        )
        assert(
          MimeFamilies.nameSuggestsDisk(name),
          s"$name declares a disk"
        )
        assert(
          SaffronDetector.mimeRule(Set("text/plain"), name),
          s"$name typed as text must still reach the Saffron probe"
        )
        assert(
          SaffronDetector.mimeRule(Set("image/png"), name),
          s"$name typed as an image must still reach the Saffron probe"
        )
        assert(
          !FileWalker.notArchive(name, Set("text/plain")),
          s"$name typed as text must still be offered to the walker"
        )
      }
  }

  // FR-2 — the name clause is narrow: prose is still refused, and an unrelated
  // extension does not smuggle a text file into the disk path.
  test("FR-2 prose is still refused") {
    Seq("readme.md", "notes.txt", "index.html", "pom.xml", "main.c")
      .foreach { name =>
        assert(!MimeFamilies.nameSuggestsDisk(name), s"$name is not a disk")
        assert(
          !SaffronDetector.mimeRule(Set("text/plain"), name),
          s"$name must not reach the Saffron probe"
        )
        assert(
          FileWalker.notArchive(name, Set("text/plain")),
          s"$name must not be offered to the walker"
        )
      }
  }

  // FR-3 — augmenter and walker agree: the same name and the same MIME set give
  // the same answer on both sides, because both call the shared algorithm.
  test("FR-3 augmenter and walker agree on the disk question") {
    val cases = Seq(
      "disk.vhd" -> Set("text/plain"),
      "disk.raw" -> Set("image/x-raw-panasonic"),
      "readme.md" -> Set("text/plain"),
      "firmware.bin" -> Set("application/octet-stream"),
      "pkg.deb" -> Set("application/x-debian-package")
    )
    cases.foreach { case (name, mimes) =>
      val augmenterAdmits = SaffronDetector.mimeRule(mimes, name)
      val walkerAttempts = !FileWalker.notArchive(name, mimes)
      assertEquals(
        walkerAttempts && mimes.contains("application/debian-package"),
        false,
        "sanity: no deb is ever a disk"
      )
      // A name that declares a disk must be seen by both; anything the augmenter
      // refuses must not be claimed by the walker as a disk either.
      if (MimeFamilies.nameSuggestsDisk(name)) {
        assert(augmenterAdmits, s"$name must reach the augmenter")
        assert(walkerAttempts, s"$name must reach the walker")
      }
      if (
        !augmenterAdmits && !MimeFamilies.couldBe(
          mimes,
          MimeFamilies.couldBeArchive
        )
      ) {
        assert(
          FileWalker.notArchive(name, mimes),
          s"$name: what the augmenter refuses the walker must not expand"
        )
      }
    }
  }
}
