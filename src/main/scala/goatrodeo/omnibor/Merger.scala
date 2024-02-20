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
import java.io.PrintStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat

/** Handle merging of OmniBOR Corpus documents
  */
object Merger {

  /** Merge a set of files and output to a file or to `stdout` if no output is
    * specified
    *
    * @param files
    *   the `Vector` of files to merge
    * @param out
    *   the output
    */
  def merge(files: Vector[File], out: Option[File]): Unit = {
    val start = System.currentTimeMillis()
    val (realOut, printOut) = out match {
      case None => (System.out, System.err)
      case Some(f) =>
        (new PrintStream(new FileOutputStream(f, false)), System.out)
    }

    val theLen = files.length

    val sources = files.map(f =>
      new BufferedReader(new InputStreamReader(new FileInputStream(f)))
    )
    val current: Array[Option[LineItem]] = new Array(theLen)
    for (i <- 0 until theLen) { current(i) = None }

    def updateCurrent(): Unit = {
      for (i <- 0 until theLen) {
        if (current(i).isEmpty) {
          var tmpRead: String = ""
          var found = false
          while (!found && tmpRead != null) {
            tmpRead = sources(i).readLine()
            if (tmpRead != null) {
              val parsed = LineItem.parse(tmpRead)
              parsed match {
                case Some(x) => {
                  current(i) = Some(x)
                  found = true
                }
                case _ =>
              }
            }
          }
        }
      }
    }

    import math.Ordered.orderingToOrdered
    var cnt = 0
    var mergeCnt = 0
    // set each line
    updateCurrent()

    while (current.forall(_.isDefined)) {
      val lst = current.zipWithIndex
        .filter(_._1.isDefined)
        .map((i, n) => (i.get, n))
        .sortWith((a, b) => a._1.md5hash < b._1.md5hash)
        .toList

      lst match {
        // the merging case
        case (item1, n1) :: (item2, n2) :: _
            if item1.md5hash == item2.md5hash =>
          // printOut.println(f"Merging ${item1.md5hash}")
          // printOut.println(item1)
          // printOut.println(item2)
          // printOut.println(item1.merge(item2))
          // return
          current(n1) = None
          // replace the second one. If there are other matching hashes, we'll still merge
          current(n2) = Some(item1.merge(item2))
          mergeCnt += 1

        case (item, n) :: _ =>
          current(n) = None // remove from set to process
          realOut.println(item.encode())

        case Nil => // do nothing... this shouldn't happen
      }
      cnt += 1

      if (cnt % 10000 == 0) {
        val hash = lst.head._1.md5hash
        val thing = {
          val r = Integer
            .valueOf(hash.substring(0, 4), 16)
            .toDouble / (256.0 * 256.0); if (r == 0.0) 0.01 else r
        }
        val delta = (System.currentTimeMillis() - start).toDouble / 1000.0
        val remaining = (delta / thing) - delta
        printOut.println(
          f"Records processed ${NumberFormat.getInstance().format(cnt)}, merged ${NumberFormat
              .getInstance()
              .format(mergeCnt)}, current hash ${hash}, seconds ${delta.round} remaining ${remaining.round}"
        )
      }
      // set each line
      updateCurrent()
    }

    println(f"Complete in ${System.currentTimeMillis() - start}, cnt ${cnt}")

  }
}
