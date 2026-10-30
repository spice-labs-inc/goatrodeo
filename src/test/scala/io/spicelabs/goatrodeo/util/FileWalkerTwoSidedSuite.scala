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

/** Tests for the two-sided filter the walker applies before expanding anything.
  *
  * WHAT: `FileWalker.notArchive` and `notCompressed` decide with the same pair
  * as the strategies. A MIME claim that the artifact cannot be a container
  * (text, media, font, class file, PDF, SQLite) refuses expansion; otherwise
  * expansion is attempted only when some MIME says the bytes could be a
  * container.
  *
  * WHY: expansion is I/O plus a parser, and the walker sees every artifact in a
  * run — including the majority that are fragments of packages, which carry
  * `application/octet-stream` or a type Tika could not determine. The old
  * predicate was one-sided: anything not on a short "definitely not" list was
  * attempted, so unknown types paid for a failed parse. The admission half
  * makes an unknown type free while keeping every genuine container expandable.
  *
  * THEORY: two Tika misreads must never be refused, or a cloud disk image is
  * surveyed as one opaque file while the run reports success: `.vhd` arrives as
  * `text/x-vhdl` and `.raw` as `image/x-raw-*`. Those are exempted before the
  * contradiction test, which is why `text/x-vhdl` is admitted while
  * `text/plain` is refused even though both are text.
  *
  * LLM note: test ids are FW-n. FW-1/FW-2 pin the contradiction half, FW-3/FW-4
  * the admission half, FW-5 the exemptions, FW-6 the compressed pair.
  */
class FileWalkerTwoSidedSuite extends GoatRodeoFunSuite {

  private def refusedArchive(m: String): Boolean =
    FileWalker.notArchive("f", Set(m))

  private def refusedCompressed(m: String): Boolean =
    FileWalker.notCompressed("f", Set(m))

  // FW-1 — claims that cannot be a container refuse expansion.
  test("FW-1 non-container claims are refused") {
    assert(refusedArchive("text/plain"))
    assert(refusedArchive("image/png"))
    assert(refusedArchive("audio/mpeg"))
    assert(refusedArchive("video/mp4"))
    assert(refusedArchive("font/ttf"))
    assert(refusedArchive("application/java-vm"))
    assert(refusedArchive("application/pdf"))
    assert(refusedArchive("application/x-sqlite3"))
  }

  // FW-2 — an unknown type is not attempted: nothing admits it, so nothing is
  // tried. This is the admission half doing its job.
  test("FW-2 an unknown type costs nothing") {
    assert(refusedArchive("application/x-some-unknown-fragment"))
    assert(refusedCompressed("application/x-some-unknown-fragment"))
  }

  // FW-3 — every genuine container family is admitted.
  test("FW-3 container families are admitted") {
    Seq(
      "application/zip",
      "application/java-archive",
      "application/vnd.android.package-archive",
      "application/x-debian-package",
      "application/x-rpm",
      "application/x-gtar",
      "application/gzip",
      "application/zstd",
      "application/x-iso9660-image",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ).foreach { m =>
      assert(!refusedArchive(m), s"$m must be attempted")
    }
  }

  // FW-4 — the walker must attempt unknown bytes and the container types Saffron
  // understands, including ELF executables, which it expands.
  test("FW-4 unknown bytes and ELF containers are admitted") {
    Seq(
      "application/octet-stream",
      "application/x-executable",
      "application/x-sharedlib",
      "application/x-object",
      "application/x-pie-executable"
    ).foreach { m =>
      assert(!refusedArchive(m), s"$m must be attempted")
    }
  }

  // FW-5 — the two Tika extension misreads are admitted even though their family
  // is otherwise refused. This is what keeps a `.vhd` or `.raw` cloud image
  // expandable while prose is not.
  test("FW-5 the two extension misreads are admitted") {
    assert(!refusedArchive("text/x-vhdl"), "a .vhd disk is misread as text")
    assert(
      !refusedArchive("image/x-raw-panasonic"),
      "a .raw disk is misread as an image"
    )
    assert(!refusedArchive("image/x-raw-canon"))
    assert(!refusedCompressed("text/x-vhdl"))
    assert(!refusedCompressed("image/x-raw-panasonic"))
    // the same families, without the misread, are still refused
    assert(refusedArchive("text/x-java-properties"))
    assert(refusedArchive("image/jpeg"))
  }

  // FW-6 — the compressed pair admits compressed families and unknown bytes, and
  // refuses the claims that cannot be a compressed stream.
  test("FW-6 compressed decisions use the same pair") {
    Seq(
      "application/gzip",
      "application/zstd",
      "application/x-xz",
      "application/x-bzip2",
      "application/octet-stream"
    )
      .foreach(m => assert(!refusedCompressed(m), s"$m must be attempted"))
    // A ZIP, a JAR and an APK are containers for the archive path, so they are
    // not offered to the uncompress attempt.
    Seq(
      "application/zip",
      "application/java-archive",
      "application/vnd.android.package-archive"
    )
      .foreach(m =>
        assert(refusedCompressed(m), s"$m is an archive, not a stream")
      )
    Seq("text/plain", "image/png", "application/java-vm", "font/ttf")
      .foreach(m => assert(refusedCompressed(m), s"$m must be refused"))
  }
}
