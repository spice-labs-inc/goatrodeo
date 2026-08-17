/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor

import com.typesafe.scalalogging.Logger
import io.bullet.borer.Dom
import io.bullet.borer.Json
import io.spicelabs.goatrodeo.ProgressListener
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.GitOIDUtils
import io.spicelabs.goatrodeo.util.Helpers

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.immutable.TreeSet
import scala.collection.parallel.CollectionConverters.VectorIsParallelizable
import scala.util.Try

/** Build the GitOIDs the container and all the sub-elements found in the
  * container
  */
object Builder {
  private val logger: Logger = Logger(getClass())

  /** Build the OmniBOR GitOID Corpus from all the files contained in the
    * directory and its subdirectories. Put the results in Storage.
    *
    * @param storage
    *   the storage destination of the corpus
    * @param threadCnt
    *   the number of threads to use when computings
    * @param blockList
    *   a file containing gitoids to not process (e.g., Apache license files)
    * @param maxRecords
    *   the maximum number of records to process at once
    * @param tag
    *   the tag associated with this run
    * @param fileListers
    *   a sequence of functions that list files. This allows for multiple `-b`
    *   and and `--file-list` flags to be sent in
    * @param ignorePathList
    *   a set of paths (canonical) to exclude. This can be used to exclude files
    *   that were processed in previous runs
    * @param excludeFileRegex
    *   regular expressions to exclude from processing
    * @param finishedFile
    *   once a file has been processed, write the file to a destination
    * @param call
    *   when the processing is done, true success, false failure
    */
  def buildDB(
      dest: File,
      threadCnt: Int,
      blockList: Option[File],
      maxRecords: Int,
      tag: Option[TagInfo],
      tempDir: Option[File],
      args: Config,
      fileListers: Seq[(File, () => Seq[File])],
      ignorePathSet: Set[String],
      excludeFileRegex: Seq[java.util.regex.Pattern],
      finishedFile: File => Unit,
      done: Boolean => Unit,
      preWriteDB: Vector[Storage => Boolean] = Vector(),
      fsFilePaths: Boolean = false
  ): Unit = {
    val totalStart = Instant.now()
    // Fresh per-run dispatcher; enforces monotonic current and catches
    // exceptions thrown by the caller-supplied ProgressListener.
    val progressNotifier = ProgressListener.notifier(args.progressListener)

    val runningCnt = AtomicInteger(0)
    val dead_? = AtomicBoolean(false)
    var queueBuildingDone = false
    val (queue, stillWorking) =
      ToProcess.buildQueueOnSeparateThread(
        fileListers,
        ignorePathSet,
        excludeFileRegex,
        finishedFile,
        tempDir,
        runningCnt,
        fsFilePaths = fsFilePaths,
        dead_? = dead_?
      )

    // The count of all the files found
    val cnt = new AtomicInteger(0)

    val fullTag = tag.map { tag =>
      // this ordering allows the user generated JSON to override the tag and the date... the latter being
      // useful for testing
      val base: Map[String, Dom.Element] = Map(
        ("tag" -> Dom.StringElem(tag.name)),
        ("date" -> Dom.StringElem(Helpers.currentDate8601()))
      )

      // Add version and date from Config if provided
      val withVersion = args.tagVersion match {
        case Some(version) => base + ("version" -> Dom.StringElem(version))
        case None          => base
      }

      val withConfigDate = args.tagDate match {
        case Some(date) =>
          import io.spicelabs.goatrodeo.util.DateParser
          withVersion + ("date" -> Dom.StringElem(DateParser.toIso8601(date)))
        case None => withVersion
      }

      val toAppend: Map[String, Dom.Element] = tag.extra match {
        case None => Map()
        case Some(me: Dom.MapElem) =>
          me.members.flatMap {
            case (s: Dom.StringElem, v) => Some(s.value -> v)
            case _                      => None
          }.toMap
        case Some(v) => Map(("extra" -> v))
      }
    val fieldMap = toAppend.foldLeft(withConfigDate) { case (curr, (k, v)) =>
      curr + (k -> v)
    }
    val json: Dom.MapElem = Dom.MapElem.Unsized(fieldMap.toVector*)
    val jsonString = Json.encode(json).toUtf8String

    TagPass(GitOIDUtils.urlForString(jsonString), jsonString, json)
    }

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

    logger.info(
      "Blocking build db until there's at least 1 item in the work queue"
    )
    // Don't kick off the consumer threads until at least 1 item
    // is in the work queue
    while (queue.isEmpty() && stillWorking.get()) {
      Thread.sleep(250)
      if (dead_?.get()) {
        logger.error("Died while waiting for any files")
        Helpers.exitWrapper(1)
      }
    }

    logger.info("Kicking off work queue consumer threads")
    val loopStart = Instant.now()
    var updatedDest = dest

    def destWithCount(dest: File, cnt: Int): File = {
      val destFileName = dest.getName()
      File(dest.getParentFile(), f"${destFileName}_${cnt}")
    }

    if (runningCnt.get() > maxRecords) {
      updatedDest = destWithCount(dest, 0)
    }

    var loopCnt = 0

    val writeThreadCnt = AtomicInteger(0)
    while (!dead_?.get() && (stillWorking.get() || !queue.isEmpty())) {
      val thread = processMaxRecords(
        updatedDest,
        threadCnt = threadCnt,
        maxRecords = maxRecords,
        queue = queue,
        stillWorking = stillWorking,
        blockGitoids = blockGitoids,
        cnt = cnt,
        runningCnt = runningCnt,
        totalStart = totalStart,
        tag = fullTag,
        dead_? = dead_?,
        loopStart = loopStart,
        writeThreadCnt = writeThreadCnt,
        tempDir = tempDir,
        args = args,
        progressNotifier = progressNotifier,
        preWriteDB = preWriteDB
      )

      loopCnt += 1
      logger.info(
        f"Finished multi-thread consumer loop ${loopCnt}%,d at ${Duration
            .between(totalStart, Instant.now())}"
      )
      updatedDest = destWithCount(dest, loopCnt)
    }

    logger.debug("Waiting for write threads")

    // loop while we wait for the end of processing
    while (writeThreadCnt.get() > 0) {
      Thread.sleep(250)
    }

    // create the non-number-suffixed directory
    dest.mkdirs()

    // call the finalize function
    done(!dead_?.get())

    logger.info("Done with run")
  }

  private def processMaxRecords(
      destDir: File,
      threadCnt: Int,
      maxRecords: Int,
      queue: ConcurrentLinkedQueue[ToProcess],
      stillWorking: AtomicBoolean,
      blockGitoids: Set[String],
      cnt: AtomicInteger,
      tag: Option[TagPass],
      runningCnt: AtomicInteger,
      totalStart: Instant,
      dead_? : AtomicBoolean,
      loopStart: Instant,
      writeThreadCnt: AtomicInteger,
      tempDir: Option[File],
      args: Config,
      progressNotifier: ProgressListener.Notifier,
      preWriteDB: Vector[Storage => Boolean] = Vector()
  ): Option[Thread] = {

    val storage = MemStorage(Some(destDir))

// if there's a tag, set it up
    tag match {
      case None => {} // do nothing
      case Some(tag) =>
        storage.write(
          "tags",
          item =>
            Some(
              Item("tags", TreeSet(EdgeType.tagTo -> tag.gitoid), None, None)
            ),
          item => "set up tags"
        )
        storage.write(
          tag.gitoid,
          item =>
            Some(
              Item(
                tag.gitoid,
                TreeSet(EdgeType.tagFrom -> "tags"),
                Some(ItemTagData.mimeType),
                Some(ItemTagData(tag.json))
              )
            ),
          x => "tags"
        )
    }

    // start time
    val start = Instant.now()

    val startedRunning = cnt.get()

    val batchName = destDir.getName()

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

          var toProcessOpt: Option[ToProcess] = None
          while (
            (cnt.get() - startedRunning) < maxRecords // only run so many items
            &&
            !dead_?.get() && {
              toProcessOpt = Option(doPoll())
              toProcessOpt.isDefined
            }
          ) {
            val toProcess = toProcessOpt.get
            val localStart = Instant.now()
            try {
              // build the package

              val byHash: Map[String, Vector[Augmentation]] =
                if (args.useStaticMetadata) {
                  toProcess.runStaticMetadataGather(args.mimeFilter)
                } else {
                  Map()
                }

              toProcess.process(
                None,
                store = storage,
                tag = tag,
                args = args,
                parentScope =
                  ParentScope.forAndWith(toProcess.main, None, byHash),
                blockList = blockGitoids,
                keepRunning = () => !dead_?.get(),
                atEnd = (parent, _) => {
                  if (parent.isEmpty) {
                    val updatedCnt = cnt.addAndGet(1)
                    val theDuration = Duration
                      .between(localStart, Instant.now())
                    if (
                      theDuration.getSeconds() > 30 || updatedCnt % 1000 == 0
                    ) {

                      // if we've got a temp dir and we're down to 10% free space, bail
                      tempDir match {
                        case Some(theDir) =>
                          for {
                            fileStore <- Try {
                              Files.getFileStore(
                                theDir.toPath().toAbsolutePath()
                              )
                            }.toOption
                            total <- Try { fileStore.getTotalSpace().toDouble }
                            available <- Try {
                              fileStore.getUsableSpace().toDouble
                            }
                          } {
                            val remaining = available / total
                            if (remaining < 0.05) {
                              val errorMsg =
                                s"Temp filesystem ${theDir} is more than 95% full. Total ${total} available ${available} remaining ${remaining}, terminating"
                              logger.error(errorMsg)
                              dead_?.set(true)
                              throw new Exception(errorMsg)
                            }
                          }
                        case _ => // do nothing
                      }
                      val now = Instant.now()
                      val totalDuration = Duration.between(totalStart, now)
                      val processDuration = Duration.between(loopStart, now)
                      val totalItems = runningCnt.get()
                      val avgMsg = if (processDuration.getSeconds() > 0) {
                        val itemsPerSecond =
                          updatedCnt.toDouble / processDuration
                            .getSeconds()
                            .toDouble
                        val itemsPerMinute = itemsPerSecond * 60.0d
                        val left = totalItems.toDouble - updatedCnt.toDouble
                        val remainingDuration = Duration.ZERO.plusSeconds(
                          (left / itemsPerSecond).round
                        )
                        f" Items/minute ${itemsPerMinute.round}, est remaining ${remainingDuration}"
                      } else ""
                      logger.info(
                        f"Processed ${updatedCnt}%,d of ${totalItems}%,d at ${totalDuration}/${processDuration}${avgMsg}. ${toProcess.main} took ${theDuration} vertices ${storage.size()}%,d"
                      )
                      progressNotifier.notify(
                        updatedCnt.toLong,
                        totalItems.toLong
                      )
                    }
                  }
                }
              )

            } catch {
              case oom: OutOfMemoryError => {
                logger.error("Out of memory", oom)
                dead_?.set(true)
                throw oom
              }
              case ise: IllegalStateException => {
                logger.error(
                  f"Failed illegal state ${toProcess.main} -- ${toProcess.mimeType} ${ise}"
                )
                dead_?.set(true)
                throw ise
              }
              case ioe: IOException => {
                if (
                  ioe.getMessage() != null && ioe
                    .getMessage()
                    .indexOf("Too many open files") > 0
                ) {
                  dead_?.set(true)
                  throw ioe

                }
                logger.error(
                  f"Failed IO ${toProcess.main} ${toProcess.mimeType} ${ioe}"
                )
              }
              case e: Exception => {
                logger.error(
                  f"Failed generic ${toProcess.main} -- ${toProcess.mimeType} ${e}",
                  e
                )
                // Helpers.bailFail()
              }

            }

          }
        },
        f"gitoid ${batchName} ${threadNum}"
      )
      t.start()
      t
    }

    // wait for the threads to complete
    for { t <- threads } {
      // deal with SIGKILL/ctrl-C
      if (t.isInterrupted() || dead_?.get()) {
        Thread.currentThread().interrupt()
        throw InterruptedException(f"${t.getName()} was interrupted")
      }
      t.join()
    }

    if (!dead_?.get()) {
      val thread = new Thread(
        () => {
          try {
            logger.info(
              f"Finished processing ${cnt.get()}%,d, vertices ${storage.size()}%,d at ${Duration
                  .between(start, Instant.now())}"
            )

            val writeStart = Instant.now()

            val writeToStorage = preWriteDB.foldLeft(true) {
              case (writeIt, preWriteFunc) => writeIt & preWriteFunc(storage)
            }

            args.cbomDir.foreach { cbomDir =>
              if (!dead_?.get()) {
                CbomEmitter
                  .emitForStorage(storage, args.cbomVersion, cbomDir) match {
                  case scala.util.Success(files) =>
                    logger.info(
                      f"Wrote ${files.length}%,d CBOM file(s) to ${cbomDir}"
                    )
                  case scala.util.Failure(e) =>
                    logger.error(
                      f"Failed to emit CBOMs to ${cbomDir}: ${e.getMessage()}",
                      e
                    )
                }
              }
            }

            val ret = storage match {
              case lf: (ListFileNames & Storage)
                  if writeToStorage && !dead_?.get() =>
                writeGoatRodeoFiles(lf, args.cutoff)
              case _ => logger.error("Didn't write"); None
            }

            logger.info(
              f"Total build time for ${batchName} ${Duration
                  .between(start, Instant.now())}, write took ${Duration
                  .between(writeStart, Instant.now())}"
            )
          } finally {
            writeThreadCnt.decrementAndGet()
          }
        },
        f"Saver ${batchName}"
      )
      writeThreadCnt.addAndGet(1)
      thread.start()
      Some(thread)
    } else None

  }

  /** Enforce an cutoff cutoff on the fully-assembled graph: drop every node
    * whose recorded file-modification time is after `cutoff`, plus every node
    * that transitively contains it or is built from it (they must be at least
    * as new), then strip any resulting dangling edges so no references to
    * removed nodes remain. Nodes without a recorded modification time are
    * always kept.
    */
  def pruneExpired(items: Vector[Item], cutoff: Instant): Vector[Item] = {
    def earliestModified(item: Item): Option[Instant] = item.body match {
      case Some(m: ItemMetaData) =>
        m.extra
          .get(Item.FileModifiedKey)
          .flatMap { values =>
            values.iterator
              .collect { case StringOf(s) => s }
              .flatMap(s => Try(Instant.ofEpochMilli(s.toLong)).toOption)
              .minByOption(_.toEpochMilli())
          }
      case _ => None
    }

    val pastCutoff: Set[String] =
      items.iterator
        .filter(i => earliestModified(i).exists(_.isAfter(cutoff)))
        .map(_.identifier)
        .toSet

    if (pastCutoff.isEmpty) items
    else {
      val byId = items.iterator.map(i => i.identifier -> i).toMap
      val removed = scala.collection.mutable.Set.from(pastCutoff)
      val queue = scala.collection.mutable.Queue.from(pastCutoff)
      while (queue.nonEmpty) {
        byId.get(queue.dequeue()).foreach { item =>
          item.connections.foreach { case (edgeType, target) =>
            // A removed node's dependents: its containers, artifacts built from it, its aliases.
            val dependent =
              EdgeType.isContainedByUp(edgeType) || EdgeType
                .isBuildsTo(edgeType) ||
                EdgeType.isAliasFrom(edgeType) || EdgeType.isAliasTo(edgeType)
            if (dependent && removed.add(target)) queue.enqueue(target)
          }
        }
      }

      val survivors = items.filterNot(i => removed.contains(i.identifier))
      val cleaned = survivors.map { i =>
        val kept =
          i.connections.filterNot { case (_, target) =>
            removed.contains(target)
          }
        if (kept.size == i.connections.size) i
        else i.copy(connections = kept)
      }
      logger.info(
        f"Cutoff ${cutoff}: removed ${removed.size}%,d nodes (${pastCutoff.size}%,d modified after cutoff, ${removed.size - pastCutoff.size}%,d dependents)"
      )
      cleaned
    }
  }

  def writeGoatRodeoFiles(
      store: ListFileNames & Storage,
      cutoff: Option[Instant] = None
  ): Option[File] = {
    store.target() match {
      case Some(target) => {
        logger.debug(f"In store with target ${target}")
        target.mkdirs()
        val start = Instant.now()

        val purlOut = store.purls()

        logger.info(f"Writing ${purlOut.size}%,d Package URLs")
        val purlFile = File(target, "purls.txt")
        purlFile.createNewFile()
        val bw = new BufferedWriter(new FileWriter(purlFile))
        try {
          for (purl <- purlOut) {
            bw.write(f"${purl}\n")
          }
        } finally {
          bw.flush()
          bw.close()
        }
        logger.debug("Wrote pURLs, about to sort the index")

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

        val finalItems = cutoff match {
          case Some(cutoff) => pruneExpired(sorted, cutoff)
          case None         => sorted
        }

        finalItems.par.foreach(_.cachedCBOR)

        logger.info(
          f"Post CBOR cache at ${Duration.between(start, Instant.now())}"
        )

        val cnt = finalItems.length

        val ret = GraphManager.writeEntries(
          target,
          finalItems.iterator
        )

        logger.info(
          f"Wrote ${cnt}%,d entries in ${Duration.between(start, Instant.now())}"
        )

        Some(ret._2)
      }
      case _ => None
    }

  }
}

case class TagPass(gitoid: String, jsonStr: String, json: Dom.Element)
