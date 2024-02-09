/* Copyright 2024 David Pollak & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package goatrodeo.loader

import upickle.default.*
import java.io.File
import java.io.FileInputStream
import java.util.jar.JarFile
import scala.util.Try
import scala.collection.JavaConverters.*
import scala.util.Success
import java.net.URL
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.io.InputStream
import goatrodeo.util.{Helpers, GitOID}
import goatrodeo.omnibor.{Entry, StorageReader}

/** Do analysis of the file to see what the composition of a particular JAR file
  * is
  */
object Analyzer {

  /** Perform the analysis of a file. Send results to `stdout`
    *
    * @param what
    *   the file to analyze
    * @param fetch -- the URL to fetch things from
    */
  def analyze(what: File, fetch: URL): Unit = {
    val (main, gitoids) = buildGitOIDs(what)

    val reader: StorageReader = StorageReader.from(fetch)

    val fetched =
      fetchOmniBOR(main :: gitoids, reader)

    for { (info, probabily) <- toplinePercent(fetched) if probabily > 0.75 } {
      println(f"${info.metadata.filename
          .getOrElse("N/A")}, ${(probabily * 100).toInt} %%, ${info.metadata.vulnerabilities match {
          case None        => "No CVEs"
          case Some(vulns) => write(vulns, 2)
        }}")
    }
  }

  /** Point to a file and get the GitOID information
    *
    * @param f
    *   the file to point to
    * @return
    *   the GitOID information for the file itself and the sub-components
    */
  def buildGitOIDs(f: File): (GitOID, List[GitOID]) = {
    // grab the GitOID for the file itself
    val is = new FileInputStream(f)
    val bytes = Helpers.slurpInput(is)
    val fileGitoid = GitOID.url(bytes)

    val ret = fileGitoid
    val extra = Try {
      // open the file as a JAR
      val jar = new JarFile(f, true)
      val files: List[GitOID] =
        (for { i <- jar.entries().asScala if !i.isDirectory() } yield {

          val inputStream = jar.getInputStream(i)
          val bytes = Helpers.slurpInput(inputStream)
          val name = i.getName()
          val gitoid = GitOID.url(bytes)
          gitoid

        }).toList
      files
    }.toOption

    (ret, extra.getOrElse(Nil))
  }

  // /** Split the filename for web lookup
  //   */
  // lazy val webSplitter: GitOID => (Vector[String], Option[String]) = s => (Vector(s), None)

  // /** Splut the filename for filesystem lookup
  //   */
  // lazy val fileSplitter: GitOID => (Vector[String], Option[String]) = s => {
  //   val (a, b, c) = GitOID.urlToFileName(s); (Vector(a, b, c), Some("json"))
  // }

  /** For a set of gitoids, look up the entries (information about the GitOID)
    *
    * @param items
    *   the set of GitOIDs
    * @param reader read the GitOID from a web or filesystem
    *   
    * @return
    *   a `Map` of `gitoid` -> `Entry`
    */
  def fetchOmniBOR(
      items: Seq[GitOID],
      reader: StorageReader
  ): Map[String, Option[Entry]] = {
    // what have we fetched
    var fetched = Map.from(items.map(i => (i, false)))

    // what are we going to return
    var ret: Map[String, Option[Entry]] = Map()

    // okay... we could make this tail recursive, but well...
    // if there's an unfetched gitoid, run the fetching process
    while (fetched.values.exists(i => !i)) {
      // for each GitOID we haven't fetched
      for { (k, got) <- fetched if !got } {
        // fetch the item
        val body = reader.read(k)

        // mark it as fetched
        fetched = fetched + (k -> true)

        val top: Option[Entry] =
          body.flatMap(b => {
            val tt = Try {
              upickle.default.read[Entry](b)
            }

            tt.toOption
          })

        // put it in the return
        ret = ret + (k -> top)
        top match {
          case Some(v) => {
            val gitoids = v.containedBy
            for (go <- gitoids) {
              if (!fetched.contains(go)) {
                fetched = fetched + (go -> false)
              }
            }
          }
          case _ =>
        }
      }
    }
    ret
  }

  /** Compute the percentage of items from the whole that are contained in
    * `Entry`s that have Package URLs
    *
    * @param in
    *   the map of GitOID to Entry
    * @return
    *   the `Entry`s that have package URLs (e.g., containers for which there
    *   may be CVEs) and the percentage of files that are part of each Package
    */
  def toplinePercent(
      in: Map[String, Option[Entry]]
  ): Map[Entry, Double] = {
    val packages = in.values.collect {
      case Some(entry) if entry.metadata.purl.isDefined =>
        entry
    }

    val pairs = for { p <- packages } yield {
      val contained = p.contains.filter(i => in.contains(i)).size.toDouble
      p -> (contained / p.contains.length.toDouble)
    }

    Map.from(pairs)
  }
}
