package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.omnibor.strategies.Certificates
import io.spicelabs.goatrodeo.util.ByteWrapper
import munit.FunSuite

/** Phase 2 — single Certificates strategy entry (spec §4, user decision 3;
  * T6.8).
  *
  * WHAT: exactly ONE non-terminal dispatch function claims the
  * pkcs7-signature MIME (the Certificates claim). The terminal catch-all
  * (GenericFile, last in the dispatch) necessarily receives every
  * unclaimed artifact — it is not a cert claimer and is excluded by
  * position (it is the last entry).
  *
  * WHY: user decision — one strategy entry, one place to look for cert
  * code. A future second registration stealing the claim fails this test.
  *
  * LLM note: behavioral — runs each dispatch function against a
  * pkcs7-hinted artifact and counts the non-terminal claimers. Dispatch
  * positions are pinned by construction (GenericFile is always last).
  */
class SingleCertificatesStrategySuite extends FunSuite {

  private def nonTerminalClaimers(hint: String): Vector[Int] = {
    val wrapper = ByteWrapper("x".getBytes("UTF-8"), "b.p7b", None, mimeHint = Some(hint))
    val byUUID: Map[String, io.spicelabs.goatrodeo.util.ArtifactWrapper] =
      Map(wrapper.uuid -> wrapper)
    val byName: Map[String, Vector[io.spicelabs.goatrodeo.util.ArtifactWrapper]] =
      Map(wrapper.path() -> Vector(wrapper))
    val total = ToProcess.computeToProcess.size
    // GenericFile is always the last entry (see ToProcess); exclude it.
    ToProcess.computeToProcess.zipWithIndex.flatMap { case (fn, idx) =>
      val (toProcess, _, _, _) = fn(byUUID, byName)
      if (toProcess.nonEmpty && idx != total - 1) Some(idx) else None
    }
  }

  test("T6.8 exactly one non-terminal strategy claims pkcs7-signature") {
    val claimers = nonTerminalClaimers(Certificates.CertPkcs7Mime)
    assertEquals(
      claimers,
      Vector(6),
      s"exactly one strategy (Certificates at position 6) may claim pkcs7-signature; got $claimers"
    )
  }

  test("T6.8b the same single claimer claims the pem-bundle MIME") {
    // sanity: the single Certificates claimer also handles the existing
    // pem-bundle family (no second claimer exists for it either)
    val claimers = nonTerminalClaimers("application/x-pem-bundle")
    assertEquals(claimers, Vector(6), s"pem-bundle claimed by exactly Certificates; got $claimers")
  }
}