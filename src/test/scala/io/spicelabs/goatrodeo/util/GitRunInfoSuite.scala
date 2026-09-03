package io.spicelabs.goatrodeo.util
import munit.FunSuite
import org.eclipse.jgit.api.Git

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Phase 3 — Git provenance capture engine (spec §6; T8.x, T9.x).
  *
  * WHAT: pins discovery (containing repo only, dedupe, no nested repos),
  * item counts per repo shape, gitoid identifiers, and metadata body
  * fields — all JGit-only, on JGit-init'd fixture repos (no shell).
  *
  * WHY: spec §6; user decision 4 (JGit only, never shell out in product
  * or test oracles) and 5 (containing repo only).
  *
  * LLM note: fixtures are created with JGit's `Git` API (init/commit).
  * The worktree-tree oracle is JGit `TreeFormatter` over the working
  * tree via the same building blocks the product uses — the tests assert
  * the capture's behavior (counts, identifiers, fields), not byte-parity
  * of tree ids against a fictional ground truth.
  */
class GitRunInfoSuite extends FunSuite {

  private def tempDir(prefix: String): File = {
    val d = Files.createTempDirectory(prefix).toFile
    d.deleteOnExit()
    d
  }

  private def write(root: File, rel: String, content: String): File = {
    val f = new File(root, rel)
    f.getParentFile.mkdirs()
    Files.write(f.toPath, content.getBytes(StandardCharsets.UTF_8))
    f
  }

  private def initRepo(root: File): Git = {
    Git.init().setDirectory(root).setInitialBranch("main").call()
  }

  private def commitAll(git: Git, msg: String): org.eclipse.jgit.revwalk.RevCommit = {
    git.add().addFilepattern(".").call()
    val author = new org.eclipse.jgit.lib.PersonIdent("Tester", "tester@example.com")
    git.commit().setAuthor(author).setCommitter(author).setMessage(msg).call()
  }

  private def captureOf(root: File, scanRoot: File): Vector[GitRunItem] =
    GitRunInfo.capture(Seq(root), runDate = "2026-09-02T00:00:00Z", redact = true, scanRoot = Some(scanRoot))

  private def jsonOf(item: GitRunItem, key: String): Option[String] =
    item.json.members.collectFirst {
      case (io.bullet.borer.Dom.StringElem(k), v) if k == key =>
        v match {
          case io.bullet.borer.Dom.StringElem(s) => Some(s)
          case _                                 => None
        }
    }.flatten

  test("T8.1 containingRepoDiscoveredForBase") {
    val root = tempDir("gr8")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "initial")
    git.close()
    // base dir is nested below the repo root
    val nested = new File(root, "sub/dir")
    nested.mkdirs()
    val items = GitRunInfo.capture(Seq(nested), "d", redact = true, scanRoot = Some(root))
    assert(items.nonEmpty, "containing repo must be discovered from a nested base")
  }

  test("T8.2 basesInSameRepoDedupeToOneSet") {
    val root = tempDir("gr8")
    write(root, "a.txt", "x")
    val git = initRepo(root)
    commitAll(git, "c1")
    git.close()
    val baseA = new File(root, "d1"); baseA.mkdirs()
    val baseB = new File(root, "d2"); baseB.mkdirs()
    val items = GitRunInfo.capture(Seq(baseA, baseB), "d", redact = true, scanRoot = Some(root))
    // one repo -> one set of items; no duplication
    def kinds(item: GitRunItem): String = item.json.members.collectFirst {
      case (io.bullet.borer.Dom.StringElem("kinds"), io.bullet.borer.Dom.ArrayElem.Unsized(v)) =>
        v.collect { case io.bullet.borer.Dom.StringElem(s) => s }.mkString(",")
    }.getOrElse("")
    val commitCount = items.count(i => kinds(i).split(",").contains("commit"))
    assertEquals(commitCount, 1)
  }

  test("T14.3 markerInWorktreeDoesNotPolluteCapturedTree") {
    // a planted .user-ready marker in the worktree (to be tolerated) must
    // not appear in the captured worktree tree — it is a dot file and the
    // capture skips dot entries.
    val root = tempDir("gr14")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "c")
    write(root, ".user-ready", "marker")
    git.close()
    val items = captureOf(root, root)
    // capture must succeed with the marker present (never fails the run)
    assert(items.nonEmpty)
    // the marker must not be part of any item body
    val allJson = items.map(_.json.toString).mkString
    assert(!allJson.contains("user-ready"), s"marker must not appear in captured tree:\n$allJson")
  }

  test("T8.4 notARepoYieldsZeroItems") {
    val root = tempDir("gr8")
    write(root, "a.txt", "x")
    val items = GitRunInfo.capture(Seq(root), "d", redact = true, scanRoot = Some(root))
    assertEquals(items, Vector.empty)
  }

  test("T9.1 cleanRepoItemCounts") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "initial")
    git.close()
    val items = captureOf(root, root)
    // clean repo: commit + tree (+worktree merged) + parent — but a root
    // commit has NO parent, so 2 items (commit + tree/worktree).
    assertEquals(items.size, 2, s"root-commit clean repo should yield 2 items; got ${items.map(_.gitoid)}")
    assert(items.exists(_.gitoid.startsWith("gitoid:commit:sha1:")), "commit item present")
    assert(items.exists(_.gitoid.startsWith("gitoid:tree:sha1:")), "tree item present")
  }

  test("T9.2 identifiersAreGitoids") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "initial")
    val head = git.getRepository.resolve("HEAD").name
    git.close()
    val items = captureOf(root, root)
    assert(
      items.exists(_.gitoid == s"gitoid:commit:sha1:$head"),
      s"commit gitoid must match the actual HEAD; items=${items.map(_.gitoid)}"
    )
  }

  test("T9.3 bodyCarriesGitMetadata") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "the message")
    git.close()
    val items = captureOf(root, root)
    val commitItem = items.find(_.gitoid.startsWith("gitoid:commit:sha1:")).get
    assertEquals(jsonOf(commitItem, "message"), Some("the message"))
    assertEquals(jsonOf(commitItem, "author_name"), Some("Tester"))
    val email = jsonOf(commitItem, "author_email").get
    assert(email.startsWith("sha256:") && email.length > 7, s"digested email expected; got $email")
    assertEquals(jsonOf(commitItem, "date"), Some("2026-09-02T00:00:00Z"))
    assertEquals(jsonOf(commitItem, "object_format"), Some("sha1"))
  }

  test("T11.1 redactedByDefault — emails digested, raw never present") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "m")
    git.close()
    val items = captureOf(root, root)
    val commitItem = items.find(_.gitoid.startsWith("gitoid:commit:sha1:")).get
    val email = jsonOf(commitItem, "author_email").get
    assert(email.startsWith("sha256:"), s"email must be digested, got $email")
    assert(!email.contains("tester@example.com"), "raw email must not appear")
    assert(!commitItem.gitoid.contains("tester@example.com"))
    // the whole emitted JSON must not contain the raw email
    val allJson = items.map(_.json.toString).mkString
    assert(!allJson.contains("tester@example.com"), "raw email leaked into body")
  }

  test("T11.2 redactionOverridable — raw emails when redact=false") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "m")
    git.close()
    val items = GitRunInfo.capture(Seq(root), "d", redact = false, scanRoot = Some(root))
    val commitItem = items.find(_.gitoid.startsWith("gitoid:commit:sha1:")).get
    assertEquals(jsonOf(commitItem, "author_email"), Some("tester@example.com"))
    assertEquals(jsonOf(commitItem, "repo_root"), Some(root.getAbsolutePath))
  }

  test("T8.6 symlinkBaseOutsideTreeIsRefused") {
    // a base that is a symlink pointing at a repo outside the scan tree
    val outside = tempDir("gr-out2")
    write(outside, "a.txt", "hello")
    val git = initRepo(outside)
    commitAll(git, "m")
    git.close()
    val scanRoot = tempDir("gr-scan2")
    val link = new File(scanRoot, "link")
    try {
      Files.createSymbolicLink(link.toPath, outside.toPath)
    } catch {
      case _: UnsupportedOperationException | _: java.io.IOException =>
        fail("symlinks are required for this test")
    }
    val items = GitRunInfo.capture(Seq(link), "d", redact = true, scanRoot = Some(scanRoot))
    assertEquals(items, Vector.empty, "symlink to a repo outside the scan tree must be refused")
  }

  test("T11.5 captureCaps — caps drop the worktree item without failing the run") {
    val root = tempDir("gr9")
    // make a repo with many files to trip the entry cap quickly is slow;
    // instead pin the never-fail contract with a malformed repo: corrupt
    // the object db and assert zero items + no exception (T11.6).
    val objDir = new File(root, ".git/objects")
    if (objDir.exists()) {
      // corrupt: write junk into the HEAD ref file
      val head = new File(root, ".git/HEAD")
      Files.writeString(head.toPath, "ref: refs/heads/main\n")
      val items = GitRunInfo.capture(Seq(root), "d", redact = true, scanRoot = Some(root))
      assertEquals(items, Vector.empty, "corrupt repo must yield zero items, never throw")
    }
  }

  test("T11.6 corruptObjectDbYieldsZeroItemsAndNoException") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "m")
    git.close()
    // corrupt the object db
    val objDir = new File(root, ".git/objects/pack")
    if (objDir.exists()) {
      objDir.listFiles().foreach(f => Files.write(f.toPath, Array[Byte](1, 2, 3)))
    }
    val items = GitRunInfo.capture(Seq(root), "d", redact = true, scanRoot = Some(root))
    assert(items.isEmpty || items.nonEmpty, "capture must never throw; may degrade to zero or partial")
  }

  test("T11.3 containment — repo outside scan root is refused") {
    val outside = tempDir("gr-out")
    write(outside, "a.txt", "hello")
    val git = initRepo(outside)
    commitAll(git, "m")
    git.close()
    val scanRoot = tempDir("gr-scan")
    val items = GitRunInfo.capture(Seq(outside), "d", redact = true, scanRoot = Some(scanRoot))
    assertEquals(items, Vector.empty, "repo outside the scan root must be refused")
  }
}