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
import java.util.concurrent.atomic.AtomicInteger
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import java.io.OutputStreamWriter
import java.io.BufferedWriter
import java.math.BigInteger
import scala.xml.Elem
import goatrodeo.util.PackageIdentifier
import java.util.concurrent.ConcurrentLinkedQueue
import goatrodeo.util.Helpers
import scala.util.Try
import java.io.FileInputStream
import goatrodeo.util.{GitOID, FileType, PackageProtocol, GitOIDUtils}
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import java.io.BufferedInputStream
import java.time.Instant
import java.time.Duration
import goatrodeo.envelopes.PayloadCompression

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
    * @param fetchVulns
    *   Should the vulnerabilities be fetched from a remote vulnerability DB
    */
  def buildDB(
      source: File,
      storage: Storage,
      threadCnt: Int
  ): Unit = {
    val files = ToProcess.buildQueue(source)

    // The count of all the files found
    val cnt = new AtomicInteger(0)

    val totalCnt = files.size()

    // start time
    val start = Instant.now()

    // fork `threadCnt` threads to do the work
    val threads = for { threadNum <- 0 until threadCnt } yield {
      val t = new Thread(
        () =>
          // pull the files from the channel
          // if the channel is closed/empty, `None` will be
          // returned, handle it gracefully

          var toProcess: ToProcess = null
          while ({ toProcess = files.poll(); toProcess } != null) {

            try {
              // build the package
              BuildGraph.graphForToProcess(toProcess, storage)
              val updatedCnt = cnt.addAndGet(1)
              println(f"Processed ${updatedCnt} of ${totalCnt} at ${Duration
                  .between(start, Instant.now())}")
            } catch {
              case ise: IllegalStateException => throw ise
              case e: Exception => {
                println(f"Failed ${toProcess.main} ${e}")
                // Helpers.bailFail()
              }

            }

          }
        ,
        f"gitoid compute ${threadNum}"
      )
      t.start()
      t
    }

    // wait for the threads to complete
    for { t <- threads } t.join()
    println(f"Finished processing ${totalCnt} at ${Duration
        .between(start, Instant.now())}")

    storage match {
      case lf: (ListFileNames with Storage) => writeGoatRodeoFiles(lf)
      case _ => println("Didn't write")
    }
  }

  def writeGoatRodeoFiles(store: ListFileNames with Storage): Unit = {
    store.target() match {
      case Some(target) =>
        println(f"In store with target ${target}")
        val start = Instant.now()
        // make sure the destination exists
        target.getAbsoluteFile().mkdirs()
        val allItems = store
          .paths()
          .flatMap(store.read(_))
        println(
          f"Pre-sort at ${Duration.between(start, Instant.now())}"
        )
        val withMd5 = allItems.map(i => (Helpers.toHex(i.identifierMD5()), i))
        val sorted = withMd5.sortBy(_._1).map(_._2)
        println(
          f"Post-sort at ${Duration.between(start, Instant.now())}"
        )
        val cnt = allItems.length
        GraphManager.writeEntries(
          target,
          sorted.toIterator,
          PayloadCompression.NONE
        )

        println(
          f"Wrote ${cnt} entries in ${Duration.between(start, Instant.now())}"
        )
      case _ =>
    }

    store.release()
  }

  private def getSha256(f: File): Option[(File, Array[Byte])] = {
    val name = f.getName()
    val allHex = name
      .chars()
      .allMatch(i => (i >= '0' && i <= '9') || (i >= 'a' && i <= 'f'))
    val rightLen = name.length() == 64
    val hasDiff = {
      val tf = (new File(f, "diff")); tf.exists() && tf.isDirectory()
    }

    if (allHex && rightLen && hasDiff)
      Some(f -> new BigInteger(name, 16).toByteArray)
    else None
  }

  def buildFromDocker(
      root: File,
      storage: Storage
  ): Vector[(File, Array[Byte])] = {
    val whichDirs =
      root.listFiles().toVector.filter(_.isDirectory()).flatMap(getSha256(_))

    whichDirs
  }
}

case class ToProcess(
    pom: Option[PackageIdentifier],
    main: File,
    source: Option[File],
    pomFile: Option[File]
)

object ToProcess {

  def findTag(in: Elem, name: String): Option[String] = {
    val topper = in \ name
    if (topper.length == 1) {
      topper.text match {
        case s if s.length() > 0 => Some(s)
        case _                   => None
      }

    } else {
      val t2 = in \ "parent" \ name
      if (t2.length == 1 && t2.text.length() > 0) { Some(t2.text) }
      else None
    }
  }

  def tryToFixVersion(
      in: Option[String],
      fileName: String
  ): Option[String] = {
    in match {
      case Some(s) if s.startsWith("${") =>
        val fn2 =
          fileName.substring(0, fileName.length() - 4) // remove ".pom"
        val li = fn2.lastIndexOf("-")
        if (li >= 0) {
          Some(fn2.substring(li + 1))
        } else in
      case _ => in
    }
  }

  def buildQueue(root: File): ConcurrentLinkedQueue[ToProcess] = {
    val pomLike = buildQueueForPOMS(root)
    if (pomLike.isEmpty()) {
      val files = Helpers
        .findFiles(root, _ => true)
        .map(f => ToProcess(None, f, None, None))

      import collection.JavaConverters.seqAsJavaListConverter
      import collection.JavaConverters.asJavaIterableConverter

      val queue = new ConcurrentLinkedQueue(files.asJava)
      queue
    } else pomLike
  }

  def buildQueueForPOMS(root: File): ConcurrentLinkedQueue[ToProcess] = {

    val poms = Helpers.findFiles(
      root,
      f => f.getName().endsWith(".pom")
    )

    val theQueue = (for {
      f <- poms.iterator
      // f = v
      xml <- Try { scala.xml.XML.load(new FileInputStream(f)) }.toOption
      item <- {
        val grp = findTag(xml, "groupId")
        val art = findTag(xml, "artifactId")
        val ver = tryToFixVersion(findTag(xml, "version"), f.getName())
        (grp, art, ver) match {
          case (Some(g), Some(a), Some(v))
              if !g.startsWith("$") && !a
                .startsWith("$") && !v.startsWith("$") &&
                f.getName() == f"${a}-${v}.pom" =>
            val name = f.getName()
            val jar = new File(
              f.getAbsoluteFile().getParent(),
              f"${name.substring(0, name.length() - 4)}.jar"
            )
            if (jar.exists()) {
              Some(
                ToProcess(
                  Some(PackageIdentifier(PackageProtocol.Maven, g, a, v)),
                  jar,
                  Helpers.findSrcFile(f),
                  Some(f)
                )
              )
            } else None

          case _ =>
            None
        }

      }
    } yield {
      item
    }).toVector

    import collection.JavaConverters.seqAsJavaListConverter
    import collection.JavaConverters.asJavaIterableConverter

    val queue = new ConcurrentLinkedQueue(theQueue.asJava)
    queue
  }

}
