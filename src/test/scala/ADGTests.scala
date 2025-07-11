import io.spicelabs.goatrodeo.omnibor.Builder
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import scala.util.Try

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
      var tagCount = 0

      Builder.buildDB(
        dest = resForBigTent,
        tempDir = None,
        threadCnt = (Option(System.getenv("TEST_THREAD_CNT")))
          .flatMap(s => Try { Integer.parseInt(s.trim()) }.toOption)
          .getOrElse(25),
        maxRecords = 50000,
        tag = Some("test"),
        fileListers = Vector(() => Helpers.findFiles(source, f => true)),
        ignorePathSet = Set(),
        excludeFileRegex = Vector(),
        blockList = None,
        finishedFile = f => {
          sync.synchronized { captured = captured :+ f }; ()
        },
        done = b => { finished = b },
        preWriteDB = store => {
          store.read("tags") match {
            case Some(tags) => {

              val theTag =
                tags.connections.filter(e => e._1 == EdgeType.tagTo).head._2
              tagCount = store
                .read(theTag)
                .get
                .connections
                .filter(_._1 == EdgeType.tagTo)
                .size
            }
            case None =>
          }
        }
      )

      assert(tagCount > 8000, s"Expecting lots of tags, got ${tagCount}")
      assert(captured.size > 5, "We should have built files")
      assert(finished, "Should have finished processing with success")
    }
  }
}
