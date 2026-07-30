import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File
class SaffronTests extends munit.FunSuite {

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
      File("saffron/src/test/resources/squashfs/alpine-minimal.squashfs")
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
