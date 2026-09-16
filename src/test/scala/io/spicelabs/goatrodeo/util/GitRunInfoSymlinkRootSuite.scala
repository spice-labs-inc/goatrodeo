package io.spicelabs.goatrodeo.util

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Regression: a scan root reached through a symlink must still contain the
  * repository found beneath it.
  *
  * Pins the containment check in `GitRunInfo.captureRepoChecked`.
  * `discoverRepos` canonicalizes the worktree (resolving symlinks) and the
  * gitdir is derived from that canonical path, but the scan roots are only
  * `toAbsolutePath.normalize`d. The `startsWith` test then fails and the repo
  * is silently skipped. On macOS this hits every build directory under `/tmp`
  * or `/var`, which resolve to `/private/...`.
  */
class GitRunInfoSymlinkRootSuite extends GoatRodeoFunSuite {

  private def tempDir(prefix: String): File = {
    val d = Files.createTempDirectory(prefix).toFile
    d.deleteOnExit()
    d
  }

  test("a repo under a symlinked scan root is captured") {
    val real = tempDir("gr-real")
    Files.write(
      new File(real, "a.txt").toPath,
      "hello".getBytes(StandardCharsets.UTF_8)
    )
    val git = Git.init().setDirectory(real).setInitialBranch("main").call()
    git.add().addFilepattern(".").call()
    git
      .commit()
      .setSign(false)
      .setAuthor(new PersonIdent("Tester", "tester@example.com"))
      .setCommitter(new PersonIdent("Tester", "tester@example.com"))
      .setMessage("m")
      .call()
    git.close()

    val link = new File(tempDir("gr-link"), "link")
    try Files.createSymbolicLink(link.toPath, real.toPath)
    catch {
      case _: UnsupportedOperationException | _: IOException =>
        assume(false, "symlinks are required for this test")
    }

    // The same symlinked path is both the build directory and the scan root,
    // so the repository is unambiguously in scope.
    val items =
      GitRunInfo.capture(Seq(link), redact = true, scanRoots = Vector(link))
    assertEquals(
      items.size,
      2,
      "HEAD commit + HEAD tree must be captured for a repo under a symlinked scan root"
    )
  }
}
