/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.spicelabs.goatrodeo.envelopes.ClusterFileEnvelope
import io.spicelabs.goatrodeo.omnibor.CbomEmitter
import io.spicelabs.goatrodeo.omnibor.GraphManager
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import munit.FunSuite
import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Tests for the tamper-evident logging feature.
  *
  * Covers: the hash-chain appender (verifiability, tamper detection, truncation
  * detection, chain-head exposure), the CBOM filename format, the `.grc` `info`
  * additions, and the run-level checksum file.
  */
class TamperEvidentSuite extends FunSuite {

  private def tempDir(): File =
    Files.createTempDirectory("tamper-test").toFile()

  private def cleanup(dir: File): Unit = {
    if (dir != null && dir.exists()) {
      Files
        .walk(dir.toPath())
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete(_))
      ()
    }
  }

  private def makeItem(
      id: String,
      fileNames: TreeSet[String] = TreeSet(),
      extra: TreeMap[String, TreeSet[StringOrPair]] = TreeMap()
  ): Item = {
    Item(
      identifier = id,
      connections = TreeSet(),
      bodyMimeType = Some(ItemMetaData.mimeType),
      body = Some(
        ItemMetaData(
          fileNames = fileNames,
          mimeType = TreeSet(),
          fileSize = 0,
          extra = extra
        )
      )
    )
  }

  private def storeItem(storage: Storage, item: Item): Unit = {
    storage.write(item.identifier, _ => Some(item), _ => "test")
    ()
  }

  /** Decode a `.grc` file's ClusterFileEnvelope. The cluster file layout is
    * magic (4 bytes) + short envelope length (2 bytes) + CBOR envelope.
    */
  private def readClusterEnv(file: File): ClusterFileEnvelope = {
    val dfp = new java.io.FileInputStream(file).getChannel()
    try {
      val magic = Helpers.readInt(dfp)
      assertEquals(magic, GraphManager.Consts.ClusterFileMagicNumber)
      val len = Helpers.readShort(dfp)
      val bytes = new Array[Byte](len)
      var off = 0
      while (off < len) {
        val r = dfp.read(java.nio.ByteBuffer.wrap(bytes, off, len - off))
        if (r < 0) throw new Exception("short read on cluster envelope")
        off += r
      }
      io.bullet.borer.Cbor.decode(bytes).to[ClusterFileEnvelope].value
    } finally {
      dfp.close()
    }
  }

  /** Recompute the chain digests from payloads and check they match the
    * `<digest> <payload>` prefixes. Returns the recomputed digests.
    */
  private def verifyChain(lines: Seq[String]): Boolean = {
    var prev: Array[Byte] = Array.emptyByteArray
    var ok = true
    for (line <- lines if line.trim.nonEmpty) {
      val idx = line.indexOf(' ')
      if (idx != 64) ok = false
      else {
        val recorded = line.substring(0, idx)
        val payload = line.substring(idx + 1)
        val computed =
          if (prev.isEmpty) Helpers.computeSHA256(payload.getBytes("UTF-8"))
          else Helpers.computeSHA256(prev ++ payload.getBytes("UTF-8"))
        if (!Helpers.toHex(computed).equals(recorded)) ok = false
        prev = computed
      }
    }
    ok
  }

  /** Install a ChainAppender on an isolated logger and return the appender + a
    * function that logs a message through it.
    */
  private def chainedLogger(
      file: File
  ): (ChainAppender, String => Unit) = {
    val context = new LoggerContext()
    val appender = new ChainAppender()
    appender.setContext(context)
    appender.setFile(file)
    appender.start()
    val logger = context.getLogger("tamper-test")
    logger.setLevel(Level.INFO)
    logger.setAdditive(false)
    logger.addAppender(appender)
    (appender, (msg: String) => logger.info(msg))
  }

  // T-01 — a sequence of log lines forms a verifiable hash chain. THEORY: the
  // chaining digest_N = SHA256(digest_{N-1} || payload_N) lets any verifier
  // recompute each line's digest from the previous line's digest and the line's
  // own payload text, without knowing the log pattern.
  test("T-01 ChainAppender produces a verifiable hash chain") {
    val dir = tempDir()
    try {
      val file = new File(dir, "run.log")
      val (_, log) = chainedLogger(file)
      val msgs = Vector("alpha", "beta", "gamma")
      msgs.foreach(log)
      val lines =
        Files.readAllLines(file.toPath()).toArray(new Array[String](0))
      assertEquals(lines.length, msgs.length)
      assert(verifyChain(lines.toSeq))
    } finally {
      cleanup(dir)
    }
  }

  // T-02 — editing a line's payload (leaving its recorded digest) breaks the
  // chain. THEORY: the tamper-evidence property is that a modified line no
  // longer hashes to its recorded prefix, so verification fails.
  test("T-02 a tampered line breaks verification") {
    val dir = tempDir()
    try {
      val file = new File(dir, "run.log")
      val (_, log) = chainedLogger(file)
      Vector("alpha", "beta", "gamma").foreach(log)
      val lines =
        Files.readAllLines(file.toPath()).toArray(new Array[String](0))
      val tampered = lines.toVector.updated(
        1,
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa betaX"
      )
      assert(!verifyChain(tampered))
    } finally {
      cleanup(dir)
    }
  }

  // T-03 — truncation is undetectable by internal verification but is caught by
  // comparing the recomputed last digest to the previously recorded chain head.
  // THEORY: the chain head is the out-of-band anchor; dropping trailing lines
  // leaves the last line's digest different from the recorded head.
  test("T-03 truncation is caught against the recorded chain head") {
    val dir = tempDir()
    try {
      val file = new File(dir, "run.log")
      val (appender, log) = chainedLogger(file)
      Vector("alpha", "beta", "gamma").foreach(log)
      val head = appender.currentChainHead()
      // remove the last line (truncate)
      val all = Files.readAllLines(file.toPath()).toArray(new Array[String](0))
      Files.writeString(
        file.toPath(),
        all.dropRight(1).mkString("", "\n", "\n")
      )
      val remaining =
        Files.readAllLines(file.toPath()).toArray(new Array[String](0))
      assert(verifyChain(remaining.toSeq))
      val last = remaining.last
      val lastDigest = last.substring(0, last.indexOf(' '))
      assert(lastDigest != head)
    } finally {
      cleanup(dir)
    }
  }

  // T-04 — currentChainHead reflects the digest of the last line emitted.
  // THEORY: the head must be the cumulative digest of all lines so far so it can
  // be embedded into a .grc and the checksum.
  test("T-04 currentChainHead returns the last cumulative digest") {
    val dir = tempDir()
    try {
      val file = new File(dir, "run.log")
      val (appender, log) = chainedLogger(file)
      log("only-line")
      val lines =
        Files.readAllLines(file.toPath()).toArray(new Array[String](0))
      val expected = lines.last.substring(0, lines.last.indexOf(' '))
      assertEquals(appender.currentChainHead(), expected)
      assert(appender.currentChainHead().length == 64)
    } finally {
      cleanup(dir)
    }
  }

  // T-05 — CBOM filenames use the escaped first file name + last 16 hex chars of
  // the gitoid. THEORY: the full gitoid is inside the CBOM, so the filename
  // needs only enough to disambiguate; the name must be filesystem-safe.
  test("T-05 CBOM filename is escaped name + last 16 of gitoid") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val root = makeItem(
        "gitoid:blob:sha256:0000000000000000000000000000000000000000000000000000000000000000",
        fileNames = TreeSet("root-ca.crt")
      )
      storeItem(storage, root)
      val files = CbomEmitter.emitForStorage(storage, "1.6", dir).get
      assertEquals(
        files.head.getName(),
        "cbom_root-ca_crt_0000000000000000.json"
      )
    } finally {
      cleanup(dir)
    }
  }

  // T-06 — a full-path file name (--fs-file-paths style) is escaped and
  // truncated to keep the filename bounded. THEORY: path characters become `_`
  // and overly long names are capped (keeping the tail).
  test("T-06 CBOM filename truncates long paths and escapes separators") {
    val dir = tempDir()
    try {
      val storage = MemStorage(None)
      val longPath =
        ("a" * 120) + "/" + ("b" * 20) + "/etc/ssl/certs/root-ca.crt"
      val root = makeItem(
        "gitoid:blob:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        fileNames = TreeSet(longPath)
      )
      storeItem(storage, root)
      val files = CbomEmitter.emitForStorage(storage, "1.6", dir).get
      val name = files.head.getName()
      assert(name.startsWith("cbom_"))
      assert(name.endsWith("_bbbbbbbbbbbbbbbb.json"))
      assert(!name.contains("/"))
      assert(name.length <= 200)
    } finally {
      cleanup(dir)
    }
  }

  // T-07 — a .grc carries correlation_id and full per-file sha256 in info, and
  // log_chain_head only when tamper-evidence is active. THEORY: info is purely
  // additive; the sha256 arrays are index-aligned with dataFiles/indexFiles.
  test(
    "T-07 .grc info records correlation id, per-file sha256, and chain head"
  ) {
    val dir = tempDir()
    try TamperEvidentLog.sync.synchronized {
      val chainHex =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
      TamperEvidentLog.start(
        "11111111-2222-3333-4444-555555555555",
        () => Some(chainHex)
      )
      val items = Vector(
        makeItem(
          "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        makeItem(
          "gitoid:blob:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
      )
      val (dataAndIndex, clusterFile) =
        GraphManager.writeEntries(dir, items.iterator)
      val env = readClusterEnv(clusterFile)
      assertEquals(
        env.info("correlation_id"),
        "11111111-2222-3333-4444-555555555555"
      )
      assertEquals(env.info("log_chain_head"), chainHex)
      // sha256 JSON arrays, index-aligned with dataFiles/indexFiles
      val sha = parse(env.info("sha256"))
      val grd = (sha \ "grd").children.map(_.values.toString)
      val gri = (sha \ "gri").children.map(_.values.toString)
      assertEquals(grd.length, dataAndIndex.length)
      assertEquals(gri.length, dataAndIndex.length)
      dataAndIndex.zipWithIndex.foreach { case (dif, i) =>
        assertEquals(grd(i), dif.dataFileSha256)
        assertEquals(gri(i), dif.indexFileSha256)
      }
    } finally {
      TamperEvidentLog.start("", () => None)
      cleanup(dir)
    }
  }

  // T-08 — log_chain_head is absent from .grc info when tamper-evidence is off.
  // THEORY: the chain head is a flag-gated, additive field.
  test("T-08 .grc info omits log_chain_head when tamper-evidence is off") {
    val dir = tempDir()
    try TamperEvidentLog.sync.synchronized {
      TamperEvidentLog.start("99999999-8888-7777-6666-555555555555", () => None)
      val items = Vector(
        makeItem(
          "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        )
      )
      val (_, clusterFile) = GraphManager.writeEntries(dir, items.iterator)
      val env = readClusterEnv(clusterFile)
      assertEquals(
        env.info("correlation_id"),
        "99999999-8888-7777-6666-555555555555"
      )
      assert(!env.info.contains("log_chain_head"))
    } finally {
      TamperEvidentLog.start("", () => None)
      cleanup(dir)
    }
  }

  // T-09 — the run-level checksum JSON contains correlation id, final chain head,
  // and every recorded .grc. THEORY: it is the out-of-band anchor that ties the
  // run's .grc files to the chained log.
  test("T-09 checksum file shape") {
    val dir = tempDir()
    try TamperEvidentLog.sync.synchronized {
      TamperEvidentLog.start(
        "abcd1234-0000-0000-0000-000000000000",
        () =>
          Some(
            "feedface00000000000000000000000000000000000000000000000000000000"
          )
      )
      TamperEvidentLog.addGrc(
        "2026_01_02_03_04_05_deadbeef.grc",
        "beefcafe0000000000000000000000000000000000000000000000000000000000"
      )
      val file = TamperEvidentLog.writeChecksum(
        dir,
        "abcd1234-0000-0000-0000-000000000000"
      )
      assertEquals(
        file.getName(),
        "goat_rodeo_abcd1234-0000-0000-0000-000000000000_checksum.json"
      )
      val json = parse(Files.readString(file.toPath()))
      assertEquals(
        (json \ "correlation_id").values.toString,
        "abcd1234-0000-0000-0000-000000000000"
      )
      assertEquals(
        (json \ "final_chain_head").values.toString,
        "feedface00000000000000000000000000000000000000000000000000000000"
      )
      assertEquals(
        (json \ "grcs")(0) \ "name" match {
          case org.json4s.JString(s) => s
          case other                 => fail(s"expected string, got $other")
        },
        "2026_01_02_03_04_05_deadbeef.grc"
      )
    } finally {
      TamperEvidentLog.start("", () => None)
      cleanup(dir)
    }
  }

  // T-10 — TamperEvidentLog.reset() invokes the run's cleanup callback (the
  // mechanism by which the chain appender is detached from the root logger).
  // THEORY: cleanup must always run at the end of a run so a tamper-evident
  // appender never leaks into subsequent work in the same JVM.
  test("T-10 reset invokes the run cleanup callback") {
    TamperEvidentLog.sync.synchronized {
      val called = new java.util.concurrent.atomic.AtomicBoolean(false)
      TamperEvidentLog.start("corr-id", () => None, () => called.set(true))
      TamperEvidentLog.addGrc("a.grc", "beef")
      TamperEvidentLog.reset()
      assert(called.get())
      assertEquals(TamperEvidentLog.correlationId, "")
      assertEquals(TamperEvidentLog.grcs, Vector())
    }
  }
}
