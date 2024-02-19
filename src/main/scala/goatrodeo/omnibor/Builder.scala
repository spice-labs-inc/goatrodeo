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

package goatrodeo.omnibor

import java.io.File
import java.util.regex.Pattern
import goatrodeo.util.Helpers
import java.util.concurrent.atomic.AtomicInteger
import goatrodeo.loader.{Loader, GitOID}
import upickle.default.*
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import java.io.OutputStreamWriter
import java.io.BufferedWriter

/** Build the GitOIDs the container and all the sub-elements found in the
  * container
  */
object Builder {

  /** Build the OmniBOR GitOID Corpus from all the JAR files contained in the
    * directory and its subdirectories. Put the results in Storage.
    *
    * @param source
    *   where to search for JAR files
    * @param storage
    *   the storage destination of the corpus
    * @param threadCnt
    *   the number of threads to use when computings
    */
  def buildDB(source: File, storage: Storage, threadCnt: Int): Unit = {
    val re = "\\.jar\\.[0-9]+$".r
    val onlyName = Pattern.compile("^(.*)\\.[0-9]+$")

    // get a channel to find all the JAR files
    val files =
      Helpers.findFiles(
        source,
        f =>
          f.isFile() &&
            (f.getName().endsWith(".jar") ||
              re.findFirstIn(f.getName()).isDefined)
      )

    // The count of all the files found
    val cnt = new AtomicInteger(0)

    // start time
    val start = System.currentTimeMillis()

    // fork `threadCnt` threads to do the work
    val threads = for { threadNum <- 0 until threadCnt } yield {
      val t = new Thread(
        () =>
          // pull the files from the channel
          // if the channel is closed/empty, `None` will be
          // returned, handle it gracefully
          for {
            fileOpt <- files
            fileUnfixed <- fileOpt
          } {
            try {

              // Get the filename
              val m = onlyName.matcher(fileUnfixed.getCanonicalPath())
              val file: java.io.File = if (m.find()) {
                new File(m.group(1))
              } else { fileUnfixed }

              // build the package
              for { p <- Loader.buildPackage(fileUnfixed, file.getName()) } {

                // compute the filename in Storage for the Root entry

                val targetFile = p.gitoid

                // if we've already processed something with the same gitoid, don't do it again
                if (!storage.exists(targetFile)) {
                  // write the root Entry
                  storage.write(
                    targetFile,
                    write(p.toEntry(), indent = -1, escapeUnicode = true)
                  )

                  // update the Package URL index
                  p.updateIndex(storage)

                  // write or update all the dependents
                  p.fixDependents(storage)

                } else {
                  println(f"Skipped duplicated ${fileUnfixed.getName()}")
                }
                val nc = cnt.incrementAndGet()
                println(
                  f"processed ${file.getName()}, count ${nc} time ${(System
                      .currentTimeMillis() - start) / 1000} seconds - thread ${threadNum}"
                )
              }
            } catch {
              case e: Throwable => {
                println(f"Failed ${fileUnfixed} ${e}")
                System.exit(1)
              }
            }
          },
        f"gitoid compute ${threadNum}"
      )
      t.start()
      t
    }

    // wait for the threads to complete
    for { t <- threads } t.join()

    storage match {
      // if the attribute of the Storage includes the ability to
      // list filenames, write sharded index
      case v: ListFileNames =>
        v.target() match {
          case Some(target) =>
            val start = System.currentTimeMillis()
            // make sure the destination exists
            target.getAbsoluteFile().getParentFile().mkdirs()

            // FIXME -- deal with proper sharding of the output
            // based on 1 or 2 digits in the MD5 hash
            val baseFos = new FileOutputStream(target)
            val fos =
              if (target.getName().endsWith(".gz"))
                new GZIPOutputStream(baseFos)
              else baseFos
            val wr = new OutputStreamWriter(fos, "UTF-8")
            val br = new BufferedWriter(wr)

            // how many records did we process
            var cnt = 0

            // Get the sorted (by MD5 of the path name)
            for {
              (hash, name) <- v.pathsSortedWithMD5()
              item <- storage.read(name)
            } {
              cnt = cnt + 1
              if (cnt % 10000 == 0)
                println(f"Count ${cnt} writing ${hash},${name}")

              // write the item
              br.write(
                f"${hash},${name}||,||${item.replace('\n', ' ')}\n"
              )
            }

            // clean up
            br.close()
            wr.close()
            fos.close()
            println(
              f"Wrote ${cnt} entries in ${(System.currentTimeMillis() - start) / 1000} seconds"
            )
          case _ =>
        }
      case _ =>
    }

    storage.release()
  }
}
