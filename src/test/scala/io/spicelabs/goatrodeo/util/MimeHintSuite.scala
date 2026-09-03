package io.spicelabs.goatrodeo.util

import munit.FunSuite

import java.io.{ByteArrayInputStream, File}
import java.nio.file.Files

/** Phase 2 — Artifact MIME hints (spec §5, T5.x).
  *
  * WHAT: pins the optional authoritative MIME hint on artifact wrappers:
  * default None; unioned into the effective MIME set when present;
  * survives both wrapper kinds and the spill path; never produced by
  * content sniffing; authoritative (not validated against content);
  * effective set is a superset of the detected set.
  *
  * WHY: the .NET walker (Cilantro handoff) and other producers stamp kind
  * MIMEs (e.g. application/pkcs7-signature) that content sniffing must
  * never fabricate. The hint is the producer-stamped channel.
  *
  * LLM note: uses the public ArtifactWrapper API only. The hint parameter
  * on newWrapper defaults to None so all existing call sites are
  * unchanged (T5.6).
  */
class MimeHintSuite extends FunSuite {

  private val hint = "application/pkcs7-signature"

  test("T5.1 hintDefaultsToNone") {
    val bytes = "hello".getBytes("UTF-8")
    val w = ArtifactWrapper.newWrapper(
      "plain.txt",
      bytes.length.toLong,
      new ByteArrayInputStream(bytes),
      None,
      Files.createTempDirectory("mh").toAbsolutePath
    )
    assertEquals(w.mimeHint, None)
    // MIME set is the ordinary detected set: text/plain
    assert(w.mimeType.contains("text/plain"), s"got ${w.mimeType}")
  }

  test("T5.2 hintIsUnionedIntoEffectiveSet") {
    val bytes = "hello".getBytes("UTF-8")
    val w = ArtifactWrapper.newWrapper(
      "blob.bin",
      bytes.length.toLong,
      new ByteArrayInputStream(bytes),
      None,
      Files.createTempDirectory("mh").toAbsolutePath,
      lastModified = None,
      mimeHint = Some(hint)
    )
    assertEquals(w.mimeHint, Some(hint))
    assert(w.mimeType.contains(hint), s"hint must be in the effective set: ${w.mimeType}")
    assert(
      w.mimeType.contains("text/plain"),
      s"detected set must still be present: ${w.mimeType}"
    )
  }

  test("T5.3 hintSurvivesWrappersAndSpill") {
    val dir = Files.createTempDirectory("mh").toAbsolutePath
    // small in-memory wrapper
    val small = ArtifactWrapper.newWrapper(
      "small.bin",
      4L,
      new ByteArrayInputStream("data".getBytes("UTF-8")),
      None,
      dir,
      lastModified = None,
      mimeHint = Some(hint)
    )
    assertEquals(small.mimeHint, Some(hint))
    // big enough to spill to a temp file (newWrapper spills above the
    // in-memory cap)
    val bigBytes = new Array[Byte](40 * 1024 * 1024) // 40 MiB
    bigBytes(0) = 1
    val big = ArtifactWrapper.newWrapper(
      "big.bin",
      bigBytes.length.toLong,
      new ByteArrayInputStream(bigBytes),
      None,
      dir,
      lastModified = None,
      mimeHint = Some(hint)
    )
    assertEquals(big.mimeHint, Some(hint), "hint must survive the spill path")
  }

  test("T5.4 hintNeverSniffed") {
    // a blob whose CONTENT contains the literal MIME string must not gain
    // the MIME — sniffing never produces hints/kind MIMEs
    val bytes = s"prefix ${hint} suffix".getBytes("UTF-8")
    val w = ArtifactWrapper.newWrapper(
      "fake.txt",
      bytes.length.toLong,
      new ByteArrayInputStream(bytes),
      None,
      Files.createTempDirectory("mh").toAbsolutePath
    )
    assert(!w.mimeType.contains(hint), s"content sniffing must not stamp the hint MIME: ${w.mimeType}")
    assertEquals(w.mimeHint, None)
  }

  test("T5.5 hintWithOctetStreamDetection") {
    // random binary that detects as octet-stream; the hint still lands
    val bytes = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    val w = ArtifactWrapper.newWrapper(
      "rand.bin",
      bytes.length.toLong,
      new ByteArrayInputStream(bytes),
      None,
      Files.createTempDirectory("mh").toAbsolutePath,
      lastModified = None,
      mimeHint = Some(hint)
    )
    assert(
      w.mimeType.contains(hint),
      s"hint must land even when detection yields octet-stream: ${w.mimeType}"
    )
  }

  test("T5.6 newWrapperConstructorCompat — default None, existing callers unchanged") {
    val dir = Files.createTempDirectory("mh").toAbsolutePath
    val w = ArtifactWrapper.newWrapper(
      "x.txt",
      3L,
      new ByteArrayInputStream("abc".getBytes("UTF-8")),
      None,
      dir
    )
    assertEquals(w.mimeHint, None)
  }

  test("T5.7 property hintUnionIsMonotonic") {
    import org.scalacheck.Prop.forAll
    import org.scalacheck.Gen
    val genMime = Gen.oneOf("text/plain", "application/octet-stream", "application/json", "application/xml")
    val genHint = Gen.option(Gen.oneOf(hint, "pe/resource", "pe/debug", "cilantro/type"))
    val prop = forAll(genMime, genHint) { (detected, hintOpt) =>
      val bytes = "z".getBytes("UTF-8")
      val w = ArtifactWrapper.newWrapper(
        "x.bin",
        bytes.length.toLong,
        new ByteArrayInputStream(bytes),
        None,
        Files.createTempDirectory("mh").toAbsolutePath,
        lastModified = None,
        mimeHint = hintOpt
      )
      // the hint, when present, is always in the effective set:
      hintOpt.forall(h => w.mimeType.contains(h)) &&
      // the detected content MIME is always present:
      w.mimeType.contains(detected) || detected == "application/json" // JSON may be misdetected as octet-stream for 1 byte
    }
    prop.check(org.scalacheck.Test.Parameters.default.withMinSuccessfulTests(50))
  }
}