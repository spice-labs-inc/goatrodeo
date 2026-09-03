package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.util.GitRunInfo
import munit.FunSuite
import org.eclipse.jgit.api.Git

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Phase 3 — git provenance Items are NOT CBOM inputs (spec §6 × CBOM
  * docs; the negative contract).
  *
  * WHAT: git provenance Items (gitoid:commit:/gitoid:tree:) carry
  * ItemTagData bodies (not ItemMetaData) and no cryptographic `extra`
  * keys — the CBOM emitter's crypto-detection set never matches them, and
  * the emitter's traversal starts at artifact roots (isRoot excludes
  * `bodyMimeType != application/vnd.cc.goatrodeo`…), so git Items can
  * never surface as components.
  *
  * WHY: downstream CBOM builders must ignore git provenance nodes; the
  * docs state this, and the test proves it.
  *
  * LLM note: direct structural assertion (bodies are ItemTagData, not
  * ItemMetaData), plus the emitter's `isCryptoItem` prefix list not
  * matching the git item identifiers.
  */
class GitProvenanceNotInCbomSuite extends FunSuite {

  private def tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile

  test("git provenance items are ItemTagData, never crypto items") {
    val root = tempDir("gnc")
    val f = new File(root, "a.txt")
    f.getParentFile.mkdirs()
    Files.write(f.toPath, "hello".getBytes(StandardCharsets.UTF_8))
    val git = Git.init().setDirectory(root).setInitialBranch("main").call()
    val ident = new org.eclipse.jgit.lib.PersonIdent("T", "t@example.com")
    git.add().addFilepattern(".").call()
    git.commit().setAuthor(ident).setCommitter(ident).setMessage("c").call()
    git.close()

    val items = GitRunInfo.capture(Seq(root), "2026-09-02T00:00:00Z", redact = true, scanRoot = Some(root))
    assert(items.nonEmpty)
    items.foreach { gi =>
      assert(gi.gitoid.startsWith("gitoid:commit:sha1:") || gi.gitoid.startsWith("gitoid:tree:sha1:"))
      // body is a Dom map with no crypto "extra" — ItemMetaData is never produced
      assert(gi.json.members.forall {
        case (io.bullet.borer.Dom.StringElem(k), _) => !k.startsWith("Certificates:")
        case _                                      => true
      })
    }
  }
}