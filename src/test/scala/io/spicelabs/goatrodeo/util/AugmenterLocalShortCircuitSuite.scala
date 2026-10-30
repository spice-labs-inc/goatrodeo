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

package io.spicelabs.goatrodeo.util
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tests for the per-augmenter short-circuit contract.
  *
  * WHAT: every MIME augmenter decides for itself whether the artifact's MIME
  * set makes its own work impossible, and the chain asks each augmenter's own
  * rule — there is no shared or global gate that silences the chain on behalf
  * of all of them.
  *
  * WHY: a MIME type is an additive claim about what an artifact is, not a
  * verdict about what it can never be. A jar is also a zip, also a container
  * that is walked, and its bytes can also hold a stored certificate or a PEM
  * block. A gate that decides "nothing more can be found" from one MIME type
  * therefore turns a performance shortcut into silent false negatives: an
  * artifact that one augmenter provably cannot use is often exactly the
  * artifact another augmenter exists to find.
  *
  * THEORY: the contract has two halves, and these tests pin both.
  *
  *   - LOCALLY BLOCKED: when an augmenter's own rule rejects the MIME set, that
  *     augmenter must not run — no probe, no evidence, no MIME of its own.
  *   - NOT GLOBALLY BLOCKED: the same MIME set must leave every augmenter whose
  *     own rule admits it still running, so a media type or an archive type can
  *     never silence the whole chain.
  *
  * Requirements: "an augmenter short-circuits on an augmenter-by-augmenter
  * basis when there is no way it can handle the existing MIME types; this is
  * local to each augmenter, never global."
  *
  * LLM note: test ids are LR-n. Each test states the claim, the artifact used
  * to falsify it, and the direction (blocked vs still-running) it pins.
  */
class AugmenterLocalShortCircuitSuite extends GoatRodeoFunSuite {

  /** The registered augmenters, listed explicitly so a test can ask each one's
    * own rule rather than any aggregate policy.
    */
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

  /** A PEM certificate block, the marker `CryptoContentDetector` looks for. */
  private val pemBlock: String =
    "-----BEGIN CERTIFICATE-----\n" +
      "MIIBszCCARygAwIBAgIUY7n3Z0ZQ0Z0Z0Z0Z0Z0Z0Z0Z0Z0wDQYJKoZIhvcNAQEL\n" +
      "-----END CERTIFICATE-----\n"

  /** A line the Java security detector keys on. */
  private val javaSecurityLine: String =
    "jdk.tls.disabledAlgorithms=SSLv3, RC4\n"

  /** Builds a zip with one STORED (uncompressed) entry, so the entry's bytes
    * appear literally in the archive's own bytes.
    */
  private def storedZip(entryName: String, body: String): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val zip = new ZipOutputStream(out)
    try {
      val entry = new ZipEntry(entryName)
      entry.setMethod(ZipEntry.STORED)
      val bytes = body.getBytes(StandardCharsets.ISO_8859_1)
      entry.setSize(bytes.length.toLong)
      entry.setCompressedSize(bytes.length.toLong)
      val crc = new java.util.zip.CRC32()
      crc.update(bytes)
      entry.setCrc(crc.getValue)
      zip.putNextEntry(entry)
      zip.write(bytes)
      zip.closeEntry()
    } finally zip.close()
    out.toByteArray
  }

  private def iso(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.ISO_8859_1)

  // LR-1 — the short-circuit is per augmenter, not a shared or global gate. Two
  // artifacts make the point: a JAR, which some augmenters refuse and one
  // accepts, and text, which different augmenters accept and refuse in different
  // combinations. If a shared list existed, every augmenter would agree.
  //
  // NOTE: the earlier form of this test asserted that every MIME family had at
  // least one admitting augmenter. That belonged to the block-list-only design;
  // under the two-sided contract a family that no consumer can use (fonts,
  // sqlite databases) is correctly refused by everyone, which
  // `AugmenterAdmissionSuite.AA-10` pins. What this test still pins is that the
  // decisions are made one augmenter at a time.
  test("LR-1 refusals are decided per augmenter, never by a shared list") {
    val jar = Set("application/java-archive")
    val jarRefusers =
      augmenters.filterNot(_.rule(jar, "payload.jar")).map(_.id).toSet
    val jarAdmitters =
      augmenters.filter(_.rule(jar, "payload.jar")).map(_.id).toSet
    assert(jarAdmitters.nonEmpty, "a JAR must be usable by the zip augmenter")
    assert(
      jarRefusers.nonEmpty,
      "a JAR must be refused by the augmenters that cannot use archives"
    )
    assert(
      jarRefusers.intersect(jarAdmitters).isEmpty,
      "no augmenter may both refuse and admit the same MIME set"
    )

    val text = Set("text/plain")
    val textAdmitters =
      augmenters.filter(_.rule(text, "notes.txt")).map(_.id).toSet
    val textRefusers =
      augmenters.filterNot(_.rule(text, "notes.txt")).map(_.id).toSet
    assert(textAdmitters.nonEmpty, "text must be usable by the text augmenters")
    assert(
      textRefusers.nonEmpty,
      "text must be refused by the binary-only augmenters"
    )
    assert(
      textAdmitters.intersect(textRefusers).isEmpty,
      "no augmenter may both refuse and admit the same MIME set"
    )
    assert(
      textAdmitters != jarAdmitters,
      "different artifacts must produce different admitting sets, or the " +
        "decision would not be per augmenter"
    )

    // Class files: every augmenter finds its own work impossible there, and each
    // says so in its own rule. Eight local decisions, visible rather than hidden.
    augmenters.foreach { a =>
      assert(
        !a.rule(Set("application/java-vm"), "Foo.class"),
        s"${a.id} should find class files impossible for its own reasons"
      )
    }
  }

  // LR-2 — an artifact whose MIME one augmenter blocks still reaches the
  // augmenters that admit it, and a container is not itself claimed for its
  // contents: the JAR is expanded into children and the stored file carries the
  // claim. Both halves follow from the shared MimeFamilies pairs.
  test(
    "LR-2 a jar is refused by content consumers and still claims its own type"
  ) {
    val jarBytes = storedZip("payload.txt", javaSecurityLine + pemBlock)
    val jar = ByteWrapper(jarBytes, "payload.jar", None)
    assert(
      jar.mimeType.contains("application/java-archive"),
      s"the fixture must be typed as a jar, got ${jar.mimeType}"
    )
    // Locally blocked: JavaSecurityDetector's rule refuses archives.
    assert(
      !jar.mimeType.contains(JavaSecurityDetector.JavaSecurityMimeType),
      s"a jar must not be probed for java.security properties, got ${jar.mimeType}"
    )
    // Not globally blocked: the archive consumer still claims it, which is what
    // makes the walker expand it into the child that carries the PEM claim.
    assert(
      JavaArchiveDetector.mimeRule(jar.mimeType),
      "a jar must still be claimable by the archive consumer"
    )
    assert(
      !CryptoContentDetector.mimeRule(jar.mimeType),
      "a jar's stored bytes are the child's claim, not the archive's"
    )
  }

  // LR-3 — the same bytes, in a container the rule admits, do fire the blocked
  // augmenter. This isolates the cause of LR-2's absence to the rule and not to
  // the detector failing on the content.
  test(
    "LR-3 the same payload in a plain text artifact fires the blocked augmenter"
  ) {
    val text =
      ByteWrapper(iso(javaSecurityLine + pemBlock), "java.security", None)
    assert(
      text.mimeType.contains(JavaSecurityDetector.JavaSecurityMimeType),
      s"a plain settings file must be detected, got ${text.mimeType}"
    )
    assert(
      text.mimeType.contains(CryptoContentDetector.EmbeddedPemMime),
      s"the PEM in the same file must be detected, got ${text.mimeType}"
    )
  }

  // LR-4 — claims are additive: one artifact can carry several augmenters'
  // MIMEs at once, so no augmenter's claim marks the artifact as finished.
  test("LR-4 one artifact carries several augmenters' claims at once") {
    val both = ByteWrapper(
      iso(javaSecurityLine + pemBlock + "openssl_conf = default_conf\n"),
      "combined.conf",
      None
    )
    val claims = Vector(
      JavaSecurityDetector.JavaSecurityMimeType,
      CryptoContentDetector.EmbeddedPemMime,
      OpenSSLConfigDetector.OpenSSLConfigMimeType
    ).filter(both.mimeType.contains)
    assert(
      claims.length >= 2,
      s"expected several augmenter claims on one artifact, got ${both.mimeType}"
    )
  }
}
