import goatrodeo.omnibor.Builder
import goatrodeo.omnibor.ToProcess
import goatrodeo.util.FileWrapper
import goatrodeo.util.Helpers

import java.io.File
class ADGTests extends munit.FunSuite {
  test("Unreadable JAR") {
    val source = File("test_data/download/adg_tests/repo_ea")

    // the test takes a couple of files with questionable TAR and ZIP archives
    // and ensures that they don't cause exceptions
    if (source.isDirectory()) {
      for {
        toTry <- Vector(
          "adif-processor-1.0.65.jar",
          "alpine-executable-war-1.2.2.jar"
        )
      } {
        val bad = File(source, toTry)
        val badWrapper = FileWrapper(bad, toTry, None)
        ToProcess.buildGraphFromArtifactWrapper(badWrapper)
      }

    }
  }

  test("Build lots of JARs") {
    val source = File("test_data/download/adg_tests/repo_ea")

    if (source.isDirectory()) {

      val resForBigTent = File("res_for_big_tent")

      // delete files if they exist
      if (resForBigTent.exists()) {
        if (resForBigTent.isDirectory()) {
          for { v <- resForBigTent.listFiles() } { v.delete() }
        } else {
          resForBigTent.delete()
        }
      }

      var captured: Vector[File] = Vector()
      val sync = new Object()
      var finished = false

      Builder.buildDB(
        dest = resForBigTent,
        tempDir = None,
        threadCnt = 32,
        maxRecords = 50000,
        fileListers = Vector(() => Helpers.findFiles(source, f => true)),
        ignorePathSet = Set(),
        excludeFileRegex = Vector(),
        blockList = None,
        finishedFile = f => {
          sync.synchronized { captured = captured :+ f }; ()
        },
        done = b => { finished = b }
      )

      assert(captured.size > 5, "We should have built files")
      assert(finished, "Should have finished processing with success")
    }
  }
}
