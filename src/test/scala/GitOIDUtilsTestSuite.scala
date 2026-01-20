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
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.GitOIDUtils
import io.spicelabs.goatrodeo.util.GitOIDUtils.HashType
import io.spicelabs.goatrodeo.util.GitOIDUtils.ObjectType
import io.spicelabs.goatrodeo.util.Helpers

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class GitOIDUtilsTestSuite extends munit.FunSuite {

  // ==================== urlToFileName Tests ====================

  test("urlToFileName - parses full gitoid URL") {
    val uri = "gitoid:blob:sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a"
    val (first, second, rest) = GitOIDUtils.urlToFileName(uri)
    assertEquals(first, "880")
    assertEquals(second, "485")
    assertEquals(rest, "f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a")
  }

  test("urlToFileName - parses partial URL without gitoid prefix") {
    val uri = "blob:sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a"
    val (first, second, rest) = GitOIDUtils.urlToFileName(uri)
    assertEquals(first, "880")
    assertEquals(second, "485")
  }

  test("urlToFileName - parses URL with sha256 prefix only") {
    val uri = ":sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a"
    val (first, second, rest) = GitOIDUtils.urlToFileName(uri)
    assertEquals(first, "880")
    assertEquals(second, "485")
  }

  test("urlToFileName - parses bare hash") {
    val uri = "880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a"
    val (first, second, rest) = GitOIDUtils.urlToFileName(uri)
    assertEquals(first, "880")
    assertEquals(second, "485")
    assertEquals(rest, "f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a")
  }

  // ==================== ObjectType Tests ====================

  test("ObjectType.gitoidName - returns blob for Blob") {
    assertEquals(ObjectType.Blob.gitoidName(), "blob")
  }

  test("ObjectType.gitoidName - returns tree for Tree") {
    assertEquals(ObjectType.Tree.gitoidName(), "tree")
  }

  test("ObjectType.gitoidName - returns commit for Commit") {
    assertEquals(ObjectType.Commit.gitoidName(), "commit")
  }

  test("ObjectType.gitoidName - returns tag for Tag") {
    assertEquals(ObjectType.Tag.gitoidName(), "tag")
  }

  // ==================== HashType Tests ====================

  test("HashType.hashTypeName - returns sha1 for SHA1") {
    assertEquals(HashType.SHA1.hashTypeName(), "sha1")
  }

  test("HashType.hashTypeName - returns sha256 for SHA256") {
    assertEquals(HashType.SHA256.hashTypeName(), "sha256")
  }

  test("HashType.getDigest - returns SHA1 digest") {
    val digest = HashType.SHA1.getDigest()
    assertEquals(digest.getAlgorithm(), "SHA-1")
  }

  test("HashType.getDigest - returns SHA256 digest") {
    val digest = HashType.SHA256.getDigest()
    assertEquals(digest.getAlgorithm(), "SHA-256")
  }

  // ==================== computeGitOID Tests ====================

  test("computeGitOID - computes SHA256 gitoid for string") {
    val input = "hello"
    val bytes = input.getBytes("UTF-8")
    val stream = new ByteArrayInputStream(bytes)
    val result = GitOIDUtils.computeGitOID(stream, bytes.length)
    assertEquals(result.length, 32, "SHA256 should produce 32 bytes")
  }

  test("computeGitOID - computes SHA1 gitoid") {
    val input = "hello"
    val bytes = input.getBytes("UTF-8")
    val stream = new ByteArrayInputStream(bytes)
    val result = GitOIDUtils.computeGitOID(stream, bytes.length, HashType.SHA1)
    assertEquals(result.length, 20, "SHA1 should produce 20 bytes")
  }

  test("computeGitOIDForString - computes gitoid from string directly") {
    val result = GitOIDUtils.computeGitOIDForString("test")
    assertEquals(result.length, 32)
  }

  test("computeGitOID - different inputs produce different hashes") {
    val result1 = GitOIDUtils.computeGitOIDForString("hello")
    val result2 = GitOIDUtils.computeGitOIDForString("world")
    assert(result1.toSeq != result2.toSeq)
  }

  test("computeGitOID - same input produces same hash") {
    val result1 = GitOIDUtils.computeGitOIDForString("consistent")
    val result2 = GitOIDUtils.computeGitOIDForString("consistent")
    assertEquals(result1.toSeq, result2.toSeq)
  }

  // ==================== hashAsHex Tests ====================

  test("hashAsHex - returns hex string") {
    val input = "test"
    val bytes = input.getBytes("UTF-8")
    val stream = new ByteArrayInputStream(bytes)
    val result = GitOIDUtils.hashAsHex(stream, bytes.length)
    assertEquals(result.length, 64, "SHA256 hex should be 64 characters")
    assert(result.matches("[0-9a-f]+"), "Should only contain hex characters")
  }

  test("hashAsHexForString - returns hex string") {
    val result = GitOIDUtils.hashAsHexForString("test")
    assertEquals(result.length, 64)
    assert(result.matches("[0-9a-f]+"))
  }

  // ==================== url Tests ====================

  test("url - generates gitoid URL format") {
    val input = "test"
    val bytes = input.getBytes("UTF-8")
    val stream = new ByteArrayInputStream(bytes)
    val result = GitOIDUtils.url(stream, bytes.length, HashType.SHA256)
    assert(result.startsWith("gitoid:blob:sha256:"))
    assertEquals(result.length, "gitoid:blob:sha256:".length + 64)
  }

  test("url - generates SHA1 URL") {
    val input = "test"
    val bytes = input.getBytes("UTF-8")
    val stream = new ByteArrayInputStream(bytes)
    val result = GitOIDUtils.url(stream, bytes.length, HashType.SHA1)
    assert(result.startsWith("gitoid:blob:sha1:"))
    assertEquals(result.length, "gitoid:blob:sha1:".length + 40)
  }

  test("url - generates tree object URL") {
    val input = "test"
    val bytes = input.getBytes("UTF-8")
    val stream = new ByteArrayInputStream(bytes)
    val result = GitOIDUtils.url(stream, bytes.length, HashType.SHA256, ObjectType.Tree)
    assert(result.startsWith("gitoid:tree:sha256:"))
  }

  test("urlForString - generates URL from string") {
    val result = GitOIDUtils.urlForString("test")
    assert(result.startsWith("gitoid:blob:sha256:"))
  }

  test("urlForString - generates SHA1 URL") {
    val result = GitOIDUtils.urlForString("test", HashType.SHA1)
    assert(result.startsWith("gitoid:blob:sha1:"))
  }

  // ==================== computeAllHashes Tests ====================

  test("computeAllHashes - returns all hash types") {
    val tempFile = Files.createTempFile("hashtest", ".txt").toFile()
    try {
      Helpers.writeOverFile(tempFile, "test content for hashing")
      val wrapper = FileWrapper(tempFile, tempFile.getName(), None)
      val (gitoidSha256, aliases) = GitOIDUtils.computeAllHashes(wrapper)

      assert(gitoidSha256.startsWith("gitoid:blob:sha256:"))
      assertEquals(aliases.length, 5)

      // Check that all expected hash types are present
      assert(aliases.exists(_.startsWith("gitoid:blob:sha1:")))
      assert(aliases.exists(_.startsWith("sha1:")))
      assert(aliases.exists(_.startsWith("sha256:")))
      assert(aliases.exists(_.startsWith("sha512:")))
      assert(aliases.exists(_.startsWith("md5:")))
    } finally {
      tempFile.delete()
    }
  }

  test("computeAllHashes - works with ByteWrapper") {
    val data = "byte wrapper content"
    val wrapper = ByteWrapper(data.getBytes("UTF-8"), "test.txt", None)
    val (gitoidSha256, aliases) = GitOIDUtils.computeAllHashes(wrapper)

    assert(gitoidSha256.startsWith("gitoid:blob:sha256:"))
    assertEquals(aliases.length, 5)
  }

  test("computeAllHashes - produces consistent results") {
    val data = "consistent data"
    val wrapper1 = ByteWrapper(data.getBytes("UTF-8"), "test1.txt", None)
    val wrapper2 = ByteWrapper(data.getBytes("UTF-8"), "test2.txt", None)

    val (gitoid1, _) = GitOIDUtils.computeAllHashes(wrapper1)
    val (gitoid2, _) = GitOIDUtils.computeAllHashes(wrapper2)

    assertEquals(gitoid1, gitoid2)
  }

  // ==================== merkleTreeFromGitoids Tests ====================

  test("merkleTreeFromGitoids - computes tree hash from gitoids") {
    val gitoids = Vector(
      "gitoid:blob:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1",
      "gitoid:blob:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2"
    )
    val result = GitOIDUtils.merkleTreeFromGitoids(gitoids)
    assert(result.startsWith("gitoid:tree:sha256:"))
  }

  test("merkleTreeFromGitoids - sorts gitoids before hashing") {
    val gitoids1 = Vector("aaaa", "bbbb")
    val gitoids2 = Vector("bbbb", "aaaa")
    val result1 = GitOIDUtils.merkleTreeFromGitoids(gitoids1)
    val result2 = GitOIDUtils.merkleTreeFromGitoids(gitoids2)
    assertEquals(result1, result2, "Order should not matter")
  }

  test("merkleTreeFromGitoids - handles empty vector") {
    val gitoids = Vector[String]()
    val result = GitOIDUtils.merkleTreeFromGitoids(gitoids)
    assert(result.startsWith("gitoid:tree:sha256:"))
  }

  test("merkleTreeFromGitoids - handles single gitoid") {
    val gitoids = Vector("aabbccdd")
    val result = GitOIDUtils.merkleTreeFromGitoids(gitoids)
    assert(result.startsWith("gitoid:tree:sha256:"))
  }

  test("merkleTreeFromGitoids - can use SHA1") {
    val gitoids = Vector("aabbccdd", "eeff0011")
    val result = GitOIDUtils.merkleTreeFromGitoids(gitoids, HashType.SHA1)
    assert(result.startsWith("gitoid:tree:sha1:"))
  }

  // ==================== Edge Cases ====================

  test("computeGitOID - handles empty content") {
    val stream = new ByteArrayInputStream(Array[Byte]())
    val result = GitOIDUtils.computeGitOID(stream, 0)
    assertEquals(result.length, 32)
  }

  test("computeGitOID - handles large content") {
    val largeContent = "a" * 100000
    val bytes = largeContent.getBytes("UTF-8")
    val stream = new ByteArrayInputStream(bytes)
    val result = GitOIDUtils.computeGitOID(stream, bytes.length)
    assertEquals(result.length, 32)
  }

  test("url - empty content produces valid URL") {
    val stream = new ByteArrayInputStream(Array[Byte]())
    val result = GitOIDUtils.url(stream, 0, HashType.SHA256)
    assert(result.startsWith("gitoid:blob:sha256:"))
    assertEquals(result.length, "gitoid:blob:sha256:".length + 64)
  }
}
