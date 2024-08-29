package io.spicelabs.goatrodeo.toplevel

import java.io.File
import org.json4s._
import org.json4s.native.JsonMethods._
import java.nio.file.Files
import java.nio.charset.Charset
import scala.collection.JavaConverters.asScalaIteratorConverter
import io.spicelabs.goatrodeo.omnibor.BuildGraph
import io.spicelabs.goatrodeo.omnibor.MemStorage
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Calendar
import java.util.GregorianCalendar

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
  def deGrimmify(toAnalyzeDir: File, outDir: File): Unit = {
    val (artToContainer, containerToArtifacts, artSet) = readGrim(
      new File("data")
    )

    val toTest = Files
      .find(toAnalyzeDir.toPath(), 100000, (a, b) => !b.isDirectory())
      .iterator()
      .asScala

    val bad =
      Map((for {
        t <- toTest
        pf = t.toFile()
        _ = println(f"Testing ${pf.getPath()}")
        tested <-
          testAFile(pf, artToContainer, containerToArtifacts, artSet)

      } yield pf.getPath() -> tested).toVector: _*)

    if (!bad.isEmpty) {
      val json = pretty(
        render(Extraction.decompose(bad), alwaysEscapeUnicode = true)
      )

      if (outDir.getName() == "-" || outDir.getName() == "stderr") {
        System.err.print(json)
        System.err.flush()
        println(f"Wrote grim list to `stderr`")
      } else {
        val d = new GregorianCalendar()
        val badFile = new File(
          outDir,
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
    val built = BuildGraph.buildItemsFor(
      toTest,
      toTest.getPath(),
      store,
      Vector(),
      None,
      Map(),
      new BufferedWriter(new OutputStreamWriter(new ByteArrayOutputStream())),
      false
    )

    // map from the gitoid-sha256 into all the places the item was found
    val artifactIdToFoundItem: Map[String, Vector[String]] =
      built.parentStackToGitOID.foldLeft(Map[String, Vector[String]]()) {
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
        )): _*
      )
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
    } yield v -> k): _*)

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
    path: Vector[String]
)