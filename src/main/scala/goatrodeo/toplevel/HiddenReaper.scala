package goatrodeo.toplevel

import java.io.File
import org.json4s._
import org.json4s.native.JsonMethods._
import java.nio.file.Files
import java.nio.charset.Charset
import scala.collection.JavaConverters.asScalaIteratorConverter
import goatrodeo.omnibor.BuildGraph
import goatrodeo.omnibor.MemStorage
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Calendar
import java.util.GregorianCalendar
import goatrodeo.omnibor.FileAndGitoid
import java.nio.file.Path

/** Methods associated with discovering Hidden Reaper issues
  */
object HiddenReaper {
  implicit def jsonFormat: DefaultFormats = org.json4s.DefaultFormats

  /** Given the directories that contain the artifacts to scan and the "outDir"
    * which is expected to contain "grim.json" and where any found artifacts
    * will be deposited
    *
    * @param toAnalyzeDir
    * @param outDir
    */
  def deGrimmify(toAnalyzeDir: File, outDir: File, threads: Int): Unit = {
    val (artToContainer, containerToArtifacts, artSet) = readGrim(
      new File("data")
    )
    val lock = new Object()

    var toTest = Files
      .find(
        toAnalyzeDir.toPath(),
        100000,
        (a, b) => !b.isDirectory() && !a.toFile().getName().startsWith(".")
      )
      .iterator()
      .asScala
      .toVector

    var res: Vector[(String, Unmasked)] = Vector()

    def dequeue(): Option[(File, Int)] = {
      lock.synchronized {
        val ret = toTest.headOption
        if (ret.isDefined) {
          toTest = toTest.drop(1)
        }
        ret.map(v => v.toFile() -> toTest.length)
      }
    }

    def pushRes(path: String, unmasked: Unmasked): Unit = {
      lock.synchronized {
        val next = res :+ path -> unmasked
        res = next
      }
    }

    def runFiles(name: String): Unit = {
      while (true) {
        dequeue() match {
          case None => return
          case Some(p -> left) =>
            println(
              f"Testing ${p.getPath()} on ${name}. ${left} files left to analyze"
            )
            testAFile(p, artToContainer, containerToArtifacts, artSet) match {
              case None         => {}
              case Some(unmask) => pushRes(p.getPath(), unmask)
            }
        }
      }
    }

    val toJoin = for { i <- 1 to threads } yield {
      val name = f"Thread ${i} of ${threads}"
      val t = new Thread(() => runFiles(name), name)
      t.start()
      t
    }

    for { t <- toJoin } {
      val name = t.getName()
      t.join()
      println(f"Joined ${name}")
    }

    val bad = Map(res*)

    if (!bad.isEmpty) {
      import org.json4s.JsonDSL._

      val json = pretty(
        render(
          ("type" -> "unmasked") ~ ("version" -> 1) ~ ("data" -> Extraction
            .decompose(bad)),
          alwaysEscapeUnicode = true
        )
      )

      if (outDir.getName() == "-" || outDir.getName() == "stderr") {
        System.err.print(json)
        System.err.flush()
        println(f"Wrote grim list to `stderr`")
      } else {
        val d = new GregorianCalendar()

        val realOutDir =
          if (outDir.exists() && outDir.isDirectory()) outDir else new File(".")

        val badFile = new File(
          realOutDir,
          f"grim_found_${d.get(Calendar.YEAR)}_${String
              .format("%02d", d.get(Calendar.MONTH))}_${String
              .format("%02d", d.get(Calendar.DAY_OF_MONTH))}_${String
              .format("%02d", d.get(Calendar.HOUR_OF_DAY))}_${String
              .format("%02d", d.get(Calendar.MINUTE))}.json"
        )
        Files.writeString(
          badFile.toPath(),
          json,
          Charset.forName("UTF-8")
        )
        println(f"Wrote grim list to ${badFile.getPath()}")
      }
    } else println("Hooray!!! No hidden reapers found")
  }

  /** Test a specific file against the "grim list".
    *
    * Builds a graph of the items in the container. Based on the graph, finds
    * all the items in the grim list. Returns a map of artifact IDs from the
    * grim list and associated artifacts found to be associated with that
    * artifact
    *
    * @param toTest
    *   the file to test
    * @param artifactToContainer
    *   the artifact id to containing artifact list
    * @param containerToArtifacts:
    *   the container gitoid to the bad artifacts
    * @param artifactSet
    *   the set of artifact ids to test against (the keys of
    *   `artifactToContainer`)
    * @return
    *   a map of artifact IDs from the grim list and the containing artifact,
    *   the percent overlap with containing artifact, and the offending item
    */
  def testAFile(
      toTest: File,
      artifactToContainer: Map[String, String],
      containerToArtifacts: Map[String, List[String]],
      artifactSet: Set[String]
  ): Option[Unmasked] = {
    val store = MemStorage(None)
    // deal with failures in reading the file
    // this may be because the file has certain markers
    // as some sort of archive, but when the archive is
    // opened, there's a problem... just log
    // the problem and move to the next file
    val built =
      try {
        BuildGraph.buildItemsFor(
          toTest,
          toTest.getPath(),
          store,
          Vector(),
          None,
          Map(),
          new BufferedWriter(
            new OutputStreamWriter(new ByteArrayOutputStream())
          ),
          false,
          Set()
        )
      } catch {
        case e: Exception =>
          println(f"Failed for file ${toTest} exception ${e.getMessage()}")
          return None
      }

    // map from the gitoid-sha256 into all the places the item was found
    val artifactIdToFoundItem: Map[String, Vector[FileAndGitoid]] =
      built.parentStackToGitOID.foldLeft(Map[String, Vector[FileAndGitoid]]()) {
        case (cur, (items, id)) =>
          cur.updatedWith(id) { v =>
            Some(v.getOrElse(Vector()) ++ items)
          }
      }

    // turn this into the keys
    val foundKeys = artifactIdToFoundItem.keySet

    // intersect with the set of grim artifact gitoids
    val overlapping = artifactSet & foundKeys

    // Got no overlapping, return nothing
    if (overlapping.isEmpty) None
    else {

      // find all the containers that overlap the individual marker gitoids
      val overlappingContainters = overlapping.map(artifactToContainer(_))

      // create a Vector of the containing grim gitoid markers and
      // the number of actual markers found for that container
      val overlapCnt: Vector[(String, (Int, Int))] =
        overlappingContainters.toVector.map { c =>
          val allContained = containerToArtifacts(c)
          val foundContained = allContained.filter(foundKeys.contains(_))
          c -> (allContained.length -> foundContained.length)
        }

        // if more than 50% of the markers are found for a particular container,
        // then that container can be counted
      val realFound: Vector[(String, Double)] = overlapCnt
        .map { case (c, (all, found)) =>
          // deal with small marker sets
          c -> (if (found > 0 && all < 3) 1.0
                else (found.toDouble / all.toDouble))
        }
        .filter(_._2 > 0.5)

      // Convert the results
      val unmaskedDetail = Map(
        (for {
          (id, overlap) <- realFound
          subArtifact <- containerToArtifacts(id)
          if artifactIdToFoundItem.contains(subArtifact)
        } yield subArtifact -> GrimInfo(
          id,
          subArtifact,
          overlap,
          artifactIdToFoundItem(
            subArtifact
          )
        ))*
      )
      // maybe some phantom markers were found... but eliminated,
      // so check again
      if (unmaskedDetail.isEmpty)
        None
      else
        Some(Unmasked(built.mainGitOID, unmaskedDetail))
    }
  }

  /** Read the "grim.json" file from the directory and return the artifact to
    * container and container to artifact grim list
    *
    * @param grimDir
    * @return
    *   the artifact to container and container to artifacts list
    */
  def readGrim(
      grimDir: File
  ): (Map[String, String], Map[String, List[String]], Set[String]) = {
    val f2 = new File(grimDir, "grim.json")
    val json = Files.readString(f2.toPath(), Charset.forName("UTF-8"))
    val containerToArtifactList = parse(json).extract[Map[String, List[String]]]
    val artToContainer = Map((for {
      (k, vs) <- containerToArtifactList.toVector
      v <- vs
    } yield v -> k)*)

    (artToContainer, containerToArtifactList, artToContainer.keySet)
  }

}

case class Unmasked(
    mainArtifactGitOID: String,
    foundMarkers: Map[String, GrimInfo]
)

case class GrimInfo(
    containingArtifact: String,
    markerArtifact: String,
    overlapWithContaining: Double,
    path: Vector[FileAndGitoid]
)
