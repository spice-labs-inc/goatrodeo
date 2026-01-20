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

import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.Helpers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class HelpersTestSuite extends munit.FunSuite {

  // ==================== Hash Functions Tests ====================

  test("computeMD5 - computes correct MD5 for simple string") {
    val input = "hello world"
    val result = Helpers.computeMD5(input)
    assertEquals(result.length, 16, "MD5 should be 16 bytes")
    assertEquals(
      Helpers.toHex(result),
      "5eb63bbbe01eeed093cb22bb8f5acdc3"
    )
  }

  test("computeMD5 - computes correct MD5 for empty string") {
    val input = ""
    val result = Helpers.computeMD5(input)
    assertEquals(
      Helpers.toHex(result),
      "d41d8cd98f00b204e9800998ecf8427e"
    )
  }

  test("computeMD5 - computes correct MD5 from InputStream") {
    val input = new ByteArrayInputStream("test".getBytes("UTF-8"))
    val result = Helpers.computeMD5(input)
    assertEquals(result.length, 16)
  }

  test("computeMD5 - computes correct MD5 from File") {
    val tempFile = Files.createTempFile("md5test", ".txt").toFile()
    try {
      Helpers.writeOverFile(tempFile, "test content")
      val result = Helpers.computeMD5(tempFile)
      assertEquals(result.length, 16)
    } finally {
      tempFile.delete()
    }
  }

  test("md5hashHex - returns hex string for input") {
    val result = Helpers.md5hashHex("hello")
    assertEquals(result.length, 32, "MD5 hex should be 32 characters")
    assertEquals(result, "5d41402abc4b2a76b9719d911017c592")
  }

  test("computeSHA1 - computes correct SHA1 for string") {
    val result = Helpers.computeSHA1("hello")
    assertEquals(result.length, 20, "SHA1 should be 20 bytes")
    assertEquals(
      Helpers.toHex(result),
      "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
    )
  }

  test("computeSHA1 - computes correct SHA1 from InputStream") {
    val input = new ByteArrayInputStream("test".getBytes("UTF-8"))
    val result = Helpers.computeSHA1(input)
    assertEquals(result.length, 20)
  }

  test("computeSHA1 - computes correct SHA1 from File") {
    val tempFile = Files.createTempFile("sha1test", ".txt").toFile()
    try {
      Helpers.writeOverFile(tempFile, "test content")
      val result = Helpers.computeSHA1(tempFile)
      assertEquals(result.length, 20)
    } finally {
      tempFile.delete()
    }
  }

  test("computeSHA256 - computes correct SHA256 for string") {
    val result = Helpers.computeSHA256("hello")
    assertEquals(result.length, 32, "SHA256 should be 32 bytes")
    assertEquals(
      Helpers.toHex(result),
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    )
  }

  test("computeSHA256 - computes correct SHA256 from InputStream") {
    val input = new ByteArrayInputStream("test".getBytes("UTF-8"))
    val result = Helpers.computeSHA256(input)
    assertEquals(result.length, 32)
  }

  test("computeSHA256 - computes correct SHA256 from File") {
    val tempFile = Files.createTempFile("sha256test", ".txt").toFile()
    try {
      Helpers.writeOverFile(tempFile, "test content")
      val result = Helpers.computeSHA256(tempFile)
      assertEquals(result.length, 32)
    } finally {
      tempFile.delete()
    }
  }

  test("computeSHA512 - computes correct SHA512 for string") {
    val result = Helpers.computeSHA512("hello")
    assertEquals(result.length, 64, "SHA512 should be 64 bytes")
  }

  test("computeSHA512 - computes correct SHA512 from InputStream") {
    val input = new ByteArrayInputStream("test".getBytes("UTF-8"))
    val result = Helpers.computeSHA512(input)
    assertEquals(result.length, 64)
  }

  test("computeSHA512 - computes correct SHA512 from File") {
    val tempFile = Files.createTempFile("sha512test", ".txt").toFile()
    try {
      Helpers.writeOverFile(tempFile, "test content")
      val result = Helpers.computeSHA512(tempFile)
      assertEquals(result.length, 64)
    } finally {
      tempFile.delete()
    }
  }

  // ==================== Byte Conversion Tests ====================

  test("toHex - converts byte array to hex string") {
    val bytes = Array[Byte](0x00, 0x0f, 0x10, 0xff.toByte)
    assertEquals(Helpers.toHex(bytes), "000f10ff")
  }

  test("toHex - converts empty byte array") {
    val bytes = Array[Byte]()
    assertEquals(Helpers.toHex(bytes), "")
  }

  test("toHex - converts Long to hex string") {
    assertEquals(Helpers.toHex(0x1L), "0000000000000001")
    assertEquals(Helpers.toHex(0xabcdef0123456789L), "abcdef0123456789")
    assertEquals(Helpers.toHex(0L), "0000000000000000")
  }

  test("byteArrayToLong63Bits - converts bytes to long") {
    val bytes = Array[Byte](
      0x18,
      0x12,
      0x10,
      0xf8.toByte,
      0xf9.toByte,
      0xc7.toByte,
      0x79,
      0xc2.toByte
    )
    val result = Helpers.byteArrayToLong63Bits(bytes)
    assertEquals(Helpers.toHex(result), "181210f8f9c779c2")
  }

  test("byteArrayToLong63Bits - clears high bit") {
    val bytes = Array[Byte](
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte
    )
    val result = Helpers.byteArrayToLong63Bits(bytes)
    assert(result >= 0, "Result should be non-negative")
  }

  test("byteArrayToLong63Bits - handles short arrays") {
    val bytes = Array[Byte](0x12, 0x34)
    val result = Helpers.byteArrayToLong63Bits(bytes)
    assert(result >= 0)
  }

  test("charToBin - converts hex chars to int") {
    assertEquals(Helpers.charToBin('0'), 0)
    assertEquals(Helpers.charToBin('9'), 9)
    assertEquals(Helpers.charToBin('a'), 10)
    assertEquals(Helpers.charToBin('f'), 15)
    assertEquals(Helpers.charToBin('A'), 10)
    assertEquals(Helpers.charToBin('F'), 15)
    assertEquals(Helpers.charToBin('g'), 0) // invalid char returns 0
  }

  test("hexChar - converts int to hex char") {
    assertEquals(Helpers.hexChar(0), '0')
    assertEquals(Helpers.hexChar(9), '9')
    assertEquals(Helpers.hexChar(10), 'a')
    assertEquals(Helpers.hexChar(15), 'f')
  }

  test("convertHexToBinaryAndAppendToStream - converts hex string") {
    val out = new ByteArrayOutputStream()
    Helpers.convertHexToBinaryAndAppendToStream("00ff10", out)
    val result = out.toByteArray
    assertEquals(result.length, 3)
    assertEquals(result(0), 0x00.toByte)
    assertEquals(result(1), 0xff.toByte)
    assertEquals(result(2), 0x10.toByte)
  }

  test("convertHexToBinaryAndAppendToStream - handles prefix with colons") {
    val out = new ByteArrayOutputStream()
    Helpers.convertHexToBinaryAndAppendToStream("gitoid:blob:sha256:00ff", out)
    val result = out.toByteArray
    assertEquals(result.length, 2)
    assertEquals(result(0), 0x00.toByte)
    assertEquals(result(1), 0xff.toByte)
  }

  // ==================== I/O Utilities Tests ====================

  test("slurpInput - reads all bytes from InputStream") {
    val data = "test data content"
    val input = new ByteArrayInputStream(data.getBytes("UTF-8"))
    val result = Helpers.slurpInput(input)
    assertEquals(new String(result, "UTF-8"), data)
  }

  test("slurpInput - handles empty InputStream") {
    val input = new ByteArrayInputStream(Array[Byte]())
    val result = Helpers.slurpInput(input)
    assertEquals(result.length, 0)
  }

  test("slurpInput - reads from File") {
    val tempFile = Files.createTempFile("slurptest", ".txt").toFile()
    try {
      val data = "file content here"
      Helpers.writeOverFile(tempFile, data)
      val result = Helpers.slurpInput(tempFile)
      assertEquals(new String(result, "UTF-8"), data)
    } finally {
      tempFile.delete()
    }
  }

  test("slurpInputToString - returns string from InputStream") {
    val data = "string content"
    val input = new ByteArrayInputStream(data.getBytes("UTF-8"))
    val result = Helpers.slurpInputToString(input)
    assertEquals(result, data)
  }

  test("slurpInputNoClose - reads without closing stream") {
    val data = "test"
    val input = new ByteArrayInputStream(data.getBytes("UTF-8"))
    val result = Helpers.slurpInputNoClose(input)
    assertEquals(new String(result, "UTF-8"), data)
  }

  test("slurpBlock - reads first 4K of data") {
    val data = "a" * 5000
    val input = new ByteArrayInputStream(data.getBytes("UTF-8"))
    val result = Helpers.slurpBlock(input)
    assertEquals(result.length, 4096)
  }

  test("slurpBlock - handles small files") {
    val data = "small"
    val input = new ByteArrayInputStream(data.getBytes("UTF-8"))
    val result = Helpers.slurpBlock(input)
    assertEquals(result.length, 5)
  }

  test("copy - copies bytes between streams") {
    val data = "copy this data"
    val input = new ByteArrayInputStream(data.getBytes("UTF-8"))
    val output = new ByteArrayOutputStream()
    val count = Helpers.copy(input, output)
    assertEquals(count, data.length.toLong)
    assertEquals(new String(output.toByteArray, "UTF-8"), data)
  }

  test("copy - handles empty stream") {
    val input = new ByteArrayInputStream(Array[Byte]())
    val output = new ByteArrayOutputStream()
    val count = Helpers.copy(input, output)
    assertEquals(count, 0L)
  }

  test("stringToInputStream - creates InputStream from String") {
    val str = "test string"
    val input = Helpers.stringToInputStream(str)
    val result = Helpers.slurpInput(input)
    assertEquals(new String(result, "UTF-8"), str)
  }

  // ==================== File Utilities Tests ====================

  test("findFiles - finds files in directory") {
    val tempDir = Files.createTempDirectory("findtest").toFile()
    try {
      val file1 = new File(tempDir, "file1.txt")
      val file2 = new File(tempDir, "file2.txt")
      file1.createNewFile()
      file2.createNewFile()
      val result = Helpers.findFiles(tempDir)
      assertEquals(result.length, 2)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("findFiles - returns empty for empty directory") {
    val tempDir = Files.createTempDirectory("emptytest").toFile()
    try {
      val result = Helpers.findFiles(tempDir)
      assertEquals(result.length, 0)
    } finally {
      tempDir.delete()
    }
  }

  test("findFiles - finds files in nested directories") {
    val tempDir = Files.createTempDirectory("nestedtest").toFile()
    try {
      val subDir = new File(tempDir, "subdir")
      subDir.mkdir()
      val file1 = new File(tempDir, "file1.txt")
      val file2 = new File(subDir, "file2.txt")
      file1.createNewFile()
      file2.createNewFile()
      val result = Helpers.findFiles(tempDir)
      assertEquals(result.length, 2)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("findFiles - ignores hidden files") {
    val tempDir = Files.createTempDirectory("hiddentest").toFile()
    try {
      val file1 = new File(tempDir, "visible.txt")
      val file2 = new File(tempDir, ".hidden")
      file1.createNewFile()
      file2.createNewFile()
      val result = Helpers.findFiles(tempDir)
      assertEquals(result.length, 1)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("writeOverFile - writes string to file") {
    val tempFile = Files.createTempFile("writetest", ".txt").toFile()
    try {
      Helpers.writeOverFile(tempFile, "new content")
      val result = new String(Files.readAllBytes(tempFile.toPath()), "UTF-8")
      assertEquals(result, "new content")
    } finally {
      tempFile.delete()
    }
  }

  test("writeOverFile - writes byte array to file") {
    val tempFile = Files.createTempFile("writetest2", ".txt").toFile()
    try {
      val data = Array[Byte](1, 2, 3, 4, 5)
      Helpers.writeOverFile(tempFile, data)
      val result = Files.readAllBytes(tempFile.toPath())
      assertEquals(result.toSeq, data.toSeq)
    } finally {
      tempFile.delete()
    }
  }

  test("writeOverFile - overwrites existing content") {
    val tempFile = Files.createTempFile("overwritetest", ".txt").toFile()
    try {
      Helpers.writeOverFile(tempFile, "first")
      Helpers.writeOverFile(tempFile, "second")
      val result = new String(Files.readAllBytes(tempFile.toPath()), "UTF-8")
      assertEquals(result, "second")
    } finally {
      tempFile.delete()
    }
  }

  test("deleteDirectory - deletes directory recursively") {
    val tempDir = Files.createTempDirectory("deletetest").toFile()
    val subDir = new File(tempDir, "subdir")
    subDir.mkdir()
    val file1 = new File(tempDir, "file1.txt")
    val file2 = new File(subDir, "file2.txt")
    file1.createNewFile()
    file2.createNewFile()

    Helpers.deleteDirectory(tempDir.toPath())

    assert(!tempDir.exists(), "Directory should be deleted")
  }

  test("streamToFile - writes stream to file") {
    val tempFile = Files.createTempFile("streamtofile", ".txt").toFile()
    try {
      val data = "stream content"
      val input = new ByteArrayInputStream(data.getBytes("UTF-8"))
      Helpers.streamToFile(input, close_? = true, tempFile)
      val result = new String(Files.readAllBytes(tempFile.toPath()), "UTF-8")
      assertEquals(result, data)
    } finally {
      tempFile.delete()
    }
  }

  test("tempFileFromStream - creates temp file from stream") {
    val tempDir = Files.createTempDirectory("tempfiletest")
    try {
      val data = "temp file content"
      val input = new ByteArrayInputStream(data.getBytes("UTF-8"))
      val tempFile = Helpers.tempFileFromStream(input, close_? = true, tempDir)
      try {
        val result = new String(Files.readAllBytes(tempFile.toPath()), "UTF-8")
        assertEquals(result, data)
      } finally {
        tempFile.delete()
      }
    } finally {
      Helpers.deleteDirectory(tempDir)
    }
  }

  // ==================== Read/Write Short/Int/Long Tests ====================

  test("readShort and writeShort - round trip") {
    val tempFile = Files.createTempFile("shorttest", ".bin").toFile()
    try {
      val writer = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE)
      Helpers.writeShort(writer, 12345)
      writer.close()

      val reader = FileChannel.open(tempFile.toPath(), StandardOpenOption.READ)
      val result = Helpers.readShort(reader)
      reader.close()

      assertEquals(result, 12345)
    } finally {
      tempFile.delete()
    }
  }

  test("readShort - handles max value") {
    val tempFile = Files.createTempFile("shortmaxtest", ".bin").toFile()
    try {
      val writer = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE)
      Helpers.writeShort(writer, 0xffff)
      writer.close()

      val reader = FileChannel.open(tempFile.toPath(), StandardOpenOption.READ)
      val result = Helpers.readShort(reader)
      reader.close()

      assertEquals(result, 0xffff)
    } finally {
      tempFile.delete()
    }
  }

  test("readInt and writeInt - round trip") {
    val tempFile = Files.createTempFile("inttest", ".bin").toFile()
    try {
      val writer = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE)
      Helpers.writeInt(writer, 123456789)
      writer.close()

      val reader = FileChannel.open(tempFile.toPath(), StandardOpenOption.READ)
      val result = Helpers.readInt(reader)
      reader.close()

      assertEquals(result, 123456789)
    } finally {
      tempFile.delete()
    }
  }

  test("readInt - handles negative values") {
    val tempFile = Files.createTempFile("intnegtest", ".bin").toFile()
    try {
      val writer = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE)
      Helpers.writeInt(writer, -1)
      writer.close()

      val reader = FileChannel.open(tempFile.toPath(), StandardOpenOption.READ)
      val result = Helpers.readInt(reader)
      reader.close()

      assertEquals(result, -1)
    } finally {
      tempFile.delete()
    }
  }

  test("readLong and writeLong - round trip") {
    val tempFile = Files.createTempFile("longtest", ".bin").toFile()
    try {
      val writer = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE)
      Helpers.writeLong(writer, 1234567890123456789L)
      writer.close()

      val reader = FileChannel.open(tempFile.toPath(), StandardOpenOption.READ)
      val result = Helpers.readLong(reader)
      reader.close()

      assertEquals(result, 1234567890123456789L)
    } finally {
      tempFile.delete()
    }
  }

  test("readLong - handles max value") {
    val tempFile = Files.createTempFile("longmaxtest", ".bin").toFile()
    try {
      val writer = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE)
      Helpers.writeLong(writer, Long.MaxValue)
      writer.close()

      val reader = FileChannel.open(tempFile.toPath(), StandardOpenOption.READ)
      val result = Helpers.readLong(reader)
      reader.close()

      assertEquals(result, Long.MaxValue)
    } finally {
      tempFile.delete()
    }
  }

  // ==================== Manifest Parsing Tests ====================

  test("treeInfoFromManifest - parses simple manifest") {
    val manifest = """Manifest-Version: 1.0
Created-By: Test
"""
    val result = Helpers.treeInfoFromManifest(manifest)
    assert(result.contains("manifest-version"))
    assert(result.contains("created-by"))
    assertEquals(result("manifest-version").head.value, "1.0")
  }

  test("treeInfoFromManifest - includes raw manifest") {
    val manifest = """Manifest-Version: 1.0
"""
    val result = Helpers.treeInfoFromManifest(manifest)
    assert(result.contains("manifest"))
    assertEquals(result("manifest").head.mimeType, Some("text/maven-manifest"))
  }

  test("treeInfoFromManifest - handles multiline values") {
    val manifest = """Manifest-Version: 1.0
Long-Value: This is a very long value that continues
 on the next line
"""
    val result = Helpers.treeInfoFromManifest(manifest)
    assert(result.contains("long-value"))
  }

  test("treeInfoFromManifest - handles empty manifest") {
    val manifest = ""
    val result = Helpers.treeInfoFromManifest(manifest)
    assert(result.contains("manifest"))
  }

  test("mergeTreeMaps - merges disjoint maps") {
    val a: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap("key1" -> TreeSet(StringOrPair("val1")))
    val b: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap("key2" -> TreeSet(StringOrPair("val2")))
    val result = Helpers.mergeTreeMaps(a, b)
    assertEquals(result.size, 2)
    assert(result.contains("key1"))
    assert(result.contains("key2"))
  }

  test("mergeTreeMaps - merges overlapping keys") {
    val a: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap("key" -> TreeSet(StringOrPair("val1")))
    val b: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap("key" -> TreeSet(StringOrPair("val2")))
    val result = Helpers.mergeTreeMaps(a, b)
    assertEquals(result.size, 1)
    assertEquals(result("key").size, 2)
  }

  test("mergeTreeMaps - handles empty maps") {
    val a: TreeMap[String, TreeSet[StringOrPair]] = TreeMap()
    val b: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap("key" -> TreeSet(StringOrPair("val")))
    val result = Helpers.mergeTreeMaps(a, b)
    assertEquals(result.size, 1)
  }

  // ==================== Random Number Tests ====================

  test("randomInt - returns non-negative value") {
    for (_ <- 1 to 10) {
      val result = Helpers.randomInt()
      assert(result >= 0, "randomInt should return non-negative")
    }
  }

  test("randomLong - returns non-negative value") {
    for (_ <- 1 to 10) {
      val result = Helpers.randomLong()
      assert(result >= 0, "randomLong should return non-negative")
    }
  }

  test("randomBytes - returns array of requested length") {
    val result = Helpers.randomBytes(32)
    assertEquals(result.length, 32)
  }

  test("randomBytes - returns different values each call") {
    val result1 = Helpers.randomBytes(16)
    val result2 = Helpers.randomBytes(16)
    assert(
      result1.toSeq != result2.toSeq,
      "Should return different random bytes"
    )
  }

  // ==================== Utility Tests ====================

  test("currentDate8601 - returns valid ISO 8601 format") {
    val result = Helpers.currentDate8601()
    assert(result.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"))
  }

  test("formatInt - formats integer with locale") {
    val result = Helpers.formatInt(1000)
    assert(result.length > 0)
  }

  test("formatInt - formats long with locale") {
    val result = Helpers.formatInt(1000000L)
    assert(result.length > 0)
  }

  test("findAllFiles - finds files recursively") {
    val tempDir = Files.createTempDirectory("findalltest").toFile()
    try {
      val subDir = new File(tempDir, "sub")
      subDir.mkdir()
      val file1 = new File(tempDir, "f1.txt")
      val file2 = new File(subDir, "f2.txt")
      file1.createNewFile()
      file2.createNewFile()

      val result = Helpers.findAllFiles(tempDir)
      assertEquals(result.length, 2)
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("findAllFiles - returns empty for empty directory") {
    val tempDir = Files.createTempDirectory("findallempty").toFile()
    try {
      val result = Helpers.findAllFiles(tempDir)
      assertEquals(result.length, 0)
    } finally {
      tempDir.delete()
    }
  }

  test("javaClassMimeTypes - contains expected mime types") {
    assert(Helpers.javaClassMimeTypes.contains("application/java-vm"))
    assert(Helpers.javaClassMimeTypes.contains("application/x-java-class"))
  }
}
