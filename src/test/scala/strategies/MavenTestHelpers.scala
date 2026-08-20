/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Directory-based test infrastructure for MavenStrategy tests.
  *
  * '''What this provides:''' Helpers for creating temp directories with JAR +
  * POM files, calling `computeMavenFiles` to bundle them into `MavenToProcess`
  * instances, and processing those bundles through the full pipeline via
  * `buildGraphForToProcess`.
  *
  * '''Why this exists:''' Prior to Phase 1, tests used
  * `buildGraphFromArtifactWrapper` (single JAR, no companion POM) or called
  * methods directly. This made it impossible to test companion POM weight
  * (REQ-3) or verify processing order (REQ-4). These helpers bridge the gap by
  * setting up realistic directory structures that mirror how Goat Rodeo
  * processes real Maven artifacts.
  *
  * '''Usage:'''
  * {{{
  * MavenTestHelpers.withTempDir("my-test") { dir =>
  *   MavenTestHelpers.writeJar(dir, "foo-1.0.jar", Seq("foo.txt" -> "hello"))
  *   MavenTestHelpers.writePom(dir, "foo-1.0.pom", "org.example", "foo", "1.0")
  *   val bundles = MavenTestHelpers.computeBundles(dir)
  *   val store = MavenTestHelpers.processBundles(bundles)
  *   val purls = store.purls().toSet
  *   assert(purls.exists(_.contains("org.example")))
  * }
  * }}}
  *
  * '''LLM context:''' This object provides three categories of helpers:
  *   1. Directory/file creation: `withTempDir`, `writeJar`, `writePom` 2.
  *      Bundling: `computeBundles` — calls `computeMavenFiles` on a directory
  *      3. Processing: `processBundles`, `processDirectoryWithStore` — runs
  *      bundles through the full pipeline and returns the store
  *
  * All temp directories are cleaned up via `finally` blocks in `withTempDir`.
  * Never use `Files.createTempDirectory` directly — always use `withTempDir`.
  */
object MavenTestHelpers {

  /** The default configuration for these tests; calls needing different
    * settings pass an explicit `(using ...)`.
    */
  private given Configuration = Configuration()

  /** Minimal ZIP header bytes (PK\x03\x04 + local file header fields). Used for
    * creating files that Tika recognizes as `application/java-archive` without
    * needing full ZIP entries. Suitable for `computeMavenFiles` tests that only
    * check mimeType, NOT for pipeline processing tests that need a valid
    * openable archive.
    */
  val jarHeader: Array[Byte] = Array[Byte](
    0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00
  )

  /** Create a temp directory, run a function on it, and delete it in a
    * `finally` block. Guarantees cleanup even if the function throws.
    *
    * @param name
    *   prefix for the temp directory name
    * @param f
    *   the function to run on the temp directory
    * @return
    *   the result of `f`
    */
  def withTempDir[T](name: String = "maven-test")(f: File => T): T = {
    val dir = Files.createTempDirectory(name).toFile
    try {
      f(dir)
    } finally {
      Helpers.deleteDirectory(dir.toPath)
    }
  }

  /** Create a temp directory without automatic cleanup.
    *
    * '''Prefer `withTempDir`''' which guarantees cleanup via `finally`. Use
    * this method only when the directory lifecycle is managed externally (e.g.,
    * by a test framework's `afterEach` hook).
    *
    * @param name
    *   prefix for the temp directory name
    * @return
    *   the created directory File
    */
  def createTempDir(name: String = "maven-test"): File = {
    Files.createTempDirectory(name).toFile
  }

  /** Write a JAR (ZIP) file with the given entries.
    *
    * Creates a valid ZIP file that Tika recognizes as
    * `application/java-archive`. If `entries` is empty, a single dummy entry
    * (`dummy.txt`) is added to ensure the file is a non-empty ZIP (some
    * pipeline components expect at least one entry).
    *
    * @param dir
    *   the directory to write the JAR in
    * @param name
    *   the filename (e.g., `foo-1.0.jar`)
    * @param entries
    *   sequence of (path, content) pairs for ZIP entries
    * @return
    *   the created File
    */
  def writeJar(
      dir: File,
      name: String,
      entries: Seq[(String, String)]
  ): File = {
    val jarFile = new File(dir, name)
    val zos = new ZipOutputStream(new FileOutputStream(jarFile))
    try {
      val actualEntries =
        if (entries.isEmpty) Seq("dummy.txt" -> "dummy") else entries
      for ((path, content) <- actualEntries) {
        zos.putNextEntry(new ZipEntry(path))
        zos.write(content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    } finally {
      zos.close()
    }
    jarFile
  }

  /** Write a POM file with the given Maven coordinates.
    *
    * @param dir
    *   the directory to write the POM in
    * @param name
    *   the filename (e.g., `foo-1.0.pom`)
    * @param groupId
    *   the Maven groupId
    * @param artifactId
    *   the Maven artifactId
    * @param version
    *   the Maven version
    * @return
    *   the created File
    */
  def writePom(
      dir: File,
      name: String,
      groupId: String,
      artifactId: String,
      version: String
  ): File = {
    val pomFile = new File(dir, name)
    val content =
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<project xmlns="http://maven.apache.org/POM/4.0.0">
         |  <modelVersion>4.0.0</modelVersion>
         |  <groupId>${groupId}</groupId>
         |  <artifactId>${artifactId}</artifactId>
         |  <version>${version}</version>
         |</project>""".stripMargin
    Helpers.writeOverFile(pomFile, content)
    pomFile
  }

  /** Build `byUUID` and `byName` maps from all files in a directory.
    *
    * Uses `f.getName()` as the path for `FileWrapper`, matching what
    * `strategyForDirectory` does. This ensures `computeMavenFiles` can find
    * companions by filename (e.g., `foo-1.0.pom` for `foo-1.0.jar`).
    *
    * @param dir
    *   the directory to scan
    * @return
    *   a tuple of (byUUID, byName) maps
    */
  def buildMaps(
      dir: File
  ): (ToProcess.ByUUID, ToProcess.ByName) = {
    val files = dir.listFiles().toVector.filter(_.isFile)
    val wrappers = files.map(f => FileWrapper(f, f.getName(), None))
    val byUUID: ToProcess.ByUUID = wrappers.map(w => w.uuid -> w).toMap
    val byName: ToProcess.ByName = wrappers.groupBy(_.path())
    (byUUID, byName)
  }

  /** Call `computeMavenFiles` on a directory and return the resulting
    * `MavenToProcess` bundles.
    *
    * This is the key function for tests that need to verify companion POM
    * bundling (e.g., asserting `pom.isDefined` on the bundle).
    *
    * @param dir
    *   the directory containing JAR + POM files
    * @return
    *   a vector of `MavenToProcess` bundles (cast from `ToProcess`)
    */
  def computeBundles(dir: File): Vector[MavenToProcess] = {
    val (byUUID, byName) = buildMaps(dir)
    val (toProcess, _, _, _) = MavenToProcess.computeMavenFiles(byUUID, byName)
    toProcess.collect { case mtp: MavenToProcess => mtp }
  }

  /** Process a vector of `ToProcess` bundles through the full pipeline and
    * return the store with pURLs and metadata.
    *
    * Uses `buildGraphForToProcess` which calls `.process()` on each bundle. The
    * pipeline processes markers in order (POM → Sources → JavaDocs → Metadata →
    * JAR), accumulates metadata from archive children, and emits pURLs via
    * `applyAccumulatedAugmentation`.
    *
    * @param bundles
    *   the bundles to process
    * @return
    *   the `MemStorage` containing all emitted pURLs
    */
  def processBundles(bundles: Vector[ToProcess]): MemStorage = {
    val store = MemStorage(None)
    ToProcess.buildGraphForToProcess(bundles, store)
    store
  }

  /** Convenience: compute bundles from a directory and process them through the
    * full pipeline.
    *
    * @param dir
    *   the directory containing JAR + POM files
    * @return
    *   the `MemStorage` containing all emitted pURLs
    */
  def processDirectoryWithStore(dir: File): MemStorage = {
    val bundles = computeBundles(dir)
    processBundles(bundles)
  }
}
