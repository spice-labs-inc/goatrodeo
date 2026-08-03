import io.spicelabs.goatrodeo.omnibor.Builder
import io.spicelabs.goatrodeo.omnibor.GRDWalker
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.TagInfo
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.time.Instant
import scala.collection.mutable
import scala.util.Try

/** Re-runs the big (~1GB) ADG build twice — once with a far-future expiry
  * (nothing pruned) and once with a real cutoff that drops the newest slice of
  * internal files — then checks that the pruned ADG is smaller (fewer nodes)
  * and that every edge still resolves to a real node.
  *
  * Heavy (two full builds), so it is inert unless opted in with
  * `GOATRODEO_BIG_EXPIRY=1` and the `test_data/download/adg_tests` corpus is
  * present.
  *
  * GOATRODEO_BIG_EXPIRY=1 sbt "testOnly ExpiryBigAdgSuite"
  */
class ExpiryBigAdgSuite extends munit.FunSuite {

  // Two full builds; give it room.
  override val munitTimeout = scala.concurrent.duration.Duration(2, "hours")

  private val threadCnt: Int =
    Option(System.getenv("TEST_THREAD_CNT"))
      .flatMap(s => Try(Integer.parseInt(s.trim())).toOption)
      .getOrElse(25)

  /** The exact same build ADGTests performs, into `dest`, with the given expiry
    * cutoff.
    */
  private def build(dest: File, expiry: Instant, source: File): Boolean = {
    if (dest.exists()) Helpers.deleteDirectory(dest.toPath())
    dest.mkdirs()

    var finished = false
    Builder.buildDB(
      dest = dest,
      tempDir = None,
      args = Config(expiry = Some(expiry)),
      threadCnt = threadCnt,
      maxRecords = 10000,
      tag = Some(TagInfo("foo", None)),
      fileListers = Vector(
        (
          source,
          () => Helpers.findFiles(source).filter(!_.getName().endsWith(".tgz"))
        )
      ),
      ignorePathSet = Set(),
      excludeFileRegex = Vector(),
      blockList = None,
      fsFilePaths = true,
      finishedFile = _ => (),
      done = b => { finished = b }
    )
    finished
  }

  private def grdFiles(dir: File): Vector[File] =
    Option(dir.listFiles())
      .getOrElse(Array.empty[File])
      .toVector
      .filter(_.getName.endsWith(".grd"))

  /** Stream every Item out of every `.grd` in `dir`, applying `f`.
    *
    * We reuse `GRDWalker.open()` to consume the magic + DataFileEnvelope, but
    * read entries ourselves: the file ends with a 2-byte `writeShort(-1)`
    * marker plus a back-pointer, and `GRDWalker.readNext` (which reads a 4-byte
    * int and only checks `== -1`) would read that marker as a large negative
    * length and crash on a real multi-entry file. A "length must be positive"
    * guard stops cleanly at the terminator instead.
    */
  private def foreachItem(dir: File)(f: Item => Unit): Unit =
    grdFiles(dir).foreach { file =>
      val channel = new FileInputStream(file).getChannel()
      try {
        new GRDWalker(channel).open() // consume magic + envelope
        var continue = true
        while (continue && channel.position() < channel.size()) {
          val entryLen = Helpers.readInt(channel)
          if (entryLen <= 0)
            continue = false // -1 short marker + back-pointer reads as negative
          else {
            val bb = ByteBuffer.allocate(entryLen)
            channel.read(bb)
            Item.decode(bb.array()).toOption.foreach(f)
          }
        }
      } finally channel.close()
    }

  /** Earliest recorded modification instant for an item, matching
    * Builder.pruneExpired.
    */
  private def earliestModified(item: Item): Option[Long] = item.body match {
    case Some(m: ItemMetaData) =>
      m.extra
        .get(Item.FileModifiedKey)
        .flatMap { values =>
          values.iterator
            .collect { case StringOf(s) => s }
            .flatMap(s => Try(s.toLong).toOption)
            .minOption
        }
    case _ => None
  }

  private def totalBytes(dir: File): Long =
    Option(dir.listFiles())
      .getOrElse(Array.empty[File])
      .toVector
      .filter(f => f.getName.endsWith(".grd") || f.getName.endsWith(".gri"))
      .map(_.length())
      .sum

  private def nodeIds(dir: File): Set[String] = {
    val ids = mutable.Set.empty[String]
    foreachItem(dir)(i => ids += i.identifier)
    ids.toSet
  }

  /** Count edges whose target identifier is not present in `ids`; return count
    * + a sample.
    */
  private def danglingEdges(
      dir: File,
      ids: Set[String]
  ): (Long, Vector[(String, String, String)]) = {
    var count = 0L
    val sample = mutable.ArrayBuffer.empty[(String, String, String)]
    foreachItem(dir) { i =>
      i.connections.foreach { case (edgeType, target) =>
        if (!ids.contains(target)) {
          count += 1
          if (sample.size < 10) sample += ((i.identifier, edgeType, target))
        }
      }
    }
    (count, sample.toVector)
  }

  test("big ADG shrinks under an expiry cutoff and stays edge-coherent") {
    assume(
      System.getenv("GOATRODEO_BIG_EXPIRY") == "1",
      "Set GOATRODEO_BIG_EXPIRY=1 to run the heavy two-build expiry check"
    )
    val source = File("test_data/download/adg_tests")
    assume(
      source.isDirectory(),
      "corpus test_data/download/adg_tests not present"
    )

    val fullDir = File("res_expiry_full")
    val prunedDir = File("res_expiry_pruned")

    // --- Step 1: FULL build. Far-future cutoff prunes nothing but records mod-times. ---
    val farFuture = Instant.parse("3000-01-01T00:00:00Z")
    println(s"[expiry] FULL build -> ${fullDir} (threadCnt=$threadCnt)")
    assert(
      build(fullDir, farFuture, source),
      "FULL build should finish successfully"
    )

    // --- Step 2: gather the mod-time distribution and choose a cutoff. ---
    val mtimes = mutable.ArrayBuffer.empty[Long]
    var fullNodes = 0L
    foreachItem(fullDir) { i =>
      fullNodes += 1
      earliestModified(i).foreach(mtimes += _)
    }
    assert(mtimes.nonEmpty, "FULL build recorded no modification times")
    val sorted = mtimes.sorted
    // 75th percentile: drop the newest ~25% of dated blobs (plus their dependents).
    val cutoffMillis = sorted((sorted.size.toLong * 75 / 100).toInt)
    val cutoff = Instant.ofEpochMilli(cutoffMillis)
    val pastCutoff = mtimes.count(_ > cutoffMillis)
    println(
      f"[expiry] recorded mtimes: ${mtimes.size}%,d dated nodes, " +
        f"range ${Instant.ofEpochMilli(sorted.head)}..${Instant.ofEpochMilli(sorted.last)}"
    )
    println(
      f"[expiry] chosen cutoff (75th pct) = $cutoff -> $pastCutoff%,d dated nodes past cutoff"
    )

    // --- Step 3: PRUNED build with the real cutoff. ---
    println(s"[expiry] PRUNED build -> ${prunedDir}")
    assert(
      build(prunedDir, cutoff, source),
      "PRUNED build should finish successfully"
    )

    // --- Step 4: verify smaller (fewer nodes, fewer bytes). ---
    var prunedNodes = 0L
    foreachItem(prunedDir)(_ => prunedNodes += 1)
    val fullBytes = totalBytes(fullDir)
    val prunedBytes = totalBytes(prunedDir)
    val nodePct = 100.0 * (fullNodes - prunedNodes) / fullNodes
    val bytePct = 100.0 * (fullBytes - prunedBytes) / fullBytes
    println(
      f"[expiry] nodes: full=$fullNodes%,d  pruned=$prunedNodes%,d  (-$nodePct%.1f%%)"
    )
    println(
      f"[expiry] bytes: full=$fullBytes%,d  pruned=$prunedBytes%,d  (-$bytePct%.1f%%)"
    )

    assert(
      prunedNodes < fullNodes,
      s"expected fewer nodes: full=$fullNodes pruned=$prunedNodes"
    )
    assert(
      prunedBytes < fullBytes,
      s"expected fewer bytes: full=$fullBytes pruned=$prunedBytes"
    )

    // --- Step 5: verify edge coherence in both clusters. ---
    val fullIds = nodeIds(fullDir)
    val (fullDangling, fullSample) = danglingEdges(fullDir, fullIds)
    println(f"[expiry] FULL dangling edges: $fullDangling%,d")
    fullSample.foreach { case (s, e, t) =>
      println(s"           full dangling: $s -[$e]-> $t")
    }
    assert(
      fullDangling == 0L,
      s"FULL cluster should have no dangling edges, found $fullDangling"
    )

    val prunedIds = nodeIds(prunedDir)
    val (prunedDangling, prunedSample) = danglingEdges(prunedDir, prunedIds)
    println(f"[expiry] PRUNED dangling edges: $prunedDangling%,d")
    prunedSample.foreach { case (s, e, t) =>
      println(s"           pruned dangling: $s -[$e]-> $t")
    }
    assert(
      prunedDangling == 0L,
      s"PRUNED cluster should have no dangling edges after expiry, found $prunedDangling"
    )
  }
}
