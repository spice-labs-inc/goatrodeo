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

package io.spicelabs.goatrodeo.omnibor

import ch.qos.logback.classic.Level
import io.spicelabs.goatrodeo.GoatRodeoBuilder
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.testsupport.LogCapture
import io.spicelabs.goatrodeo.testsupport.RunOutputs
import io.spicelabs.goatrodeo.testsupport.RunOutputs.Run

import java.io.File
import java.nio.file.Files

/** Disk images: their files must reach the ADG as items.
  *
  * ==What this suite tests==
  *
  * A disk image is expanded by `FileWalker.asSaffronFilesystem`, which mounts
  * each filesystem and wraps every regular file. This suite asserts the step
  * after that: the wrappers become **items** in the ADG, carrying the path they
  * had inside the filesystem and the bytes they had inside the image.
  *
  * ==Why it exists==
  *
  * `SaffronTests` asserts what `FileWalker.withinArchiveStream` returns —
  * wrapper artifacts, matched on `filenameWithNoPath`. It stops one step short
  * of what a consumer sees. Nothing in the tree asserted that those wrappers
  * become items, or that an item's recorded name is the in-filesystem path
  * rather than a basename, or that the item's size reflects the image's bytes.
  * So a change that dropped a filesystem, flattened the paths, or populated
  * items from empty placeholders would pass every existing test.
  * `IoTGoatCbomSuite` is the one existing end-to-end image test; it checks five
  * crypto-relevant paths through the CBOM on one image, which is a precedent
  * for this harness rather than a substitute for this coverage.
  *
  * ==Theory of the path==
  *
  * `FileWalker` names each wrapper with Saffron's `regular.path()` — the path
  * inside the filesystem, with no leading slash — and `ToProcess` records
  * `Vector(artifact.path())` as the item's file name. So the in-filesystem path
  * is what a consumer matches on (Surveyor's `path_suffix` selection, the
  * documented `boot/grub/stage1` shape), and it must survive to the item.
  *
  * ==LLM summary==
  *
  * | Test | Asserts                                                                                                                    |
  * |:-----|:---------------------------------------------------------------------------------------------------------------------------|
  * | S-1  | `raw.img`'s files become items with their in-filesystem paths, measured sizes and MIME types                               |
  * | S-2  | `alpine-minimal.squashfs` contributes at least 40 file items (58 measured) including `bin/busybox` and `etc/os-release`    |
  * | S-3  | `fsFilePaths` does not change in-container names                                                                           |
  * | S-4  | a recognised disk with no mountable filesystem is surveyed as one opaque artifact, logs no error, and yields no file items |
  * | S-5  | a raw disk named `.raw` is recognised as a Saffron disk and its files become items                                         |
  */
class SaffronImageItemSuite extends GoatRodeoFunSuite {

  /** Sizes measured on 2026-10-14 from the image itself, so the assertion is
    * against the image's contents and not against whatever the pipeline last
    * produced.
    */
  private val rawImageFiles: Vector[(String, Long, String)] = Vector(
    ("files/small.txt", 21L, "text/plain"),
    ("files/README.md", 15L, "text/markdown")
  )

  /** A run over a single fixture.
    *
    * @param fixture
    *   the file to survey, taken from `test_data`
    * @param fsFilePaths
    *   whether the run should record full filesystem paths for top-level files
    * @param subdirectory
    *   an optional subdirectory of the input to place the fixture in, so that
    *   the fixture's path relative to the build root differs from its basename
    *   and `fsFilePaths` therefore has an observable effect
    * @param rename
    *   an optional name to give the fixture in the input directory, so a test
    *   can exercise what the extension does to MIME detection
    * @return
    *   the run's directories, to be cleaned up by the caller
    */
  private def build(
      fixture: String,
      fsFilePaths: Boolean = false,
      subdirectory: Option[String] = None,
      rename: Option[String] = None
  ): Run = {
    val input = Files.createTempDirectory("img-in").toFile()
    val output = Files.createTempDirectory("img-out").toFile()
    val json = Files.createTempDirectory("img-json").toFile()
    val f = new File("test_data", fixture)
    val destination = subdirectory match {
      case Some(sub) =>
        val dir = new File(input, sub)
        dir.mkdirs()
        new File(dir, rename.getOrElse(fixture))
      case None => new File(input, rename.getOrElse(fixture))
    }
    Files.copy(f.toPath, destination.toPath)
    new GoatRodeoBuilder()
      .withPayload(input.getAbsolutePath)
      .withOutput(output.getAbsolutePath)
      .withExtraArg("emitJsonDir", json.getAbsolutePath)
      .withFsFilePaths(fsFilePaths)
      .withThreads(2)
      .withMaxRecords(50000)
      .run()
    RunOutputs.deleteTree(input)
    RunOutputs.Run(output, json)
  }

  /** Every (file name, size, MIME) triple the run recorded for items that carry
    * at least one file name.
    *
    * @param run
    *   the run to read
    * @return
    *   one triple per recorded name
    */
  private def namedItems(run: Run): Vector[(String, Long, Set[String])] =
    run.batches
      .flatMap(d => RunOutputs.readItemsFromGrd(d))
      .flatMap(i =>
        i.bodyAsItemMetaData.toVector.flatMap(md =>
          md.fileNames.toVector
            .map(name => (name, md.fileSize, md.mimeType.toSet))
        )
      )

  // S-1 — files inside a raw disk image become items carrying their
  // in-filesystem paths.
  // THEORY: the ADG is the product; a wrapper with the right name that never
  // becomes an item is invisible to every consumer. Asserting the path at the
  // item makes the disk-image support observable, and the size is the cheapest
  // evidence that the item was populated from the image's bytes rather than
  // from a zero-length placeholder. This is the class of per-file check that
  // found the FAT 8.3 case-mapping defect downstream, where a filesystem's
  // names came out upper-cased and every existing count-based test still passed.
  test("S-1 raw disk image files become items with their in-filesystem paths") {
    val run = build("raw.img")
    try {
      val named = namedItems(run)
      rawImageFiles.foreach { case (path, size, mime) =>
        val matches = named.filter(_._1 == path)
        assertEquals(
          matches.length,
          1,
          s"expected exactly one item named $path, found ${matches
              .map(_._1)} among ${named.map(_._1).sorted}"
        )
        val (_, gotSize, gotMimes) = matches.head
        assertEquals(
          gotSize,
          size,
          s"$path: the item's size must be the file's size inside the image"
        )
        assert(
          gotMimes.contains(mime),
          s"$path: expected MIME $mime, got ${gotMimes.toVector.sorted}"
        )
      }

      // the image itself is also an item, and carries the Saffron disk MIME
      assert(
        named.exists { case (name, _, mimes) =>
          name == "raw.img" && mimes.contains("application/x-saffron-raw-disk")
        },
        s"the image must be an item tagged with the Saffron raw-disk MIME, got ${named.map(_._1).sorted}"
      )
    } finally run.cleanup()
  }

  // S-2 — a disk image contributes at least as many file items as it has files.
  // THEORY: this is the completeness check for image expansion. `FileWalker`
  // returns None when no filesystem mounted, so a run that loses a whole
  // filesystem still produces a well-formed ADG; only a count catches it. The
  // floor was measured on 2026-10-14: the squashfs run yields 58 items with a
  // file name (the image's own files, plus the ELF sections and segments of the
  // binaries inside it, which are themselves containers). It is set well below
  // the measurement so ordinary variation does not cause churn, while a change
  // that halves the expansion still fails.
  test("S-2 squashfs contributes its files as items") {
    val run = build("alpine-minimal.squashfs")
    try {
      val named = namedItems(run)
      val names = named.map(_._1).toSet

      assert(
        names.contains("bin/busybox"),
        s"expected an item for bin/busybox, got ${names.toVector.sorted.take(20)}"
      )
      assert(
        names.contains("etc/os-release"),
        s"expected an item for etc/os-release, got ${names.toVector.sorted.take(20)}"
      )
      assert(
        named.length >= 40,
        s"expected at least 40 items with a file name (58 measured on 2026-10-14), found ${named.length}"
      )
    } finally run.cleanup()
  }

  // S-3 — image file paths are the in-filesystem paths, independent of
  // `fsFilePaths`.
  // THEORY: `fsFilePaths` rewrites the TOP-LEVEL file's logical name to its path
  // relative to the build root (`ToProcess`); names of files inside a container
  // come from the container walker instead. `--fs-file-paths` is user-visible,
  // so if a change made it prefix in-container names with the host path, every
  // consumer that matches on in-container paths would break. The assertion is
  // equality between the two runs rather than a literal name, so it stays
  // correct if the in-container convention is ever deliberately changed in both
  // branches at once.
  //
  // The fixture is placed in a subdirectory so that the flag has an observable
  // effect: with the fixture at the build root its relative path equals its
  // basename, `fsFilePaths` would change nothing at all, and the test would pass
  // without ever exercising the flag. The assertion that the top-level name DOES
  // differ between the runs is what makes this test non-vacuous.
  test("S-3 fsFilePaths does not change in-container names") {
    val off = build("raw.img", fsFilePaths = false, subdirectory = Some("sub"))
    val on = build("raw.img", fsFilePaths = true, subdirectory = Some("sub"))
    try {
      // The disk item is identified by its MIME, not by the shape of its name:
      // with the flag on its name contains a slash, with the flag off it does
      // not, so filtering on a slash would silently move the item between the
      // two sets being compared.
      def isDisk(mimes: Set[String]): Boolean =
        mimes.contains("application/x-saffron-raw-disk")

      def diskNames(run: Run): Set[String] =
        namedItems(run).collect { case (n, _, m) if isDisk(m) => n }.toSet

      def innerNames(run: Run): Set[String] =
        namedItems(run).collect { case (n, _, m) if !isDisk(m) => n }.toSet

      // non-vacuity: the flag must actually have changed the disk item's name
      assert(
        diskNames(on).contains("sub/raw.img"),
        s"fsFilePaths=true must record the path relative to the build root, got ${diskNames(on).toVector.sorted}"
      )
      assert(
        diskNames(off).contains("raw.img"),
        s"fsFilePaths=false must record the basename, got ${diskNames(off).toVector.sorted}"
      )

      assertEquals(
        innerNames(on),
        innerNames(off),
        "fsFilePaths must not change the names of files inside a disk image"
      )
      assert(
        innerNames(off).contains("files/small.txt"),
        s"expected the in-container path files/small.txt, got ${innerNames(off).toVector.sorted}"
      )
      assert(
        !innerNames(off).exists(_.contains("test_data")),
        s"an in-container name must not carry the host path: ${innerNames(off).toVector.sorted}"
      )
    } finally {
      off.cleanup()
      on.cleanup()
    }
  }

  // S-4 — a recognised disk with no filesystem yields no file items and does not
  // fail the run.
  // THEORY: `test_data/minimal.raw` is the interesting middle case. It is 1 MiB
  // of zeros with only the MBR boot signature at offset 0x1FE: no partition
  // table, no filesystem. Saffron recognises it as a disk — the detector
  // promotes it to `application/x-saffron-raw-disk` and its probe reports
  // DISK_RAW — but `FileSystemMount.mountAll` returns nothing to mount. The
  // required behaviour is that the run succeeds and the disk is surveyed as one
  // opaque artifact: not that files appear, and not that the run fails. This
  // pins the boundary between "not a disk" (which must not be expanded) and "a
  // disk with nothing to mount" (which must not be an error), which is easy to
  // break in either direction.
  //
  // The MIME assertion is the one Surveyor's image cases cannot make; its
  // coverage manifest records that test as partial precisely because "the MIME
  // types are not checked".
  test("S-4 a disk with no filesystem is surveyed as one opaque artifact") {
    val (outcome, events) = LogCapture { () =>
      build("minimal.raw")
    }
    val run = outcome
    try {
      // the run completed: a batch directory with a cluster file exists
      assert(
        run.batches.nonEmpty,
        "a disk with no filesystem must still produce a completed run"
      )

      val named = namedItems(run)
      val diskItem = named.filter(_._1 == "minimal.raw")
      assertEquals(
        diskItem.length,
        1,
        s"expected one item for the disk itself, got ${named.map(_._1).sorted}"
      )
      assert(
        diskItem.head._3.contains("application/x-saffron-raw-disk"),
        s"the disk must carry the Saffron raw-disk MIME, got ${diskItem.head._3.toVector.sorted}"
      )

      // nothing was expanded, because there was nothing to mount
      assertEquals(
        named.map(_._1).toSet,
        Set("minimal.raw"),
        "a disk with no filesystem must not yield file items"
      )

      // and the run did not report an error for it
      val errors = events
        .filter(e => e.getLevel == Level.ERROR)
        .map(_.getFormattedMessage)
      assert(
        errors.isEmpty,
        s"a disk with no filesystem must not log an error, got: ${errors.take(5)}"
      )
    } finally run.cleanup()
  }
  // S-5 — a raw disk named `.raw` is recognised and walked.
  // THEORY: Tika types a `.raw` file as a Panasonic camera RAW photo
  // (`image/x-raw-panasonic`), by extension alone. An `image/` type is terminal
  // for MIME augmentation and `FileWalker.notArchive` rejects anything under
  // `image/`, so the disk is never even offered to Saffron: a Debian cloud image
  // shipped as `.raw` is surveyed as one opaque file. The extension must not
  // decide the format — the bytes must — so the detector has to be allowed to
  // re-check a camera RAW type and, when Saffron recognises the file, to drop
  // it. `S-4` covers the recognised-but-unmountable case; this covers the case
  // where there is a filesystem to walk, and is the one whose loss would be
  // silent, because the run would still succeed.
  //
  // The fixture is `raw.img` renamed to `disk.raw`, so the bytes are identical
  // to `S-1`'s and the only variable is the extension.
  test("S-5 a raw disk named .raw is recognised and its files become items") {
    val run = build("raw.img", rename = Some("disk.raw"))
    try {
      val named = namedItems(run)

      val diskItem = named.filter(_._1 == "disk.raw")
      assertEquals(
        diskItem.length,
        1,
        s"expected one item for the disk itself, got ${named.map(_._1).sorted}"
      )
      assert(
        diskItem.head._3.contains("application/x-saffron-raw-disk"),
        s"the .raw disk must carry the Saffron raw-disk MIME, got ${diskItem.head._3.toVector.sorted}"
      )
      assert(
        !diskItem.head._3.exists(_.startsWith("image/")),
        s"the camera-RAW misreading must be dropped, got ${diskItem.head._3.toVector.sorted}"
      )

      // and the filesystem inside it was walked, not skipped
      rawImageFiles.foreach { case (path, size, _) =>
        val matches = named.filter(_._1 == path)
        assertEquals(
          matches.length,
          1,
          s"expected exactly one item named $path, got ${named.map(_._1).sorted}"
        )
        assertEquals(
          matches.head._2,
          size,
          s"$path: the item's size must be the file's size inside the image"
        )
      }
    } finally run.cleanup()
  }
}
