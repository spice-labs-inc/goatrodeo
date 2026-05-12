/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.forAll

import java.io.File as JFile
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Phase 8 — generative X.509 roundtrip property tests.
  *
  * Per the plan (`certificates-strategy/phases-8-9-tests-docs.md`):
  *
  * > Generate synthetic certs at test time using Bouncy Castle's >
  * `X509v3CertificateBuilder`. For each generated cert, assert > [13
  * properties].
  *
  * The rationale for runtime generation (not corpus-driven): the fixture corpus
  * tests can only check what's in the corpus. A regression in `purlsForCert`
  * that affects an algorithm-key-size combination not present in the corpus
  * would slip through. Runtime generation lets us cover the cartesian product
  * RSA × {2048,3072, 4096} + EC × {p-256,p-384,p-521} + Ed25519 + Ed448 without
  * any fixture additions.
  *
  * Properties are kept STRONG (per the Phase-6 N5 lesson — weak properties pass
  * without testing anything useful). Each property either roundtrips a hash,
  * asserts a structural invariant on the pURL, or guards an absence (no leak).
  */
class CertificatesPropertySuite extends ScalaCheckSuite {

  // Register BC if not already (idempotent — also done by Certificates).
  if (Security.getProvider("BC") == null) {
    Security.addProvider(
      new org.bouncycastle.jce.provider.BouncyCastleProvider()
    )
  }

  // Cap test count: per plan, "Target 50 generated certs per test run".
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(50)

  /** All algorithm cases the suite generates. Each case knows how to build a
    * `KeyPair` of the right shape.
    */
  sealed trait CertGenCase {
    def algName: String
    def expectedAlg: String
    def keyPair(): KeyPair
    def signatureAlg: String
    def displayName: String
  }
  case class RsaCase(bits: Int) extends CertGenCase {
    val algName = "RSA"
    val expectedAlg = "rsa"
    def keyPair(): KeyPair = {
      val kpg = KeyPairGenerator.getInstance("RSA")
      kpg.initialize(bits, new SecureRandom())
      kpg.generateKeyPair()
    }
    val signatureAlg = "SHA256withRSA"
    val displayName = s"rsa-$bits"
  }
  case class EcCase(curveName: String, jcaCurve: String) extends CertGenCase {
    val algName = "EC"
    val expectedAlg = "ec"
    def keyPair(): KeyPair = {
      val kpg = KeyPairGenerator.getInstance("EC", "BC")
      kpg.initialize(new java.security.spec.ECGenParameterSpec(jcaCurve))
      kpg.generateKeyPair()
    }
    val signatureAlg = "SHA256withECDSA"
    val displayName = s"ec-$curveName"
  }
  case object Ed25519Case extends CertGenCase {
    val algName = "Ed25519"
    val expectedAlg = "ed25519"
    def keyPair(): KeyPair = {
      val kpg = KeyPairGenerator.getInstance("Ed25519", "BC")
      kpg.generateKeyPair()
    }
    val signatureAlg = "Ed25519"
    val displayName = "ed25519"
  }
  case object Ed448Case extends CertGenCase {
    val algName = "Ed448"
    val expectedAlg = "ed448"
    def keyPair(): KeyPair = {
      val kpg = KeyPairGenerator.getInstance("Ed448", "BC")
      kpg.generateKeyPair()
    }
    val signatureAlg = "Ed448"
    val displayName = "ed448"
  }

  /** All 8 algorithm cases per the Phase 8 plan: "Generators should cover at
    * minimum: RSA {2048, 3072, 4096}, EC {p-256, p-384, p-521}, Ed25519,
    * Ed448."
    */
  private val allCases: Vector[CertGenCase] = Vector(
    RsaCase(2048),
    RsaCase(3072),
    RsaCase(4096),
    EcCase("p-256", "secp256r1"),
    EcCase("p-384", "secp384r1"),
    EcCase("p-521", "secp521r1"),
    Ed25519Case,
    Ed448Case
  )

  /** Random sampling distribution for `forAll`. Used by properties that benefit
    * from variance (parse idempotence, regex shape). Note: with `Gen.oneOf` and
    * 50 runs, statistically every case gets ~6 hits — but no per-run guarantee.
    * The `[STRATIFIED]` tests below give a per-case GUARANTEE per run.
    */
  private val genCase: Gen[CertGenCase] = Gen.oneOf(allCases)

  /** Stratified sampling: invoke the property block once per algorithm case,
    * guaranteeing all 8 are exercised regardless of ScalaCheck's random seed.
    * Used in tandem with `genCase` when a particular property must hold for
    * every alg class — not just "with high probability over random samples."
    */
  private def perCase(label: String)(body: CertGenCase => Unit): Unit = {
    allCases.foreach { c =>
      test(s"[STRATIFIED $label] ${c.displayName}") {
        body(c)
      }
    }
  }

  private def buildSelfSignedCert(
      c: CertGenCase
  ): (KeyPair, X509Certificate) = {
    import org.bouncycastle.asn1.x500.X500Name
    import org.bouncycastle.cert.jcajce.{
      JcaX509CertificateConverter,
      JcaX509v3CertificateBuilder
    }
    import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
    val kp = c.keyPair()
    val subject = new X500Name(
      s"CN=GoatRodeo Property Test ${c.displayName}, O=GoatRodeo"
    )
    val notBefore = new Date()
    val cal = Calendar.getInstance(); cal.setTime(notBefore);
    cal.add(Calendar.YEAR, 1)
    val notAfter = cal.getTime
    val serial = new BigInteger(64, new SecureRandom())
    val builder = new JcaX509v3CertificateBuilder(
      subject,
      serial,
      notBefore,
      notAfter,
      subject,
      kp.getPublic
    )
    val signer = new JcaContentSignerBuilder(c.signatureAlg)
      .setProvider("BC")
      .build(kp.getPrivate)
    val holder = builder.build(signer)
    val cert =
      new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
    (kp, cert)
  }

  /** Compute SHA-256 lowercase hex without using `Certificates.sha256Hex` —
    * independent verification per the property.
    */
  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** Per the plan, parse the cert through the strategy and check the resulting
    * pURLs. We use `parseSingleCert` + `purlsForCert` to exercise the actual
    * emission path.
    */
  private def driveThroughStrategy(
      cert: X509Certificate
  ): (X509Certificate, Vector[String]) = {
    val derBytes = cert.getEncoded
    val artifact: ArtifactWrapper = ByteWrapper(derBytes, "generated.der", None)
    val parsed = Certificates.parseSingleCert(artifact).get
    val purls = Certificates.purlsForCert(parsed).map(_.canonicalize().nn)
    (parsed, purls)
  }

  // ===== Property 1: SPKI roundtrip (hash) ===============================

  property(
    "[PROP] spki-sha256 qualifier matches SHA-256(DER SPKI) computed independently"
  ) {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (parsed, purls) = driveThroughStrategy(cert)
      val expectedSpkiHex = sha256Hex(Certificates.spkiBytesFromCert(parsed))
      val spkiPurl = purls.find(_.contains("spki-sha256")).get
      Prop.collect(c.displayName)(spkiPurl.contains(expectedSpkiHex))
    }
  }

  // ===== Property 2: Cert fingerprint roundtrip ==========================

  property(
    "[PROP] cert-sha256 qualifier matches SHA-256(DER cert) computed independently"
  ) {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      val expectedCertHex = sha256Hex(cert.getEncoded)
      val certPurl = purls.find(_.contains("cert-sha256")).get
      certPurl.contains(expectedCertHex)
    }
  }

  // ===== Property 3: alg always present on key-bearing pURLs =============

  property(
    "[PROP] every key-bearing pURL has exactly one alg= qualifier (not sig-alg)"
  ) {
    // Must use a word-boundary-aware pattern: `alg=` is a substring of
    // `sig-alg=`, so a naive `alg=[a-z0-9-]+` regex matches both. The
    // valid `alg=` qualifier is preceded by `?` (start of qualifier
    // section) or `&` (separator between qualifiers).
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls.forall { purl =>
        val algCount = "(?:^|[?&])alg=[a-z0-9-]+".r.findAllMatchIn(purl).length
        algCount == 1
      }
    }
  }

  // ===== Property 4: RSA → size present ==================================

  property(
    "[PROP] RSA pURLs always carry size= qualifier with positive integer"
  ) {
    forAll(genCase.suchThat(_.expectedAlg == "rsa")) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls.forall { purl =>
        val sizeMatch = "size=([0-9]+)".r.findFirstMatchIn(purl)
        sizeMatch.exists { m =>
          val n = m.group(1).nn.toInt
          n > 0
        }
      }
    }
  }

  // ===== Property 5: EC → curve present ==================================

  property(
    "[PROP] EC pURLs always carry a curve= qualifier with canonical value"
  ) {
    val canonicalCurves = Set(
      "p-256",
      "p-384",
      "p-521",
      "secp256k1",
      "brainpoolp256r1",
      "brainpoolp384r1",
      "brainpoolp512r1",
      "curve25519"
    )
    forAll(genCase.suchThat(_.expectedAlg == "ec")) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls.forall { purl =>
        "curve=([a-z0-9-]+)".r
          .findFirstMatchIn(purl)
          .map(_.group(1).nn)
          .exists(canonicalCurves.contains)
      }
    }
  }

  // ===== Property 6: alg matches the generator's expected alg ============

  property(
    "[PROP] emitted alg= matches the generator's intended canonical alg"
  ) {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls.forall { purl =>
        purl.contains(s"alg=${c.expectedAlg}")
      }
    }
  }

  // ===== Property 7: pURL parses cleanly (no canonicalize exceptions) ====

  property(
    "[PROP] every emitted pURL parses through PackageURL without throwing"
  ) {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls.forall { purl =>
        scala.util.Try(new com.github.packageurl.PackageURL(purl)).isSuccess
      }
    }
  }

  // ===== Property 8: lowercase qualifier values ==========================

  property(
    "[PROP] every qualifier value is lowercase / hyphens / digits only"
  ) {
    val rxQual = "([a-z-]+)=([^&]+)".r
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls.forall { purl =>
        val qIdx = purl.indexOf('?')
        if (qIdx < 0) true
        else {
          val quals = purl.substring(qIdx + 1)
          rxQual.findAllMatchIn(quals).forall { m =>
            val v = m.group(2).nn
            v.matches("[a-z0-9-]+")
          }
        }
      }
    }
  }

  // ===== Property 9: cert-sha256 has sig-alg =============================

  property("[PROP] every cert-sha256 pURL carries a sig-alg= qualifier") {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls.find(_.contains("cert-sha256")).exists(_.contains("sig-alg="))
    }
  }

  // ===== Property 10: cert-sha256 has self-signed=true (we built it self-signed) ==

  property("[PROP] self-signed certs emit self-signed=true qualifier") {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls
        .find(_.contains("cert-sha256"))
        .exists(_.contains("self-signed=true"))
    }
  }

  // ===== Property 11: spki-sha256 has version= ===========================

  property("[PROP] spki-sha256 pURL carries version= qualifier (X.509 plan)") {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls) = driveThroughStrategy(cert)
      purls.find(_.contains("spki-sha256")).exists(_.contains("version=3"))
    }
  }

  // ===== Property 12: no private material in metadata (hard rule) ========

  // A5 in v2 review: this property previously relied on
  // `assertNoLeak` raising — duplicating the leak suite's work.
  // Strengthened: do the regex sweep IN-TEST against the emitted
  // metadata values, independent of the strategy's own leak guard.
  // If a refactor accidentally rendered `assertNoLeak` no-op, this
  // property would still catch a real leak.
  private val appendixCRegexes: Seq[java.util.regex.Pattern] = Seq(
    "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "-----BEGIN PGP PRIVATE KEY BLOCK-----",
    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----",
    "MIIEvQIBADAN",
    "MIIEpAIBAAKCAQEA",
    "MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+",
    "openssh-key-v1"
  ).map(java.util.regex.Pattern.compile)

  property(
    "[PROP] no emitted metadata value matches any Appendix-C pattern (independent in-test sweep)"
  ) {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val derBytes = cert.getEncoded
      val artifact: ArtifactWrapper =
        ByteWrapper(derBytes, "generated.der", None)
      val state = new CertificatesState(
        artifact,
        Some(
          Certificates.SingleCert(Certificates.parseSingleCert(artifact).get)
        )
      )
      val (md, _) = state.getMetadata(
        artifact,
        stubItem(),
        io.spicelabs.goatrodeo.omnibor.SingleMarker()
      )
      // INDEPENDENT sweep: don't rely on strategy's assertNoLeak.
      // For each emitted metadata value, check against the regex list.
      md.forall { case (_, values) =>
        values.forall { v =>
          val text = v match {
            case io.spicelabs.goatrodeo.omnibor.StringOf(s)   => s
            case io.spicelabs.goatrodeo.omnibor.PairOf(_, s2) => s2
          }
          appendixCRegexes.forall(p => !p.matcher(text).find())
        }
      }
    }
  }

  // ===== Property 13: parse idempotence ==================================

  property("[PROP] DER-encode-then-reparse yields identical pURL set") {
    forAll(genCase) { c =>
      val (_, cert) = buildSelfSignedCert(c)
      val (_, purls1) = driveThroughStrategy(cert)
      val (_, purls2) = driveThroughStrategy(cert)
      purls1.toSet == purls2.toSet
    }
  }

  // ===== Corpus-driven properties for SSH cert + CRL =====================
  //
  // The plan lists these as properties but they aren't reasonably
  // runtime-generative (CRLs need an issuing key + revocation list;
  // SSH certs need a CA. Building either from scratch in test setup
  // is more work than driving the corpus). The corpus is finite —
  // these properties iterate the existing fixtures, which the
  // CoverageSuite ensures has the right shape.

  private val sshCertSidecars: Vector[String] = {
    val root = java.nio.file.Paths.get("test_data/certificates/ssh")
    if (!java.nio.file.Files.exists(root)) Vector.empty
    else {
      import scala.jdk.CollectionConverters.*
      java.nio.file.Files
        .walk(root)
        .iterator()
        .asScala
        .filter(p => p.toString.endsWith(".expected.json"))
        // SSH cert fixtures emit pkg:generic/ssh/cert-sha256@... — the literal
        // substring `cert-sha256` appears in the sidecar's pURL value
        // for cert fixtures (and never for plain-pubkey fixtures).
        .filter(p =>
          scala.util
            .Try(
              scala.io.Source
                .fromFile(p.toFile, "UTF-8")
                .mkString
                .contains("cert-sha256")
            )
            .getOrElse(false)
        )
        .map(_.toString.stripSuffix(".expected.json"))
        .toVector
    }
  }

  private val crlSidecars: Vector[String] = {
    val root = java.nio.file.Paths.get("test_data/certificates/crls")
    if (!java.nio.file.Files.exists(root)) Vector.empty
    else {
      import scala.jdk.CollectionConverters.*
      java.nio.file.Files
        .walk(root)
        .iterator()
        .asScala
        .filter(p => p.toString.endsWith(".expected.json"))
        .map(_.toString.stripSuffix(".expected.json"))
        .toVector
    }
  }

  private val genSshCert: Gen[String] =
    if (sshCertSidecars.isEmpty) Gen.fail else Gen.oneOf(sshCertSidecars)
  private val genCrl: Gen[String] =
    if (crlSidecars.isEmpty) Gen.fail else Gen.oneOf(crlSidecars)

  property("[PROP] SSH cert pURLs always carry cert-type ∈ {user, host}") {
    forAll(genSshCert) { path =>
      val w = FileWrapper(new JFile(path), path, None)
      Certificates.classifyAndParse(w) match {
        case Some(c: Certificates.SshCert) =>
          val state = new CertificatesState(w, Some(c))
          val (purls, _) = state.getPurls(
            w,
            stubItem(),
            io.spicelabs.goatrodeo.omnibor.SingleMarker()
          )
          val canonicalized = purls.map(_.canonicalize().nn)
          val certPurls = canonicalized.filter(_.contains("ssh/cert-sha256"))
          certPurls.forall { p =>
            val ct =
              "cert-type=([a-z]+)".r.findFirstMatchIn(p).map(_.group(1).nn)
            ct.contains("user") || ct.contains("host")
          }
        case _ => true // not an SSH cert; property doesn't apply
      }
    }
  }

  property("[PROP] SSH cert pURLs always carry sig-alg= qualifier") {
    forAll(genSshCert) { path =>
      val w = FileWrapper(new JFile(path), path, None)
      Certificates.classifyAndParse(w) match {
        case Some(c: Certificates.SshCert) =>
          val state = new CertificatesState(w, Some(c))
          val (purls, _) = state.getPurls(
            w,
            stubItem(),
            io.spicelabs.goatrodeo.omnibor.SingleMarker()
          )
          val canonicalized = purls.map(_.canonicalize().nn)
          val certPurls = canonicalized.filter(_.contains("ssh/cert-sha256"))
          certPurls.forall(_.contains("sig-alg="))
        case _ => true
      }
    }
  }

  property("[PROP] CRL pURLs always carry sig-alg= qualifier") {
    forAll(genCrl) { path =>
      val w = FileWrapper(new JFile(path), path, None)
      Certificates.classifyAndParse(w) match {
        case Some(crl: Certificates.Crl) =>
          val state = new CertificatesState(w, Some(crl))
          val (purls, _) = state.getPurls(
            w,
            stubItem(),
            io.spicelabs.goatrodeo.omnibor.SingleMarker()
          )
          purls.forall(p => p.canonicalize().nn.contains("sig-alg="))
        case _ => true
      }
    }
  }

  property(
    "[PROP] CRL crl-sha256 hash matches SHA-256(DER CRL bytes) computed independently"
  ) {
    forAll(genCrl) { path =>
      val w = FileWrapper(new JFile(path), path, None)
      Certificates.classifyAndParse(w) match {
        case Some(crl: Certificates.Crl) =>
          val derBytes = crl.crl.getEncoded
          val expectedHex = sha256Hex(derBytes)
          val state = new CertificatesState(w, Some(crl))
          val (purls, _) = state.getPurls(
            w,
            stubItem(),
            io.spicelabs.goatrodeo.omnibor.SingleMarker()
          )
          purls.exists(p => p.canonicalize().nn.contains(expectedHex))
        case _ => true
      }
    }
  }

  // ===== Stratified per-algorithm-case tests (A1 in v2 review) ===========
  //
  // The `forAll` properties above cover the 8 algorithm cases via
  // random sampling. With 50 runs / 8 cases, every case is
  // statistically exercised — but a particular run could miss e.g.
  // Ed448 if the random seed never picked it. The stratified tests
  // below run each algorithm case ONCE, GUARANTEED, so a regression
  // specific to (say) ECDSA-P521 cannot slip through a green run.

  perCase("SPKI roundtrip") { c =>
    val (_, cert) = buildSelfSignedCert(c)
    val (parsed, purls) = driveThroughStrategy(cert)
    val expectedSpkiHex = sha256Hex(Certificates.spkiBytesFromCert(parsed))
    val spkiPurl = purls.find(_.contains("spki-sha256")).get
    assert(
      spkiPurl.contains(expectedSpkiHex),
      s"${c.displayName}: spki hash mismatch"
    )
  }

  perCase("emitted alg= matches expected") { c =>
    val (_, cert) = buildSelfSignedCert(c)
    val (_, purls) = driveThroughStrategy(cert)
    purls.foreach { purl =>
      assert(
        purl.contains(s"alg=${c.expectedAlg}"),
        s"${c.displayName}: pURL missing alg=${c.expectedAlg}: $purl"
      )
    }
  }

  perCase("RSA→size, EC→curve, Ed→neither size nor curve") { c =>
    val (_, cert) = buildSelfSignedCert(c)
    val (_, purls) = driveThroughStrategy(cert)
    val spkiPurl = purls.find(_.contains("spki-sha256")).get
    c match {
      case _: RsaCase =>
        assert(
          "size=[0-9]+".r.findFirstIn(spkiPurl).isDefined,
          s"${c.displayName}: missing size= qualifier"
        )
        assert(
          !spkiPurl.contains("curve="),
          s"${c.displayName}: should not have curve="
        )
      case _: EcCase =>
        assert(
          spkiPurl.contains("curve="),
          s"${c.displayName}: missing curve= qualifier"
        )
        assert(
          !spkiPurl.contains("size="),
          s"${c.displayName}: should not have size="
        )
      case Ed25519Case | Ed448Case =>
        assert(
          !spkiPurl.contains("size="),
          s"${c.displayName}: should not have size="
        )
        assert(
          !spkiPurl.contains("curve="),
          s"${c.displayName}: should not have curve="
        )
    }
  }

  // ===== Helper ==========================================================

  private def stubItem(): io.spicelabs.goatrodeo.omnibor.Item = {
    import io.spicelabs.goatrodeo.omnibor.{Item, ItemMetaData}
    import io.spicelabs.goatrodeo.util.Gitoid
    Item(
      identifier = Gitoid("gitoid:blob:sha256:phase8-property-stub"),
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
  }
}
