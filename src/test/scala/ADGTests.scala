import io.spicelabs.goatrodeo.omnibor.Builder
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.TagInfo
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class ADGTests extends munit.FunSuite {

  // Builds an ADG over the whole ~10 GB adg_tests corpus; munit's 30-second
  // default is nowhere near enough, and it is looser still when this runs
  // alongside other test classes.
  override val munitTimeout = scala.concurrent.duration.Duration(1, "hour")

  /** The default configuration for these tests; individual calls override it
    * with an explicit `(using ...)` where they need different settings.
    */
  private given Configuration = Configuration()

  test("Questionable archives do not cause exceptions") {
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

  test("Build database from many JARs") {
    val source = File("test_data/download/adg_tests")

    if (source.isDirectory()) {

      // Under `target/` rather than the repo root: suites now run in parallel
      // forks that share one filesystem, and this build writes a multi-GB tree
      // that should not land next to the sources or collide with another
      // suite's output.
      val resForBigTent = File("target/test-out/res_for_big_tent")
      resForBigTent.getParentFile().mkdirs()

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

      val config = Configuration(
        tempDir = None,
        threads = (Option(System.getenv("TEST_THREAD_CNT")))
          .flatMap(s => Try { Integer.parseInt(s.trim()) }.toOption)
          .getOrElse(25),
        maxRecords = 10000,
        blockList = None,
        fsFilePaths = true
      )

      Builder.buildDB(
        dest = resForBigTent,
        tag = Some(TagInfo("foo", None)),
        fileListers = Vector(
          (
            source,
            () =>
              Helpers.findFiles(source).filter(!_.getName().endsWith(".tgz"))
          )
        ),
        ignorePathSet = Set(),
        excludeFileRegex = Vector(),
        finishedFile = f => {
          sync.synchronized { captured = captured :+ f }; ()
        },
        done = b => { finished = b },
        preWriteDB = Vector(store => {
          store.keys().toVector.zipWithIndex.foreach {
            case (key, idx) => {
              val item = store.read(key).get
              val round = Item.decode(item.encodeCBOR())
              round match {
                case Success(value) =>
                case Failure(exception) =>
                  exception.printStackTrace()
              }

              assert(
                round == Success(item),
                f"Pos ${idx} Failed to round trip ${key} original ${item} round tripped ${round}"
              )
            }
          }

          assertEquals(
            "repo_ea/artio-ilink3-impl-0.144.jar",
            store
              .read(
                store
                  .read("sha1:ea95ab1f5b392d690443c7087168bd96568366ad")
                  .get
                  .connections
                  .head
                  ._2
              )
              .get
              .body
              .get
              .asInstanceOf[ItemMetaData]
              .fileNames
              .toVector
              .filter(_.endsWith(".jar"))
              .filter(_.startsWith("repo_ea/"))
              .head
          )

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

              true
            }
            case None => assert(false, "Failed to read tags"); true
          }
        })
      )(using config)

      assert(tagCount > 8000, s"Expecting lots of tags, got ${tagCount}")
      assert(captured.size > 5, "We should have built files")
      assert(finished, "Should have finished processing with success")
    }
  }
}
