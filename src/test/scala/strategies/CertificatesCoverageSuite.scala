/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import munit.FunSuite
import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.File
import scala.jdk.CollectionConverters.*

/** Phase 8 — coverage matrix completeness suite.
  *
  * Per the plan (`certificates-strategy/phases-8-9-tests-docs.md`
  * lines 39-66):
  *
  * > Enumerate all `*.expected.json` sidecars. Build a matrix of
  * > which (algorithm, key-size-or-curve-or-params, artifact-type)
  * > combinations are represented. Fail the suite if any required
  * > cell in the coverage matrix is unfilled.
  *
  * > A failing coverage test is a test-data gap, not a strategy bug
  * > — but it still fails the build. This prevents silent erosion of
  * > test coverage as fixtures get added/removed.
  *
  * Strategy: parse every sidecar, extract qualifiers from declared
  * pURLs (and MIMEs / metadata where relevant), build sets of
  * (alg, size), (alg, curve), (sig-alg), (cert-type), (envelope),
  * (mime), (Phase-2 detector), etc. Then check each required cell
  * against the observed set.
  */
class CertificatesCoverageSuite extends FunSuite {

  private val corpusRoot = new File("test_data/certificates")

  // ===== Sidecar walker ===================================================

  private case class Sidecar(
      path: String,
      mimeContains: Set[String],
      mimeNotContains: Set[String],
      purlsContains: Vector[String],
      purlsNotContains: Vector[String],
      metadataContains: Map[String, String],
  )

  private def parseSidecar(file: File): Sidecar = {
    val raw = scala.io.Source.fromFile(file, "UTF-8").mkString
    val json = parse(raw)

    def getStringSet(jval: JValue): Set[String] = jval match {
      case JArray(xs) => xs.collect { case JString(s) => s }.toSet
      case _ => Set.empty
    }
    def getStringVector(jval: JValue): Vector[String] = jval match {
      case JArray(xs) => xs.collect { case JString(s) => s }.toVector
      case _ => Vector.empty
    }
    def getStringMap(jval: JValue): Map[String, String] = jval match {
      case JObject(fields) => fields.collect {
        case (k, JString(v)) => k -> v
      }.toMap
      case _ => Map.empty
    }

    val mimeContains = getStringSet(json \ "mimeTypes" \ "mustContain")
    val mimeNotContains = getStringSet(json \ "mimeTypes" \ "mustNotContain")
    val purlsContains = getStringVector(json \ "purls" \ "mustContain")
    val purlsNotContains = getStringVector(json \ "purls" \ "mustNotContain")
    val metadataContains = getStringMap(json \ "metadata" \ "mustContain")

    Sidecar(
      path = file.getPath,
      mimeContains = mimeContains,
      mimeNotContains = mimeNotContains,
      purlsContains = purlsContains,
      purlsNotContains = purlsNotContains,
      metadataContains = metadataContains,
    )
  }

  private def discoverSidecars(): Vector[Sidecar] = {
    if (!corpusRoot.exists()) Vector.empty
    else {
      val all = java.nio.file.Files.walk(corpusRoot.toPath).iterator().asScala
      all.filter(p => p.toString.endsWith(".expected.json"))
        .map(p => parseSidecar(p.toFile))
        .toVector
    }
  }

  // ===== pURL qualifier extraction =======================================

  /** Parse `pkg:scheme/identifier@hex?qual1=val1&qual2=val2` into
    * `(scheme, qualifiers)` where qualifiers is a Map[String, String]. */
  private def parsePurl(purl: String): Option[(String, Map[String, String])] = {
    val schemeRx = "^pkg:([a-z0-9-]+)/".r
    val schemeMatch = schemeRx.findFirstMatchIn(purl)
    schemeMatch.map { sm =>
      val scheme = sm.group(1).nn
      val qIdx = purl.indexOf('?')
      val quals: Map[String, String] =
        if (qIdx < 0) Map.empty
        else {
          purl.substring(qIdx + 1).split('&').toVector.flatMap { kv =>
            val eqIdx = kv.indexOf('=')
            if (eqIdx <= 0) None
            else Some(kv.substring(0, eqIdx) -> kv.substring(eqIdx + 1))
          }.toMap
        }
      scheme -> quals
    }
  }

  // ===== Suite-state: parse the corpus once for every test ===============

  private val sidecars: Vector[Sidecar] = discoverSidecars()
  private val allPurls: Vector[(String, Map[String, String])] =
    sidecars.flatMap(s => s.purlsContains).flatMap(parsePurl)

  private val purlsByScheme: Map[String, Vector[Map[String, String]]] =
    allPurls.groupBy(_._1).view.mapValues(_.map(_._2)).toMap

  // ===== Sanity: corpus exists ===========================================

  test("[COVERAGE] corpus has at least 100 sidecars (Phase 0b minimum carried forward)") {
    assert(sidecars.length >= 100,
      s"corpus appears empty or much smaller than expected: ${sidecars.length} sidecars")
  }

  // ===== Required matrix per Phase 8 plan ===============================

  private def x509SpkiOrCertPurls: Vector[Map[String, String]] =
    purlsByScheme.getOrElse("x509", Vector.empty)
      .filter(_.contains("alg")) // exclude crl-sha256 which has no alg

  test("[COVERAGE] X.509 alg: rsa, ec, ed25519, ml-dsa") {
    val algs = x509SpkiOrCertPurls.flatMap(_.get("alg")).toSet
    val required = Set("rsa", "ec", "ed25519", "ml-dsa")
    val missing = required -- algs
    assert(missing.isEmpty,
      s"X.509 alg coverage missing: $missing (observed: $algs)")
  }

  test("[COVERAGE] X.509 RSA size: 2048, 4096") {
    val sizes = x509SpkiOrCertPurls
      .filter(_.get("alg").contains("rsa"))
      .flatMap(_.get("size")).toSet
    val required = Set("2048", "4096")
    val missing = required -- sizes
    assert(missing.isEmpty,
      s"X.509 RSA size coverage missing: $missing (observed: $sizes)")
  }

  test("[COVERAGE] X.509 EC curve: p-256, p-384") {
    val curves = x509SpkiOrCertPurls
      .filter(_.get("alg").contains("ec"))
      .flatMap(_.get("curve")).toSet
    val required = Set("p-256", "p-384")
    val missing = required -- curves
    assert(missing.isEmpty,
      s"X.509 EC curve coverage missing: $missing (observed: $curves)")
  }

  test("[COVERAGE] X.509 ML-DSA params: 65") {
    val params = x509SpkiOrCertPurls
      .filter(_.get("alg").contains("ml-dsa"))
      .flatMap(_.get("params")).toSet
    assert(params.contains("65"),
      s"X.509 ML-DSA params=65 not present (observed: $params)")
  }

  test("[COVERAGE] X.509 sig-alg: at least sha1-rsa, sha256-rsa, ed25519") {
    val sigAlgs = purlsByScheme.getOrElse("x509", Vector.empty)
      .flatMap(_.get("sig-alg")).toSet
    val required = Set("sha1-rsa", "sha256-rsa", "ed25519")
    val missing = required -- sigAlgs
    assert(missing.isEmpty,
      s"X.509 sig-alg coverage missing: $missing (observed: $sigAlgs)")
  }

  test("[COVERAGE] X.509 self-signed: both true and false") {
    val selfSignedValues = purlsByScheme.getOrElse("x509", Vector.empty)
      .flatMap(_.get("self-signed")).toSet
    val required = Set("true", "false")
    val missing = required -- selfSignedValues
    assert(missing.isEmpty,
      s"X.509 self-signed coverage missing: $missing (observed: $selfSignedValues)")
  }

  test("[COVERAGE] Encoding: at least one PEM and one DER fixture") {
    val mimes = sidecars.flatMap(_.mimeContains).toSet
    assert(mimes.contains("application/x-pem-file") || mimes.contains("application/x-pem-bundle"),
      s"PEM encoding not present in any fixture's mustContain (observed: $mimes)")
    assert(mimes.contains("application/pkix-cert") || mimes.contains("application/x-x509-ca-cert"),
      "DER X.509 encoding not present in any fixture's mustContain")
  }

  test("[COVERAGE] Keystore format: jks, jceks, pkcs12 (bks is nice-to-have)") {
    val mimes = sidecars.flatMap(_.mimeContains).toSet
    val required = Set(
      "application/x-java-keystore",
      "application/x-java-jce-keystore",
      "application/pkcs12",
    )
    val missing = required -- mimes
    assert(missing.isEmpty,
      s"Keystore format coverage missing: $missing (observed mimes filtered: ${mimes.intersect(required)})")
  }

  test("[COVERAGE] PEM bundle size: at least one single-cert and one multi-cert (≥ 10 entries)") {
    val bundleSidecars = sidecars.filter(_.mimeContains.contains("application/x-pem-bundle"))
    assert(bundleSidecars.nonEmpty, "no pem-bundle fixture found")
    val purlCounts = bundleSidecars.map(_.purlsContains.length)
    val singleish = purlCounts.exists(c => c >= 1 && c <= 4) // ≤ 2 certs each emits ≤ 2 pURLs
    val largeBundle = purlCounts.exists(_ >= 20) // 10 certs ≈ 20 pURLs (each cert: spki + cert)
    assert(singleish, s"no small-bundle fixture (1-2 certs); pURL counts: $purlCounts")
    assert(largeBundle, s"no large-bundle fixture (≥10 certs); pURL counts: $purlCounts")
  }

  test("[COVERAGE] SSH alg: rsa, ec (at least p-256), ed25519") {
    val sshPurls = purlsByScheme.getOrElse("ssh", Vector.empty)
    val algs = sshPurls.flatMap(_.get("alg")).toSet
    val required = Set("rsa", "ec", "ed25519")
    val missing = required -- algs
    assert(missing.isEmpty,
      s"SSH alg coverage missing: $missing (observed: $algs)")
    val ecCurves = sshPurls.filter(_.get("alg").contains("ec"))
      .flatMap(_.get("curve")).toSet
    assert(ecCurves.contains("p-256"),
      s"SSH EC p-256 not present (observed curves: $ecCurves)")
  }

  test("[COVERAGE] SSH certificate type: at least one user and one host") {
    val certTypes = purlsByScheme.getOrElse("ssh", Vector.empty)
      .flatMap(_.get("cert-type")).toSet
    val required = Set("user", "host")
    val missing = required -- certTypes
    assert(missing.isEmpty,
      s"SSH cert-type coverage missing: $missing (observed: $certTypes)")
  }

  test("[COVERAGE] SSH certificate cross-algorithm: at least one cert where signed-key alg differs from CA-sig alg") {
    // pkg:ssh/cert-sha256@... has both `alg` (signed key) and `sig-alg` (CA sig).
    // A cross-algorithm cert is one where, e.g., alg=rsa and sig-alg=ssh-ed25519.
    val sshCerts = purlsByScheme.getOrElse("ssh", Vector.empty)
      .filter(p => p.contains("cert-type") && p.contains("alg") && p.contains("sig-alg"))
    val crossAlg = sshCerts.exists { q =>
      val signedAlg = q.getOrElse("alg", "")
      val sigAlg = q.getOrElse("sig-alg", "")
      // Strip "ssh-" or "ecdsa-sha2-" prefixes from sig-alg to compare cleanly.
      val sigAlgCanonical =
        if (sigAlg.startsWith("ssh-")) sigAlg.stripPrefix("ssh-")
        else if (sigAlg.startsWith("rsa-sha2-")) "rsa"
        else if (sigAlg.startsWith("ecdsa-sha2-")) "ec"
        else sigAlg
      signedAlg != sigAlgCanonical
    }
    assert(crossAlg,
      s"no cross-algorithm SSH cert found (signed key alg ≠ CA sig alg)")
  }

  test("[COVERAGE] PGP alg: rsa, ed25519") {
    val algs = purlsByScheme.getOrElse("pgp", Vector.empty)
      .flatMap(_.get("alg")).toSet
    val required = Set("rsa", "ed25519")
    val missing = required -- algs
    assert(missing.isEmpty,
      s"PGP alg coverage missing: $missing (observed: $algs)")
  }

  test("[COVERAGE] PGP version: v4 and v6") {
    val versions = purlsByScheme.getOrElse("pgp", Vector.empty)
      .flatMap(_.get("version")).toSet
    val required = Set("4", "6")
    val missing = required -- versions
    assert(missing.isEmpty,
      s"PGP version coverage missing: $missing (observed: $versions)")
  }

  test("[COVERAGE] CRL: at least one DER and one PEM fixture") {
    val crlSidecars = sidecars.filter(_.mimeContains.contains("application/pkix-crl"))
    assert(crlSidecars.nonEmpty, "no CRL fixture found")
    // Distinguish DER vs PEM by file extension on the matching fixture.
    val derCrl = crlSidecars.exists(s => s.path.contains(".crl") &&
      !s.path.contains(".pem"))
    val pemCrl = crlSidecars.exists(s => s.path.contains(".pem"))
    assert(derCrl, "no DER CRL fixture")
    assert(pemCrl, "no PEM CRL fixture")
  }

  test("[COVERAGE] CRL sig-alg: at least one SHA-1 (sha1-rsa) and one modern (sha256-rsa or ed25519)") {
    val crlPurls = purlsByScheme.getOrElse("x509", Vector.empty)
      .filter(_ => true) // crl-sha256 also has scheme=x509 in our pURL design
    val crlSigAlgs = sidecars
      .filter(_.mimeContains.contains("application/pkix-crl"))
      .flatMap(_.purlsContains)
      .flatMap(parsePurl)
      .flatMap(_._2.get("sig-alg")).toSet
    val hasSha1 = crlSigAlgs.exists(_.contains("sha1"))
    val hasModern = crlSigAlgs.exists(s => s.contains("sha256") || s == "ed25519")
    assert(hasSha1, s"no SHA-1-signed CRL (observed: $crlSigAlgs)")
    assert(hasModern, s"no modern-signed CRL (observed: $crlSigAlgs)")
  }

  test("[COVERAGE] CRL: at least one empty CRL (RevokedCount=0)") {
    val empty = sidecars
      .filter(_.mimeContains.contains("application/pkix-crl"))
      .exists(_.metadataContains.get("Certificates:RevokedCount").contains("0"))
    assert(empty,
      "no empty-CRL fixture (Certificates:RevokedCount=0 not asserted in any sidecar)")
  }

  test("[COVERAGE] Private key envelope — unlocked: plaintext with rsa AND with ed25519") {
    val unlocked = sidecars
      .filter(_.metadataContains.get("Certificates:Envelope").contains("plaintext"))
    val rsaOk = unlocked.exists(_.metadataContains.get("Certificates:KeyAlgorithm").contains("rsa"))
    val edOk = unlocked.exists(_.metadataContains.get("Certificates:KeyAlgorithm").contains("ed25519"))
    assert(rsaOk, "no plaintext private-key fixture with alg=rsa")
    assert(edOk, "no plaintext private-key fixture with alg=ed25519")
  }

  test("[COVERAGE] Private key envelope — encrypted: pkcs8-encrypted AND openssh-encrypted") {
    val envelopes = sidecars.flatMap(_.metadataContains.get("Certificates:Envelope")).toSet
    val required = Set("pkcs8-encrypted", "openssh-encrypted")
    val missing = required -- envelopes
    assert(missing.isEmpty,
      s"encrypted envelope coverage missing: $missing (observed: $envelopes)")
  }

  // A4 in v2 review: Phase-7 second-pass remediation introduced two
  // additional envelope values that the original Phase-8 plan didn't
  // know about: `pem-legacy-encrypted` (RFC 1421 legacy PEM with
  // Proc-Type:4,ENCRYPTED) and `pgp-encrypted-secret-key`. Coverage
  // matrix must require these so a future contributor who removes
  // the matching fixtures fails the build.
  test("[COVERAGE] Private key envelope — pem-legacy-encrypted (RFC 1421 / Phase-7 path)") {
    val envelopes = sidecars.flatMap(_.metadataContains.get("Certificates:Envelope")).toSet
    assert(envelopes.contains("pem-legacy-encrypted"),
      s"pem-legacy-encrypted envelope not present in any fixture (observed: $envelopes)")
  }

  test("[COVERAGE] Private key envelope — pgp-encrypted-secret-key (Phase-7 second-pass remediation)") {
    val envelopes = sidecars.flatMap(_.metadataContains.get("Certificates:Envelope")).toSet
    assert(envelopes.contains("pgp-encrypted-secret-key"),
      s"pgp-encrypted-secret-key envelope not present in any fixture (observed: $envelopes)")
  }

  test("[COVERAGE] Private key envelope — plaintext PGP (Phase-7 second-pass: pgp-secret-* unencrypted fixture)") {
    // The PGP-secret unencrypted fixture is the only Phase-7 fixture
    // that emits Envelope=plaintext WITHOUT a top-level KeyAlgorithm
    // (PGP per-key namespacing). Verify it's present.
    val plaintextSidecars = sidecars.filter(
      _.metadataContains.get("Certificates:Envelope").contains("plaintext"))
    val pgpPlaintext = plaintextSidecars.exists { s =>
      s.mimeContains.contains("application/pgp-keys")
    }
    assert(pgpPlaintext,
      "no plaintext-PGP private-key fixture (Envelope=plaintext + " +
      "MIME=application/pgp-keys) — Phase-7 PGP-secret-key path uncovered")
  }

  test("[COVERAGE] Keystore encryption state: at least one unencrypted AND one encrypted") {
    val keystoreSidecars = sidecars.filter { s =>
      s.mimeContains.exists(m =>
        m == "application/x-java-keystore" ||
        m == "application/x-java-jce-keystore" ||
        m == "application/pkcs12")
    }
    // Strategy emits `Certificates:KeystoreEncrypted=true` for encrypted
    // keystores (null-password load failed) and `Certificates:EntryCount`
    // with a positive integer for unencrypted (loadable) ones.
    val encrypted = keystoreSidecars.exists { s =>
      s.metadataContains.get("Certificates:KeystoreEncrypted").contains("true") ||
        s.metadataContains.get("Certificates:EntryCount").contains("0")
    }
    val unencrypted = keystoreSidecars.exists { s =>
      val cnt = s.metadataContains.get("Certificates:EntryCount")
      cnt.exists(c => scala.util.Try(c.toInt).toOption.exists(_ > 0))
    }
    assert(encrypted, "no encrypted keystore fixture")
    assert(unencrypted, "no unencrypted (loadable, EntryCount > 0) keystore fixture")
  }
}
