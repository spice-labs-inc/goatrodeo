/* Copyright 2024 David Pollak, Spice Labs, Inc. & Contributors

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
import goatrodeo.util.{GitOID, PackageProtocol, GitOIDUtils}
import java.io.BufferedInputStream
import java.time.Instant
import java.time.Duration
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import scala.annotation.tailrec
import java.io.FileWriter
import scala.collection.immutable.TreeSet
import goatrodeo.util.FileWrapper
import java.nio.file.Files

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
      threadCnt: Int,
      blockList: Option[File]
  ): Option[File] = {
    val totalStart = Instant.now()
    val runningCnt = AtomicInteger(0)
    val (queue, stillWorking) = ToProcess.buildQueue(source, runningCnt)

    // The count of all the files found
    val cnt = new AtomicInteger(0)

    // Get the gitoids to block
    val blockGitoids: Set[String] = blockList match {
      case None => Set()
      case Some(file) => 
        Try{
          import scala.jdk.CollectionConverters.CollectionHasAsScala

          val lines = Files.readAllLines(file.toPath()).asScala.toVector
          Set(lines*)
        }.toOption match {
          case None => Set()
          case Some(s) => s
        }
      
    }

    val destDir = storage
      .destDirectory()
      .getOrElse({
        val file = File.createTempFile("goat_rodeo_purls", "_out")
        file.delete()
        file.mkdirs()
        file
      })

    destDir.mkdirs()
    val purlOut = BufferedWriter(
      FileWriter(
        File(destDir, "purls.txt")
      )
    )

    // start time
    val start = Instant.now()
    @volatile var dead_? = false
    // fork `threadCnt` threads to do the work
    val threads = for { threadNum <- 0 until threadCnt } yield {
      val t = new Thread(
        () => {

          @tailrec
          def doPoll(): ToProcess = {
            val tried = queue.poll()
            if (tried != null || !stillWorking.get()) {
              tried
            } else {
              // wait 1-32 ms
              Thread.sleep(1 + Math.abs(Helpers.randomLong()) % 32)
              doPoll()
            }
          }

          // pull the files from the channel
          // if the channel is closed/empty, `None` will be
          // returned, handle it gracefully

          var toProcess: ToProcess = null
          while (
            !dead_? && {
              toProcess = doPoll();

              toProcess
            } != null
          ) {
            val localStart = Instant.now()
            try {
              // build the package

              BuildGraph.graphForToProcess(
                toProcess,
                storage,
                purlOut,
                blockGitoids
              )
              val updatedCnt = cnt.addAndGet(1)
              val theDuration = Duration
                .between(localStart, Instant.now())
              if (theDuration.getSeconds() > 30 || updatedCnt % 1000 == 0) {
                println(
                  f"Processed ${updatedCnt} of ${runningCnt.get()} at ${Duration
                      .between(start, Instant.now())} thread ${threadNum}. ${toProcess.main} took ${theDuration} vertices ${String
                      .format("%,d", storage.size())}"
                )
              }
            } catch {
              case ise: IllegalStateException => {
                dead_? = true
                throw ise
              }
              case ioe: IOException => {
                if (
                  ioe.getMessage() != null && ioe
                    .getMessage()
                    .indexOf("Too many open files") > 0
                ) {
                  dead_? = true
                  throw ioe

                }
                println(f"Failed ${toProcess.main} ${ioe}")
              }
              case e: Exception => {
                println(f"Failed ${toProcess.main} ${e}")
                // Helpers.bailFail()
              }

            }

          }
        },
        f"gitoid compute ${threadNum}"
      )
      t.start()
      t
    }

    // wait for the threads to complete
    for { t <- threads } {
      // deal with SIGKILL/ctrl-C
      if (t.isInterrupted() || dead_?) {
        Thread.currentThread().interrupt()
        throw InterruptedException(f"${t.getName()} was interrupted")
      }
      t.join()
    }
    println(f"Finished processing ${runningCnt.get()} at ${Duration
        .between(start, Instant.now())}")

    purlOut.flush()
    purlOut.close()

    val ret = storage match {
      case lf: (ListFileNames & Storage) if !dead_? =>
        writeGoatRodeoFiles(lf)
      case _ => println("Didn't write"); None
    }

    println(f"Total build time ${Duration.between(totalStart, Instant.now())}")

    ret
  }

  def writeGoatRodeoFiles(store: ListFileNames & Storage): Option[File] = {
    store.target() match {
      case Some(target) => {
        println(f"In store with target ${target}")
        val start = Instant.now()
        // make sure the destination exists
        target.getAbsoluteFile().mkdirs()
        val sorted = {
          val allItems = store
            .pathsSortedWithMD5()
            .flatMap(v => store.read(v._2))

          allItems
        }

        println(
          f"Post-sort at ${Duration.between(start, Instant.now())}"
        )
        val cnt = sorted.length

        val ret = GraphManager.writeEntries(
          target,
          sorted.toIterator
        )

        println(
          f"Wrote ${cnt} entries in ${Duration.between(start, Instant.now())}"
        )

        Some(ret._2)
      }
      case _ => None
    }

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

  def buildQueueAsVec(root: File): Vector[ToProcess] = {
    val cnt = AtomicInteger(0)
    val (queue, stillWorking) = buildQueue(root, cnt)

    // wait for the work to be done
    while (stillWorking.get()) {
      Thread.sleep(5)
    }

    println(f"Found ${cnt.get()} items to process")

    import scala.collection.JavaConverters.collectionAsScalaIterableConverter
    import scala.collection.JavaConverters.iterableAsScalaIterableConverter

    queue.asScala.toVector
  }

  def buildQueue(
      root: File,
      count: AtomicInteger
  ): (ConcurrentLinkedQueue[ToProcess], AtomicBoolean) = {
    val stillWorking = AtomicBoolean(true)
    val queue = ConcurrentLinkedQueue[ToProcess]()
    val buildIt: Runnable = () => {
      var fileSet = TreeSet(
        Helpers
          .findFiles(root, _ => true)
          .map(_.getAbsoluteFile())*
      )

      val pomLike = buildQueueForPOMS(
        root,
        queue,
        found => {
          count.incrementAndGet()
          fileSet -= found.main.getAbsoluteFile()
          found.pomFile.foreach(f => fileSet -= f.getAbsoluteFile())
          found.source.foreach(f => fileSet -= f.getAbsoluteFile())
        }
      )

      fileSet.foreach(f => {
        count.incrementAndGet()
        queue.add(ToProcess(PackageIdentifier.computePurl(FileWrapper(f, f.getPath(), false)), f, None, None))
      })

      stillWorking.set(false)
    }

    val t = Thread(buildIt, "File Finder")
    t.start()

    (queue, stillWorking)
  }

  def buildQueueForPOMS(
      root: File,
      queue: ConcurrentLinkedQueue[ToProcess],
      found: ToProcess => Unit
  ): Unit = {
    val start = Instant.now()
    val poms = Helpers.findFiles(
      root,
      f => f.getName().endsWith(".pom")
    )
    println(f"Finding all pom files got ${poms.length} in ${Duration
        .between(start, Instant.now())}")

    val possibleSize = poms.length
    var cnt = 0

    for {
      f <- poms

      xml <- Try {
        scala.xml.XML.load({
          val bytes = Helpers.slurpInput(f)
          new ByteArrayInputStream(bytes)
        })
      }.toOption
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
                  Some(
                    PackageIdentifier(
                      PackageProtocol.Maven,
                      g,
                      a,
                      v,
                      None,
                      None,
                      Map()
                    )
                  ),
                  jar,
                  Helpers.findSrcFile(f),
                  Some(f)
                )
              )
            } else if ({
              val jar = new File(
                f.getAbsoluteFile().getParent(),
                f"${name.substring(0, name.length() - 4)}.war"
              )
              jar.exists()
            }) {
              Some(
                ToProcess(
                  Some(
                    PackageIdentifier(
                      PackageProtocol.Maven,
                      g,
                      a,
                      v,
                      None,
                      None,
                      Map()
                    )
                  ),
                  new File(
                    f.getAbsoluteFile().getParent(),
                    f"${name.substring(0, name.length() - 4)}.war"
                  ),
                  Helpers.findSrcFile(f),
                  Some(f)
                )
              )

            } else if ({
              val jar = new File(
                f.getAbsoluteFile().getParent(),
                f"${name.substring(0, name.length() - 4)}.aar"
              )
              jar.exists()
            }) {
              Some(
                ToProcess(
                  Some(
                    PackageIdentifier(
                      PackageProtocol.Maven,
                      g,
                      a,
                      v,
                      None,
                      None,
                      Map()
                    )
                  ),
                  new File(
                    f.getAbsoluteFile().getParent(),
                    f"${name.substring(0, name.length() - 4)}.aar"
                  ),
                  Helpers.findSrcFile(f),
                  Some(f)
                )
              )

            } else None

          case _ =>
            None
        }

      }
    } {
      cnt += 1
      if (cnt % 5000 == 0) {
        println(f"pom loading ${cnt} of ${possibleSize} at ${Duration
            .between(start, Instant.now())}")
      }

      queue.add(item)
      found(item)
    }
  }

}
