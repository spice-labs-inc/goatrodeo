package io.spicelabs.goatrodeo.util

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.Try

/** Regression: `GitRunInfo.capture` is documented as "never throws; on any
  * failure the repo is skipped", and `Builder.buildDB` calls it unguarded.
  *
  * Pins `GitRunInfo.captureRepo`: it has try/finally but no catch, and
  * `builder.build()` sits outside the try. A repository whose HEAD names an
  * object that is missing from the object store makes `RevWalk.parseCommit`
  * throw, and the exception escapes `capture` and aborts the run.
  *
  * The two existing tests in `GitRunInfoSuite` that claim to pin this contract
  * do not: one is gated on a directory that never exists, the other corrupts
  * pack files that a JGit-created fixture does not have.
  *
  * Not covered here: `discoverRepos` never closes the `Repository` it builds. A
  * `FileRepository` holds no OS handle until packs are read, so there is no
  * externally observable signal to assert on.
  */
class GitRunInfoNeverThrowsSuite extends GoatRodeoFunSuite {

  private def tempDir(prefix: String): File = {
    val d = Files.createTempDirectory(prefix).toFile
    d.deleteOnExit()
    d
  }

  private def freshRepo(): File = {
    val root = tempDir("gr-nt")
    val f = new File(root, "a.txt")
    Files.write(f.toPath, "hello".getBytes(StandardCharsets.UTF_8))
    val git = Git.init().setDirectory(root).setInitialBranch("main").call()
    git.add().addFilepattern(".").call()
    git
      .commit()
      .setSign(false)
      .setAuthor(new PersonIdent("Tester", "tester@example.com"))
      .setCommitter(new PersonIdent("Tester", "tester@example.com"))
      .setMessage("m")
      .call()
    git.close()
    root
  }

  private def captureNeverThrows(root: File): Unit = {
    val r = Try(
      GitRunInfo.capture(
        Seq(root),
        redact = true,
        // canonical so the containment check cannot mask the throw (see
        // GitRunInfoSymlinkRootSuite for that separate defect)
        scanRoots = Vector(root.getCanonicalFile)
      )
    )
    assert(
      r.isSuccess,
      s"capture must never throw, but threw: ${r.failed.toOption.map(_.toString)}"
    )
    assertEquals(r.get, Vector.empty, "a broken repo must yield zero items")
  }

  test(
    "HEAD pointing at a missing commit object yields zero items, no exception"
  ) {
    val root = freshRepo()
    // Remove every loose object: HEAD still resolves to an id, but the
    // commit it names is gone.
    val objects = new File(root, ".git/objects")
    val looseDirs = Option(objects.listFiles())
      .getOrElse(Array.empty[File])
      .filter(d => d.isDirectory && d.getName.matches("[0-9a-f]{2}"))
    assert(
      looseDirs.nonEmpty,
      "fixture sanity: JGit must have written loose objects"
    )
    looseDirs.foreach(d => Option(d.listFiles()).foreach(_.foreach(_.delete())))
    captureNeverThrows(root)
  }

  test("a garbage HEAD file yields zero items, no exception") {
    val root = freshRepo()
    Files.write(
      new File(root, ".git/HEAD").toPath,
      "this is not a ref".getBytes(StandardCharsets.UTF_8)
    )
    captureNeverThrows(root)
  }
}
