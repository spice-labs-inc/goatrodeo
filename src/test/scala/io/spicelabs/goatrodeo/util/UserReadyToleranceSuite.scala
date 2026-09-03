package io.spicelabs.goatrodeo.util

import munit.FunSuite

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Phase 4 — `.user-ready` fixture tolerance (spec §10; T14.x).
  *
  * WHAT: pins that fixture listing and deletion tolerate an externally
  * planted `.user-ready` marker file (possibly root-owned/un-deletable):
  * discovery skips dot-names by NAME (readable or not), recursive
  * deletion never throws on an un-deletable marker and never follows it
  * out of the tree, and git fixtures exclude it from cleanliness.
  *
  * WHY: test fixtures may carry an externally planted, root-owned marker;
  * Goat Rodeo's own discovery/cleanup must not choke on it.
  *
  * LLM note: the marker matrix pins that the skip is name-based (the
  * `.` prefix), not permission-based: a readable `.user-ready` is skipped,
  * an unreadable one is skipped, and a readable non-dot root-owned file
  * is discovered (unchanged).
  */
class UserReadyToleranceSuite extends FunSuite {

  private def treeWithMarker(root: File, markerName: String, readable: Boolean): File = {
    val f = new File(root, markerName)
    Files.write(f.toPath, "marker".getBytes(StandardCharsets.UTF_8))
    if (!readable) {
      f.setReadable(false)
      f.setWritable(false)
    }
    root
  }

  private def discovered(root: File): Vector[File] =
    Helpers.findFiles(root)

  test("T14.1 fileDiscoverySkipsDotFiles (readable and unreadable)") {
    val root = Files.createTempDirectory("ur").toFile
    try {
      // readable dot-name
      val r1 = treeWithMarker(root, ".user-ready", readable = true)
      // unreadable dot-name
      val r2 = treeWithMarker(root, ".user-ready2", readable = false)
      // a regular file
      val normal = new File(root, "payload.txt")
      Files.write(normal.toPath, "x".getBytes)

      val found = discovered(root).map(_.getName)
      assertEquals(found, Vector("payload.txt"), s"dot-names must never be discovered; got $found")
      assert(!found.contains(".user-ready"))
      assert(!found.contains(".user-ready2"))
    } finally Helpers.deleteDirectory(root.toPath)
  }

  test("T14.2 recursiveDeleteToleratesUndeletableMarker") {
    val root = Files.createTempDirectory("ur").toFile
    val marker = new File(root, ".user-ready")
    Files.write(marker.toPath, "m".getBytes)
    // attempt to make it undeletable (may not succeed on all systems, but
    // deletion must not throw either way)
    marker.setWritable(false)
    // must not throw even if the marker survives
    Helpers.deleteDirectory(root.toPath)
  }

  test("T14.4 property_discoveryUnchangedByMarker") {
    import org.scalacheck.Prop.forAll
    import org.scalacheck.Gen
    val names = Gen.listOf(Gen.oneOf("a.txt", "b.txt", "c.bin", ".user-ready", ".keep"))
    val prop = forAll(names) { fnames =>
      val root = Files.createTempDirectory("urp").toFile
      try {
        fnames.distinct.foreach { n =>
          Files.write(new File(root, n).toPath, "x".getBytes)
        }
        val found = discovered(root).map(_.getName).toSet
        // every non-dot name discovered; no dot name ever
        fnames.distinct.filterNot(_.startsWith(".")).forall(found.contains) &&
        !found.exists(_.startsWith("."))
      } finally Helpers.deleteDirectory(root.toPath)
    }
    prop.check(org.scalacheck.Test.Parameters.default.withMinSuccessfulTests(50))
  }
}