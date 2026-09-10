package io.spicelabs.goatrodeo.omnibor
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.GitRunInfo
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.Date
import scala.collection.mutable.ArrayBuffer

/** Hard invariant: Goat Rodeo NEVER modifies git files. Scanned repositories
  * are strictly read-only — provenance capture reads the HEAD commit and HEAD
  * tree ids from the repository itself and computes no object ids of its own,
  * so there is no write-capable API left in the product path to regress.
  *
  * WHAT: two black-box tests, at the two layers that touch git:
  *   1. `GitRunInfo.capture` over a fixture repo leaves `.git` byte-for-byte
  *      untouched (same entries, sizes, and mtimes). 2. A tagged
  *      `Builder.buildDB` run (the full product path that drives provenance
  *      capture) leaves the scanned repository's `.git` byte-for-byte untouched
  *      — and does produce git provenance Items, so the capture path
  *      demonstrably ran.
  *
  * WHY: user directive — JGit may never make any modifications to git files,
  * and Goat Rodeo must always read. These tests are the regression gate in case
  * a future change reintroduces any repository mutation.
  *
  * LLM note: the fixture repo is created with JGit commits (test setup only —
  * temp dirs, never a real repo). The snapshot records every entry under `.git`
  * — relative path, kind (file/directory), size, and mtime; a modified, added,
  * or removed entry fails the equality assert. Reading never changes mtimes;
  * only writes do.
  */
class GitReadOnlyInvariantSuite extends GoatRodeoFunSuite {

  private def tempDir(prefix: String): File =
    Files.createTempDirectory(prefix).toFile

  private def write(root: File, rel: String, content: String): Unit = {
    val f = new File(root, rel)
    f.getParentFile.mkdirs()
    Files.write(f.toPath, content.getBytes(StandardCharsets.UTF_8))
  }

  /** A small real git repository: a regular file, a nested file, and a symlink
    * (so the symlink-blob id computation path is exercised).
    */
  private def fixtureRepo(): File = {
    val root = tempDir("gri")
    write(root, "a.txt", "hello world")
    write(root, "sub/b.txt", "nested content")
    Files.createSymbolicLink(
      new File(root, "link.txt").toPath,
      new File("a.txt").toPath
    )
    val git = Git.init().setDirectory(root).setInitialBranch("main").call()
    val ident = new PersonIdent("T", "t@example.com")
    git.add().addFilepattern(".").call()
    // Fixture commits must never sign. JGit inherits the developer's global
    // git config; `commit.gpgsign=true` plus `gpg.format=ssh` (a common SSH
    // signing setup) makes JGit throw UnsupportedSigningFormatException
    // because JGit has no SSH signer. setSign(false) pins signing off
    // regardless of any environment config.
    git
      .commit()
      .setSign(false)
      .setAuthor(ident)
      .setCommitter(ident)
      .setMessage("c1")
      .call()
    git.close()
    root
  }

  /** Fingerprint of a `.git` directory: every entry (files and directories)
    * with relative path, kind, size, and last-modified time, sorted for
    * determinism. Reading never touches mtimes; only writes do, so equality
    * before/after a run proves no modification — including newly created
    * directories (e.g. a future flush creating `objects/pack`).
    */
  private def gitSnapshot(
      gitDir: File
  ): Vector[(String, String, Long, Long)] = {
    val out =
      ArrayBuffer[(String, String, Long, Long)]()
    def walk(dir: File, prefix: String): Unit = {
      val children = Option(dir.listFiles()).getOrElse(Array.empty[File])
      children.sortBy(_.getName).foreach { f =>
        val rel = s"$prefix${f.getName}"
        if (f.isDirectory) {
          out += ((rel, "d", 0L, f.lastModified()))
          walk(f, s"$rel/")
        } else out += ((rel, "f", f.length(), f.lastModified()))
      }
    }
    walk(gitDir, "")
    out.toVector
  }

  private def readItems(out: File): Vector[Item] = {
    val grc = out.listFiles().filter(_.getName.endsWith(".grc")).headOption
    assert(grc.isDefined, "grc must exist")
    val items = ArrayBuffer[Item]()
    out.listFiles().filter(_.getName.endsWith(".grd")).foreach { grd =>
      val channel = new FileInputStream(grd).getChannel
      try {
        val walker = new GRDWalker(channel)
        walker.open().get
        items ++= walker.items()
      } finally channel.close()
    }
    items.toVector
  }

  test("GitRunInfo.capture never modifies the repository .git directory") {
    val repo = fixtureRepo()
    val before = gitSnapshot(new File(repo, ".git"))
    val items = GitRunInfo.capture(
      Seq(repo),
      redact = true,
      scanRoots = Vector(repo)
    )
    assert(items.nonEmpty, "git capture must produce provenance items")
    val after = gitSnapshot(new File(repo, ".git"))
    assertEquals(
      after,
      before,
      ".git must be byte-for-byte untouched by GitRunInfo.capture"
    )
  }

  test("tagged buildDB run never modifies the scanned repository .git") {
    val repo = fixtureRepo()
    val before = gitSnapshot(new File(repo, ".git"))
    val out = tempDir("gri-out")
    val aFile = new File(repo, "a.txt")
    val config = Configuration(
      build = Vector(repo),
      tag = Some("run-1"),
      tagDate = Some(
        Date.from(Instant.parse("2026-09-02T00:00:00Z"))
      ),
      out = Some(out)
    )
    Builder.buildDB(
      out,
      Some(TagInfo("run-1", None)),
      Seq(repo -> (() => Seq(aFile))),
      Set(),
      Vector(),
      _ => (),
      _ => ()
    )(using config)

    val items = readItems(out)
    val gitItems = items.filter(i =>
      i.identifier.startsWith("gitoid:commit:sha1:") || i.identifier.startsWith(
        "gitoid:tree:sha1:"
      )
    )
    assert(
      gitItems.nonEmpty,
      "git provenance items must be produced by the tagged run"
    )
    val after = gitSnapshot(new File(repo, ".git"))
    assertEquals(
      after,
      before,
      ".git must be byte-for-byte untouched by a tagged buildDB run"
    )
  }
}
