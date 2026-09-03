package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.util.Configuration
import munit.FunSuite
import org.eclipse.jgit.api.Git

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Phase 3 — tagged-run provenance integration + tag date (spec §6, §7;
  * T10.x, T12.x).
  *
  * WHAT: a tagged `Builder.buildDB` over a git fixture repo produces the
  * git provenance Items in the ADG (identified by gitoid, connected to
  * the run tag), with the run-tag date carried verbatim; untagged runs
  * produce none.
  *
  * WHY: spec §6 (tagged runs only) and §7 (tag + provenance always agree
  * on the run date).
  *
  * LLM note: exercises the real Builder path with a JGit-created fixture
  * repo; reads the written GRD/GRC via GraphManager to inspect Items.
  */
class GitTaggedRunIntegrationSuite extends FunSuite {

  private def tempDir(prefix: String): File =
    Files.createTempDirectory(prefix).toFile

  private def write(root: File, rel: String, content: String): Unit = {
    val f = new File(root, rel)
    f.getParentFile.mkdirs()
    Files.write(f.toPath, content.getBytes(StandardCharsets.UTF_8))
  }

  private def fixtureRepo(): File = {
    val root = tempDir("gtr")
    write(root, "a.txt", "hello world")
    val git = Git.init().setDirectory(root).setInitialBranch("main").call()
    val ident = new org.eclipse.jgit.lib.PersonIdent("T", "t@example.com")
    git.add().addFilepattern(".").call()
    git.commit().setAuthor(ident).setCommitter(ident).setMessage("c1").call()
    git.close()
    root
  }

  private def readItems(out: File): Vector[Item] = {
    val grc = out.listFiles().filter(_.getName.endsWith(".grc")).headOption
    assert(grc.isDefined, "grc must exist")
    val items = scala.collection.mutable.ArrayBuffer[Item]()
    // walk the grd files referenced by the grc via GraphManager
    out.listFiles().filter(_.getName.endsWith(".grd")).foreach { grd =>
      val channel = new java.io.FileInputStream(grd).getChannel
      try {
        val walker = new GRDWalker(channel)
        walker.open()
        items ++= walker.items()
      } finally channel.close()
    }
    items.toVector
  }

  test("T10.1 tagged run produces git provenance items") {
    val repo = fixtureRepo()
    val out = tempDir("gtr-out")
    val aFile = new File(repo, "a.txt")
    val config = Configuration(
      build = Vector(repo),
      tag = Some("run-1"),
      tagDate = Some(java.util.Date.from(java.time.Instant.parse("2026-09-02T00:00:00Z"))),
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
      i.identifier.startsWith("gitoid:commit:sha1:") || i.identifier.startsWith("gitoid:tree:sha1:")
    )
    assert(gitItems.nonEmpty, "git provenance items must be written for a tagged run")
    val commitItem = gitItems.find(_.identifier.startsWith("gitoid:commit:sha1:")).get
    val json = commitItem.body match {
      case Some(td: ItemTagData) => td.tag.toString
      case other                   => fail(s"expected ItemTagData body, got $other")
    }
    assert(json.contains("2026-09-02T00:00:00Z"), s"git item must carry the run-tag date verbatim: $json")
  }

  test("T10.2 untagged run produces no git items") {
    val repo = fixtureRepo()
    val out = tempDir("gtr-out")
    val aFile = new File(repo, "a.txt")
    val config = Configuration(
      build = Vector(repo),
      tag = None,
      out = Some(out)
    )
    Builder.buildDB(
      out,
      None,
      Seq(repo -> (() => Seq(aFile))),
      Set(),
      Vector(),
      _ => (),
      _ => ()
    )(using config)
    val items = readItems(out)
    val gitItems = items.filter(i =>
      i.identifier.startsWith("gitoid:commit:sha1:") || i.identifier.startsWith("gitoid:tree:sha1:")
    )
    assertEquals(gitItems, Vector.empty, "untagged run must not capture git provenance")
  }
}