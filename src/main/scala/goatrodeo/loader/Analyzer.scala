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
import goatrodeo.omnibor.BulkStorageReader

/** Do analysis of the file to see what the composition of a particular JAR file
  * is
  */
object Analyzer {

  /** Perform the analysis of a file. Send results to `stdout`
    *
    * @param what
    *   the file to analyze
    * @param fetch
    *   -- the URL to fetch things from
    */
  def analyze(what: File, fetch: URL): Unit = {
    val (main, gitoids) = buildGitOIDs(what)

    val reader: StorageReader = StorageReader.from(fetch)

    val fetched = fetchOmniBOR(main :: gitoids, reader)

    // (for {
    //   vv <- fetched.values
    //   v <- vv
    //   purl <- v.metadata.purl
    // } yield purl).toVector.sorted.foreach(println(_))

    for { (info, probabily) <- toplinePercent(main :: gitoids, fetched) if probabily > 0.5 } {
      println(f"${info.metadata.purl
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
    val fileGitoid = GitOIDUtils.url(bytes)

    val ret = fileGitoid
    val extra = Try {
      // open the file as a JAR
      val jar = new JarFile(f, true)
      val files: List[GitOID] =
        (for { i <- jar.entries().asScala if !i.isDirectory() } yield {

          val inputStream = jar.getInputStream(i)
          val bytes = Helpers.slurpInput(inputStream)
          val name = i.getName()
          val gitoid = GitOIDUtils.url(bytes)
          gitoid

        }).toList
      files
    }.toOption

    (ret, extra.getOrElse(Nil))
  }

  /** For a set of gitoids, look up the entries (information about the GitOID)
    *
    * @param items
    *   the set of GitOIDs
    * @param reader
    *   read the GitOID from a web or filesystem
    *
    * @return
    *   a `Map` of `gitoid` -> `Entry`
    */
  def fetchOmniBOR(
      items: Seq[GitOID],
      reader: StorageReader
  ): Map[GitOID, Option[Entry]] = {
    // what have we fetched
    var fetched = Map.from(items.map(i => (i, false)))

    // what are we going to return
    var ret: Map[GitOID, Option[Entry]] = Map()

    var triedBulk = false
    // okay... we could make this tail recursive, but well...
    // if there's an unfetched gitoid, run the fetching process
    while (fetched.values.exists(i => !i)) {

      reader match {

        case bs: BulkStorageReader if !triedBulk => {
          val toRead = fetched.filter(!_._2).keys.toVector
          val latest = bs.bulkRead(Set(toRead: _*), Map())

          // if the bulk endpoint returns fewer values than we are looking for
          // then go to the non-bulk interface
          if (latest.size < toRead.size) {
            triedBulk = true
          }
          // mark fetched as read
          for (k <- toRead) {
            fetched = fetched + (k -> true)
          }
          // update the list of stuff fetched
          for ((k -> v) <- latest) {
            fetched = fetched + (k -> true)
            ret = ret + (k -> v)
          }
        }

        // bulk doesn't work, so we'll get the items one by one
        case _ => {

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
  def toplinePercent(toCheck: Seq[String],
      in: Map[String, Option[Entry]]
  ): Map[Entry, Double] = {
    val checkSet = toCheck.toSet
    val packages = in.values.collect {
      case Some(entry) if entry.metadata.purl.isDefined =>
        entry
    }

    val pairs = for { p <- packages } yield {
      val containedSet = p.contains.toSet.intersect(checkSet)
      val contained = containedSet.size.toDouble
      p -> (contained / p.contains.length.toDouble)
    }

    Map.from(pairs)
  }
}
