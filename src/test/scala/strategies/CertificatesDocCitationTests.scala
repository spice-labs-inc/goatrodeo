/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import munit.FunSuite

import java.io.File
import scala.io.Source

/** Phase 9 — mechanical claims-citation + internal-link checker.
  *
  * Per the Phase 9 plan §"Acceptance":
  *
  * > - All doc updates land
  * > - Internal links are valid (no 404 links)
  * > - ADRs exist with both human and LLM sections per invariant #14
  * > - Every claim in `info/certificates_strategy.md` and its `_llm`
  * >   copy cites a named test
  *
  * And §"Phase Exit Review (HS-2)":
  *
  * > Claims-verification (step 2) is especially important for this
  * > phase: read every documentation claim, identify its referenced
  * > test, run that test, confirm the test actually tests what the
  * > claim says — not just that a test with that name exists.
  *
  * This suite is the MECHANICAL portion of that requirement: it
  * verifies test-name references exist as compilable test names AND
  * internal markdown links resolve to real files. The semantic
  * verification ("does the test actually test the claim?") still
  * needs human reading — but a missing test name or 404 link is
  * caught here.
  */
class CertificatesDocCitationTests extends FunSuite {

  // ===== Walk all known test files to collect declared test names =====

  private val testDirs = Vector(
    new File("src/test/scala/strategies"),
  )

  /** Extract every `test("name") {` and `property("name") {`
    * declaration from a Scala test file. */
  private def extractTestNames(file: File): Set[String] = {
    val content = Source.fromFile(file, "UTF-8").mkString
    val rx = """(?:test|property)\(\s*"([^"]+)"""".r
    rx.findAllMatchIn(content).map(_.group(1).nn).toSet
  }

  private val allTestNames: Set[String] = {
    val files = testDirs.flatMap { d =>
      if (d.exists() && d.isDirectory)
        d.listFiles.toVector.filter(_.getName.endsWith(".scala"))
      else Vector.empty
    }
    files.flatMap(extractTestNames).toSet
  }

  // ===== Walk certificates_strategy.md + _llm extracting test references =====

  /** Test-name patterns the Phase 9 doc uses for citations:
    *
    *   - `\`SuiteName::test name\``  (Certificates docs format)
    *   - `\`SuiteName.test_name\``  (alternate format used in some Phase-0 tests)
    *   - `\`[PROP] ...\`` / `\`[LEAK SWEEP] ...\`` (bare test-name with bracketed tag)
    */
  private def extractTestRefs(content: String): Set[String] = {
    // Pattern 1: `Suite::test name` — extract test name AFTER "::"
    val rxScopedTest = """`([A-Za-z][A-Za-z0-9_]*(?:Suite|Tests))::([^`]+)`""".r
    val scopedTestNames = rxScopedTest.findAllMatchIn(content).map(_.group(2).nn.trim).toSet

    // Pattern 2: `[TAG] ...` — bracketed-tag bare test names (without Suite:: prefix)
    val rxBareBracketed = """`(\[[A-Z][A-Z0-9 -]+\][^`]+)`""".r
    val bareBracketedNames = rxBareBracketed.findAllMatchIn(content).map(_.group(1).nn.trim).toSet

    scopedTestNames ++ bareBracketedNames
  }

  // ===== Internal-link validation =====

  /** Markdown link pattern: `[text](path)` or `[text](path#anchor)`. */
  private def extractLinks(content: String): Vector[(String, String)] = {
    val rx = """\[([^\]]+)\]\(([^)]+)\)""".r
    rx.findAllMatchIn(content).map { m =>
      (m.group(1).nn, m.group(2).nn)
    }.toVector
  }

  private def isInternalLink(url: String): Boolean =
    !url.startsWith("http://") && !url.startsWith("https://") &&
      !url.startsWith("mailto:")

  private def stripAnchor(path: String): String = {
    val hash = path.indexOf('#')
    if (hash >= 0) path.substring(0, hash) else path
  }

  /** Resolve a link relative to the doc that contains it. */
  private def resolveLink(docFile: File, link: String): File = {
    val cleanLink = stripAnchor(link)
    new File(docFile.getParentFile, cleanLink)
  }

  // ===== Tests =====

  test("[DOC CITATION] test-name pool is non-empty (sanity check)") {
    assert(allTestNames.nonEmpty,
      "expected to extract at least one test name from src/test/scala/strategies")
    // Spot-check: known test names should be present.
    assert(allTestNames.contains("[LEAK SWEEP] zero forbidden-pattern matches across the entire corpus"),
      "expected canonical leak-sweep test name in the pool")
  }

  test("[DOC CITATION] every test-name reference in certificates_strategy.md exists in the test pool") {
    val docFile = new File("info/certificates_strategy.md")
    assert(docFile.exists(), s"missing doc: ${docFile.getPath}")
    val content = Source.fromFile(docFile, "UTF-8").mkString
    val refs = extractTestRefs(content)
    val missing = refs.filterNot { ref =>
      // Strict match OR substring match (the doc may cite "...openssl SPKI ground truth"
      // which is a suffix of the full test name).
      allTestNames.exists(name =>
        name == ref || name.contains(ref) || ref.contains(name))
    }
    assert(missing.isEmpty,
      s"certificates_strategy.md references ${missing.size} test names not " +
      s"found in src/test/scala/strategies/*.scala test() declarations:\n  " +
      missing.toVector.sorted.mkString("\n  "))
  }

  test("[DOC CITATION] every test-name reference in certificates_strategy_llm.md exists in the test pool") {
    val docFile = new File("info/certificates_strategy_llm.md")
    assert(docFile.exists(), s"missing doc: ${docFile.getPath}")
    val content = Source.fromFile(docFile, "UTF-8").mkString
    val refs = extractTestRefs(content)
    val missing = refs.filterNot { ref =>
      allTestNames.exists(name =>
        name == ref || name.contains(ref) || ref.contains(name))
    }
    assert(missing.isEmpty,
      s"certificates_strategy_llm.md references ${missing.size} test names not " +
      s"found in test pool:\n  " + missing.toVector.sorted.mkString("\n  "))
  }

  test("[DOC LINKS] every internal link in certificates_strategy.md resolves") {
    val docFile = new File("info/certificates_strategy.md")
    val content = Source.fromFile(docFile, "UTF-8").mkString
    val links = extractLinks(content).filter { case (_, url) => isInternalLink(url) }
    val broken = links.filterNot { case (_, url) =>
      val target = resolveLink(docFile, url)
      target.exists()
    }
    assert(broken.isEmpty,
      s"broken internal links in certificates_strategy.md:\n  " +
      broken.map { case (text, url) => s"[$text]($url)" }.mkString("\n  "))
  }

  test("[DOC LINKS] every internal link in certificates_strategy_llm.md resolves") {
    val docFile = new File("info/certificates_strategy_llm.md")
    val content = Source.fromFile(docFile, "UTF-8").mkString
    val links = extractLinks(content).filter { case (_, url) => isInternalLink(url) }
    val broken = links.filterNot { case (_, url) =>
      val target = resolveLink(docFile, url)
      target.exists()
    }
    assert(broken.isEmpty,
      s"broken internal links in certificates_strategy_llm.md:\n  " +
      broken.map { case (text, url) => s"[$text]($url)" }.mkString("\n  "))
  }

  test("[ADR] every ADR has both a human and an _llm parallel file") {
    val adrDir = new File("info/adrs")
    val all = adrDir.listFiles.toVector.filter(_.getName.endsWith(".md"))
    val byBase = all.groupBy { f =>
      f.getName.stripSuffix(".md").stripSuffix("_llm")
    }
    val missing = byBase.toVector.flatMap { case (base, files) =>
      val hasHuman = files.exists(f => !f.getName.endsWith("_llm.md"))
      val hasLlm = files.exists(_.getName.endsWith("_llm.md"))
      if (hasHuman && hasLlm) None
      else Some(s"$base (human=$hasHuman, llm=$hasLlm)")
    }
    assert(missing.isEmpty,
      s"ADRs missing human or LLM parallel:\n  " + missing.mkString("\n  "))
  }

  test("[ADR] required Phase 9 ADRs (4-7) all exist with both human + LLM copies") {
    val adrDir = new File("info/adrs")
    val required = Vector(
      "adr-004-metadata-key-separator",
      "adr-005-keystore-flat-item",
      "adr-006-encrypted-stays-opaque",
      "adr-007-java-17-to-21",
    )
    required.foreach { base =>
      val human = new File(adrDir, s"$base.md")
      val llm = new File(adrDir, s"${base}_llm.md")
      assert(human.exists(), s"missing required ADR: $human")
      assert(llm.exists(), s"missing required ADR LLM parallel: $llm")
    }
  }

  test("[DOC LINKS] info/README.md updates point at certificates_strategy.md") {
    val readme = new File("info/README.md")
    val content = Source.fromFile(readme, "UTF-8").mkString
    assert(content.contains("certificates_strategy.md"),
      "info/README.md must reference certificates_strategy.md (Phase 9 plan §README update)")
  }

  test("[DOC LINKS] info/architecture.md mentions Certificates strategy") {
    val arch = new File("info/architecture.md")
    val content = Source.fromFile(arch, "UTF-8").mkString
    assert(content.contains("Certificates"),
      "info/architecture.md must mention the Certificates strategy (Phase 9 plan §architecture update)")
    assert(content.contains("Certificates.computeCertificateFiles"),
      "info/architecture.md must show Certificates.computeCertificateFiles in the strategies Vector")
  }

  test("[DOC LINKS] info/goat_rodeo_operation.md mentions Certificates strategy") {
    val ops = new File("info/goat_rodeo_operation.md")
    val content = Source.fromFile(ops, "UTF-8").mkString
    assert(content.contains("Certificates.computeCertificateFiles"),
      "info/goat_rodeo_operation.md must show Certificates.computeCertificateFiles in the Vector")
  }

  test("[DOC LINKS] info/mime_types.md has the CryptoDetector augmentation table") {
    val mimes = new File("info/mime_types.md")
    val content = Source.fromFile(mimes, "UTF-8").mkString
    assert(content.contains("CryptoDetector"),
      "info/mime_types.md must mention CryptoDetector (Phase 9 plan §mime_types update)")
    assert(content.contains("application/pgp-keys"),
      "info/mime_types.md must list the CryptoDetector signature inventory")
    assert(content.contains("Pure-addition"),
      "info/mime_types.md must explain pure-addition design vs SaffronDetector's text-strip behavior")
  }
}
