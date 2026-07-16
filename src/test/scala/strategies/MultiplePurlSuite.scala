/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.GitOIDUtils
import munit.FunSuite

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** Tests that Goat Rodeo finds the same pURLs as the reference scanner for
  * uberjar/assembly JARs containing multiple embedded Maven packages
  * (shaded/fat JARs).
  *
  * '''What this tests:''' For each uberjar where the reference scanner found
  * multiple pURLs, Goat Rodeo must also find all of those pURLs as `alias:from`
  * connections on the JAR's Item. This is the "metadata parity" requirement:
  * Goat Rodeo must discover every embedded package that the reference scanner
  * discovers.
  *
  * '''How it works:''' The test processes both the JAR and its companion POM
  * file together in a temp directory. After processing, it computes the gitoid
  * of the JAR file, reads the corresponding Item from the store, and extracts
  * the `alias:from` connections — these are the pURLs associated with that
  * specific artifact (from embedded pom.properties inside the JAR).
  *
  * '''Test data filtering:''' Only JARs that (a) have a companion .pom file and
  * (b) do NOT contain nested .jar entries are included. JARs with nested JARs
  * (e.g., Spring Boot fat JARs with BOOT-INF/lib) are excluded because their
  * pURLs come from recursively-processed nested JARs, not from embedded
  * pom.properties in the uberjar itself.
  *
  * '''LLM Summary:''' This test suite checks metadata parity for
  * uberjar/assembly JARs. Each test copies the JAR and its companion POM to a
  * temp directory, processes them through the full Maven pipeline, and asserts
  * that every pURL the reference scanner found is present in the JAR's
  * `alias:from` connections.
  */
class MultiplePurlSuite extends FunSuite {

  test("JSON resource loads correctly") {
    val entries = MultiplePurlSuite.referenceEntries
    assert(
      entries.nonEmpty,
      "No entries loaded from metadata_multiple_purls.json"
    )
    assert(
      entries.length == 164,
      s"Expected 164 entries, got ${entries.length}"
    )
  }

  val loadedEntries = MultiplePurlSuite.referenceEntries

  // Filter to only uberjar/assembly JARs: must have companion POM and
  // must NOT contain nested .jar entries (which would make it a container
  // of JARs, not a shaded uberjar).
  val validEntries = loadedEntries.filter { entry =>
    val jarFile = File(entry.path)
    if (!jarFile.exists()) false
    else if (!MultiplePurlSuite.hasCompanionPom(jarFile)) false
    else if (MultiplePurlSuite.hasNestedJars(jarFile)) false
    else true
  }

  validEntries.foreach { entry =>
    test(s"Metadata parity: ${entry.name} (${entry.purls.size} pURLs)") {
      MavenTestHelpers.withTempDir(s"metadata-${entry.name}") { dir =>
        // Copy JAR and companion POM to temp directory
        val jarFile = File(entry.path)
        val pomFile = MultiplePurlSuite.companionPom(jarFile).get
        val destJar = new File(dir, jarFile.getName)
        val destPom = new File(dir, pomFile.getName)
        Files.copy(jarFile.toPath, destJar.toPath)
        Files.copy(pomFile.toPath, destPom.toPath)

        // Process the directory (JAR + POM together)
        val store = MavenTestHelpers.processDirectoryWithStore(dir)

        // Compute gitoid of the JAR to look up its Item
        val artifact = FileWrapper(destJar, destJar.getName, None)
        val (gitoid, _) = GitOIDUtils.computeAllHashes(artifact)

        // Read the Item and extract alias:from pURLs
        val itemOpt = store.read(gitoid)
        val goatRodeoPurls: Set[String] = itemOpt match {
          case Some(item) =>
            item.connections
              .filter(_._1 == EdgeType.aliasFrom)
              .map(_._2)
              .filter(_.startsWith("pkg:"))
              .toSet
          case None =>
            Set.empty
        }

        val referencePurls: Set[String] = entry.purls.toSet

        // Normalize pURLs by stripping query qualifiers (?packaging=sources,
        // ?classifier=javadoc, etc.) before comparison. Goat Rodeo emits
        // classifier qualifiers on sources/javadoc pURLs as a deliberate
        // design decision (ADR 0009), but the reference scanner does not
        // use qualifiers.
        def normalizePurl(p: String): String = {
          val queryIdx = p.indexOf('?')
          if (queryIdx >= 0) p.substring(0, queryIdx) else p
        }

        val normalizedGoatRodeo = goatRodeoPurls.map(normalizePurl)
        val normalizedReference = referencePurls.map(normalizePurl)

        // Check that every pURL the reference scanner found is also in
        // Goat Rodeo's alias:from connections. Goat Rodeo finding extra
        // pURLs is OK (superset is acceptable).
        val missing = normalizedReference -- normalizedGoatRodeo

        assert(
          missing.isEmpty,
          s"""Goat Rodeo is missing ${missing.size} of ${referencePurls.size} pURLs that the reference scanner found.
             |
             |JAR: ${entry.path}
             |Gitoid: ${gitoid}
             |Reference scanner found ${referencePurls.size} pURLs, Goat Rodeo found ${goatRodeoPurls.size} alias:from pURLs.
             |
             |Missing pURLs (in reference scanner but not in Goat Rodeo alias:from):
             |${missing.toList.sorted.map(p => s"  - $p").mkString("\n")}
             |
             |Goat Rodeo alias:from pURLs:
             |${goatRodeoPurls.toList.sorted.map(p => s"  - $p").mkString("\n")}
             |""".stripMargin
        )
      }
    }
  }
}

object MultiplePurlSuite {

  /** The reference entries loaded from the metadata ground truth JSON.
    *
    * Uses `MetadataGroundTruth.loadEntries` to read
    * `metadata_multiple_purls.json` — the same format as the other metadata
    * ground truth files.
    */
  val referenceEntries: Vector[MetadataGroundTruth.MetadataEntry] =
    MetadataGroundTruth.loadEntries("metadata_multiple_purls.json")

  /** Check if a JAR file has a companion .pom file. */
  def hasCompanionPom(jarFile: File): Boolean = {
    companionPom(jarFile).isDefined
  }

  /** Get the companion .pom file for a JAR, if it exists. */
  def companionPom(jarFile: File): Option[File] = {
    val basePath = jarFile.getAbsolutePath
    val pomPath = basePath
      .stripSuffix(".jar")
      .stripSuffix(".war")
      .stripSuffix(".ear")
      .stripSuffix(".hpi") + ".pom"
    val pomFile = File(pomPath)
    if (pomFile.exists()) Some(pomFile) else None
  }

  /** Check if a JAR file contains nested .jar entries (indicating it's a
    * container of JARs, not a shaded uberjar).
    */
  def hasNestedJars(jarFile: File): Boolean = {
    val zip = new ZipFile(jarFile)
    try {
      zip.entries().asScala.exists(_.getName.endsWith(".jar"))
    } finally {
      zip.close()
    }
  }
}
