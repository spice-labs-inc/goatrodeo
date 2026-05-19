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

package io.spicelabs.goatrodeo.bench

import io.spicelabs.goatrodeo.omnibor.*
import io.spicelabs.goatrodeo.util.*

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Standalone benchmark for the graph-write / graph-read path, isolated from
  * the main test suite so it can be exercised in ~1 minute with low noise.
  *
  * Run with:
  *
  *   sbt 'Test/runMain io.spicelabs.goatrodeo.bench.GraphBenchmark'
  *
  * Optional CLI: a single integer arg overrides the default item count
  * (10 000). Lower it for faster iteration; raise it to see the asymptotic
  * behaviour.
  *
  * The benchmark reports wall-clock time for each phase. Each phase is run
  * three times and the median is printed alongside min/max so transient noise
  * is visible.
  *
  * To compare two encoder strategies (e.g. text identifier vs binary
  * identifier in `Item.scala`'s `given Encoder[Item]`), swap the encoder body,
  * `sbt 'Test/runMain io.spicelabs.goatrodeo.bench.GraphBenchmark'`, and
  * compare the printed phase medians.
  */
object GraphBenchmark {

  // ----- Synthetic graph generators -----------------------------------------

  /** Deterministic synthetic SHA-256 gitoid for seed `i`. */
  private def synthGitoid(seed: Int): Gitoid = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(s"item-$seed".getBytes("UTF-8"))
    Gitoid(Gitoid.ObjectType.Blob, Gitoid.HashAlgorithm.Sha256, md.digest())
  }

  /** Synthesise a hash-alias string ("sha1:…", "md5:…", etc.) for seed `i`. */
  private def synthHash(prefix: String, byteLen: Int, seed: Int): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(s"$prefix-$seed".getBytes("UTF-8"))
    val bytes = md.digest().take(byteLen)
    s"$prefix:${Helpers.toHex(bytes)}"
  }

  /** Build a synthetic Item with a realistic edge fan-out:
    *  - 4 hash-alias edges (sha1, sha256, sha512, md5)
    *  - 1 containedBy edge pointing at a "container" gitoid (every ~50
    *    items share a container, simulating archives)
    */
  def makeItem(seed: Int): Item = {
    val gitoid = synthGitoid(seed)
    val containerGitoid = synthGitoid(seed % 50).apply()
    val connections: TreeSet[Edge] = TreeSet(
      EdgeType.aliasFrom -> synthHash("sha1", 20, seed),
      EdgeType.aliasFrom -> synthHash("sha256", 32, seed),
      EdgeType.aliasFrom -> synthHash("sha512", 64, seed),
      EdgeType.aliasFrom -> synthHash("md5", 16, seed),
      EdgeType.containedBy -> containerGitoid
    )
    Item(
      Identifier.fromGitoid(gitoid),
      connections,
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(s"file-$seed.txt"),
          mimeType = TreeSet("application/octet-stream"),
          fileSize = 100L + seed,
          extra = TreeMap()
        )
      )
    )
  }

  // ----- Timing helpers -----------------------------------------------------

  private def timeOnce(body: => Unit): Long = {
    val start = System.nanoTime()
    body
    System.nanoTime() - start
  }

  /** Run `body` `repeats` times and report median / min / max in ms. */
  private def report(label: String, repeats: Int)(body: => Unit): Unit = {
    val nanos = (1 to repeats).map(_ => timeOnce(body)).toVector.sorted
    val medianMs = nanos(repeats / 2) / 1e6
    val minMs = nanos.head / 1e6
    val maxMs = nanos.last / 1e6
    println(
      f"  $label%-38s median ${medianMs}%8.1f ms   min ${minMs}%8.1f ms   max ${maxMs}%8.1f ms"
    )
  }

  private def warmup(items: Vector[Item]): Unit = {
    val n = math.min(items.size, 1000)
    var i = 0
    while (i < n) {
      val cbor = items(i).encodeCBOR()
      Item.decode(cbor).get
      i += 1
    }
  }

  // ----- Phases -------------------------------------------------------------

  def main(args: Array[String]): Unit = {
    val itemCount = args.headOption.flatMap(_.toIntOption).getOrElse(50000)
    val repeats = 5
    println(s"# Graph benchmark — $itemCount items, $repeats repeats per phase")
    println()

    println("Building synthetic graph...")
    val items = (1 to itemCount).map(makeItem).toVector
    val totalEdges = items.map(_.connections.size).sum
    println(s"  $itemCount items, $totalEdges total edges")
    println()

    println("Warming up JIT...")
    warmup(items)
    println()

    println("Phase 1 — CBOR encode each Item:")
    // Pre-clear the cached CBOR on each pass so the encoder runs every time.
    // (Item.cachedCBOR is a lazy val and would otherwise hit cache on repeats.)
    // We do this by allocating fresh Items per repeat.
    report("encode N items (fresh per repeat)", repeats) {
      val fresh = (1 to itemCount).map(makeItem).toVector
      var i = 0
      while (i < fresh.size) {
        fresh(i).encodeCBOR()
        i += 1
      }
    }
    val cborBytes = items.map(_.encodeCBOR())
    val totalCborBytes = cborBytes.map(_.length.toLong).sum
    println(
      f"  CBOR size: total $totalCborBytes B, avg ${totalCborBytes.toDouble / itemCount}%.1f B/Item"
    )
    println()

    println("Phase 2 — CBOR decode each Item:")
    report("decode N items", repeats) {
      var i = 0
      while (i < cborBytes.size) {
        Item.decode(cborBytes(i)).get
        i += 1
      }
    }
    println()

    println("Phase 3 — MemStorage write/read (String-keyed in-memory map):")
    report("write N items into MemStorage", repeats) {
      val storage = MemStorage(None)
      var i = 0
      while (i < items.size) {
        val item = items(i)
        storage.write(item.identifierString, _ => Some(item), _ => "")
        i += 1
      }
    }
    val populatedStorage = {
      val s = MemStorage(None)
      items.foreach(item =>
        s.write(item.identifierString, _ => Some(item), _ => "")
      )
      s
    }
    report("read N items from MemStorage", repeats) {
      var i = 0
      while (i < items.size) {
        populatedStorage.read(items(i).identifierString)
        i += 1
      }
    }
    println()

    println("Phase 4 — GraphManager.writeEntries (CBOR-on-disk):")
    val tempDir = Files.createTempDirectory("gr-bench").toFile()
    try {
      var lastTotalGrdBytes = 0L
      report("writeEntries to disk", repeats) {
        // Clean dir each repeat so each run writes fresh files.
        tempDir.listFiles().foreach(_.delete())
        GraphManager.writeEntries(tempDir, items.iterator)
        lastTotalGrdBytes =
          tempDir.listFiles().filter(_.getName.endsWith(".grd")).map(_.length).sum
      }
      println(
        f"  GRD on-disk size: total $lastTotalGrdBytes B, avg ${lastTotalGrdBytes.toDouble / itemCount}%.1f B/Item"
      )
      println()

      // (Phases 6+ executed below outside the temp-dir cleanup.)

      println("Phase 5 — GRDWalker.readNext (read all back from disk):")
      // GRDWalker.items() walks to EOF, but the GRD format ends with a
      // 2-byte sentinel and 8-byte back-pointer that GRDWalker.readNext
      // doesn't recognise as an end marker (a pre-existing edge case in the
      // codebase). For the benchmark we know exactly how many items were
      // written, so we drive readNext that many times.
      val grdFiles =
        tempDir.listFiles().filter(_.getName.endsWith(".grd")).toVector
      report("read N items via GRDWalker.readNext", repeats) {
        var count = 0
        for (grd <- grdFiles) {
          val ch = new FileInputStream(grd).getChannel()
          try {
            val w = new GRDWalker(ch)
            w.open().get
            while (count < itemCount) {
              w.readNext() match {
                case Some(_) => count += 1
                case None    => count = itemCount
              }
            }
          } finally ch.close()
        }
      }
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
    println()

    // ------------------------------------------------------------------------
    // Hashing-oriented phases. The hypothesis: with `Item.identifier:
    // Identifier` backed by `ArraySeq.ofByte`, `Identifier.hashCode` (and
    // therefore `Item.hashCode`) is recomputed O(N) on every call, where it
    // was effectively O(1) when identifier was a `String` (because
    // `String.hashCode` is cached on the String). Hash-heavy code paths —
    // HashSet/HashMap operations on `Item` or `Identifier` — should pay
    // dearly without a hashCode cache.
    //
    // If the focused benchmark also shows these phases are slow, this likely
    // explains the test-suite-level slowdown we saw with binary CBOR
    // (which itself is fast in encode/decode).
    // ------------------------------------------------------------------------

    println("Phase 6 — Identifier.hashCode (isolated):")
    val identifiers = items.map(_.identifier)
    report("compute Identifier.hashCode N times", repeats) {
      var i = 0
      var acc = 0
      while (i < identifiers.size) {
        acc ^= identifiers(i).##
        i += 1
      }
      if (acc == Int.MinValue) throw new RuntimeException("dce-guard")
    }
    println()

    println("Phase 7 — Item.hashCode (auto-generated case-class):")
    report("compute Item.hashCode N times", repeats) {
      var i = 0
      var acc = 0
      while (i < items.size) {
        acc ^= items(i).##
        i += 1
      }
      if (acc == Int.MinValue) throw new RuntimeException("dce-guard")
    }
    println()

    println("Phase 8 — HashSet[Item] build + contains:")
    report("build immutable HashSet[Item] of N items", repeats) {
      var s = scala.collection.immutable.HashSet.empty[Item]
      var i = 0
      while (i < items.size) {
        s = s + items(i)
        i += 1
      }
    }
    val populatedSet =
      items.foldLeft(scala.collection.immutable.HashSet.empty[Item])(_ + _)
    report("contains() N items in HashSet[Item]", repeats) {
      var i = 0
      while (i < items.size) {
        populatedSet.contains(items(i))
        i += 1
      }
    }
    println()

    println("Phase 9 — HashMap[Identifier, Int] build + lookup:")
    report("build immutable HashMap[Identifier, Int]", repeats) {
      var m = scala.collection.immutable.HashMap.empty[Identifier, Int]
      var i = 0
      while (i < items.size) {
        m = m.updated(items(i).identifier, i)
        i += 1
      }
    }
    val populatedMap = items.zipWithIndex.foldLeft(
      scala.collection.immutable.HashMap.empty[Identifier, Int]
    ) { case (m, (item, i)) => m.updated(item.identifier, i) }
    report("get() N keys in HashMap[Identifier, Int]", repeats) {
      var i = 0
      while (i < identifiers.size) {
        populatedMap.get(identifiers(i))
        i += 1
      }
    }
    println()
    println("Done.")
  }
}
