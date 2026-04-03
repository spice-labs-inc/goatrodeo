import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import java.io.File
import io.spicelabs.goatrodeo.util.FileWrapper
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
}
