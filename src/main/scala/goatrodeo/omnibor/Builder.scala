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
import java.util.Base64

object Builder {
    def buildDB(source: File, storage: Storage, threadCnt: Int): Unit = {
    val re = "\\.jar\\.[0-9]+$".r
    val onlyName = Pattern.compile("^(.*)\\.[0-9]+$")
    val files =
      Helpers.findFiles(
        source,
        f =>
          f.isFile() &&
            (f.getName().endsWith(".jar") || re
              .findFirstIn(f.getName())
              .isDefined)
      )
    val cnt = new AtomicInteger(0)
    val start = System.currentTimeMillis()
    val threads = for { threadNum <- 0 until threadCnt } yield {
      val t = new Thread(
        () =>
          for {
            fileOpt <- files
            fileUnfixed <- fileOpt
          } {
            try {
              val m = onlyName.matcher(fileUnfixed.getCanonicalPath())
              val file: java.io.File = if (m.find()) {
                new File(m.group(1))
              } else { fileUnfixed }
              for { p <- Loader.buildPackage(fileUnfixed, file.getName()) } {
                val (path_1, path_2, fileName) = GitOID.urlToFileName(p.gitoid)

                val targetFile = f"${path_1}/${path_2}/${fileName}.json"

                // if we've already processed something with the same gitoid, don't do it again
                if (!storage.exists(targetFile)) {
                  storage.write(targetFile, write(p.toEntry(), indent = 2))

                  p.updateIndex(storage)

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
              case e: Exception => println(f"Failed ${fileUnfixed} ${e}")
            }
          },
        f"gitoid compute ${threadNum}"
      )
      t.start()
      t
    }
    for { t <- threads } t.join()

    storage match {
      case v: ListFileNames =>
        v.target() match {
          case Some(target) =>
            val start = System.currentTimeMillis()
            target.getAbsoluteFile().getParentFile().mkdirs()
            val baseFos = new FileOutputStream(target)
            val fos =
              if (target.getName().endsWith(".gz"))
                new GZIPOutputStream(baseFos)
              else baseFos
            val wr = new OutputStreamWriter(fos, "UTF-8")
            val br = new BufferedWriter(wr)
            var cnt = 0

            for {
              (hash, name) <- v.pathsSortedWithMD5()
              item <- storage.read(name)
            } {
              cnt = cnt + 1
              if (cnt % 10000 == 0)
                println(f"Count ${cnt} writing ${hash},${name}")
              br.write(
                f"${hash},${name}||,||${Base64.getEncoder().encodeToString(item)}\n"
              )
            }

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
