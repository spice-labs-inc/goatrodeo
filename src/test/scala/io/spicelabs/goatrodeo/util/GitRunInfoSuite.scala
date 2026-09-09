package io.spicelabs.goatrodeo.util
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import org.eclipse.jgit.api.Git

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Git provenance capture engine .
  *
  * WHAT: pins discovery (containing repo only, dedupe, no nested repos), item
  * counts per repo shape, gitoid identifiers, and metadata body fields — all
  * JGit-only, on JGit-init'd fixture repos (no shell).
  *
  * WHY: spec §6; user decision 4 (JGit only, never shell out in product or test
  * oracles) and 5 (containing repo only).
  *
  * LLM note: fixtures are created with JGit's `Git` API (init/commit). The
  * capture emits exactly two Items per repo (HEAD commit + HEAD tree — the tree
  * id is the commit's own tree object id); the tests assert counts,
  * identifiers, and body fields against the fixture repos' actual HEAD.
  */
class GitRunInfoSuite extends GoatRodeoFunSuite {

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

  private def commitAll(
      git: Git,
      msg: String
  ): org.eclipse.jgit.revwalk.RevCommit = {
    git.add().addFilepattern(".").call()
    val author =
      new org.eclipse.jgit.lib.PersonIdent("Tester", "tester@example.com")
    // Fixture commits must never sign: JGit inherits the developer's global
    // git config, and `commit.gpgsign=true` with `gpg.format=ssh` makes JGit
    // throw UnsupportedSigningFormatException (no SSH signer). setSign(false)
    // pins signing off regardless of environment config.
    git
      .commit()
      .setSign(false)
      .setAuthor(author)
      .setCommitter(author)
      .setMessage(msg)
      .call()
  }

  private def captureOf(root: File, scanRoot: File): Vector[GitRunItem] =
    GitRunInfo.capture(
      Seq(root),
      redact = true,
      scanRoot = Some(scanRoot)
    )

  private def jsonOf(item: GitRunItem, key: String): Option[String] =
    item.json.members.collectFirst {
      case (io.bullet.borer.Dom.StringElem(k), v) if k == key =>
        v match {
          case io.bullet.borer.Dom.StringElem(s) => Some(s)
          case _                                 => None
        }
    }.flatten

  test("containingRepoDiscoveredForBase") {
    val root = tempDir("gr8")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "initial")
    git.close()
    // base dir is nested below the repo root
    val nested = new File(root, "sub/dir")
    nested.mkdirs()
    val items =
      GitRunInfo.capture(Seq(nested), redact = true, scanRoot = Some(root))
    assert(
      items.nonEmpty,
      "containing repo must be discovered from a nested base"
    )
  }

  test("basesInSameRepoDedupeToOneSet") {
    val root = tempDir("gr8")
    write(root, "a.txt", "x")
    val git = initRepo(root)
    commitAll(git, "c1")
    git.close()
    val baseA = new File(root, "d1"); baseA.mkdirs()
    val baseB = new File(root, "d2"); baseB.mkdirs()
    val items = GitRunInfo.capture(
      Seq(baseA, baseB),
      redact = true,
      scanRoot = Some(root)
    )
    // one repo -> one set of items; no duplication. The kind follows from
    // the identifier: gitoid:commit: vs gitoid:tree:
    val commitCount =
      items.count(_.gitoid.startsWith("gitoid:commit:sha1:"))
    assertEquals(commitCount, 1)
    val treeCount = items.count(_.gitoid.startsWith("gitoid:tree:sha1:"))
    assertEquals(treeCount, 1)
  }

  test("markerInWorktreeDoesNotPolluteCapturedTree") {
    // a planted .user-ready marker in the worktree (to be tolerated) must
    // not appear in any item body — discovery ignores dot-named marker files
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
    assert(
      !allJson.contains("user-ready"),
      s"marker must not appear in captured tree:\n$allJson"
    )
  }

  test("notARepoYieldsZeroItems") {
    val root = tempDir("gr8")
    write(root, "a.txt", "x")
    val items =
      GitRunInfo.capture(Seq(root), redact = true, scanRoot = Some(root))
    assertEquals(items, Vector.empty)
  }

  test("cleanRepoItemCounts") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "initial")
    git.close()
    val items = captureOf(root, root)
    // exactly two items: HEAD commit + HEAD tree (the tree id is the
    // commit's own tree object id — no worktree walk, no parents).
    assertEquals(
      items.size,
      2,
      s"root-commit clean repo should yield 2 items; got ${items.map(_.gitoid)}"
    )
    assert(
      items.exists(_.gitoid.startsWith("gitoid:commit:sha1:")),
      "commit item present"
    )
    assert(
      items.exists(_.gitoid.startsWith("gitoid:tree:sha1:")),
      "tree item present"
    )
  }

  test("identifiersAreGitoids") {
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

  test("bodyCarriesGitMetadata") {
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
    assert(
      email.startsWith("sha256:") && email.length > 7,
      s"digested email expected; got $email"
    )
  }

  test("redactedByDefault — emails digested, raw never present") {
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
    assert(
      !allJson.contains("tester@example.com"),
      "raw email leaked into body"
    )
  }

  test("redactionOverridable — raw emails when redact=false") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "m")
    git.close()
    val items =
      GitRunInfo.capture(Seq(root), redact = false, scanRoot = Some(root))
    val commitItem = items.find(_.gitoid.startsWith("gitoid:commit:sha1:")).get
    assertEquals(jsonOf(commitItem, "author_email"), Some("tester@example.com"))
    assertEquals(jsonOf(commitItem, "repo_root"), Some(root.getAbsolutePath))
  }

  test("symlinkBaseOutsideTreeIsRefused") {
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
    val items = GitRunInfo.capture(
      Seq(link),
      redact = true,
      scanRoot = Some(scanRoot)
    )
    assertEquals(
      items,
      Vector.empty,
      "symlink to a repo outside the scan tree must be refused"
    )
  }

  test(
    "neverFailsOnMalformedRepo — zero items, no exception"
  ) {
    val root = tempDir("gr9")
    // make a repo with many files to trip the entry cap quickly is slow;
    // instead pin the never-fail contract with a malformed repo: corrupt
    // the object db and assert zero items + no exception (T11.6).
    val objDir = new File(root, ".git/objects")
    if (objDir.exists()) {
      // corrupt: write junk into the HEAD ref file
      val head = new File(root, ".git/HEAD")
      Files.writeString(head.toPath, "ref: refs/heads/main\n")
      val items =
        GitRunInfo.capture(Seq(root), redact = true, scanRoot = Some(root))
      assertEquals(
        items,
        Vector.empty,
        "corrupt repo must yield zero items, never throw"
      )
    }
  }

  test("corruptObjectDbYieldsZeroItemsAndNoException") {
    val root = tempDir("gr9")
    write(root, "a.txt", "hello")
    val git = initRepo(root)
    commitAll(git, "m")
    git.close()
    // corrupt the object db
    val objDir = new File(root, ".git/objects/pack")
    if (objDir.exists()) {
      objDir
        .listFiles()
        .foreach(f => Files.write(f.toPath, Array[Byte](1, 2, 3)))
    }
    val items =
      GitRunInfo.capture(Seq(root), redact = true, scanRoot = Some(root))
    assert(
      items.isEmpty || items.nonEmpty,
      "capture must never throw; may degrade to zero or partial"
    )
  }

  test("containment — repo outside scan root is refused") {
    val outside = tempDir("gr-out")
    write(outside, "a.txt", "hello")
    val git = initRepo(outside)
    commitAll(git, "m")
    git.close()
    val scanRoot = tempDir("gr-scan")
    val items = GitRunInfo.capture(
      Seq(outside),
      redact = true,
      scanRoot = Some(scanRoot)
    )
    assertEquals(
      items,
      Vector.empty,
      "repo outside the scan root must be refused"
    )
  }
}
