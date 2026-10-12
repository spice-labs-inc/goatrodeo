import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File
import java.nio.file.Files
class SaffronTests extends GoatRodeoFunSuite {

  // this takes a compressed disk image file, decompresses it, then
  // tries to open it with (ultimately) saffron and
  // ensure that it generates artifacts, that it has the
  // expected number of files and that the generated
  // artifacts contain at least two files: small.txt and puddle_jumper_octodex.jpg
  def testDiskImage(file: File, expectedFiles: Int): Unit = {
    assert(file.exists())
    val artifact: ArtifactWrapper =
      FileWrapper(file, file.getName(), None)
    val openedArchiveOpt = FileWalker.withinArchiveStream(artifact)(vec => {
      assertEquals(vec.length, 1)
      val image = vec(0)
      val successOpt = FileWalker.withinArchiveStream(image)(files => {
        assertEquals(files.length, expectedFiles)
        assert(files.exists(art => art.filenameWithNoPath == "small.txt"))
        assert(
          files.exists(art =>
            art.filenameWithNoPath == "puddle_jumper_octodex.jpg"
          )
        )
        ()
      })
      assert(successOpt.isDefined)
    })
    assert(openedArchiveOpt.isDefined)
  }

  test("check minimal vhdx for files") {
    val vhdxzip = File("test_data/basicvhdx.zip")
    testDiskImage(vhdxzip, 5)
  }

  test("check minimal vhd for files") {
    // tika identifies this as a "text/vhdl" which it decidedly is not
    // the Saffron augmentor adds in "application/vhd"
    val vhdzip = File("test_data/alsobasic.zip")
    testDiskImage(vhdzip, 5)
  }

  test("check minimal qemu for files") {
    val qemu = File("test_data/smallqemu.zip")
    testDiskImage(qemu, 2)
  }

  // A disk image's files are all wrapped before any is processed, so only the
  // small ones may stay in memory; a Linux root filesystem otherwise holds GiBs.
  test("files of a disk image above the small in-memory size go to temp files") {
    val squashfs = File("test_data/alpine-minimal.squashfs")
    val artifact: ArtifactWrapper =
      FileWrapper(squashfs, squashfs.getName(), None)
    val openedArchiveOpt = FileWalker.withinArchiveStream(artifact)(files => {
      val large = files.filter(_.size() > ArtifactWrapper.smallInMemorySize)
      assert(large.nonEmpty, "the fixture needs a file above the limit")
      assert(large.forall(_.isRealFile()), large.map(_.getClass()).toString)
      assert(
        files.filter(_.size() <= ArtifactWrapper.smallInMemorySize).exists(!_.isRealFile())
      )
      ()
    })
    assert(openedArchiveOpt.isDefined)
  }

  test("check minimal vmdk for files") {
    val vmdk = File("test_data/smallvm.zip")
    testDiskImage(vmdk, 2)
  }

  // Test a raw .img file that only has the generic octet-stream MIME type.
  // SaffronDetector must promote it to a Saffron-specific MIME type so that
  // FileWalker does not reject it as "definitely not an archive".
  test("check minimal raw .img for files") {
    val raw = File("test_data/raw.zip")
    val artifact: ArtifactWrapper = FileWrapper(raw, raw.getName(), None)
    val openedArchiveOpt = FileWalker.withinArchiveStream(artifact)(vec => {
      assertEquals(vec.length, 1)
      val image = vec(0)
      val successOpt = FileWalker.withinArchiveStream(image)(files => {
        assertEquals(files.length, 2)
        assert(files.exists(art => art.filenameWithNoPath == "small.txt"))
        assert(files.exists(art => art.filenameWithNoPath == "README.md"))
        ()
      })
      assert(successOpt.isDefined)
    })
    assert(openedArchiveOpt.isDefined)
  }

  // A raw disk named `.raw` (the Debian and other cloud images ship that way).
  // Tika types `.raw` as a Panasonic camera RAW photo (image/x-raw-panasonic),
  // and an image/ MIME type used to stop both the augmenters and the archive
  // walk, so the disk was never opened.
  test("check minimal raw disk named .raw for files") {
    val dir = Files.createTempDirectory("saffron_raw")
    val raw = dir.resolve("disk.raw").toFile()
    try {
      Files.copy(File("test_data/raw.img").toPath(), raw.toPath())
      val artifact: ArtifactWrapper = FileWrapper(raw, raw.getName(), None)
      assert(artifact.mimeType.contains("application/x-saffron-raw-disk"))
      assert(!artifact.mimeType.exists(_.startsWith("image/")))
      val successOpt = FileWalker.withinArchiveStream(artifact)(files => {
        assertEquals(files.length, 2)
        assert(files.exists(art => art.filenameWithNoPath == "small.txt"))
        assert(files.exists(art => art.filenameWithNoPath == "README.md"))
        ()
      })
      assert(successOpt.isDefined)
    } finally {
      raw.delete()
      dir.toFile().delete()
    }
  }

  // Test a gzip-compressed raw .img.gz file. Saffron must detect the gzip
  // wrapper as a raw disk, decompress it, and walk the embedded filesystem.
  test("check minimal gzip-compressed raw .img.gz for files") {
    val rawgz = File("test_data/rawgz.zip")
    val artifact: ArtifactWrapper = FileWrapper(rawgz, rawgz.getName(), None)
    val openedArchiveOpt = FileWalker.withinArchiveStream(artifact)(vec => {
      assertEquals(vec.length, 1)
      val image = vec(0)
      val successOpt = FileWalker.withinArchiveStream(image)(files => {
        assertEquals(files.length, 2)
        assert(files.exists(art => art.filenameWithNoPath == "small.txt"))
        assert(files.exists(art => art.filenameWithNoPath == "README.md"))
        ()
      })
      assert(successOpt.isDefined)
    })
    assert(openedArchiveOpt.isDefined)
  }

  // Test a squashfs filesystem image. Saffron must detect the .squashfs
  // extension as a raw disk, mount the squashfs filesystem, and walk its
  // contents.
  test("check minimal squashfs for files") {
    val squashfs =
      File("test_data/alpine-minimal.squashfs")
    assert(squashfs.exists())
    val artifact: ArtifactWrapper =
      FileWrapper(squashfs, squashfs.getName(), None)
    val openedArchiveOpt = FileWalker.withinArchiveStream(artifact)(files => {
      assert(files.nonEmpty)
      assert(files.exists(art => art.filenameWithNoPath == "busybox"))
      assert(files.exists(art => art.filenameWithNoPath == "os-release"))
      ()
    })
    assert(openedArchiveOpt.isDefined)
  }
}
