package io.spicelabs.goatrodeo.util

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Enforces the probe rules for MIME augmentation, mechanically.
  *
  * WHAT: every registered augmenter reads at most its declared
  * [[ProbeBudget.maxBytes]] of an artifact (one disk block, 4 KiB, unless the
  * augmenter declares an exception), and `withFile` cannot be called while
  * augmentation is running.
  *
  * WHY: augmentation runs for every artifact in the corpus — 84.9 million in
  * the 2026-10-09 run. A probe that reads a whole file, or that spills an
  * in-memory artifact to disk to obtain a `Path`, turns the fastest stage of
  * the pipeline into a disk-bound one. `withFile`'s own scaladoc calls it "VERY
  * expensive… ONLY if `withStream` will not suffice"; these tests hold the code
  * to that with byte counts and exceptions rather than a source scan.
  *
  * THEORY: `ArtifactWrapper.runAugmenter` creates the probe the chain would
  * create, runs the augmenter, and reports how many bytes were inspected. A
  * deliberately hostile 1 MiB artifact cannot be confused with a small one, so
  * an augmenter that slurps or scans shows up as an oversized `inspectedBytes`
  * — named by the augmenter id — instead of as a slow production run. The
  * budget is read from the augmenter itself, so a declared exception is visible
  * in one place and a new augmenter is held to one block by default.
  *
  * LLM note: PR-x = test id.
  */
class MimeAugmentationProbeRulesSuite extends GoatRodeoFunSuite {

  /** Every registered augmenter, in registration order. */
  private val augmenters: Vector[MimeAugmenter] = Vector(
    DotnetDetector.augmenter,
    SaffronDetector.augmenter,
    CryptoDetector.augmenter,
    OpenSSLConfigDetector.augmenter,
    JavaSecurityDetector.augmenter,
    JavaArchiveDetector.augmenter,
    CryptoContentDetector.augmenter,
    CarvedCertAugmenter.augmenter
  )

  private def hostileArtifact(): ArtifactWrapper = {
    val bytes = new Array[Byte](1024 * 1024)
    java.util.Arrays.fill(bytes, 'a'.toByte)
    val head = Array[Byte](0x30, 0x82.toByte, 0x01, 0x00)
    System.arraycopy(head, 0, bytes, 0, head.length)
    val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
      .getBytes("UTF-8")
    System.arraycopy(pem, 0, bytes, 16, pem.length)
    ByteWrapper(bytes, "probe.bin", None)
  }

  // PR-1 — no augmenter inspects more than its declared budget.
  test("PR-1 no augmenter inspects more than its declared probe budget") {
    val violations = augmenters.flatMap { augmenter =>
      Try(
        ArtifactWrapper.runAugmenter(
          augmenter,
          hostileArtifact(),
          Set("application/octet-stream")
        )
      ) match {
        case Success(out) if out.inspectedBytes <= augmenter.budget.maxBytes =>
          None
        case Success(out) =>
          Some(
            s"${augmenter.id} inspected ${out.inspectedBytes} bytes " +
              s"(declared max ${augmenter.budget.maxBytes})"
          )
        case Failure(e) => Some(s"${augmenter.id}: ${e.getMessage}")
      }
    }
    assertEquals(
      violations,
      Vector.empty[String],
      "augmenters exceeded their budgets"
    )
  }

  // PR-2 — withFile is refused outright while augmentation is running.
  test("PR-2 withFile is refused during MIME augmentation") {
    val w = hostileArtifact()
    val refused = Try(
      ArtifactWrapper.inAugmentScope {
        w.withFile(f => f.getName)
      }
    )
    assert(
      refused.isFailure,
      "withFile must not be callable during augmentation"
    )
    assert(
      refused.failed.toOption.exists(
        _.isInstanceOf[ArtifactWrapper.WithFileDuringMimeProbe]
      ),
      s"expected WithFileDuringMimeProbe, got $refused"
    )
  }

  // PR-3 — the default policy is one 4 KiB level and cannot be extended.
  test("PR-3 the default budget is one block and cannot extend") {
    val w = hostileArtifact()
    val out = ArtifactWrapper.runAugmenter(
      JavaArchiveDetector.augmenter,
      w,
      Set("application/octet-stream")
    )
    assertEquals(out.inspectedBytes, ArtifactWrapper.MaxMimeProbeBytes)
    assertEquals(JavaArchiveDetector.augmenter.budget.maxDoublings, 0)
  }

  // PR-4 — only CryptoDetector may *extend* mid-probe, and it does. Saffron is a
  // different case: it declares its full prefix up front rather than extending,
  // because SaffronProbe is designed around a 128 KiB head and a shorter read
  // cannot see the signatures it documents.
  test("PR-4 only CryptoDetector may extend, and it extends once to 8 KiB") {
    val w = hostileArtifact()
    val out = ArtifactWrapper.runAugmenter(
      CryptoDetector.augmenter,
      w,
      Set("application/octet-stream")
    )
    assertEquals(out.inspectedBytes, 8192)
    augmenters.filterNot(_.id == "CryptoDetector").foreach { augmenter =>
      assertEquals(
        augmenter.budget.maxDoublings,
        0,
        s"${augmenter.id} must not declare an extension"
      )
    }
  }

  // PR-5 — Saffron probes one block from the head and reads one block from the
  // tail separately. The ELF container type no longer depends on the head reaching
  // the end of a large binary: the carve augmenter claims it from the magic.
  test("PR-5 Saffron probes one block, and reads one block from the tail") {
    val w = hostileArtifact()
    val out = ArtifactWrapper.runAugmenter(
      SaffronDetector.augmenter,
      w,
      Set("application/octet-stream")
    )
    assertEquals(SaffronDetector.probePrefixBytes, MimeProbe.MaxBytes)
    assertEquals(SaffronDetector.probeSuffixBytes, MimeProbe.MaxBytes)
    assertEquals(out.inspectedBytes, MimeProbe.MaxBytes)
    assertEquals(SaffronDetector.augmenter.budget.maxDoublings, 0)
  }
}
