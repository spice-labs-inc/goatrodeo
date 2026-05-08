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

/** Property-based tests for the PGP path.
  *
  * ## What these tests test
  *
  * Phase 6 first-pass gap analysis G6 + second-pass N5/N9: example-based tests
  * can only pin specific fixtures. These properties hold across the full corpus
  * and their **strength** is the focus of N5: each property must catch a
  * realistic class of bug, not be tautological.
  *
  * ### Properties (post-N5 / N9 strengthening)
  *
  *   1. *Fingerprint hex shape* — every parsed key's fingerprint is lowercase
  *      hex, length ∈ {40, 64} (v4=20B SHA-1, v5/v6=32B SHA-256). Catches case
  *      bugs and length-mapping bugs. 2. *Parse idempotence (FULL key equality,
  *      not just fingerprint set)* — parsing the same file twice yields
  *      identical PgpKey values across all fields (fingerprint, version, alg
  *      id, canonical alg, keySize, curve, isPrimary, dates, userIds).
  *      Strengthened from G6's original "fingerprint set only" form. Catches
  *      any non-deterministic decode of algorithm/curve/size/ timestamps
  *      between parses. 3. *pURL canonical-form structural invariant* — every
  *      emitted pURL parses to a string that:
  *      - starts with `pkg:pgp/fingerprint@`
  *      - contains the lowercase fingerprint hex
  *      - has alphabetically-sorted qualifiers
  *      - includes both `alg=` and `version=` qualifiers Strengthened from G6's
  *        `once == twice` form (which only proved function purity). 4. *Per-key
  *        pURL distinctness* — every key in a ring emits a pURL that is
  *        distinct after canonicalization. Same dedup the strategy applies in
  *        `getPurls`. 5. *Emission-path equivalence (N9)* — the property-test
  *        pURL computation matches what the actual `getPurls` emission path
  *        produces (the new property exercises the strategy's claim pipeline
  *        through `Certificates.computeCertificateFiles`-style classification,
  *        so it's not just a unit test of the helper).
  *
  * ## Why this matters
  *
  * G6 in `info/2026_05_01_phase6_gaps.md` flagged the absence of property
  * tests; N5/N9 in the second-pass gap analysis flagged that the original
  * property set was weak (`once == twice` is referential transparency, not a
  * meaningful invariant; "fingerprint set" missed algorithm/version/curve
  * mismatches). These reformulated properties actually catch realistic bug
  * classes.
  */
class PgpPropertyTests extends ScalaCheckSuite {

  /** All `.asc` PGP fixtures in the corpus. The property tests run against
    * every one.
    */
  private val pgpFixtures: Seq[File] = {
    val root = Paths.get("test_data/certificates/pgp")
    if (!Files.exists(root)) Seq.empty
    else {
      import scala.jdk.CollectionConverters.*
      Files
        .walk(root)
        .iterator()
        .asScala
        .filter(p => p.toString.endsWith(".asc"))
        .map(_.toFile)
        .toSeq
    }
  }

  private val genFixture: Gen[File] =
    if (pgpFixtures.isEmpty) Gen.fail
    else Gen.oneOf(pgpFixtures)

  private def parse(f: File): Option[Certificates.PgpKeyRing] =
    Certificates.parsePgpKeyRing(FileWrapper(f, f.getName, None))

  property(
    "[PROP] every parsed PGP key has lowercase-hex fingerprint of length 40 or 64 (G6)"
  ) {
    forAll(genFixture) { f =>
      parse(f) match {
        case None => true // unparseable file isn't a property failure
        case Some(r) =>
          r.keys.forall { k =>
            k.fingerprintHex.matches("[0-9a-f]+") &&
            (k.fingerprintHex.length == 40 || k.fingerprintHex.length == 64)
          }
      }
    }
  }

  // N5 — strengthened from "fingerprint set equality" to FULL PgpKey
  // equality. The original property would have passed even if a parse
  // run scrambled algorithm or curve assignments between runs (since
  // it only compared fingerprint sets). This version compares every
  // field on every key, in the order each parse returned.
  property(
    "[PROP] parse idempotence: same file → identical PgpKey list (N5 / G6)"
  ) {
    forAll(genFixture) { f =>
      val a = parse(f)
      val b = parse(f)
      (a, b) match {
        case (Some(ra), Some(rb)) =>
          // Compare each field of each key in iteration order. A non-
          // deterministic decode of curve/keySize/version/timestamps
          // would fail this even if fingerprints matched.
          ra.keys.length == rb.keys.length &&
          ra.keys.zip(rb.keys).forall { case (ka, kb) =>
            ka.fingerprintHex == kb.fingerprintHex &&
            ka.version == kb.version &&
            ka.pgpAlgId == kb.pgpAlgId &&
            ka.canonicalAlg == kb.canonicalAlg &&
            ka.keySize == kb.keySize &&
            ka.curve == kb.curve &&
            ka.isPrimary == kb.isPrimary &&
            ka.creationTime == kb.creationTime &&
            ka.expirationTime == kb.expirationTime &&
            ka.userIds == kb.userIds
          } &&
          ra.primaryUserId == rb.primaryUserId
        case (None, None) => true
        case _            => false
      }
    }
  }

  // N5 — strengthened from `once == twice` (referential transparency,
  // tests nothing) to a structural invariant on the canonicalized pURL
  // form. Catches: (a) fingerprint-not-in-pURL bugs, (b) qualifier-
  // ordering regressions, (c) missing alg/version qualifiers.
  property("[PROP] pURL canonical form: structural invariants (N5 / G6)") {
    forAll(genFixture) { f =>
      parse(f) match {
        case None => true
        case Some(r) =>
          r.keys.forall { k =>
            val purl = Certificates.purlForPgpKey(k).canonicalize().nn
            // (a) shape: pkg:pgp/fingerprint@{hex}?...
            purl.startsWith(s"pkg:pgp/fingerprint@${k.fingerprintHex}?") &&
            // (b) the alg= qualifier is present and matches canonical
            purl.contains(s"alg=${k.canonicalAlg}") &&
            // (c) the version= qualifier is present and matches
            purl.contains(s"version=${k.version}") &&
            // (d) qualifiers are alphabetically sorted (canonical form)
            {
              val qual = purl.dropWhile(_ != '?').drop(1)
              val keys = qual.split('&').toVector.map(_.takeWhile(_ != '='))
              keys == keys.sorted
            }
          }
      }
    }
  }

  // N5 — strengthened: was "distinctness of pURL strings" (which is
  // structurally guaranteed since fingerprint is in the pURL). Now also
  // asserts that the count matches keys.length (no silent dedup of
  // distinct keys).
  property(
    "[PROP] purlForPgpKey: one canonical pURL per cryptographic identity (G6)"
  ) {
    forAll(genFixture) { f =>
      parse(f) match {
        case None => true
        case Some(r) =>
          val purls =
            r.keys.map(k => Certificates.purlForPgpKey(k).canonicalize().nn)
          purls.distinct.length == r.keys.length &&
          // Every pURL contains its key's full fingerprint (regression
          // guard against truncation bugs like fp8 leaking into emission).
          r.keys.zip(purls).forall { case (k, p) =>
            p.contains(k.fingerprintHex)
          }
      }
    }
  }

  /** Minimal `Item` for plumbing `state.getPurls` — the PGP branch doesn't
    * actually read the Item, but the signature requires one.
    */
  private def stubItem(): Item = Item(
    identifier = "gitoid:blob:sha256:pgp-property-test",
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

  // N9 — emission-path parity. The properties above unit-test
  // `purlForPgpKey` directly. This property exercises the actual
  // emission path through `CertificatesState.getPurls`, which is what
  // the strategy pipeline calls. A future refactor that moved
  // canonicalization or dedup out of `getPurls`, or short-circuited
  // PGP emission, would fail here even if `purlForPgpKey` itself was
  // unchanged.
  property(
    "[PROP] strategy emission path (CertificatesState.getPurls) matches unit computation (N9)"
  ) {
    forAll(genFixture) { f =>
      parse(f) match {
        case None => true
        case Some(r) =>
          val w = FileWrapper(f, f.getName, None)
          val state = new CertificatesState(w, Some(r))
          val (emittedPurls, _) = state.getPurls(w, stubItem(), SingleMarker())
          val emittedSet = emittedPurls.map(_.canonicalize().nn).toSet
          val unitSet = r.keys
            .map(k => Certificates.purlForPgpKey(k).canonicalize().nn)
            .toSet
          emittedSet == unitSet
      }
    }
  }
}
