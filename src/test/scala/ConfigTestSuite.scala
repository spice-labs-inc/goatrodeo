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

import io.spicelabs.goatrodeo.GoatRodeo
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.ConfigurationParser
import io.spicelabs.goatrodeo.util.ExpandFiles
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.VectorOfStrings

import java.io.File
import java.nio.file.Files

class ConfigTestSuite extends GoatRodeoFunSuite {

  // ==================== Configuration Defaults Tests ====================

  test("Configuration - has sensible defaults") {
    val config = Configuration()

    assertEquals(config.out, None)
    assert(config.build.isEmpty)
    assertEquals(config.threads, 4)
    assertEquals(config.maxRecords, 50000)
    assertEquals(config.useStaticMetadata, false)
    assertEquals(config.fsFilePaths, false)
  }

  test("Configuration - out can be set") {
    val dir = new File("/tmp/test-out")
    val config = Configuration(out = Some(dir))

    assertEquals(config.out, Some(dir))
  }

  test("Configuration - threads can be set") {
    val config = Configuration(threads = 8)

    assertEquals(config.threads, 8)
  }

  test("GoatRodeoBuilder - withThreads rejects zero") {
    interceptMessage[IllegalArgumentException](
      "requirement failed: threads must be >= 1, got 0"
    ) {
      GoatRodeo.builder().withThreads(0)
    }
  }

  test("GoatRodeoBuilder - withThreads rejects negative") {
    interceptMessage[IllegalArgumentException](
      "requirement failed: threads must be >= 1, got -1"
    ) {
      GoatRodeo.builder().withThreads(-1)
    }
  }

  test("Configuration - tag can be set") {
    val config = Configuration(tag = Some("release-1.0"))

    assertEquals(config.tag, Some("release-1.0"))
  }

  test("Configuration - maxRecords can be set") {
    val config = Configuration(maxRecords = 100000)

    assertEquals(config.maxRecords, 100000)
  }

  test("Configuration - useStaticMetadata can be set") {
    val config = Configuration(useStaticMetadata = true)

    assertEquals(config.useStaticMetadata, true)
  }

  test("Configuration - fsFilePaths can be set") {
    val config = Configuration(fsFilePaths = true)

    assertEquals(config.fsFilePaths, true)
  }

  // ==================== getFileListBuilders Tests ====================

  test("getFileListBuilders - returns empty for empty config") {
    val config = Configuration()
    val builders = config.getFileListBuilders()

    assert(builders.isEmpty)
  }

  test("getFileListBuilders - returns builder for build directory") {
    val tempDir = Files.createTempDirectory("buildtest").toFile()
    try {
      val file1 = new File(tempDir, "file1.txt")
      Helpers.writeOverFile(file1, "content")

      val config = Configuration(build = Vector(tempDir))
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

      val config = Configuration(fileList = Vector(fileListFile))
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

      val config = Configuration(fileList = Vector(fileListFile))
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

  // ==================== Configuration Copy Tests ====================

  test("Configuration.copy - preserves unchanged fields") {
    val original = Configuration(threads = 8, tag = Some("test"))
    val copied = original.copy(threads = 16)

    assertEquals(copied.threads, 16)
    assertEquals(copied.tag, Some("test"))
  }

  test("Configuration - mimeFilter defaults to empty IncludeExclude") {
    val config = Configuration()

    assert(config.mimeFilter.shouldInclude(Set("anything")))
  }

  test("Configuration - exclude patterns can be added") {
    import scala.util.Try
    import java.util.regex.Pattern

    val pattern = ".*\\.html$"
    val config =
      Configuration(exclude = Vector((pattern, Try(Pattern.compile(pattern)))))

    assertEquals(config.exclude.length, 1)
    assertEquals(config.exclude.head._1, pattern)
    assert(config.exclude.head._2.isSuccess)
  }

  test("Configuration - multiple --exclude-pattern flags all apply") {
    // The CLI must accept one --exclude-pattern per pattern. Each occurrence
    // appends to `Configuration.exclude`; a second occurrence must not be
    // rejected as an unknown option (scopt treats a repeated non-unbounded
    // option that way) and must not abort the run.
    val parsed = ConfigurationParser.parse(
      Array(
        "-b",
        "/tmp/exclude-pattern-in",
        "--exclude-pattern",
        "html$",
        "--exclude-pattern",
        "maven-metadata.xml$",
        "--exclude-pattern",
        "reify-state.json$"
      )
    )
    assert(
      parsed.isDefined,
      "multiple --exclude-pattern flags must parse without being rejected"
    )
    val config = parsed.get
    val patterns = config.exclude.map(_._1)
    assertEquals(
      patterns,
      Vector("html$", "maven-metadata.xml$", "reify-state.json$")
    )
    assert(
      config.exclude.forall(_._2.isSuccess),
      "every pattern must compile as a regular expression"
    )
  }

  test("Configuration - multiple -b/--build flags all apply") {
    // Each occurrence of -b/--build must append its directory to
    // `Configuration.build`; a second occurrence must not be rejected.
    val dirA = Files.createTempDirectory("build-a").toFile()
    val dirB = Files.createTempDirectory("build-b").toFile()
    val parsed =
      ConfigurationParser.parse(Array("-b", dirA.getPath, "-b", dirB.getPath))
    assert(parsed.isDefined, "multiple --build flags must parse")
    val config = parsed.get
    assert(config.build.contains(dirA), "first build directory must be kept")
    assert(config.build.contains(dirB), "second build directory must be kept")
  }

  test("Configuration - multiple --ignore flags all apply") {
    // Each occurrence of --ignore must append its file to `Configuration.ignore`.
    val fileA = Files.createTempFile("ignore-a", ".txt").toFile()
    val fileB = Files.createTempFile("ignore-b", ".txt").toFile()
    val parsed = ConfigurationParser.parse(
      Array("--ignore", fileA.getPath, "--ignore", fileB.getPath)
    )
    assert(parsed.isDefined, "multiple --ignore flags must parse")
    val config = parsed.get
    assert(config.ignore.contains(fileA), "first ignore file must be kept")
    assert(config.ignore.contains(fileB), "second ignore file must be kept")
  }

  test("Configuration - multiple --file-list flags all apply") {
    // Each occurrence of --file-list must append its file to
    // `Configuration.fileList`.
    val fileA = Files.createTempFile("file-list-a", ".txt").toFile()
    val fileB = Files.createTempFile("file-list-b", ".txt").toFile()
    val parsed = ConfigurationParser.parse(
      Array("--file-list", fileA.getPath, "--file-list", fileB.getPath)
    )
    assert(parsed.isDefined, "multiple --file-list flags must parse")
    val config = parsed.get
    assert(config.fileList.contains(fileA), "first file-list must be kept")
    assert(config.fileList.contains(fileB), "second file-list must be kept")
  }

  test("Configuration - multiple --mime-filter flags all apply") {
    // Each occurrence of --mime-filter must append its predicate to the
    // IncludeExclude; a second occurrence must not be rejected.
    val parsed = ConfigurationParser.parse(
      Array(
        "-b",
        "/tmp/mime-filter-in",
        "--mime-filter",
        "-application/octet-stream",
        "--mime-filter",
        "-text/x-crypto-shape"
      )
    )
    assert(parsed.isDefined, "multiple --mime-filter flags must parse")
    val config = parsed.get
    assert(
      !config.mimeFilter.shouldInclude(Set("application/octet-stream")),
      "first mime-filter predicate must be active"
    )
    assert(
      !config.mimeFilter.shouldInclude(Set("text/x-crypto-shape")),
      "second mime-filter predicate must be active"
    )
  }

  test("Configuration - multiple --mime-filter-file flags all apply") {
    // Each occurrence of --mime-filter-file must append its lines as
    // predicates; a second occurrence must not be rejected.
    val fileA = Files.createTempFile("mime-filter-a", ".txt").toFile()
    val fileB = Files.createTempFile("mime-filter-b", ".txt").toFile()
    Files.writeString(fileA.toPath(), "-alpha\n")
    Files.writeString(fileB.toPath(), "-beta\n")
    val parsed = ConfigurationParser.parse(
      Array(
        "--mime-filter-file",
        fileA.getPath,
        "--mime-filter-file",
        fileB.getPath
      )
    )
    assert(parsed.isDefined, "multiple --mime-filter-file flags must parse")
    val config = parsed.get
    assert(
      !config.mimeFilter.shouldInclude(Set("alpha")),
      "first mime-filter-file lines must be active"
    )
    assert(
      !config.mimeFilter.shouldInclude(Set("beta")),
      "second mime-filter-file lines must be active"
    )
  }

  test("Configuration - blockList can be set") {
    val blockFile = new File("/tmp/blocklist.txt")
    val config = Configuration(blockList = Some(blockFile))

    assertEquals(config.blockList, Some(blockFile))
  }

  test("Configuration - tempDir can be set") {
    val tempDir = new File("/tmp/custom-temp")
    val config = Configuration(tempDir = Some(tempDir))

    assertEquals(config.tempDir, Some(tempDir))
  }
}
