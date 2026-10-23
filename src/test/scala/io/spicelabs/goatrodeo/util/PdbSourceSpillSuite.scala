package io.spicelabs.goatrodeo.util

import io.spicelabs.cilantro.PDBView
import io.spicelabs.cilantro.PortablePdbFile
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.nio.file.Files
import java.security.MessageDigest
import java.util.Arrays
import java.util.zip.ZipFile
import scala.util.Try

/** Tests for how the portable-PDB branch wraps its embedded sources.
  *
  * WHAT: `FileWalker.asPdbContainer` hands each `EmbeddedSourceFile` to
  * `ArtifactWrapper.newWrapper` with an unknown size, so every embedded source
  * becomes a `FileWrapper` in the walk directory rather than a `ByteWrapper`
  * copied whole into memory.
  *
  * WHY: the previous code buffered each source into a `ByteArrayOutputStream`
  * with no bound. A portable PDB is attacker-supplied data — it arrives inside a
  * compiled assembly — so one pathological embedded source could exhaust the
  * heap and kill the run. Cilantro's `EmbeddedSourceFile` exposes only `name` and
  * `processStream`, with no length, so `newWrapper`'s unknown-size branch (which
  * writes a temp file) is the only way to route the decision through the one
  * place that owns it.
  *
  * THEORY: the thing worth pinning is the pair "no whole-source buffering" and
  * "content unchanged". S-1 pins the first: every yielded wrapper must be a
  * `FileWrapper`, which cannot happen if the source was buffered in memory.
  * S-2 pins the second by comparing each yielded wrapper's content with the
  * bytes the PDB reader itself yields for the same file, digest by digest, so a
  * spill that truncated or reordered bytes fails.
  */
class PdbSourceSpillSuite extends GoatRodeoFunSuite {

  private val nupkg = "test_data/dotnet/polly.8.4.1.nupkg"

  /** The same real portable PDB the dotnet container suite walks. */
  private def pdbWrapper(): ArtifactWrapper = {
    val zip = new ZipFile(nupkg)
    try {
      val e = zip.getEntry("lib/net6.0/Polly.pdb")
      val in = zip.getInputStream(e)
      val bytes = try in.readAllBytes()
      finally in.close()
      val tempDir = Files.createTempDirectory("pdb-source-suite")
      ArtifactWrapper
        .newWrapper(
          nominalPath = "Polly.pdb",
          size = bytes.length.toLong,
          data = new java.io.ByteArrayInputStream(bytes),
          tempDir = Some(tempDir.toFile),
          tempPath = tempDir,
          mimeHint = Set("pe/debug; format=mpdb")
        )
        .get
    } finally zip.close()
  }

  private def digest(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"$b%02x")
      .mkString

  // S-1 — nothing is buffered whole: every source is a file.
  //
  // Asserted inside the walk callback: a spilled source lives in the walk's
  // temp directory, which is deleted when the walk returns, exactly like every
  // other container member. Reading it afterwards would test a deleted file.
  test("S-1 every embedded PDB source is wrapped as a file, not buffered") {
    val pdb = pdbWrapper()
    val walked = FileWalker.withinArchiveStream(artifact = pdb) { found =>
      assert(found.nonEmpty, "the PDB must yield sources")
      found.foreach { s =>
        assert(
          s.isInstanceOf[FileWrapper],
          s"${s.path()} must be a FileWrapper (memory must not hold the source), got ${s.getClass.getSimpleName}"
        )
        assert(
          s.mimeType.contains("text/plain"),
          s"${s.path()} must carry the text/plain hint, got ${s.mimeType}"
        )
        assert(s.size() > 0, s"${s.path()} must have content while the walk is open")
      }
      found.length
    }
    assert(walked.isDefined, "the PDB must be walked as a container")
    assert(walked.get > 0, "the PDB must yield sources")
  }

  // S-2 — the content a caller reads is exactly what the PDB reader holds.
  test("S-2 spilled sources keep the PDB's exact content") {
    val pdb = pdbWrapper()

    val direct: Set[String] = pdb.withFile { f =>
      val acc = scala.collection.mutable.Set.empty[String]
      PortablePdbFile.withPdb[Unit](f, None) { (outcome: Try[Option[PDBView]]) =>
        outcome.foreach(_.foreach { view =>
          view.sources.foreach { src =>
            acc += digest(src.processStream(_.readAllBytes()))
          }
        })
        ()
      }
      acc.toSet
    }
    assert(direct.nonEmpty, "the PDB view must expose source content")

    val walked = FileWalker.withinArchiveStream(artifact = pdb) { found =>
      found.map(s => digest(s.withStream(_.readAllBytes()))).toSet
    }
    assert(walked.isDefined, "the PDB must be walked as a container")
    assertEquals(
      walked.get,
      direct,
      "every wrapped source must carry exactly the bytes the PDB reader yields"
    )
  }

  // S-3 — the wrappers are independent, not aliases of one buffer.
  test("S-3 distinct sources do not share one buffer") {
    val pdb = pdbWrapper()
    val contents = FileWalker.withinArchiveStream(artifact = pdb) { found =>
      found.map(s => s.path() -> digest(s.withStream(_.readAllBytes()))).toVector
    }
    assert(contents.isDefined)
    val byPath = contents.get
    assertEquals(
      byPath.map(_._1).distinct.length,
      byPath.length,
      s"each source must have its own path, got ${byPath.map(_._1)}"
    )
    assert(
      byPath.map(_._2).distinct.length > 1,
      "the fixture should contain more than one distinct source file"
    )
  }
}
