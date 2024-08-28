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
import goatrodeo.omnibor.{ StorageReader}
import goatrodeo.omnibor.BulkStorageReader
import io.bullet.borer.Json

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
    ???
    // FIXME
    // val (main, gitoids) = buildGitOIDs(what)

    // val reader: StorageReader = StorageReader.from(fetch)

    // val fetched = fetchOmniBOR(main :: gitoids, reader)

    // for { (info, probabily) <- toplinePercent(main :: gitoids, fetched) if probabily > 0.5 } {
    //   println(f"${info.metadata.purl
    //       .getOrElse("N/A")}, ${(probabily * 100).toInt} %%")
    // }
  }

}
