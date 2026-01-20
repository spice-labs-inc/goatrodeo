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

import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.Config.ExpandFiles
import io.spicelabs.goatrodeo.util.Config.VectorOfStrings
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import java.nio.file.Files

class ConfigTestSuite extends munit.FunSuite {

  // ==================== Config Defaults Tests ====================

  test("Config - has sensible defaults") {
    val config = Config()

    assertEquals(config.out, None)
    assert(config.build.isEmpty)
    assertEquals(config.threads, 4)
    assertEquals(config.maxRecords, 50000)
    assertEquals(config.useStaticMetadata, false)
    assertEquals(config.fsFilePaths, false)
  }

  test("Config - out can be set") {
    val dir = new File("/tmp/test-out")
    val config = Config(out = Some(dir))

    assertEquals(config.out, Some(dir))
  }

  test("Config - threads can be set") {
    val config = Config(threads = 8)

    assertEquals(config.threads, 8)
  }

  test("Config - tag can be set") {
    val config = Config(tag = Some("release-1.0"))

    assertEquals(config.tag, Some("release-1.0"))
  }

  test("Config - maxRecords can be set") {
    val config = Config(maxRecords = 100000)

    assertEquals(config.maxRecords, 100000)
  }

  test("Config - useStaticMetadata can be set") {
    val config = Config(useStaticMetadata = true)

    assertEquals(config.useStaticMetadata, true)
  }

  test("Config - fsFilePaths can be set") {
    val config = Config(fsFilePaths = true)

    assertEquals(config.fsFilePaths, true)
  }

  // ==================== getFileListBuilders Tests ====================

  test("getFileListBuilders - returns empty for empty config") {
    val config = Config()
    val builders = config.getFileListBuilders()

    assert(builders.isEmpty)
  }

  test("getFileListBuilders - returns builder for build directory") {
    val tempDir = Files.createTempDirectory("buildtest").toFile()
    try {
      val file1 = new File(tempDir, "file1.txt")
      Helpers.writeOverFile(file1, "content")

      val config = Config(build = Vector(tempDir))
      val builders = config.getFileListBuilders()

      assertEquals(builders.length, 1)
      assertEquals(builders.head._1, tempDir)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("getFileListBuilders - fileList reads file names from file") {
    val tempDir = Files.createTempDirectory("filelisttest").toFile()
    try {
      // Create some test files
      val testFile1 = new File(tempDir, "testfile1.txt")
      val testFile2 = new File(tempDir, "testfile2.txt")
      Helpers.writeOverFile(testFile1, "content1")
      Helpers.writeOverFile(testFile2, "content2")

      // Create a file list file
      val fileListFile = new File(tempDir, "filelist.txt")
      Helpers.writeOverFile(
        fileListFile,
        s"${testFile1.getAbsolutePath()}\n${testFile2.getAbsolutePath()}"
      )

      val config = Config(fileList = Vector(fileListFile))
      val builders = config.getFileListBuilders()

      assertEquals(builders.length, 1)
      val files = builders.head._2()
      assertEquals(files.length, 2)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("getFileListBuilders - fileList filters non-existent files") {
    val tempDir = Files.createTempDirectory("filelistfilter").toFile()
    try {
      val testFile = new File(tempDir, "exists.txt")
      Helpers.writeOverFile(testFile, "content")

      val fileListFile = new File(tempDir, "filelist.txt")
      Helpers.writeOverFile(
        fileListFile,
        s"${testFile.getAbsolutePath()}\n/nonexistent/file.txt"
      )

      val config = Config(fileList = Vector(fileListFile))
      val builders = config.getFileListBuilders()

      val files = builders.head._2()
      assertEquals(files.length, 1)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  // ==================== ExpandFiles Tests ====================

  test("ExpandFiles.fixTilde - expands tilde in path") {
    val homeDir = System.getProperty("user.home")
    val file = new File(s"~${File.separator}test.txt")
    val result = ExpandFiles.fixTilde(file)

    assertEquals(result.getPath(), s"${homeDir}${File.separator}test.txt")
  }

  test("ExpandFiles.fixTilde - leaves non-tilde paths unchanged") {
    val file = new File("/absolute/path/test.txt")
    val result = ExpandFiles.fixTilde(file)

    assertEquals(result, file)
  }

  test("ExpandFiles.fixTilde - leaves relative paths unchanged") {
    val file = new File("relative/path/test.txt")
    val result = ExpandFiles.fixTilde(file)

    assertEquals(result, file)
  }

  test("ExpandFiles.apply - returns file for non-existent path") {
    val file = new File("/nonexistent/path/test.txt")
    val result = ExpandFiles(file)

    assertEquals(result.length, 1)
    assertEquals(result.head, file)
  }

  test("ExpandFiles.apply - returns pattern file for non-existent wildcard") {
    val tempDir = Files.createTempDirectory("expandtest").toFile()
    try {
      Helpers.writeOverFile(new File(tempDir, "test1.txt"), "content")
      Helpers.writeOverFile(new File(tempDir, "test2.txt"), "content")

      // ExpandFiles returns the input file if it doesn't exist
      // Wildcard patterns that don't exist as actual files are returned as-is
      val pattern = new File(tempDir, "test*.txt")
      val result = ExpandFiles(pattern)

      // Since "test*.txt" doesn't exist as an actual file, it's returned as-is
      assertEquals(result.length, 1)
      assertEquals(result.head, pattern)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("ExpandFiles.apply - returns single file for exact match") {
    val tempDir = Files.createTempDirectory("expandexact").toFile()
    try {
      val testFile = new File(tempDir, "exact.txt")
      Helpers.writeOverFile(testFile, "content")

      val result = ExpandFiles(testFile)

      assertEquals(result.length, 1)
      assertEquals(result.head.getName(), "exact.txt")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  // ==================== VectorOfStrings Tests ====================

  test("VectorOfStrings - reads lines from file") {
    val tempDir = Files.createTempDirectory("vectorstrings").toFile()
    try {
      val testFile = new File(tempDir, "lines.txt")
      Helpers.writeOverFile(testFile, "line1\nline2\nline3")

      val result = VectorOfStrings(testFile)

      assertEquals(result.length, 3)
      assertEquals(result(0), "line1")
      assertEquals(result(1), "line2")
      assertEquals(result(2), "line3")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("VectorOfStrings - handles empty file") {
    val tempDir = Files.createTempDirectory("vectorempty").toFile()
    try {
      val testFile = new File(tempDir, "empty.txt")
      Helpers.writeOverFile(testFile, "")

      val result = VectorOfStrings(testFile)

      assert(result.isEmpty)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("VectorOfStrings - strips newlines from lines") {
    val tempDir = Files.createTempDirectory("vectornewlines").toFile()
    try {
      val testFile = new File(tempDir, "newlines.txt")
      Helpers.writeOverFile(testFile, "line1\nline2\n")

      val result = VectorOfStrings(testFile)

      assertEquals(result.length, 2)
      assert(!result(0).contains("\n"))
      assert(!result(1).contains("\n"))
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("VectorOfStrings - accepts string path") {
    val tempDir = Files.createTempDirectory("vectorstring").toFile()
    try {
      val testFile = new File(tempDir, "test.txt")
      Helpers.writeOverFile(testFile, "content")

      val result = VectorOfStrings(testFile.getAbsolutePath())

      assertEquals(result.length, 1)
      assertEquals(result(0), "content")
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  // ==================== Config Copy Tests ====================

  test("Config.copy - preserves unchanged fields") {
    val original = Config(threads = 8, tag = Some("test"))
    val copied = original.copy(threads = 16)

    assertEquals(copied.threads, 16)
    assertEquals(copied.tag, Some("test"))
  }

  test("Config - mimeFilter defaults to empty IncludeExclude") {
    val config = Config()

    assert(config.mimeFilter.shouldInclude("anything"))
  }

  test("Config - filenameFilter defaults to empty IncludeExclude") {
    val config = Config()

    assert(config.filenameFilter.shouldInclude("anyfile.txt"))
  }

  test("Config - exclude patterns can be added") {
    import scala.util.Try
    import java.util.regex.Pattern

    val pattern = ".*\\.html$"
    val config =
      Config(exclude = Vector((pattern, Try(Pattern.compile(pattern)))))

    assertEquals(config.exclude.length, 1)
    assertEquals(config.exclude.head._1, pattern)
    assert(config.exclude.head._2.isSuccess)
  }

  test("Config - blockList can be set") {
    val blockFile = new File("/tmp/blocklist.txt")
    val config = Config(blockList = Some(blockFile))

    assertEquals(config.blockList, Some(blockFile))
  }

  test("Config - tempDir can be set") {
    val tempDir = new File("/tmp/custom-temp")
    val config = Config(tempDir = Some(tempDir))

    assertEquals(config.tempDir, Some(tempDir))
  }
}
