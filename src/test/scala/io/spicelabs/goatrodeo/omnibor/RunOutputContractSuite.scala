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

package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.GoatRodeoBuilder
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.testsupport.ForbiddenPatterns
import io.spicelabs.goatrodeo.testsupport.RunOutputs
import io.spicelabs.goatrodeo.testsupport.RunOutputs.Run
import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** The contract a Goat Rodeo run's output directory must honour.
  *
  * ==What this suite tests==
  *
  * A run writes `.grd` (the data), `.gri` (the index), `.grc` (the cluster
  * manifest), `history.jsonl` (the run's provenance) and `purls.txt` (the flat
  * package index); with `emitJsonDir` it also writes `items_<timestamp>.json`.
  * These files are an interface: the Spice CLI, BigTent, Surveyor's integration
  * runner and end users read them. This suite asserts that interface by running
  * real builds and reading the files back the way a consumer would.
  *
  * ==Why it exists==
  *
  * Every property here was previously unasserted. `purls.txt` was written but
  * never read by any test; `history.jsonl` was checked only for the presence of
  * the text `goat_rodeo_version`, never its value; `.gri` file existence was
  * asserted nowhere; `items_*.json` was checked only for existing; no test
  * asserted a run's item count; and a run's `.grd` files were read back only
  * once, narrowly, for git provenance. So a change that emptied `purls.txt`,
  * relocated a batch's output, or left a temporary file in place would ship
  * unnoticed.
  *
  * ==Theory of the batch boundary==
  *
  * `maxRecords` caps how many items one batch consumes. A run that exceeds it
  * splits into batches, and every batch writes its own directory: the first
  * into the output directory, later ones beside it. Each batch directory is
  * self-describing, so a consumer that reads only the output directory silently
  * misses every batch after the first. The contract is that the union across
  * batch directories is the whole graph, and that nothing temporary survives in
  * any of them.
  *
  * ==Why not golden files==
  *
  * Byte-exact fixtures are the usual pattern for output contracts and are the
  * wrong tool here: the `.grc` name embeds a timestamp, `correlation_id` is a
  * random UUID, and `history.jsonl` carries the run date. What is stable, and
  * what these tests assert, is structure: presence, naming rules,
  * cross-references, and agreement between the two published views of the
  * graph.
  *
  * ==LLM summary==
  *
  * | Test | Asserts                                                                                               |
  * |:-----|:------------------------------------------------------------------------------------------------------|
  * | O-1  | single-batch layout: `.grc`, `.grd`, `.gri`, `history.jsonl`, `purls.txt` all in the output directory |
  * | O-2  | `.grd`/`.gri`/`.grc` names follow the implemented rule, computed independently of `Helpers`           |
  * | O-3  | every name in a `.grc` resolves in its directory and hashes to the recorded SHA-256                   |
  * | O-4  | `purls.txt` holds exactly the run's purls                                                             |
  * | O-5  | `history.jsonl` records version, commit, operation, date, cluster name                                |
  * | O-6  | the JSON dump and the `.grd` files hold the same items                                                |
  * | O-7  | the run's item count meets a measured floor and the purls are reachable                               |
  * | O-8  | a crossed batch boundary writes every batch's artifacts and loses nothing                             |
  * | O-9  | the JSON dump carries no private-key material (with a sentinel proving the sweep fires)               |
  * | O-10 | no temporary file survives in any batch directory                                                     |
  *
  * Shared helper: [[RunOutputs]].
  */
class RunOutputContractSuite extends GoatRodeoFunSuite {

  /** The three package fixtures. Each is small, committed, and not a `*.jar` or
    * `*.tar`, so the suite does not depend on the LFS pull; each has a purl
    * asserted elsewhere in the tree, so this suite does not invent ground
    * truth.
    */
  private val fixtures: Vector[(String, String)] = Vector(
    "debwithmetadata.deb" -> "pkg:deb/debian/not-a-real-package@1.2.3",
    "twilito-0.5.0.gem" -> "pkg:gem/twilito@0.5.0",
    "cpufreqctl-8-1-x86_64.pkg.tar.zst" -> "pkg:alpm/arch/cpufreqctl@8-1?arch=x86_64"
  )

  /** A PKCS#8 private key, surveyed only by O-9. It is deliberately part of
    * that run so the leak sweep has something that would leak if the filter
    * failed. It is kept out of the shared run so O-4's exact purl equality
    * stays over the three known package purls (the key yields an SPKI purl of
    * its own).
    */
  private val privateKeyFixture =
    "test_data/certificates/private-keys/synthetic/pkcs8-ed25519-unencrypted.pem"

  private val expectedPurls: Set[String] = fixtures.map(_._2).toSet

  private def fixturePaths: Vector[File] =
    fixtures.map { case (name, _) => new File("test_data", name) }

  /** The one run at the default `maxRecords`, shared by O-1 … O-7 and O-10.
    * Each of those tests reads a different artifact of the same run, so a
    * second identical build would cost time and prove nothing extra. munit runs
    * a suite's tests sequentially, so the run is read-only and safe to share.
    */
  private var shared: Option[Run] = None

  /** Build a run over the fixture set.
    *
    * Tamper-evident logging is enabled so that every `.grc` carries a
    * `correlation_id` (GraphManager adds it only when a log is configured),
    * which is what `info/README.md` documents and what O-3 asserts. The
    * checksum file lands outside the output directory, so it cannot disturb O-1
    * or O-10.
    *
    * @param maxRecords
    *   the batch size; a value below the item count forces a batch boundary
    * @param extraFixtures
    *   additional files to survey
    * @return
    *   the run's directories, to be cleaned up by the caller
    */
  private def build(
      maxRecords: Int,
      extraFixtures: Vector[File] = Vector.empty
  ): Run = {
    val input = Files.createTempDirectory("roc-in").toFile()
    val output = Files.createTempDirectory("roc-out").toFile()
    val json = Files.createTempDirectory("roc-json").toFile()
    val checksum = Files.createTempFile("roc-checksum", ".json").toFile()
    (fixturePaths ++ extraFixtures).foreach { f =>
      Files.copy(f.toPath, new File(input, f.getName).toPath)
    }
    new GoatRodeoBuilder()
      .withPayload(input.getAbsolutePath)
      .withOutput(output.getAbsolutePath)
      .withExtraArg("emitJsonDir", json.getAbsolutePath)
      .withTamperEvidentLog(checksum.getAbsolutePath)
      .withThreads(2)
      .withMaxRecords(maxRecords)
      .run()
    RunOutputs.deleteTree(input)
    RunOutputs.Run(output, json, Vector(checksum))
  }

  override def beforeAll(): Unit = {
    shared = Some(build(maxRecords = 50000))
  }

  override def afterAll(): Unit = {
    shared.foreach(_.cleanup())
    shared = None
  }

  private def run: Run =
    shared.getOrElse(fail("the shared run was not built"))

  // O-1 — a single-batch run emits the full output set in the output directory.
  // THEORY: with `maxRecords` above the item count there is exactly one batch
  // and the first batch's directory IS the output directory, so every artifact
  // a consumer may rely on is directly there. If any is missing, a consumer
  // following the documented layout finds nothing, however correct the graph is.
  test("O-1 a single-batch run emits the whole output set") {
    val out = run.output
    assert(out.isDirectory, s"output directory missing: $out")

    val cluster = RunOutputs.filesWithSuffix(out, ".grc")
    assertEquals(
      cluster.length,
      1,
      s"expected exactly one .grc in the output directory, found ${cluster.map(_.getName)}"
    )
    assert(
      RunOutputs.filesWithSuffix(out, ".grd").nonEmpty,
      s"no .grd in ${out.listFiles().map(_.getName).mkString(", ")}"
    )
    assert(
      RunOutputs.filesWithSuffix(out, ".gri").nonEmpty,
      s"no .gri in ${out.listFiles().map(_.getName).mkString(", ")}"
    )

    val history = new File(out, "history.jsonl")
    assert(history.isFile, "history.jsonl must be in the output directory")
    assert(
      Files.readAllLines(history.toPath).size() > 0,
      "history.jsonl must not be empty"
    )

    val purls = new File(out, "purls.txt")
    assert(purls.isFile, "purls.txt must be in the output directory")
    assert(
      Files.readAllLines(purls.toPath).size() > 0,
      "purls.txt must not be empty"
    )
  }

  // O-2 — .grd/.gri/.grc names follow the implemented naming rule.
  // THEORY: the name is derived from the file's own content hash so a consumer
  // can tell a renamed file from a corrupted one and match a file to the hash
  // recorded in the .grc. The implemented rule is NOT a plain hash prefix:
  // `Helpers.byteArrayToLong63Bits` clears the top bit of the first 8 bytes, so
  // the 16-hex-character name always begins with `0`-`7`. The expected name is
  // computed here with explicit bit arithmetic rather than by calling the
  // helper under test, because asserting `name == toHex(byteArrayToLong63Bits(..))`
  // would move the expectation with the implementation and prove nothing.
  test("O-2 .grd, .gri and .grc names follow the implemented naming rule") {
    val hex16 = "^[0-9a-f]{16}$".r

    RunOutputs.filesWithSuffix(run.output, ".grd").foreach { f =>
      val base = f.getName.stripSuffix(".grd")
      assert(hex16.matches(base), s"${f.getName} is not 16 hex characters")
      assertEquals(
        base,
        RunOutputs.expectedHashName(RunOutputs.sha256(f)),
        s"${f.getName} is not named for its own content hash"
      )
      assert(
        base.head >= '0' && base.head <= '7',
        s"${f.getName}: the high bit of the first digest byte must be cleared"
      )
    }

    RunOutputs.filesWithSuffix(run.output, ".gri").foreach { f =>
      val base = f.getName.stripSuffix(".gri")
      assert(hex16.matches(base), s"${f.getName} is not 16 hex characters")
      assertEquals(
        base,
        RunOutputs.expectedHashName(RunOutputs.sha256(f)),
        s"${f.getName} is not named for its own content hash"
      )
    }

    val grcPattern =
      "^\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_[0-9a-f]{16}\\.grc$".r
    RunOutputs.filesWithSuffix(run.output, ".grc").foreach { f =>
      assert(
        grcPattern.matches(f.getName),
        s"${f.getName} does not match the documented cluster-file name"
      )
      val digestPart = f.getName.stripSuffix(".grc").split("_").last
      assert(
        digestPart.head >= '0' && digestPart.head <= '7',
        s"${f.getName}: the high bit of the first digest byte must be cleared"
      )
    }
  }

  // O-3 — the .grc manifest resolves to files that exist and match their hashes.
  // THEORY: the .grc is the entry point; a consumer opens the cluster file and
  // resolves the rest of the ADG from it. So every name it lists must resolve in
  // the same directory and the file's content must hash to the recorded value,
  // or the cluster is unreadable or silently wrong. TamperEvidentSuite.T-07
  // proves the envelope's internal consistency from hand-built items; this
  // proves it end-to-end from a real run.
  test("O-3 the .grc manifest resolves to files that hash as recorded") {
    RunOutputs.filesWithSuffix(run.output, ".grc").foreach { grc =>
      val env = RunOutputs.readClusterEnvelope(grc)
      val info = env.info

      assert(
        info
          .get("correlation_id")
          .exists(s => scala.util.Try(UUID.fromString(s)).isSuccess),
        s"${grc.getName}: correlation_id must be a UUID, got ${info.get("correlation_id")}"
      )

      val sha = parse(info.getOrElse("sha256", "{}"))
      def digests(field: String): Vector[String] =
        (sha \ field) match {
          case JArray(arr) => arr.collect { case JString(s) => s }.toVector
          case _           => Vector.empty
        }

      val grdDigests = digests("grd")
      val griDigests = digests("gri")
      val grdFiles = RunOutputs.filesWithSuffix(run.output, ".grd")
      val griFiles = RunOutputs.filesWithSuffix(run.output, ".gri")

      assertEquals(
        grdDigests.length,
        grdFiles.length,
        s"${grc.getName} records ${grdDigests.length} .grd digests but the directory holds ${grdFiles.length}"
      )
      assertEquals(
        griDigests.length,
        griFiles.length,
        s"${grc.getName} records ${griDigests.length} .gri digests but the directory holds ${griFiles.length}"
      )

      grdDigests.foreach { recorded =>
        val name = RunOutputs.expectedHashName(hexToBytes(recorded)) + ".grd"
        val f = new File(run.output, name)
        assert(f.isFile, s"${grc.getName} names $name, which does not exist")
        assertEquals(
          RunOutputs.sha256(f).map("%02x".format(_)).mkString,
          recorded,
          s"$name does not hash to the digest ${grc.getName} records"
        )
      }

      griDigests.foreach { recorded =>
        val name = RunOutputs.expectedHashName(hexToBytes(recorded)) + ".gri"
        val f = new File(run.output, name)
        assert(f.isFile, s"${grc.getName} names $name, which does not exist")
        assertEquals(
          RunOutputs.sha256(f).map("%02x".format(_)).mkString,
          recorded,
          s"$name does not hash to the digest ${grc.getName} records"
        )
      }
    }
  }

  // O-4 — purls.txt holds exactly the run's purls, one per line.
  // THEORY: purls.txt is the run's flat package index and the only output a user
  // is documented to grep (info/ipa_integration.md). A user's grep must find
  // every purl the ADG holds, or the documented workflow under-reports. Exact
  // equality, not a superset: a superset would mean the file advertises packages
  // the run did not find.
  test("O-4 purls.txt holds exactly the run's purls") {
    val expected = expectedPurls
    assertEquals(
      run.purls,
      expected,
      s"purls.txt lines: ${run.purls.toVector.sorted}"
    )
  }

  // O-5 — history.jsonl records the version, commit and operation of the run.
  // THEORY: history.jsonl is the run's provenance record; it is how a reader
  // learns which Goat Rodeo wrote an ADG and which cluster file belongs to it.
  // GraphManagerTestSuite asserts only that the TEXT `goat_rodeo_version`
  // appears, so a build stamping the wrong version or commit, or a cluster name
  // that does not exist, would pass today.
  test("O-5 history.jsonl records the version, commit and cluster of the run") {
    val history = new File(run.output, "history.jsonl")
    val lines = Files
      .readAllLines(history.toPath)
      .toArray(new Array[String](0))
      .toVector
      .filter(_.trim.nonEmpty)
    assert(lines.nonEmpty, "history.jsonl must have at least one line")

    lines.foreach { line =>
      val j = parse(line)
      assertEquals(
        (j \ "goat_rodeo_version") match {
          case JString(s) => s
          case _          => ""
        },
        hellogoat.BuildInfo.version,
        s"history line records the wrong version: $line"
      )
      assertEquals(
        (j \ "goat_rodeo_commit") match {
          case JString(s) => s
          case _          => ""
        },
        hellogoat.BuildInfo.commit,
        s"history line records the wrong commit: $line"
      )
      assertEquals(
        (j \ "operation") match {
          case JString(s) => s
          case _          => ""
        },
        "build_adg",
        s"history line records the wrong operation: $line"
      )
      val date = (j \ "date") match {
        case JString(s) => s
        case _          => ""
      }
      assert(
        scala.util.Try(java.time.ZonedDateTime.parse(date)).isSuccess,
        s"history date is not a parseable ISO date-time: $date"
      )
      val clusterName = (j \ "cluster_name") match {
        case JString(s) => s
        case _          => ""
      }
      assert(
        new File(run.output, clusterName).isFile,
        s"history names cluster $clusterName, which does not exist in the output directory"
      )
    }
  }

  // O-6 — the ADG JSON dump agrees with the ADG on disk.
  // THEORY: the dump and the .grd files are two published views of one graph,
  // and a consumer may read either, so they must not disagree. This is the
  // strongest structural check available without golden files: it does not pin
  // the encoding, only that both views hold the same items. StorageTestSuite
  // asserts only that a file named items_* exists; nothing parses it. Field
  // order, absent fields and the exact body shape are deliberately not asserted:
  // the dump is Item.encode-shaped and pinning it would be brittle without
  // protecting a consumer.
  test("O-6 the JSON dump holds the same items as the .grd files") {
    val dumps = RunOutputs.dumpFiles(run.json)
    assertEquals(
      dumps.length,
      1,
      s"a single-batch run writes one dump, found ${dumps.map(_.getName)}"
    )

    val text = new String(Files.readAllBytes(dumps.head.toPath), "UTF-8")
    val parsed = parse(text) match {
      case JArray(arr) => arr
      case other =>
        fail(s"the dump must be a JSON array, got ${other.getClass}")
    }
    assert(parsed.nonEmpty, "the dump must hold items")
    assert(
      parsed.forall(e =>
        ((e \ "identifier") match {
          case JString(s) => s.nonEmpty
          case _          => false
        })
      ),
      "every dumped item must have a non-empty identifier"
    )

    assertEquals(
      run.dumpIdentifiers,
      run.grdIdentifiers,
      "the JSON dump and the .grd files must hold the same items"
    )
  }

  // O-7 — the run's item count meets a measured floor and the purls are reachable.
  // THEORY: a run that silently drops artifacts still emits well-formed files.
  // Counting items and purls is what distinguishes "the graph is well-formed"
  // from "the graph is complete". ADGTests asserts captured.size > 5 (build
  // files) and tagCount > 8000 (tag edges) — neither is an item count, so a
  // whole-run item floor is currently unasserted.
  //
  // The floor was measured on 2026-10-14 for the three package fixtures: 37
  // items with a body. The literal is deliberately below the measurement so
  // ordinary variation in alias/blob items does not cause churn, while a change
  // that drops whole artifacts still fails.
  test("O-7 the run's item count meets the floor and its purls are reachable") {
    val items = run.batches.flatMap(d => RunOutputs.readItemsFromGrd(d))
    val withBody = items.filter(_.body.isDefined)
    assert(
      withBody.length >= 30,
      s"expected at least 30 items with a body (37 measured on 2026-10-14), found ${withBody.length}"
    )

    val reachable = run.purls
    assert(
      expectedPurls.subsetOf(reachable),
      s"missing purls: ${expectedPurls.diff(reachable).toVector.sorted}"
    )
  }

  // O-8 — crossing the batch boundary writes every batch's artifacts and loses
  // nothing.
  // THEORY: `maxRecords` caps how many items one batch consumes. When the
  // workload exceeds it the run splits into batches and each batch writes its own
  // directory. Two failure modes follow and neither is currently caught: later
  // batches are written somewhere a consumer does not look, or the union across
  // batches loses items. Superset rather than equality because a multi-batch run
  // legitimately holds items a single-batch run does not — each batch stamps its
  // own synthetic tag item — so equality would fail for a correct run. The
  // number of directories is deliberately not asserted: batch overlap is
  // timing-dependent, while the completeness of the union is the property a
  // consumer depends on.
  test("O-8 a crossed batch boundary writes every batch and loses nothing") {
    val multi = build(maxRecords = 1)
    try {
      val batches = multi.batches
      assert(
        batches.exists(_ != multi.output),
        s"expected the run to write a batch directory besides ${multi.output.getName}, found ${batches
            .map(_.getName)}"
      )

      batches.foreach { dir =>
        assert(
          RunOutputs.filesWithSuffix(dir, ".grd").nonEmpty,
          s"${dir.getName} has a .grc but no .grd"
        )
        assert(
          RunOutputs.filesWithSuffix(dir, ".gri").nonEmpty,
          s"${dir.getName} has a .grc but no .gri"
        )
        assert(
          new File(dir, "purls.txt").isFile,
          s"${dir.getName} has a .grc but no purls.txt"
        )
        assert(
          new File(dir, "history.jsonl").isFile,
          s"${dir.getName} has a .grc but no history.jsonl"
        )
      }

      val singleIds = run.grdIdentifiers
      val multiIds = multi.batches
        .flatMap(d => RunOutputs.readItemsFromGrd(d))
        .map(_.identifier)
        .toSet
      assert(
        singleIds.subsetOf(multiIds),
        s"the batch boundary lost ${singleIds.diff(multiIds).size} items, e.g. ${singleIds.diff(multiIds).take(3)}"
      )
      assertEquals(
        multi.purls,
        expectedPurls,
        s"the union of purls.txt across batches: ${multi.purls.toVector.sorted}"
      )
    } finally multi.cleanup()
  }

  // O-9 — the JSON dump carries no forbidden private-key material.
  // THEORY: the dump is a new exposure surface — the whole graph in one file,
  // written to a directory the user chooses, and the artifact most likely to be
  // copied elsewhere. CertificatesLeakSuite proves the in-process Item is clean;
  // nothing proves the serialised form is. A filter applied on the way into
  // metadata suffices today, but a change that serialises an unfiltered field,
  // or that writes the dump from a different code path than the leak suite
  // exercises, would expose key material in a file that leaves the machine. The
  // run includes a real PKCS#8 private key so there is something to leak.
  //
  // The sweep is over the raw file text, not parsed metadata values: a pattern
  // in a field the test does not know about, or in an encoding it does not
  // anticipate, is still a leak. The sentinel assertion proves the sweep can
  // fail — without it, "the sweep found nothing" is not evidence the sweep works.
  test("O-9 the JSON dump carries no private-key material") {
    val dump = build(
      maxRecords = 50000,
      extraFixtures = Vector(new File(privateKeyFixture))
    )
    try {
      val dumps = RunOutputs.dumpFiles(dump.json)
      assert(dumps.nonEmpty, "the run must write a dump to sweep")

      // Non-vacuity: the private key must actually have been surveyed. A sweep
      // over a dump that never held the key proves nothing, so the SPKI purl
      // the certificate strategy derives from a key is required to be present.
      val allText = dumps
        .map(f => new String(Files.readAllBytes(f.toPath), "UTF-8"))
        .mkString("\n")
      assert(
        allText.contains("spki-sha256"),
        "the dump must hold the surveyed private key's item, or O-9 is vacuous"
      )

      dumps.foreach { f =>
        val text = new String(Files.readAllBytes(f.toPath), "UTF-8")
        val hits = ForbiddenPatterns.matches(text)
        assert(
          hits.isEmpty,
          s"${f.getName} matches forbidden private-key pattern(s): $hits"
        )
      }

      // Sentinel: the sweep must fire on a known leak. Without this the
      // assertion above would pass even if the patterns never matched anything.
      val sentinelHits = ForbiddenPatterns.matches(ForbiddenPatterns.sentinel)
      assert(
        sentinelHits.nonEmpty,
        "the forbidden-pattern sweep must detect its own sentinel, or O-9 proves nothing"
      )
    } finally dump.cleanup()
  }

  // O-10 — no temporary files are left behind in any batch directory.
  // THEORY: GraphManager writes each artifact to a temporary file
  // (`goat_rodeo_data_*`, `goat_rodeo_cluster_*`) and renames it to its
  // content-addressed name, ignoring the rename's result. So if a rename fails
  // the temporary file survives and the final artifact never appears: the
  // directory looks populated but is unusable, and nothing reports it. Asserting
  // that no temporary file survives proves the rename completed for every
  // artifact in every batch directory. An exact file set is deliberately not
  // asserted: the property that matters is "nothing temporary survived", not
  // "the set of files is exactly this".
  test("O-10 no temporary files survive in any batch directory") {
    val multi = build(maxRecords = 1)
    try {
      (run.batches ++ multi.batches).foreach { dir =>
        val leftovers = Option(dir.listFiles())
          .getOrElse(Array.empty[File])
          .filter(f =>
            RunOutputs.temporaryNamePrefixes.exists(p =>
              f.getName.startsWith(p)
            )
          )
        assert(
          leftovers.isEmpty,
          s"${dir.getName} holds temporary file(s): ${leftovers.map(_.getName).toVector}"
        )

        // every artifact present must be referenced by that directory's .grc,
        // so a renamed-but-orphaned file is caught too
        val env = RunOutputs.readClusterEnvelope(
          RunOutputs.filesWithSuffix(dir, ".grc").head
        )
        val sha = parse(env.info.getOrElse("sha256", "{}"))
        val recorded: Set[String] =
          Vector("grd", "gri").flatMap { field =>
            (sha \ field) match {
              case JArray(arr) =>
                arr
                  .collect { case JString(s) => s }
                  .map(s => RunOutputs.expectedHashName(hexToBytes(s)))
              case _ => Vector.empty
            }
          }.toSet
        val present: Set[String] =
          (RunOutputs
            .filesWithSuffix(dir, ".grd")
            .map(_.getName.stripSuffix(".grd")) ++
            RunOutputs
              .filesWithSuffix(dir, ".gri")
              .map(_.getName.stripSuffix(".gri"))).toSet
        assertEquals(
          present,
          recorded,
          s"${dir.getName}: the files present are not exactly the files its .grc records"
        )
      }
    } finally multi.cleanup()
  }

  /** Parse a lower-case hex string into bytes.
    *
    * @param hex
    *   an even-length hex string
    * @return
    *   the bytes
    */
  private def hexToBytes(hex: String): Array[Byte] =
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
}
