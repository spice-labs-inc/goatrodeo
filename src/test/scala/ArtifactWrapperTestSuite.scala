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

import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class ArtifactWrapperTestSuite extends munit.FunSuite {

  // ==================== FileWrapper Tests ====================

  test("FileWrapper - path returns correct path") {
    val tempFile = Files.createTempFile("pathtest", ".txt").toFile()
    try {
      val wrapper = FileWrapper(tempFile, "custom/path.txt", None)
      assertEquals(wrapper.path(), "custom/path.txt")
    } finally {
      tempFile.delete()
    }
  }

  test("FileWrapper - size returns correct file size") {
    val tempFile = Files.createTempFile("sizetest", ".txt").toFile()
    try {
      Helpers.writeOverFile(tempFile, "hello world")
      val wrapper = FileWrapper(tempFile, tempFile.getName(), None)
      assertEquals(wrapper.size(), 11L)
    } finally {
      tempFile.delete()
    }
  }

  test("FileWrapper - withStream provides access to content") {
    val tempFile = Files.createTempFile("streamtest", ".txt").toFile()
    try {
      val content = "stream content"
      Helpers.writeOverFile(tempFile, content)
      val wrapper = FileWrapper(tempFile, tempFile.getName(), None)
      val result = wrapper.withStream(Helpers.slurpInputToString(_))
      assertEquals(result, content)
    } finally {
      tempFile.delete()
    }
  }

  test("FileWrapper - isRealFile returns true") {
    val tempFile = Files.createTempFile("realfiletest", ".txt").toFile()
    try {
      val wrapper = FileWrapper(tempFile, tempFile.getName(), None)
      assert(wrapper.isRealFile())
    } finally {
      tempFile.delete()
    }
  }

  test("FileWrapper - throws exception for non-existent file") {
    intercept[Exception] {
      FileWrapper(new File("/nonexistent/file.txt"), "file.txt", None)
    }
  }

  test("FileWrapper - filenameWithNoPath returns just filename") {
    val tempFile = Files.createTempFile("nametest", ".txt").toFile()
    try {
      val wrapper = FileWrapper(tempFile, "path/to/file.txt", None)
      assertEquals(wrapper.filenameWithNoPath, "file.txt")
    } finally {
      tempFile.delete()
    }
  }

  test("FileWrapper - uuid is unique per instance") {
    val tempFile = Files.createTempFile("uuidtest", ".txt").toFile()
    try {
      val wrapper1 = FileWrapper(tempFile, tempFile.getName(), None)
      val wrapper2 = FileWrapper(tempFile, tempFile.getName(), None)
      assertNotEquals(wrapper1.uuid, wrapper2.uuid)
    } finally {
      tempFile.delete()
    }
  }

  test("FileWrapper.fromName - creates wrapper from filename") {
    val tempFile = Files.createTempFile("fromnametest", ".txt").toFile()
    try {
      val wrapper = FileWrapper.fromName(tempFile.getAbsolutePath(), None)
      assertEquals(wrapper.size(), 0L)
    } finally {
      tempFile.delete()
    }
  }

  test("FileWrapper - forceFile returns wrapped file") {
    val tempFile = Files.createTempFile("forcefiletest", ".txt").toFile()
    val tempDir = Files.createTempDirectory("tempdir")
    try {
      val wrapper = FileWrapper(tempFile, tempFile.getName(), None)
      val forcedFile = wrapper.forceFile(tempDir)
      assertEquals(forcedFile, tempFile)
    } finally {
      tempFile.delete()
      Helpers.deleteDirectory(tempDir)
    }
  }

  // ==================== ByteWrapper Tests ====================

  test("ByteWrapper - path returns correct path") {
    val wrapper = ByteWrapper(Array[Byte](1, 2, 3), "test.bin", None)
    assertEquals(wrapper.path(), "test.bin")
  }

  test("ByteWrapper - size returns correct byte count") {
    val data = Array[Byte](1, 2, 3, 4, 5)
    val wrapper = ByteWrapper(data, "test.bin", None)
    assertEquals(wrapper.size(), 5L)
  }

  test("ByteWrapper - withStream provides access to content") {
    val data = "test content".getBytes("UTF-8")
    val wrapper = ByteWrapper(data, "test.txt", None)
    val result = wrapper.withStream(Helpers.slurpInputToString(_))
    assertEquals(result, "test content")
  }

  test("ByteWrapper - isRealFile returns false") {
    val wrapper = ByteWrapper(Array[Byte](1, 2, 3), "test.bin", None)
    assert(!wrapper.isRealFile())
  }

  test("ByteWrapper - finished does nothing") {
    val wrapper = ByteWrapper(Array[Byte](1, 2, 3), "test.bin", None)
    wrapper.finished() // Should not throw
  }

  // ==================== ArtifactWrapper.newWrapper Tests ====================

  test("newWrapper - creates ByteWrapper for small content") {
    val tempDir = Files.createTempDirectory("newwrappertest")
    try {
      val data = "small content".getBytes("UTF-8")
      val input = new ByteArrayInputStream(data)
      val wrapper = ArtifactWrapper.newWrapper(
        "test.txt",
        data.length,
        input,
        None,
        tempDir
      )
      assert(wrapper.isInstanceOf[ByteWrapper])
      assertEquals(wrapper.size(), data.length.toLong)
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  test("newWrapper - creates FileWrapper for large content") {
    val tempDir = Files.createTempDirectory("largewrappertest")
    try {
      // Create content larger than maxInMemorySize (32MB)
      val largeSize = ArtifactWrapper.maxInMemorySize + 1000
      val data = new Array[Byte](largeSize.toInt)
      val input = new ByteArrayInputStream(data)
      val wrapper =
        ArtifactWrapper.newWrapper("large.bin", largeSize, input, None, tempDir)
      assert(wrapper.isInstanceOf[FileWrapper])
      assertEquals(wrapper.size(), largeSize)
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  test("newWrapper - fixes path starting with ./") {
    val tempDir = Files.createTempDirectory("fixpathtest")
    try {
      val data = "test".getBytes("UTF-8")
      val input = new ByteArrayInputStream(data)
      val wrapper = ArtifactWrapper.newWrapper(
        "./path/file.txt",
        data.length,
        input,
        None,
        tempDir
      )
      assertEquals(wrapper.path(), "path/file.txt")
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  test("newWrapper - fixes path starting with /") {
    val tempDir = Files.createTempDirectory("fixpath2test")
    try {
      val data = "test".getBytes("UTF-8")
      val input = new ByteArrayInputStream(data)
      val wrapper = ArtifactWrapper.newWrapper(
        "/path/file.txt",
        data.length,
        input,
        None,
        tempDir
      )
      assertEquals(wrapper.path(), "path/file.txt")
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  test("newWrapper - fixes path starting with ../") {
    val tempDir = Files.createTempDirectory("fixpath3test")
    try {
      val data = "test".getBytes("UTF-8")
      val input = new ByteArrayInputStream(data)
      val wrapper = ArtifactWrapper.newWrapper(
        "../path/file.txt",
        data.length,
        input,
        None,
        tempDir
      )
      assertEquals(wrapper.path(), "path/file.txt")
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  // ==================== MIME Type Detection Tests ====================

  test("mimeTypeFor - detects JAR file") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val metadata = new Metadata()
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, jarFile.getName())
      val input = TikaInputStream.get(jarFile.toPath(), metadata)
      try {
        val mimeType = ArtifactWrapper.mimeTypeFor(input, jarFile.getName())
        assertEquals(mimeType, "application/java-archive")
      } finally {
        input.close()
      }
    }
  }

  test("mimeTypeFor - detects DEB file") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val metadata = new Metadata()
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, debFile.getName())
      val input = TikaInputStream.get(debFile.toPath(), metadata)
      try {
        val mimeType = ArtifactWrapper.mimeTypeFor(input, debFile.getName())
        assertEquals(mimeType, "application/x-debian-package")
      } finally {
        input.close()
      }
    }
  }

  test("mimeTypeFor - detects JSON content in text/plain") {
    val jsonContent = """{"key": "value"}""".getBytes("UTF-8")
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "data.txt")
    val input = TikaInputStream.get(jsonContent, metadata)
    try {
      val mimeType = ArtifactWrapper.mimeTypeFor(input, "data.txt")
      assertEquals(mimeType, "application/json")
    } finally {
      input.close()
    }
  }

  test("mimeTypeFor - detects nupkg as application/zip") {
    val nupkgFile = new File("test_data/newtonsoft.json.13.0.4.nupkg")
    if (nupkgFile.exists()) {
      val metadata = new Metadata()
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, nupkgFile.getName())
      val input = TikaInputStream.get(nupkgFile.toPath(), metadata)
      try {
        val mimeType = ArtifactWrapper.mimeTypeFor(input, nupkgFile.getName())
        assertEquals(mimeType, "application/zip")
      } finally {
        input.close()
      }
    }
  }

  test("FileWrapper - mimeType is computed correctly for JAR") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val wrapper = FileWrapper(jarFile, jarFile.getName(), None)
      assertEquals(wrapper.mimeType, "application/java-archive")
    }
  }

  test("FileWrapper - mimeType is computed correctly for DEB") {
    val debFile = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    if (debFile.exists()) {
      val wrapper = FileWrapper(debFile, debFile.getName(), None)
      assertEquals(wrapper.mimeType, "application/x-debian-package")
    }
  }

  test("ByteWrapper - mimeType is computed for content") {
    val textContent = "Hello, this is plain text content".getBytes("UTF-8")
    val wrapper = ByteWrapper(textContent, "test.txt", None)
    assertEquals(wrapper.mimeType, "text/plain")
  }

  // ==================== requireTempFile Tests ====================

  test("requireTempFile - returns true for .dll files") {
    assert(ArtifactWrapper.requireTempFile("test.dll"))
  }

  test("requireTempFile - returns true for .exe files") {
    assert(ArtifactWrapper.requireTempFile("test.exe"))
  }

  test("requireTempFile - returns false for .jar files") {
    assert(!ArtifactWrapper.requireTempFile("test.jar"))
  }

  test("requireTempFile - returns false for .txt files") {
    assert(!ArtifactWrapper.requireTempFile("test.txt"))
  }

  // ==================== maxInMemorySize Tests ====================

  test("maxInMemorySize - is 32MB") {
    assertEquals(ArtifactWrapper.maxInMemorySize, 32L * 1024L * 1024L)
  }

  // ==================== isNupkg Tests ====================

  test("isNupkg - returns Some(true) for valid nupkg") {
    val nupkgFile = new File("test_data/newtonsoft.json.13.0.4.nupkg")
    if (nupkgFile.exists()) {
      val metadata = new Metadata()
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, nupkgFile.getName())
      val input = TikaInputStream.get(nupkgFile.toPath(), metadata)
      try {
        val result = ArtifactWrapper.isNupkg(
          nupkgFile.getName(),
          "application/x-tika-ooxml",
          input
        )
        assertEquals(result, Some(true))
      } finally {
        input.close()
      }
    }
  }

  test("isNupkg - returns Some(false) for non-nupkg") {
    val jarFile = new File("test_data/log4j-core-2.22.1.jar")
    if (jarFile.exists()) {
      val metadata = new Metadata()
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, jarFile.getName())
      val input = TikaInputStream.get(jarFile.toPath(), metadata)
      try {
        val result = ArtifactWrapper.isNupkg(
          jarFile.getName(),
          "application/java-archive",
          input
        )
        assertEquals(result, Some(false))
      } finally {
        input.close()
      }
    }
  }

  // ==================== Edge Cases ====================

  test("ByteWrapper - handles empty byte array") {
    val wrapper = ByteWrapper(Array[Byte](), "empty.bin", None)
    assertEquals(wrapper.size(), 0L)
  }

  test("FileWrapper - handles empty file") {
    val tempFile = Files.createTempFile("emptyfile", ".txt").toFile()
    try {
      val wrapper = FileWrapper(tempFile, tempFile.getName(), None)
      assertEquals(wrapper.size(), 0L)
    } finally {
      tempFile.delete()
    }
  }

  test("newWrapper - handles zero-length content") {
    val tempDir = Files.createTempDirectory("zerolengthtest")
    try {
      val input = new ByteArrayInputStream(Array[Byte]())
      val wrapper =
        ArtifactWrapper.newWrapper("empty.txt", 0, input, None, tempDir)
      assertEquals(wrapper.size(), 0L)
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }
}
