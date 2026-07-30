/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package strategies

import java.io.File

/** Enumerates fixtures under a corpus root and surfaces counts, pairings, and
  * orphan detection.
  *
  * ## LLM-friendly summary
  *
  * A "fixture" is any file under the root that is not a sidecar, not a
  * `SOURCES.md`, not a `README.md` / `README_llm.md`, not a `generate.sh`, not
  * a `.gitkeep`, not hidden, and not inside the `tools/` subtree.
  *
  * A "sidecar" is any file ending in `.expected.json`.
  *
  * The pairing rule: a sidecar named `foo.pem.expected.json` must sit alongside
  * a fixture named `foo.pem`. The fixture's name is the sidecar's name with
  * `.expected.json` stripped.
  *
  * This class is a pure function of its root path — tests can instantiate it
  * against a scratch temp directory. The default singleton
  * [[CertificatesFixtureInventory]] binds it to `test_data/certificates`, which
  * is what production suites use.
  */
class CertificatesFixtureInventoryImpl(val corpusRoot: File) {

  import CertificatesFixtureInventoryImpl.*

  /** A (fixture file, sidecar file) pair — both are real files on disk. */
  final case class FixturePair(fixture: File, sidecar: File, category: String)

  private def allFiles(dir: File): Vector[File] = {
    if (!dir.exists() || !dir.isDirectory()) Vector.empty
    else {
      val kids =
        Option(dir.listFiles()).map(_.toVector).getOrElse(Vector.empty)
      kids.flatMap { f =>
        if (f == null) Vector.empty
        else if (f.getName.startsWith(".")) Vector.empty
        else if (f.isDirectory()) allFiles(f)
        else Vector(f)
      }
    }
  }

  private def isUnderInfrastructureDir(f: File): Boolean = {
    val rootPath = corpusRoot.getAbsolutePath
    val p = f.getAbsolutePath
    if (!p.startsWith(rootPath)) false
    else {
      val rel = p.substring(rootPath.length).stripPrefix("/").stripPrefix("\\")
      val firstSeg = rel.split("[/\\\\]").headOption.getOrElse("")
      infrastructureDirs.contains(firstSeg)
    }
  }

  private def categoryOf(f: File): String = {
    val rootPath = corpusRoot.getAbsolutePath
    val p = f.getAbsolutePath
    if (!p.startsWith(rootPath)) "<root>"
    else {
      val rel = p.substring(rootPath.length).stripPrefix("/").stripPrefix("\\")
      val segs = rel.split("[/\\\\]").toVector
      if (segs.length <= 1) "<root>" else segs.head
    }
  }

  private def isSidecar(f: File): Boolean =
    f.getName.endsWith(sidecarSuffix)

  private def isFixtureCandidate(f: File): Boolean = {
    val name = f.getName
    !isSidecar(f) &&
    !infrastructureNames.contains(name) &&
    !isUnderInfrastructureDir(f)
  }

  private def fixtureNameForSidecar(sidecar: File): String = {
    val sName = sidecar.getName
    sName.substring(0, sName.length - sidecarSuffix.length)
  }

  /** All discovered sidecars (files ending in `.expected.json`). */
  def allSidecars: Vector[File] =
    allFiles(corpusRoot).filter(isSidecar)

  /** All discovered fixture-candidate files (non-sidecar, non-infrastructure).
    */
  def allFixtureCandidates: Vector[File] =
    allFiles(corpusRoot).filter(isFixtureCandidate)

  /** Pairs where both a fixture and its sidecar exist in the same directory. */
  def pairs: Vector[FixturePair] = {
    val candidatesByDir: Map[String, Vector[File]] =
      allFixtureCandidates.groupBy(f => f.getParentFile.getAbsolutePath)
    allSidecars.flatMap { side =>
      val dirPath = side.getParentFile.getAbsolutePath
      val wantName = fixtureNameForSidecar(side)
      candidatesByDir
        .getOrElse(dirPath, Vector.empty)
        .find(_.getName == wantName)
        .map(fx => FixturePair(fx, side, categoryOf(fx)))
    }
  }

  /** Sidecars that have no matching fixture next to them. */
  def orphanSidecars: Vector[File] = {
    val matched = pairs.map(_.sidecar).toSet
    allSidecars.filterNot(matched.contains)
  }

  /** Fixture candidates that have no matching sidecar next to them. */
  def orphanFixtures: Vector[File] = {
    val matched = pairs.map(_.fixture).toSet
    allFixtureCandidates.filterNot(matched.contains)
  }

  /** Total paired fixtures — checked against the 200-fixture floor. */
  def totalCount: Int = pairs.size

  /** Counts of paired fixtures by category (immediate subdirectory). */
  def countByCategory: Map[String, Int] =
    pairs.groupBy(_.category).view.mapValues(_.size).toMap
}

/** Shared constants for [[CertificatesFixtureInventoryImpl]]. */
object CertificatesFixtureInventoryImpl {

  /** Files that are infrastructure, not fixtures. Matched by exact basename. */
  val infrastructureNames: Set[String] =
    Set("SOURCES.md", "README.md", "README_llm.md", ".gitkeep", "generate.sh")

  /** Extension marking a sidecar. */
  val sidecarSuffix: String = ".expected.json"

  /** Relative paths (from a corpus root) that are infrastructure trees rather
    * than category trees.
    */
  val infrastructureDirs: Set[String] = Set("tools")
}

/** Default singleton — binds [[CertificatesFixtureInventoryImpl]] to the
  * production corpus root at `test_data/certificates/`.
  *
  * All production test suites reference this object. Unit tests that exercise
  * the discovery logic instantiate [[CertificatesFixtureInventoryImpl]] against
  * a scratch directory instead.
  */
object CertificatesFixtureInventory
    extends CertificatesFixtureInventoryImpl(new File("test_data/certificates"))
