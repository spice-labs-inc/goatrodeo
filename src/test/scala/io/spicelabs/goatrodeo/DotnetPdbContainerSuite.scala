package io.spicelabs.goatrodeo

import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite

import java.io.ByteArrayInputStream
import java.nio.file.Files
import scala.util.Try

/** Cilantro 0.4.0 PDB-as-container (handoff §2.4; user design: a PDB is just
  * a container; embedded sources become ArtifactWrappers; contain:up
  * connections happen automatically in the graph).
  *
  * Fixtures are REAL portable PDBs (BSJB root) with embedded sources from the
  * committed NuGet packages in test_data/dotnet/:
  *   - polly.8.4.1.nupkg -> lib/net6.0/Polly.pdb (verified: contains the
  *     #EmbeddedSource GUID and opens via PortablePdbFile.withPdb).
  *
  * WHAT verifies:
  *   - A wrapper whose MIME is `pe/debug; format=mpdb` (the MIME the
  *     assembly walk stamps on a type-17 debug blob) is treated by FileWalker
  *     as a PDB container: inside a single withFile, PortablePdbFile.withPdb
  *     parses it, and view.sources become ArtifactWrappers (the embedded
  *     source files) with their names and content.
  *   - A `pe/debug; format=mpdb` wrapper that is NOT a parseable portable
  *     PDB is rejected as a container (no children, no throw).
  *
  * LLM note: on 0.4.0 the PDB readers are callback-owned and the walk itself
  * creates no temp state; the spool dir is a uniquely-named subdir of the
  * walk temp dir that GR cleans up at scope exit. This suite pins the
  * container contract (MIME key, withFile + withPdb, wrap sources inside the
  * callback, reject-without-throw).
  */
class DotnetPdbContainerSuite extends FunSuite {

  private def pollyPdbWrapper(): ArtifactWrapper = {
    val zip = new java.util.zip.ZipFile("test_data/dotnet/polly.8.4.1.nupkg")
    try {
      val e = zip.getEntry("lib/net6.0/Polly.pdb")
      val in = zip.getInputStream(e)
      val bytes =
        try in.readAllBytes()
        finally in.close()
      val tempDir = Files.createTempDirectory("pdbnupkg")
      ArtifactWrapper.newWrapper(
        nominalPath = "Polly.pdb",
        size = bytes.length.toLong,
        data = new ByteArrayInputStream(bytes),
        tempDir = Some(tempDir.toFile),
        tempPath = tempDir,
        mimeHint = Some("pe/debug; format=mpdb")
      )
    } finally zip.close()
  }

  test("PDB-1 a pe/debug;format=mpdb wrapper is walked as a PDB container") {
    val pdb = pollyPdbWrapper()
    val walked = FileWalker.withinArchiveStream(artifact = pdb) { found =>
      found
    }
    assert(
      walked.isDefined,
      "FileWalker must treat the pe/debug;format=mpdb wrapper as a container"
    )
    val sources = walked.get
    assert(sources.nonEmpty, "the PDB container must yield source files")
    assert(
      sources.exists(_.path().toLowerCase.endsWith(".cs")),
      s"expected .cs sources, got ${sources.map(_.path()).take(5)}"
    )
  }

  test("PDB-2 the yielded sources carry real content") {
    val pdb = pollyPdbWrapper()
    FileWalker
      .withinArchiveStream(artifact = pdb) { found =>
        found.foreach { src =>
          val len = src.withStream { stream =>
            var n = 0L
            val buf = new Array[Byte](4096)
            var r = stream.read(buf)
            while (r >= 0) {
              n += r
              r = stream.read(buf)
            }
            n
          }
          assert(len > 0, s"source ${src.path()} must have content")
        }
      }
      .get
  }

  test("PDB-3 a fake pe/debug;format=mpdb blob is rejected, not thrown") {
    val tempDir = Files.createTempDirectory("fakepdb")
    try {
      val fake = ArtifactWrapper.newWrapper(
        nominalPath = "fake.pdb",
        size = 4,
        data = new ByteArrayInputStream(Array[Byte](1, 2, 3, 4)),
        tempDir = Some(tempDir.toFile),
        tempPath = tempDir,
        mimeHint = Some("pe/debug; format=mpdb")
      )
      val walked = Try(
        FileWalker.withinArchiveStream(artifact = fake) { found =>
          found
        }
      )
      assert(
        walked.isSuccess,
        s"walk on a fake PDB must not throw: ${walked.failed.toOption}"
      )
      assert(
        walked.get.isEmpty,
        "a fake PDB must be rejected as a container"
      )
    } finally Helpers.deleteDirectory(tempDir)
  }
}