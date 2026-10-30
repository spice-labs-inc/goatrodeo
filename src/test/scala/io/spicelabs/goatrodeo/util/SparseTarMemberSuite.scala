package io.spicelabs.goatrodeo.util

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Tests that a GNU sparse tar member is measured by its real (logical) size.
  *
  * WHAT: `FileWalker.asApacheCommonsArchiveWrapper` takes each member's
  * declared size from the archive entry and hands it to
  * `ArtifactWrapper.newWrapper`, which decides "heap or temp file" from that
  * number. The declared size now comes from `TarArchiveEntry.getRealSize()`,
  * not `getSize()`.
  *
  * WHY: for a GNU/PAX sparse member `getSize()` is the *stored* (compacted)
  * size — the bytes the archive holds — while the archive stream expands the
  * holes and yields the *logical* file. Commons Compress 1.28.0 on a real
  * sparse fixture reports `getSize()=8192`, `getRealSize()=3101184`, 3101184
  * bytes readable. Using the compacted size made every sparse member look tiny,
  * so it was kept in memory (the limit is 64 KiB when a temp dir is set)
  * instead of being written to the walk temp dir, and `newWrapper` logged
  * "expected N bytes, but got M". A tar full of sparse members therefore
  * bypassed the in-memory limit entirely.
  *
  * THEORY: the two assertions are chosen to discriminate without measuring
  * memory. With a temp dir present the in-memory limit is 64 KiB, so a sparse
  * member whose real size is 3 MB *must* end up as a `FileWrapper` and one
  * measured by `getSize()` would not. The ordinary member in the same archive
  * must stay a `ByteWrapper`, which proves `getRealSize()` did not change the
  * behaviour of non-sparse entries.
  *
  * FIXTURE: `test_data/tar/sparse-gnu.tar`, produced by `tar --sparse
  * --format=gnu --owner=0 --group=0 --numeric-owner` from a 3,101,184-byte file
  * holding two short runs of data and a `normal.txt` of 17 bytes. Members are
  * only readable inside the walk callback, because their temp files live in the
  * walk's temp directory.
  *
  * LLM note: SP-x = test id.
  */
class SparseTarMemberSuite extends GoatRodeoFunSuite {

  private val archive = new File("test_data/tar/sparse-gnu.tar")
  private val sparseLogicalSize = 3101184L

  /** A wrapper whose temp dir is set, so the in-memory limit is 64 KiB. */
  private def archiveWrapper(tempDir: File): ArtifactWrapper =
    FileWrapper(archive, archive.getPath, Some(tempDir))

  private def withTempDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("sparse-tar-suite")
    try f(dir)
    finally {
      val listed = Files.list(dir)
      try listed.forEach(p => Files.deleteIfExists(p))
      finally listed.close()
      Files.deleteIfExists(dir)
    }
  }

  // SP-1 — a sparse member is measured by its real size and leaves the heap.
  test(
    "SP-1 a GNU sparse member is wrapped by its real size, not its stored size"
  ) {
    withTempDir { tempDir =>
      val walked =
        FileWalker.withinArchiveStream(archiveWrapper(tempDir.toFile)) {
          found =>
            assert(found.nonEmpty, "the archive must yield members")
            val sparse = found
              .find(_.path() == "sparsefile")
              .getOrElse(
                fail(s"sparsefile missing, got ${found.map(_.path())}")
              )
            assert(
              sparse.isInstanceOf[FileWrapper],
              s"a ${sparseLogicalSize}-byte sparse member must go to a temp file, got ${sparse.getClass.getSimpleName}"
            )
            assertEquals(
              sparse.size(),
              sparseLogicalSize,
              "the member's size must be the logical size"
            )
            val read =
              sparse.withStream(_.readNBytes(sparseLogicalSize.toInt)).length
            assertEquals(
              read,
              sparseLogicalSize.toInt,
              "the member's content must be the expanded holes"
            )
            ()
        }
      assert(walked.isDefined, "the tar must be walked as a container")
    }
  }

  // SP-2 — ordinary members are unaffected by the change.
  test(
    "SP-2 an ordinary tar member keeps its declared size and stays in memory"
  ) {
    withTempDir { tempDir =>
      val walked =
        FileWalker.withinArchiveStream(archiveWrapper(tempDir.toFile)) {
          found =>
            val normal = found
              .find(_.path() == "normal.txt")
              .getOrElse(
                fail(s"normal.txt missing, got ${found.map(_.path())}")
              )
            assertEquals(
              normal.size(),
              17L,
              "an ordinary member keeps its size"
            )
            assert(
              normal.isInstanceOf[ByteWrapper],
              s"a 17-byte member must stay in memory, got ${normal.getClass.getSimpleName}"
            )
            ()
        }
      assert(walked.isDefined, "the tar must be walked as a container")
    }
  }
}
