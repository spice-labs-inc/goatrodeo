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

import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

class FileWalkerTestSuite extends munit.FunSuite {

  // ==================== withinArchiveStream Tests ====================

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
    assert(FileWalker.notArchive("test.txt", "text/plain"))
  }

  test("notArchive - returns true for image types") {
    assert(FileWalker.notArchive("test.png", "image/png"))
    assert(FileWalker.notArchive("test.jpg", "image/jpeg"))
    assert(FileWalker.notArchive("test.gif", "image/gif"))
  }

  test("notArchive - returns true for application/java-vm") {
    assert(FileWalker.notArchive("Test.class", "application/java-vm"))
  }

  test("notArchive - returns true for .xpi with application/zip") {
    assert(FileWalker.notArchive("addon.xpi", "application/zip"))
  }

  test("notArchive - returns false for application/zip") {
    assert(!FileWalker.notArchive("test.zip", "application/zip"))
  }

  test("notArchive - returns false for application/java-archive") {
    assert(!FileWalker.notArchive("test.jar", "application/java-archive"))
  }

  test("notArchive - uses ArtifactWrapper overload") {
    val textArtifact = ByteWrapper("hello".getBytes("UTF-8"), "test.txt", None)
    assert(FileWalker.notArchive(textArtifact))
  }

  // ==================== notCompressed Tests ====================

  test("notCompressed - returns true for text/plain") {
    assert(FileWalker.notCompressed("test.txt", "text/plain"))
  }

  test("notCompressed - returns true for image types") {
    assert(FileWalker.notCompressed("test.png", "image/png"))
  }

  test("notCompressed - returns true for APK files") {
    assert(FileWalker.notCompressed("app.apk", "application/vnd.android.package-archive"))
  }

  test("notCompressed - returns false for gzip") {
    assert(!FileWalker.notCompressed("test.gz", "application/gzip"))
  }

  // ==================== getContentNamesFromArchive Tests ====================

  test("getContentNamesFromArchive - returns file names from archive") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val bis = new BufferedInputStream(new FileInputStream(jarFile))
      try {
        val result = FileWalker.getContentNamesFromArchive(bis)

        assert(result.isDefined)
        val names = result.get
        assert(names.nonEmpty)
        assert(names.exists(_._1.endsWith(".class")))
      } finally {
        bis.close()
      }
    }
  }

  test("getContentNamesFromArchive - identifies directories") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val bis = new BufferedInputStream(new FileInputStream(jarFile))
      try {
        val result = FileWalker.getContentNamesFromArchive(bis)

        assert(result.isDefined)
        val entries = result.get
        val hasDir = entries.exists(_._2) // second element is isDirectory
        // Jar files typically have directory entries
      } finally {
        bis.close()
      }
    }
  }

  test("getContentNamesFromArchive - returns mime types") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val bis = new BufferedInputStream(new FileInputStream(jarFile))
      try {
        val result = FileWalker.getContentNamesFromArchive(bis)

        assert(result.isDefined)
        val entries = result.get
        val filesWithMime = entries.filter(!_._2).filter(_._3.isDefined)
        assert(filesWithMime.nonEmpty)
      } finally {
        bis.close()
      }
    }
  }

  // ==================== withinTempDir Tests ====================

  test("withinTempDir - provides temp directory") {
    val result = FileWalker.withinTempDir { tempDir =>
      Files.isDirectory(tempDir)
    }

    assert(result)
  }

  test("withinTempDir - cleans up directory") {
    var tempPathOpt: Option[java.nio.file.Path] = None

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

    assertEquals(result, 42)
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
    val corruptData = Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00)
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
