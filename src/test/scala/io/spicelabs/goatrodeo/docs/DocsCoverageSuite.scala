package io.spicelabs.goatrodeo.docs

import munit.FunSuite
import scala.util.Try

/** Phase 5 — docs-claim coverage (invariant 12; D-1).
  *
  * WHAT: every claim made in the phase documentation maps to a named test
  * that exists in the test tree. The mapping is a data structure in this
  * suite (claim → suite + test-method marker), checked for presence only —
  * the suite/class must be loadable and the mapping non-empty.
  *
  * WHY: the project invariant requires every doc claim to be verified by
  * a named test; the per-phase docs list the mappings, and this suite
  * keeps the mapping honest without grepping doc or source text.
  *
  * LLM note: presence-only by construction — the "tests exist" half is
  * enforced by class loading (each suite is referenced); the "claim maps
  * to it" half is the documented table in each phase doc, spot-checked
  * here via the listed suite names for the new features.
  */
class DocsCoverageSuite extends FunSuite {

  /** (document, feature, verifying suite class name) */
  private val mappings: List[(String, String, String)] = List(
    ("info/dependency_gate.md", "pinned resolution", "io.spicelabs.goatrodeo.ResolutionPinsSuite"),
    ("info/dependency_gate.md", "osv gate script behavior", "io.spicelabs.goatrodeo.OsvGateScriptSuite"),
    ("info/dependency_gate.md", "osv dump correctness", "io.spicelabs.goatrodeo.OsvDumpSuite"),
    ("info/dependency_gate.md", "independent osv ci job", "io.spicelabs.goatrodeo.OsvCiWiringSuite"),
    ("info/dependency_gate.md", "rpm payload streaming", "io.spicelabs.goatrodeo.util.RpmStreamingSuite"),
    ("info/mime_types.md", "mime hint semantics", "io.spicelabs.goatrodeo.util.MimeHintSuite"),
    ("info/mime_types.md", "pkcs7 claim semantics", "io.spicelabs.goatrodeo.omnibor.strategies.CertificatesPkcs7Suite"),
    ("info/cbom_emitter.md", "pkcs7 certs in cbom", "io.spicelabs.goatrodeo.omnibor.Pkcs7CbomSuite"),
    ("info/cbom_emitter.md", "single cert strategy entry", "io.spicelabs.goatrodeo.omnibor.SingleCertificatesStrategySuite"),
    ("info/git_provenance.md", "git provenance capture", "io.spicelabs.goatrodeo.util.GitRunInfoSuite"),
    ("info/git_provenance.md", "tagged-run provenance integration", "io.spicelabs.goatrodeo.omnibor.GitTaggedRunIntegrationSuite"),
    ("info/git_provenance.md", "git provenance excluded from cbom", "io.spicelabs.goatrodeo.omnibor.GitProvenanceNotInCbomSuite"),
    ("info/append_only_graph.md", "grd eof semantics", "GrdEofSuite"),
    ("info/cbom_emitter.md", "user-ready tolerance", "io.spicelabs.goatrodeo.util.UserReadyToleranceSuite")
  )

  test("D-1 every phase-4/5 doc claim maps to a loadable suite") {
    mappings.foreach { case (doc, feature, suite) =>
      val loaded = Try(Class.forName(suite)).toOption.isDefined
      assert(loaded, s"$doc: claim '$feature' maps to suite $suite which must exist")
    }
  }

  test("D-2 the mapping table is non-empty and unique per (doc, feature)") {
    assert(mappings.nonEmpty)
    val dupes = mappings.groupBy(m => (m._1, m._2)).collect { case (k, v) if v.size > 1 => k }
    assertEquals(dupes, Map.empty)
  }
}