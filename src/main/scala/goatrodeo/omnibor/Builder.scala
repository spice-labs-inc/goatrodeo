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
import goatrodeo.util.Helpers
import scala.util.Try
import java.io.FileInputStream
import goatrodeo.util.{GitOID, GitOIDUtils}
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
import com.typesafe.scalalogging.Logger
import com.github.packageurl.PackageURL
import goatrodeo.util.ArtifactWrapper
import goatrodeo.omnibor.ToProcess.ByUUID
import goatrodeo.omnibor.ToProcess.ByName

/** Build the GitOIDs the container and all the sub-elements found in the
  * container
  */
object Builder {
  val logger = Logger(getClass())

  /** Build the OmniBOR GitOID Corpus from all the files contained in the
    * directory and its subdirectories. Put the results in Storage.
    *
    * @param source
    *   where to search for files
    * @param storage
    *   the storage destination of the corpus
    * @param threadCnt
    *   the number of threads to use when computings
    * @param blockList
    *   a file containing gitoids to not process (e.g., Apache license files)
    */
  def buildDB(
      source: File,
      storage: Storage,
      threadCnt: Int,
      blockList: Option[File]
  ): Option[File] = {
    val totalStart = Instant.now()
    val runningCnt = AtomicInteger(0)
    val (queue, stillWorking) =
      ToProcess.buildQueueOnSeparateThread(source, runningCnt)

    // The count of all the files found
    val cnt = new AtomicInteger(0)

    // Get the gitoids to block
    val blockGitoids: Set[String] = blockList match {
      case None => Set()
      case Some(file) =>
        Try {
          import scala.jdk.CollectionConverters.CollectionHasAsScala

          val lines = Files.readAllLines(file.toPath()).asScala.toSet
          lines
        }.toOption match {
          case None    => Set()
          case Some(s) => s
        }

    }

    val lock = Object()
    var packageUrls: Vector[PackageURL] = Vector()

    val purlOut = (p: PackageURL) => {
      lock.synchronized {
        packageUrls = packageUrls :+ p
      }
    }

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

              toProcess.process(
                None,
                store = storage,
                purlOut = purlOut,
                blockList = blockGitoids,
                keepRunning = () => !dead_?,
                atEnd = (parent, _) => {
                  if (parent.isEmpty) {
                    val updatedCnt = cnt.addAndGet(1)
                    val theDuration = Duration
                      .between(localStart, Instant.now())
                    if (
                      theDuration.getSeconds() > 30 || updatedCnt % 1000 == 0
                    ) {
                      logger.info(
                        f"Processed ${updatedCnt} of ${runningCnt.get()} at ${Duration
                            .between(start, Instant.now())} thread ${threadNum}. ${toProcess.main} took ${theDuration} vertices ${String
                            .format("%,d", storage.size())}"
                      )
                    }
                  }
                }
              )

            } catch {
              case ise: IllegalStateException => {
                logger.error(
                  f"Failed illegal state ${toProcess.main} -- ${toProcess.mimeType} ${ise}"
                )
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
                logger.error(
                  f"Failed IO ${toProcess.main} ${toProcess.mimeType} ${ioe}"
                )
              }
              case e: Exception => {
                logger.error(
                  f"Failed generic ${toProcess.main} -- ${toProcess.mimeType} ${e}"
                )
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
    logger.info(f"Finished processing ${runningCnt.get()} at ${Duration
        .between(start, Instant.now())}")

    val ret = storage match {
      case lf: (ListFileNames & Storage) if !dead_? =>
        computeAndAddMerkleTrees(lf)
        writeGoatRodeoFiles(lf, packageUrls)
      case _ => logger.error("Didn't write"); None
    }

    logger.info(
      f"Total build time ${Duration.between(totalStart, Instant.now())}"
    )

    ret
  }

  /** Finds all the Items that "Contain" other items and generate a Merkle Tree
    * alias
    *
    * @param data
    *   the items
    */
  def computeAndAddMerkleTrees(data: ListFileNames & Storage): Unit = {
    val keys = data.keys()

    logger.info(f"Computing merkle trees for ${keys.size} items")
    val merkleTrees = for {
      k <- keys
      item <- data.read(k)
      if !item.connections.filter(a => EdgeType.isDown(a._1)).isEmpty
    } yield {
      item.identifier -> GitOIDUtils.merkleTreeFromGitoids(
        item.connections.toVector
          .filter(a => EdgeType.isDown(a._1))
          .map(_._2)
      )
    }

    logger.info(f"Computed ${merkleTrees.size} Merkle Trees")

    // write the aliases
    for { (itemId, tree) <- merkleTrees } {
      // create the Merkle Tree Alias
      data.write(
        tree,
        maybeAlias => {
          val alias = maybeAlias.getOrElse(
            Item(
              identifier = tree,
              reference = Item.noopLocationReference,
              connections = TreeSet(),
              bodyMimeType = None,
              body = None
            )
          )
          val toAdd = (EdgeType.aliasTo, itemId)
          val updatedAlias =
            if (alias.connections.contains(toAdd)) { alias }
            else {
              alias.copy(
                connections = (alias.connections + toAdd)
              )
            }
          updatedAlias
        }
      )
      // and update the Item with the AliasFrom
      data.write(
        itemId,
        {
          case None =>
            throw new Exception(
              f"Should be able to find ${itemId}, but it's missing"
            )
          case Some(item) =>
            val toAdd = (EdgeType.aliasFrom, tree)
            if (item.connections.contains(toAdd)) item
            else item.copy(connections = item.connections + toAdd)
        }
      )
    }

    logger.info(f"Wrote ${merkleTrees.size} Merkle Trees")

  }

  def writeGoatRodeoFiles(
      store: ListFileNames & Storage,
      purlOut: Vector[PackageURL]
  ): Option[File] = {
    store.target() match {
      case Some(target) => {
        logger.info(f"In store with target ${target}")
        val start = Instant.now()

        logger.info(f"Writing ${purlOut.length} Package URLs")
        val purlFile = File(target, "purls.txt")
        val bw = new BufferedWriter(new FileWriter(purlFile))
        try {
          import scala.jdk.CollectionConverters.CollectionHasAsScala
          for (purl <- purlOut) {
            bw.write(f"${purl.canonicalize()}\n")
          }
        } finally {
          bw.flush()
          bw.close()
        }

        // make sure the destination exists
        target.getAbsoluteFile().mkdirs()
        val sorted = {
          val allItems = store
            .pathsSortedWithMD5()
            .flatMap(v => store.read(v._2))

          allItems
        }

        logger.info(
          f"Post-sort at ${Duration.between(start, Instant.now())}"
        )
        val cnt = sorted.length

        val ret = GraphManager.writeEntries(
          target,
          sorted.toIterator
        )

        logger.info(
          f"Wrote ${cnt} entries in ${Duration.between(start, Instant.now())}"
        )

        Some(ret._2)
      }
      case _ => None
    }

  }
}
