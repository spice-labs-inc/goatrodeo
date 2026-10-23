package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Tests for the whole-blob read budgets.
  *
  * WHAT: `Helpers.slurpInput` buffers a stream until EOF with no upper bound, so
  * any parser that treats the bytes as one complete structure can be handed an
  * arbitrarily large artifact. `Helpers.slurpInputUpTo` bounds the read and
  * refuses an over-budget stream (`None`) instead of returning a truncated
  * buffer, and the certificate parsers use it with per-parser budgets.
  *
  * WHY: the MIME test that routes an artifact to a certificate parser is a 4 KB
  * sniff. A multi-gigabyte container whose first 4 KB happens to contain
  * `-----BEGIN CERTIFICATE-----` is therefore "a certificate" as far as that
  * test is concerned, and the old code buffered the whole thing before trying to
  * parse it. The failure mode is an out-of-memory kill of the whole run, which
  * reports nothing useful.
  *
  * THEORY: the cap is only honest if the read stops at the boundary, so W-1
  * uses a stream that throws when read past `cap + 1` — a buffer-then-check
  * implementation cannot pass it. W-2 pins the boundary in both directions.
  * W-3 and W-5 pin the deliberate trade-off together: an over-budget artifact is
  * refused by the whole-buffer parser (`parseSingleCert`) but still yields its
  * certificates through the streaming parser (`parseBundle`), which is why
  * bounding the former loses nothing on a real corpus.
  *
  * LLM note: W-x = test id. Budget values asserted here are the constants in
  * `Certificates` (1 MiB for a single certificate).
  */
class WholeBlobReadBudgetSuite extends GoatRodeoFunSuite {

  /** An InputStream over `data` that fails if asked for a byte past `limit`.
    *
    * This is what makes W-1 substantive: a "read everything, then check the
    * size" implementation reads past the limit and fails the test, while a
    * bounded read never asks.
    */
  private final class GuardedStream(data: Array[Byte], limit: Int)
      extends InputStream {
    private var pos = 0
    private var served = 0

    override def read(): Int =
      if (pos >= data.length) -1
      else {
        if (served >= limit)
          throw new IllegalStateException(
            s"read past the budget: $served bytes already served, limit $limit"
          )
        served += 1
        val b = data(pos) & 0xff
        pos += 1
        b
      }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      if (pos >= data.length) -1
      else {
        val n = math.min(len, data.length - pos)
        if (served + n > limit)
          throw new IllegalStateException(
            s"read past the budget: $served + $n bytes, limit $limit"
          )
        System.arraycopy(data, pos, b, off, n)
        pos += n
        served += n
        n
      }
    }
  }

  private def bytesOf(n: Int, fill: Byte = 'x'.toByte): Array[Byte] = {
    val a = new Array[Byte](n)
    java.util.Arrays.fill(a, fill)
    a
  }

  // W-1 — an over-budget stream yields None and is never read past cap + 1.
  test("W-1 slurpInputUpTo refuses an over-budget stream without over-reading") {
    val cap = 64
    val data = bytesOf(4096, 'a'.toByte)
    val guarded = new GuardedStream(data, cap + 1)
    // No exception: the guarded stream throws as soon as the read goes past
    // cap + 1, so reaching the assertion at all is the proof of no over-read.
    val got = Helpers.slurpInputUpTo(guarded, cap)
    assertEquals(got, None, "a stream longer than the budget must be refused")
  }

  // W-2 — the boundary is exact in both directions.
  test("W-2 slurpInputUpTo accepts a stream that fits, including exactly at the cap") {
    val cap = 64
    val shorter = bytesOf(63, 'b'.toByte)
    val exact = bytesOf(64, 'c'.toByte)

    assertEquals(
      Helpers.slurpInputUpTo(new GuardedStream(shorter, cap + 1), cap)
        .map(_.length),
      Some(63)
    )
    assertEquals(
      Helpers.slurpInputUpTo(new GuardedStream(exact, cap + 1), cap)
        .map(_.length),
      Some(64),
      "a stream of exactly the budget is within it"
    )
    assertEquals(
      Helpers.slurpInputUpTo(new GuardedStream(exact, cap), cap)
        .map(new String(_, StandardCharsets.ISO_8859_1)),
      Some(new String(exact, StandardCharsets.ISO_8859_1)),
      "the accepted bytes must be the stream's bytes"
    )
  }

  /** A real, single-certificate PEM fixture, read whole.
    *
    * Read whole rather than sliced out of a bundle: a slice has to reproduce the
    * fixture's newlines exactly, and getting that wrong tests the slice instead
    * of the parser.
    */
  private def realCertPem(): Array[Byte] =
    Files.readAllBytes(
      new java.io.File(
        "test_data/certificates/x509/canonical/letsencrypt-isrgrootx1.pem"
      ).toPath
    )

  /** The cert followed by enough filler to exceed the 1 MiB single-cert budget. */
  private def overBudgetCert(): Array[Byte] = {
    val cert = realCertPem()
    val filler = bytesOf(2 * 1024 * 1024, ' '.toByte)
    val out = new Array[Byte](cert.length + filler.length)
    System.arraycopy(cert, 0, out, 0, cert.length)
    System.arraycopy(filler, 0, out, cert.length, filler.length)
    out
  }

  private def wrapper(bytes: Array[Byte], name: String): ArtifactWrapper =
    ByteWrapper(bytes, name, None)

  // W-3 — the whole-buffer parser refuses an over-budget artifact.
  test("W-3 parseSingleCert refuses an artifact larger than its budget") {
    val w = wrapper(overBudgetCert(), "root-ca.crt")
    assert(w.size() > 1024 * 1024, "fixture must exceed the 1 MiB budget")
    assertEquals(
      Certificates.parseSingleCert(w),
      None,
      "an over-budget artifact must be refused, not truncated and parsed"
    )
  }

  // W-4 — the same certificate under the budget still parses (no regression).
  test("W-4 parseSingleCert still parses a certificate that fits") {
    val w = wrapper(realCertPem(), "root-ca.crt")
    assert(
      Certificates.parseSingleCert(w).isDefined,
      "a certificate within the budget must still parse"
    )
  }

  // W-5 — the streaming parser is unaffected, which is why the bound is safe.
  test("W-5 parseBundle still finds the certificate in the over-budget artifact") {
    val w = wrapper(overBudgetCert(), "root-ca.crt")
    val bundle = Certificates.parseBundle(w)
    assert(
      bundle.isDefined,
      "the streaming parser must still recover the certificate"
    )
    assert(bundle.get.certs.nonEmpty, "the bundle should hold the certificate")
  }
}
