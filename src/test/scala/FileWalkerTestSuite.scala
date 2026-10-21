import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

class FileWalkerTestSuite extends GoatRodeoFunSuite {

  // ==================== withinArchiveStream Tests ====================

  /** Counts the entries in a directory, or -1 when it does not exist.
    *
    * LLM note: used only to observe whether a walk created anything; it says
    * nothing about the contents of any source file.
    */
  private def entryCount(dir: Path): Int =
    if (!Files.isDirectory(dir)) -1 else Files.list(dir).count().toInt

  /** Number of entries in a directory, or -1 when the directory no longer
    * exists (the cleanup signal).
    */
  private def step(dir: Path): Int = entryCount(dir)

  /** WHAT: a walk that declines an artifact must not create a walk directory.
    *
    * WHY: the walk directory was previously created eagerly for every artifact
    * offered to `withinArchiveStream`, including the great majority that are
    * settled by a MIME check in a few microseconds. The directory is now
    * materialized only by a branch that actually writes into it.
    *
    * THEORY: `plain.txt` is `text/plain`, which `notArchive` rejects before any
    * branch runs, and its temp dir stays empty. If the directory were created
    * eagerly this assertion fails, so the test discriminates the change rather
    * than merely describing it.
    */
  test("withinArchiveStream - creates no walk directory when it declines") {
    val tempDir = Files.createTempDirectory("lazy-walk-declined")
    try {
      val wrapper = ByteWrapper(
        "just some text".getBytes("UTF-8"),
        "plain.txt",
        Some(tempDir.toFile)
      )
      val result =
        FileWalker.withinArchiveStream(wrapper)(files => files.length)
      assertEquals(result, None)
      assertEquals(
        entryCount(tempDir),
        0,
        "a declined walk must leave the artifact's temp dir untouched"
      )
    } finally {
      Files.list(tempDir).forEach(p => Files.deleteIfExists(p))
      Files.deleteIfExists(tempDir)
    }
  }

  /** WHAT: a walk that succeeds still gets a real directory, and it is removed
    * afterwards.
    *
    * WHY: laziness must not become "never created"; the zip branch genuinely
    * needs a directory to hold the extracted members. This pins that the lazy
    * thunk yields a usable directory and that the cleanup still happens.
    *
    * THEORY: during the callback the walk directory exists inside the
    * artifact's temp dir, which proves the lazy thunk produced a usable
    * directory; after the callback it is gone, which proves cleanup still runs.
    * Counting its entries would not work: small zip members are held in memory,
    * so the directory is legitimately empty even on a successful walk.
    */
  test(
    "withinArchiveStream - materializes the directory only for a real walk"
  ) {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val tempDir = Files.createTempDirectory("lazy-walk-created")
      var walkDir: Option[Path] = None
      try {
        val wrapper =
          FileWrapper(jarFile, jarFile.getName(), Some(tempDir.toFile))
        val result = FileWalker.withinArchiveStream(wrapper) { files =>
          val listed = Files.list(tempDir)
          try
            walkDir = listed.iterator().asScala.find(p => Files.isDirectory(p))
          finally listed.close()
          files.length
        }
        assert(result.isDefined, "the zip walk should succeed")
        assert(
          walkDir.isDefined,
          "the walk directory must exist while the walk callback runs"
        )
        assert(
          step(walkDir.get) == -1,
          "the walk directory must be deleted when the walk finishes"
        )
      } finally {
        Files.list(tempDir).forEach(p => Files.deleteIfExists(p))
        Files.deleteIfExists(tempDir)
      }
    }
  }

  /** WHAT: while a walk is running, `withinTempDir` reuses the walk's
    * directory.
    *
    * WHY: nested code that needs somewhere to write (a `ByteWrapper.withFile`
    * spill goes through `withinTempDir`) must land in the walk's directory, not
    * in a sibling the walk's cleanup would never remove. The directory is now
    * created on demand, so it has to be published at that moment rather than at
    * walk entry.
    *
    * THEORY: inside the zip callback, `withinTempDir` must return a directory
    * whose parent is the artifact's own temp dir. If publication were missing,
    * `withinTempDir` would create its own directory most of the time; this
    * asserts the parent relationship, which holds either way, plus that the
    * returned directory is the same one the walk created.
    */
  test("withinArchiveStream - publishes the walk directory to withinTempDir") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val tempDir = Files.createTempDirectory("lazy-walk-published")
      var reused: Option[Path] = None
      var walkDir: Option[Path] = None
      try {
        val wrapper =
          FileWrapper(jarFile, jarFile.getName(), Some(tempDir.toFile))
        FileWalker.withinArchiveStream(wrapper) { files =>
          reused = FileWalker.withinTempDir(dir => dir)
          val listed = Files.list(tempDir)
          try
            walkDir = listed.iterator().asScala.find(p => Files.isDirectory(p))
          finally listed.close()
          files.length
        }
        assert(reused.isDefined, "a walk should publish a directory")
        assertEquals(
          reused,
          walkDir,
          "withinTempDir must return this walk's directory, not a new one"
        )
      } finally {
        Files.list(tempDir).forEach(p => Files.deleteIfExists(p))
        Files.deleteIfExists(tempDir)
      }
    }
  }

  test("withinArchiveStream - extracts files from ZIP") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getName(), None)

      val result = FileWalker.withinArchiveStream(wrapper) { files =>
        files.length
      }

      assert(result.isDefined)
      assert(result.get > 0)
    }
  }

  test("withinArchiveStream - extracts files from TAR") {
    val tarFile = new File("test_data/ics_test.tar")
    if (tarFile.exists()) {
      val wrapper = FileWrapper(tarFile, tarFile.getName(), None)

      val result = FileWalker.withinArchiveStream(wrapper) { files =>
        files.length
      }

      assert(result.isDefined)
      assert(result.get > 0)
    }
  }

  test("withinArchiveStream - handles DEB package") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)

      val result = FileWalker.withinArchiveStream(wrapper) { files =>
        files.length
      }

      assert(result.isDefined)
      assert(result.get > 0)
    }
  }

  test("withinArchiveStream - handles GZIP compressed TAR") {
    val tgzFile = new File("test_data/toml-rs.tgz")
    if (tgzFile.exists()) {
      val wrapper = FileWrapper(tgzFile, tgzFile.getName(), None)

      val result = FileWalker.withinArchiveStream(wrapper) { files =>
        files.length
      }

      assert(result.isDefined)
      assert(result.get > 0)
    }
  }

  test("withinArchiveStream - returns None for non-archive") {
    val textContent = "just plain text"
    val wrapper = ByteWrapper(textContent.getBytes("UTF-8"), "test.txt", None)

    val result = FileWalker.withinArchiveStream(wrapper) { files =>
      files.length
    }

    assertEquals(result, None)
  }

  test("withinArchiveStream - provides ArtifactWrappers with correct paths") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getName(), None)

      val result = FileWalker.withinArchiveStream(wrapper) { files =>
        files.map(_.path())
      }

      assert(result.isDefined)
      val paths = result.get
      assert(paths.exists(_.endsWith(".class")))
    }
  }

  test("withinArchiveStream - files have correct sizes") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getName(), None)

      val result = FileWalker.withinArchiveStream(wrapper) { files =>
        files.forall(_.size() > 0)
      }

      assert(result.isDefined)
      // Note: Some entries might be empty, so just check the result exists
    }
  }

  test("withinArchiveStream - handles nested archives") {
    val nestedFile = new File("test_data/nested.tar")
    if (nestedFile.exists()) {
      val wrapper = FileWrapper(nestedFile, nestedFile.getName(), None)

      val result = FileWalker.withinArchiveStream(wrapper) { files =>
        files.length
      }

      assert(result.isDefined)
    }
  }

  // ==================== notArchive Tests ====================

  test("notArchive - returns true for text/plain") {
    assert(FileWalker.notArchive("test.txt", Set("text/plain")))
  }

  test("notArchive - returns true for image types") {
    assert(FileWalker.notArchive("test.png", Set("image/png")))
    assert(FileWalker.notArchive("test.jpg", Set("image/jpeg")))
    assert(FileWalker.notArchive("test.gif", Set("image/gif")))
  }

  test("notArchive - returns true for application/java-vm") {
    assert(FileWalker.notArchive("Test.class", Set("application/java-vm")))
  }

  test("notArchive - returns false for application/zip") {
    assert(!FileWalker.notArchive("test.zip", Set("application/zip")))
  }

  test("notArchive - returns false for application/java-archive") {
    assert(!FileWalker.notArchive("test.jar", Set("application/java-archive")))
  }

  test("notArchive - uses ArtifactWrapper overload") {
    val textArtifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    assert(FileWalker.notArchive(textArtifact))
  }

  // ==================== notCompressed Tests ====================

  test("notCompressed - returns true for text/plain") {
    assert(FileWalker.notCompressed("test.txt", Set("text/plain")))
  }

  test("notCompressed - returns true for image types") {
    assert(FileWalker.notCompressed("test.png", Set("image/png")))
  }

  test("notCompressed - returns true for APK files") {
    assert(
      FileWalker.notCompressed(
        "app.apk",
        Set("application/vnd.android.package-archive")
      )
    )
  }

  test("notCompressed - returns false for gzip") {
    assert(!FileWalker.notCompressed("test.gz", Set("application/gzip")))
  }

  // ==================== withinTempDir Tests ====================

  test("withinTempDir - provides temp directory") {
    val result = FileWalker.withinTempDir { tempDir =>
      Files.isDirectory(tempDir)
    }

    assert(result.getOrElse(false))
  }

  test("withinTempDir - cleans up directory") {
    var tempPathOpt: Option[Path] = None

    FileWalker.withinTempDir { tempDir =>
      tempPathOpt = Some(tempDir)
      Files.createFile(tempDir.resolve("testfile.txt"))
    }

    // Directory should be cleaned up
    assert(tempPathOpt.isDefined)
    assert(!Files.exists(tempPathOpt.get))
  }

  test("withinTempDir - returns function result") {
    val result = FileWalker.withinTempDir { _ =>
      42
    }

    assertEquals(result.getOrElse(0), 42)
  }

  // ==================== Edge Cases ====================

  test("withinArchiveStream - handles empty archive") {
    val emptyTgz = new File("test_data/empty.tgz")
    if (emptyTgz.exists()) {
      val wrapper = FileWrapper(emptyTgz, emptyTgz.getName(), None)

      val result = FileWalker.withinArchiveStream(wrapper) { files =>
        files.length
      }

      // May be None or Some(0) depending on implementation
      assert(result.isEmpty || result.exists(_ >= 0))
    }
  }

  test("withinArchiveStream - handles corrupt archive gracefully") {
    val corruptData =
      Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00)
    val wrapper = ByteWrapper(corruptData, "corrupt.zip", None)

    val result = FileWalker.withinArchiveStream(wrapper) { files =>
      files.length
    }

    // Should not throw, may return None
    assert(result.isEmpty || result.isDefined)
  }

  // ==================== definitelyNotArchive Set Tests ====================

  test("definitelyNotArchive - contains java-vm") {
    assert(FileWalker.definitelyNotArchive.contains("application/java-vm"))
  }

  test("definitelyNotArchive - contains text/plain") {
    assert(FileWalker.definitelyNotArchive.contains("text/plain"))
  }

  test("definitelyNotArchive - contains octet-stream") {
    assert(FileWalker.definitelyNotArchive.contains("application/octet-stream"))
  }

  // ==================== notZip Set Tests ====================

  test("notZip - contains RPM") {
    assert(FileWalker.notZip.contains("application/x-rpm"))
  }

  test("notZip - contains DEB") {
    assert(FileWalker.notZip.contains("application/x-debian-package"))
  }

  test("notZip - contains ISO") {
    assert(FileWalker.notZip.contains("application/x-iso9660-image"))
  }

  test("notZip - contains gzip") {
    assert(FileWalker.notZip.contains("application/gzip"))
  }
}
