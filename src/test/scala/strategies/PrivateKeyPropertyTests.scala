/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** ScalaCheck properties over the Phase-7 corpus.
  *
  * ## What these tests test
  *
  * Phase 7's hard rules apply uniformly across every private-key fixture. These
  * properties pin those uniform invariants so a new fixture added later is
  * automatically covered:
  *
  *   1. *Parse idempotence* — same fixture parsed twice yields the same
  *      `ClaimedContent` (same envelope variant, same ADT sub-type for
  *      plaintext, same SPKI bytes / wire bytes). 2. *Encrypted → no pURL* —
  *      every fixture that classifies as `PrivateKeyEncrypted` produces zero
  *      pURLs through `getPurls`. Encrypted keys produce zero pURLs and no
  *      SPKI. 3. *Unencrypted → exactly one pURL* — every fixture that
  *      classifies as `PrivateKeyPlaintextPem` or `PrivateKeyPlaintextOpenSsh`
  *      produces exactly one pURL. 4. *Leak-sweep cleanliness* — `getMetadata`
  *      on every fixture runs without raising the leak-guard exception, i.e.,
  *      no forbidden pattern AND no long-hex run on a non-allowlisted key. 5.
  *      *Encrypted metadata never leaks key-derived fields* — for every
  *      encrypted fixture, the emitted metadata MUST NOT contain
  *      `Certificates:KeyAlgorithm`, `Certificates:KeySize`,
  *      `Certificates:Curve`, `Certificates:SpkiSha256`, or
  *      `Certificates:SshFingerprintSha256`.
  *
  * ## Why this matters (HS-3)
  *
  * No fixture in this phase triggers a password prompt, a decryption attempt,
  * or a log message about encryption status beyond what's in the metadata."
  * These properties pin the uniform parts of that contract.
  */
class PrivateKeyPropertyTests extends ScalaCheckSuite {

  private val pkFixtures: Seq[File] = {
    val root = Paths.get("test_data/certificates/private-keys")
    if (!Files.exists(root)) Seq.empty
    else {
      import scala.jdk.CollectionConverters.*
      Files
        .walk(root)
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p))
        .filter(p => !p.toString.endsWith(".expected.json"))
        .filter(p => !p.toString.endsWith("/generate.sh"))
        .filter(p => !p.toString.endsWith("/SOURCES.md"))
        .map(_.toFile)
        .toSeq
    }
  }

  private val genFixture: Gen[File] =
    if (pkFixtures.isEmpty) Gen.fail else Gen.oneOf(pkFixtures)

  private def wrap(f: File): FileWrapper =
    FileWrapper(f, f.getName, None)

  private def stubItem(): Item = Item(
    identifier = "gitoid:blob:sha256:phase7-prop-stub",
    connections = TreeSet.empty,
    bodyMimeType = Some(ItemMetaData.mimeType),
    body = Some(
      ItemMetaData(
        fileNames = TreeSet.empty,
        mimeType = TreeSet.empty,
        fileSize = 0L,
        extra = TreeMap.empty
      )
    )
  )

  property(
    "[PROP] parse idempotence: every fixture yields the same claim variant twice"
  ) {
    forAll(genFixture) { f =>
      val a = Certificates.classifyAndParse(wrap(f))
      val b = Certificates.classifyAndParse(wrap(f))
      (a, b) match {
        case (None, None) => true
        case (
              Some(ax: Certificates.PrivateKeyPlaintextPem),
              Some(bx: Certificates.PrivateKeyPlaintextPem)
            ) =>
          ax.canonicalAlg == bx.canonicalAlg &&
          ax.keySize == bx.keySize &&
          ax.curve == bx.curve &&
          ax.params == bx.params &&
          java.util.Arrays.equals(ax.spkiBytes, bx.spkiBytes)
        case (
              Some(ax: Certificates.PrivateKeyPlaintextOpenSsh),
              Some(bx: Certificates.PrivateKeyPlaintextOpenSsh)
            ) =>
          ax.algName == bx.algName &&
          ax.rsaModulusBits == bx.rsaModulusBits &&
          java.util.Arrays.equals(ax.wireBytes, bx.wireBytes)
        case (
              Some(ax: Certificates.PrivateKeyPlaintextPgp),
              Some(bx: Certificates.PrivateKeyPlaintextPgp)
            ) =>
          ax.ring.keys.length == bx.ring.keys.length &&
          ax.ring.keys.zip(bx.ring.keys).forall { case (ka, kb) =>
            ka.fingerprintHex == kb.fingerprintHex &&
            ka.canonicalAlg == kb.canonicalAlg &&
            ka.curve == kb.curve &&
            ka.keySize == kb.keySize &&
            ka.version == kb.version
          }
        case (
              Some(ax: Certificates.PrivateKeyEncrypted),
              Some(bx: Certificates.PrivateKeyEncrypted)
            ) =>
          ax == bx
        case _ => false
      }
    }
  }

  property(
    "[PROP] encrypted private keys produce zero pURLs (Phase 7 hard rule)"
  ) {
    forAll(genFixture) { f =>
      Certificates.classifyAndParse(wrap(f)) match {
        case Some(_: Certificates.PrivateKeyEncrypted) =>
          val state = new CertificatesState(
            wrap(f),
            Certificates.classifyAndParse(wrap(f))
          )
          val (purlSet, _) = state.getPurls(wrap(f), stubItem(), SingleMarker())
          val purls = purlSet.canonicalStrings
          purls.isEmpty
        case _ => true
      }
    }
  }

  property("[PROP] unencrypted private keys produce exactly one pURL") {
    forAll(genFixture) { f =>
      Certificates.classifyAndParse(wrap(f)) match {
        case Some(_: Certificates.PrivateKeyPlaintextPem) |
            Some(_: Certificates.PrivateKeyPlaintextOpenSsh) =>
          val state = new CertificatesState(
            wrap(f),
            Certificates.classifyAndParse(wrap(f))
          )
          val (purlSet, _) = state.getPurls(wrap(f), stubItem(), SingleMarker())
          val purls = purlSet.canonicalStrings
          purls.length == 1
        case _ => true
      }
    }
  }

  property(
    "[PROP] leak sweep is clean for every fixture (no forbidden pattern, no leaked private scalar)"
  ) {
    forAll(genFixture) { f =>
      Certificates.classifyAndParse(wrap(f)) match {
        case None => true
        case Some(claim) =>
          val state = new CertificatesState(wrap(f), Some(claim))
          // getMetadata runs assertNoLeak internally. If it raises,
          // the property fails.
          scala.util
            .Try {
              val _ = state.getMetadata(wrap(f), stubItem(), SingleMarker())
              true
            }
            .getOrElse(false)
      }
    }
  }

  property("[PROP] encrypted fixtures emit zero key-derived metadata fields") {
    forAll(genFixture) { f =>
      Certificates.classifyAndParse(wrap(f)) match {
        case Some(_: Certificates.PrivateKeyEncrypted) =>
          val state = new CertificatesState(
            wrap(f),
            Certificates.classifyAndParse(wrap(f))
          )
          val (md, _) = state.getMetadata(wrap(f), stubItem(), SingleMarker())
          val forbidden = Set(
            "Certificates:KeyAlgorithm",
            "Certificates:KeySize",
            "Certificates:Curve",
            "Certificates:SpkiSha256",
            "Certificates:SshFingerprintSha256",
            "Certificates:DerivedFromPrivateKey"
          )
          forbidden.forall(k => !md.contains(k))
        case _ => true
      }
    }
  }
}
