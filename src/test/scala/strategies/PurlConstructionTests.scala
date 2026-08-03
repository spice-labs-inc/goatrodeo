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

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.coordinates.Purl
import io.spicelabs.goatrodeo.util.ByteWrapper
import munit.FunSuite
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/** Red-phase tests demonstrating that the current string-concat pURL
  * construction crashes on adversarial qualifier values.
  *
  * ## What these tests test
  *
  * Every test calls an existing `private[strategies]` method with an input that
  * produces a qualifier value containing characters illegal in a pURL string.
  * The test asserts that the method returns a valid `PackageURL` that
  * round-trips through the strict parser `new PackageURL(canonicalize)`.
  *
  * ## Why these tests exist
  *
  * Some pURL construction methods build URLs by formatting qualifiers as
  * `"key=value"` strings, concatenating them with `&`, and embedding the result
  * in a URL string passed to `new PackageURL(String)`. The `PackageURL(String)`
  * constructor is a strict URI parser that rejects characters like `<` and `>`
  * in the query component. When a qualifier value contains such characters
  * (e.g. the `<unknown-sig-oid-1.2.840.113549.1.1.2>` fallback from
  * `canonicalSigAlg`), the constructor throws `MalformedPackageURLException`.
  *
  * Other methods have already been migrated to `PackageURLBuilder` which
  * handles encoding internally. These tests define the target behavior for the
  * remaining methods: they must return valid `PackageURL` instances regardless
  * of qualifier value content.
  *
  * ## Current state (Red)
  *
  * Tests 2, 3, and 4 fail at runtime with `MalformedPackageURLException`
  * because qualifier values contain `<` and `>` which are illegal when
  * raw-interpolated via `new PackageURL(String)`. Tests 5-6 fail because `new
  * PackageURL(s"...")` call sites still exist in the source code. Tests 1, 7,
  * 8, and 9 pass on the current code because those methods have already been
  * migrated to `PackageURLBuilder`.
  *
  * ## Green state
  *
  * All tests pass after the remaining string-concat sites are replaced with
  * `PackageURLBuilder` construction.
  */
class PurlConstructionTests extends FunSuite {

  if (Security.getProvider("BC") == null) {
    Security.addProvider(
      new org.bouncycastle.jce.provider.BouncyCastleProvider()
    )
  }

  private val dummyArtifact: ByteWrapper =
    ByteWrapper(Array.empty[Byte], "dummy", None)

  private def assertRoundTrips(purl: Purl, clue: String): Unit = {
    val canonical = purl.toCanonical()
    val reparsed = Purl.parse(canonical)
    assertEquals(
      reparsed.toCanonical(),
      canonical,
      s"$clue — pURL must round-trip through the parser: $canonical"
    )
    // Adversarial qualifier values (e.g. the `<unknown-sig-oid-…>` fallback)
    // must be percent-encoded in the canonical form, never emitted raw.
    assert(
      !canonical.contains("<") && !canonical.contains(">"),
      s"$clue — angle brackets must be percent-encoded: $canonical"
    )
  }

  // ===== Test 1: SSH cert pURLs (already migrated to builder) ==========
  //
  // These pass on the current code. They are regression guards.

  test(
    "purlsForSshCert with @ in caSigAlgName should return two valid pURLs that round-trip"
  ) {
    val cert = Certificates.SshCert(
      certBytes = Array(0x00, 0x01, 0x02),
      certTypeName = "ssh-rsa-cert-v01@openssh.com",
      signedKeyWire = Array(0x03, 0x04, 0x05),
      signedKeyAlgName = "ssh-rsa",
      rsaModulusBits = Some(2048),
      serial = BigInt(0),
      certType = 1L,
      keyId = "test",
      principals = Vector.empty,
      validAfter = 0L,
      validBefore = 0L,
      criticalOptions = Vector.empty,
      extensions = Vector.empty,
      caKeyWire = Array(0x06, 0x07),
      caSigAlgName = "ssh-rsa-cert-v01@openssh.com",
      comment = None
    )
    val state = new CertificatesState(dummyArtifact)
    val purls = state.purlsForSshCert(cert)
    assertEquals(purls.length, 2)
    purls.zipWithIndex.foreach { case (p, i) =>
      assertRoundTrips(p, s"SSH cert pURL #$i")
    }
  }

  test(
    "purlsForSshCert with unknown certType should return two valid pURLs containing cert-type=unknown-99"
  ) {
    val cert = Certificates.SshCert(
      certBytes = Array(0x00, 0x01, 0x02),
      certTypeName = "ssh-rsa-cert-v01@openssh.com",
      signedKeyWire = Array(0x03, 0x04, 0x05),
      signedKeyAlgName = "ssh-rsa",
      rsaModulusBits = Some(2048),
      serial = BigInt(0),
      certType = 99L,
      keyId = "test",
      principals = Vector.empty,
      validAfter = 0L,
      validBefore = 0L,
      criticalOptions = Vector.empty,
      extensions = Vector.empty,
      caKeyWire = Array(0x06, 0x07),
      caSigAlgName = "ssh-rsa",
      comment = None
    )
    val state = new CertificatesState(dummyArtifact)
    val purls = state.purlsForSshCert(cert)
    assertEquals(purls.length, 2)
    val certPurl = purls.find(_.toCanonical().contains("cert-sha256")).get
    assertRoundTrips(certPurl, "SSH cert pURL with unknown certType")
    assert(
      certPurl.toCanonical().contains("cert-type=unknown-99"),
      s"cert pURL must contain cert-type=unknown-99, got: ${certPurl.toCanonical()}"
    )
    purls.foreach(p => assertRoundTrips(p, "SSH cert pURL"))
  }

  // ===== Test 2: purlForPgpKey with angle-bracket canonicalAlg =========
  //
  // `purlForPgpKey` uses `new PackageURL(s"pkg:generic/pgp/fingerprint@...?$qual")`
  // where `qual` is built from `ListBuffer[String]` of `"key=value"` pairs
  // concatenated with `&`. If any qualifier value contains `<` or `>`,
  // the `PackageURL(String)` constructor throws `MalformedPackageURLException`.
  //
  // This test constructs a `PgpKey` with `canonicalAlg = "<unknown-alg-99>"`
  // — the same pattern as the `sigAlgOidMap` fallback. The method must
  // return a valid pURL regardless of qualifier value content.

  test(
    "purlForPgpKey with angle-bracket canonicalAlg should return valid pURL that round-trips"
  ) {
    val key = Certificates.PgpKey(
      fingerprintHex = "aabbccdd" * 8,
      version = 4,
      pgpAlgId = 99,
      canonicalAlg = "<unknown-alg-99>",
      keySize = None,
      curve = None,
      isPrimary = true,
      creationTime = new Date(),
      expirationTime = None,
      userIds = Vector("test@example.com")
    )
    val purl = Certificates.purlForPgpKey(key)
    assertRoundTrips(purl, "PGP key pURL with angle-bracket canonicalAlg")
    assert(
      purl.toCanonical().contains("alg="),
      s"PGP pURL must contain alg= qualifier, got: ${purl.toCanonical()}"
    )
  }

  // ===== Test 3: purlsForCert with unknown sig OID =====================
  //
  // `purlsForCert` uses `new PackageURL(s"pkg:generic/x509/cert-sha256@...?$qual")`
  // where `qual` is built from `Seq[String]` of `"key=value"` pairs. The
  // `sig-alg` qualifier receives the output of `canonicalSigAlg`, which
  // returns `<unknown-sig-oid-...>` for OIDs not in `sigAlgOidMap`. The
  // angle brackets crash `new PackageURL(String)`.
  //
  // This test builds a cert with a fabricated sig OID (`1.3.9999.9999.1`)
  // via a BC `ContentSigner` that reports the fake OID in the
  // AlgorithmIdentifier. Both the SPKI and cert pURLs must round-trip.

  test(
    "purlsForCert with unknown sig OID should return two valid pURLs that round-trip"
  ) {
    val kp = KeyPairGenerator.getInstance("RSA")
    kp.initialize(2048, new SecureRandom())
    val pair = kp.generateKeyPair()
    val cert = buildCertWithUnknownSigOid(pair)

    val sigAlg = Certificates.canonicalSigAlg(cert)
    assert(
      sigAlg.startsWith("<unknown-sig-oid-"),
      s"Expected unknown-sig-oid fallback, got: $sigAlg"
    )

    val purls = Certificates.purlsForCert(cert)
    assertEquals(purls.length, 2)
    purls.zipWithIndex.foreach { case (p, i) =>
      assertRoundTrips(p, s"X.509 cert pURL #$i with unknown sig OID")
    }
  }

  // ===== Test 4: purlForCrl with unknown sig OID =======================
  //
  // Same crash pattern as test 3 but on the CRL path. `purlForCrl` uses
  // `new PackageURL(s"pkg:generic/x509/crl-sha256@...?sig-alg=$sigAlg")` where
  // `sigAlg` comes from `canonicalSigAlgCrl` with the same fallback.

  test(
    "purlForCrl with unknown sig OID should return valid pURL that round-trips"
  ) {
    val kp = KeyPairGenerator.getInstance("RSA")
    kp.initialize(2048, new SecureRandom())
    val pair = kp.generateKeyPair()
    val crl = buildCrlWithUnknownSigOid(pair)

    val state = new CertificatesState(dummyArtifact)
    val purl = state.purlForCrl(crl)
    assertRoundTrips(purl, "CRL pURL with unknown sig OID")
  }

  // ===== Test 5: No string-concat pURL in Certificates.scala ===========

  test(
    "no new PackageURL(String) call sites in Certificates.scala production code"
  ) {
    val src = scala.io.Source.fromFile(
      "src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala"
    )
    val content = src.mkString
    src.close()
    val bannedPatterns = List(
      "new PackageURL(s\"",
      "new PackageURL(s'"
    )
    bannedPatterns.foreach { pattern =>
      assert(
        !content.contains(pattern),
        s"Certificates.scala must not contain '$pattern' — use PackageURLBuilder instead"
      )
    }
  }

  // ===== Test 6: No string-concat pURL in CertificatesState.scala ======

  test(
    "no new PackageURL(String) call sites in CertificatesState.scala production code"
  ) {
    val src = scala.io.Source.fromFile(
      "src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/CertificatesState.scala"
    )
    val content = src.mkString
    src.close()
    val bannedPatterns = List(
      "new PackageURL(s\"",
      "new PackageURL(s'"
    )
    bannedPatterns.foreach { pattern =>
      assert(
        !content.contains(pattern),
        s"CertificatesState.scala must not contain '$pattern' — use PackageURLBuilder instead"
      )
    }
  }

  // ===== Test 7: Private key pURLs (already migrated to builder) =======

  test(
    "purlForPrivateKeyPem with angle-bracket params should return valid pURL that round-trips"
  ) {
    val pem = Certificates.PrivateKeyPlaintextPem(
      spkiBytes = Array.fill[Byte](32)(0x42),
      canonicalAlg = "ec",
      keySize = None,
      curve = None,
      params = Some("<unknown-params-1.2.840.10045.2.1>")
    )
    val state = new CertificatesState(dummyArtifact)
    val purl = state.purlForPrivateKeyPem(pem)
    assertRoundTrips(purl, "Private key pURL with angle-bracket params")
  }

  // ===== Test 8: PGP key with raw OID curve (already safe) =============

  test(
    "purlForPgpKey with raw OID curve should return valid pURL that round-trips"
  ) {
    val key = Certificates.PgpKey(
      fingerprintHex = "aabbccdd" * 8,
      version = 4,
      pgpAlgId = 18,
      canonicalAlg = "ec",
      keySize = None,
      curve = Some("1.3.132.0.34"),
      isPrimary = true,
      creationTime = new Date(),
      expirationTime = None,
      userIds = Vector("test@example.com")
    )
    val purl = Certificates.purlForPgpKey(key)
    assertRoundTrips(purl, "PGP key pURL with raw OID curve")
    assert(
      purl.toCanonical().contains("curve="),
      s"PGP pURL must contain curve= qualifier, got: ${purl.toCanonical()}"
    )
  }

  // ===== Test 9: Private key with OID params (already safe) ============

  test(
    "purlForPrivateKeyPem with OID params should return valid pURL that round-trips"
  ) {
    val pem = Certificates.PrivateKeyPlaintextPem(
      spkiBytes = Array.fill[Byte](32)(0x42),
      canonicalAlg = "ec",
      keySize = None,
      curve = Some("1.3.132.0.34"),
      params = Some("1.2.840.10045.2.1")
    )
    val state = new CertificatesState(dummyArtifact)
    val purl = state.purlForPrivateKeyPem(pem)
    assertRoundTrips(purl, "Private key pURL with OID params")
  }

  // ===== Structural invariant tests: pkg:generic/{namespace}/{name}@... ====
  //
  // These tests verify the pURL object structure (type, namespace, name)
  // for every crypto pURL construction method. They enforce the migration
  // from `pkg:{type}/{name}@...` to `pkg:generic/{namespace}/{name}@...`
  // per the user request.
  //
  // ## What these tests test
  //
  // Each test constructs a minimal input, calls the pURL construction method,
  // and asserts on the PackageURL field accessors (getType, getNamespace,
  // getName) rather than on string content. This is a stronger invariant
  // than string matching because it verifies the structural decomposition
  // the library performs, not just the serialized form.
  //
  // ## Why these tests exist
  //
  // The pURL spec defines `generic` as the correct type for non-ecosystem
  // identifiers. The crypto types (pgp, x509, ssh) are not registered pURL
  // types, so they must be expressed as namespaces under `generic`:
  //   pkg:generic/pgp/fingerprint@...
  //   pkg:generic/x509/spki-sha256@...
  //   pkg:generic/x509/cert-sha256@...
  //   pkg:generic/x509/crl-sha256@...
  //   pkg:generic/ssh/sha256@...
  //   pkg:generic/ssh/cert-sha256@...

  test(
    "purlForPgpKey returns pURL with type=generic, namespace=pgp, name=fingerprint"
  ) {
    val key = Certificates.PgpKey(
      fingerprintHex = "aabbccdd" * 8,
      version = 4,
      pgpAlgId = 1,
      canonicalAlg = "rsa",
      keySize = Some(2048),
      curve = None,
      isPrimary = true,
      creationTime = new Date(),
      expirationTime = None,
      userIds = Vector("test@example.com")
    )
    val purl = Certificates.purlForPgpKey(key)
    assertEquals(purl.`type`, "generic")
    assertEquals(purl.namespace, "pgp")
    assertEquals(purl.name, "fingerprint")
    assertRoundTrips(purl, "PGP pURL structural invariant")
  }

  test(
    "purlsForCert returns pURLs with type=generic, namespace=x509"
  ) {
    val kp = KeyPairGenerator.getInstance("RSA")
    kp.initialize(2048, new SecureRandom())
    val pair = kp.generateKeyPair()
    val cert = buildCertWithUnknownSigOid(pair)
    val purls = Certificates.purlsForCert(cert)
    assertEquals(purls.length, 2)
    purls.foreach { purl =>
      assertEquals(
        purl.`type`,
        "generic",
        s"cert pURL type must be generic, got ${purl.`type`}"
      )
      assertEquals(
        purl.namespace,
        "x509",
        s"cert pURL namespace must be x509, got ${purl.namespace}"
      )
    }
    val names = purls.map(_.name).toSet
    assert(
      names.contains("spki-sha256"),
      s"must contain spki-sha256 name, got: $names"
    )
    assert(
      names.contains("cert-sha256"),
      s"must contain cert-sha256 name, got: $names"
    )
    purls.foreach(p =>
      assertRoundTrips(p, "X.509 cert pURL structural invariant")
    )
  }

  test(
    "purlForCrl returns pURL with type=generic, namespace=x509, name=crl-sha256"
  ) {
    val kp = KeyPairGenerator.getInstance("RSA")
    kp.initialize(2048, new SecureRandom())
    val pair = kp.generateKeyPair()
    val crl = buildCrlWithUnknownSigOid(pair)
    val state = new CertificatesState(dummyArtifact)
    val purl = state.purlForCrl(crl)
    assertEquals(purl.`type`, "generic")
    assertEquals(purl.namespace, "x509")
    assertEquals(purl.name, "crl-sha256")
    assertRoundTrips(purl, "CRL pURL structural invariant")
  }

  test(
    "purlForSshPubkey returns pURL with type=generic, namespace=ssh, name=sha256"
  ) {
    val pubkey = Certificates.SshPubkey(
      wireBytes = Array(0x00, 0x01, 0x02, 0x03),
      algName = "ssh-ed25519",
      comment = None,
      rsaModulusBits = None
    )
    val state = new CertificatesState(dummyArtifact)
    val purl = state.purlForSshPubkey(pubkey)
    assertEquals(purl.`type`, "generic")
    assertEquals(purl.namespace, "ssh")
    assertEquals(purl.name, "sha256")
    assertRoundTrips(purl, "SSH pubkey pURL structural invariant")
  }

  test(
    "purlsForSshCert returns pURLs with type=generic, namespace=ssh"
  ) {
    val cert = Certificates.SshCert(
      certBytes = Array(0x00, 0x01, 0x02),
      certTypeName = "ssh-rsa-cert-v01@openssh.com",
      signedKeyWire = Array(0x03, 0x04, 0x05),
      signedKeyAlgName = "ssh-rsa",
      rsaModulusBits = Some(2048),
      serial = BigInt(0),
      certType = 1L,
      keyId = "test",
      principals = Vector.empty,
      validAfter = 0L,
      validBefore = 0L,
      criticalOptions = Vector.empty,
      extensions = Vector.empty,
      caKeyWire = Array(0x06, 0x07),
      caSigAlgName = "ssh-rsa",
      comment = None
    )
    val state = new CertificatesState(dummyArtifact)
    val purls = state.purlsForSshCert(cert)
    assertEquals(purls.length, 2)
    purls.foreach { purl =>
      assertEquals(
        purl.`type`,
        "generic",
        s"SSH cert pURL type must be generic, got ${purl.`type`}"
      )
      assertEquals(
        purl.namespace,
        "ssh",
        s"SSH cert pURL namespace must be ssh, got ${purl.namespace}"
      )
    }
    val names = purls.map(_.name).toSet
    assert(
      names.contains("cert-sha256"),
      s"must contain cert-sha256 name, got: $names"
    )
    assert(names.contains("sha256"), s"must contain sha256 name, got: $names")
    purls.foreach(p =>
      assertRoundTrips(p, "SSH cert pURL structural invariant")
    )
  }

  test(
    "purlForPrivateKeyPem returns pURL with type=generic, namespace=x509, name=spki-sha256"
  ) {
    val pem = Certificates.PrivateKeyPlaintextPem(
      spkiBytes = Array.fill[Byte](32)(0x42),
      canonicalAlg = "rsa",
      keySize = Some(2048),
      curve = None,
      params = None
    )
    val state = new CertificatesState(dummyArtifact)
    val purl = state.purlForPrivateKeyPem(pem)
    assertEquals(purl.`type`, "generic")
    assertEquals(purl.namespace, "x509")
    assertEquals(purl.name, "spki-sha256")
    assertRoundTrips(purl, "Private key PEM pURL structural invariant")
  }

  test(
    "purlForPrivateKeyOpenSsh returns pURL with type=generic, namespace=ssh, name=sha256"
  ) {
    val key = Certificates.PrivateKeyPlaintextOpenSsh(
      wireBytes = Array(0x00, 0x01, 0x02, 0x03),
      algName = "ssh-ed25519",
      rsaModulusBits = None
    )
    val state = new CertificatesState(dummyArtifact)
    val purl = state.purlForPrivateKeyOpenSsh(key)
    assertEquals(purl.`type`, "generic")
    assertEquals(purl.namespace, "ssh")
    assertEquals(purl.name, "sha256")
    assertRoundTrips(purl, "Private key OpenSSH pURL structural invariant")
  }

  // ===== Helpers ========================================================

  /** Fabricated OID guaranteed not to be in `sigAlgOidMap`. Arc 1.3 is
    * ISO-identified org; the deep sub-arc avoids collision with any standard
    * signature algorithm OID while remaining syntactically valid for BC's
    * `ASN1ObjectIdentifier` constructor.
    */
  private val UnknownSigOid = "1.3.9999.9999.1"

  /** Build a self-signed X.509 certificate whose signature algorithm OID is
    * [[UnknownSigOid]]. The cert is actually signed with SHA-256/RSA (so the
    * bytes are well-formed), but the AlgorithmIdentifier reports the fabricated
    * OID. This causes `canonicalSigAlg` to fall through to the
    * `<unknown-sig-oid-1.3.9999.9999.1>` string.
    */
  private def buildCertWithUnknownSigOid(
      pair: KeyPair
  ): X509Certificate = {
    val subject = new X500Name("CN=PurlUnknownOidTest, O=GoatRodeo")
    val notBefore = new Date()
    val cal = Calendar.getInstance()
    cal.setTime(notBefore)
    cal.add(Calendar.YEAR, 1)
    val notAfter = cal.getTime
    val serial = new BigInteger(64, new SecureRandom())
    val builder = new JcaX509v3CertificateBuilder(
      subject,
      serial,
      notBefore,
      notAfter,
      subject,
      pair.getPublic
    )
    val realSigner = new JcaContentSignerBuilder("SHA256withRSA")
      .setProvider("BC")
      .build(pair.getPrivate)
    val signerWithFakeOid = new ContentSigner {
      override def getAlgorithmIdentifier: AlgorithmIdentifier =
        new AlgorithmIdentifier(
          new ASN1ObjectIdentifier(UnknownSigOid),
          null
        )
      override def getOutputStream: OutputStream = realSigner.getOutputStream
      override def getSignature: Array[Byte] = realSigner.getSignature
    }
    val holder = builder.build(signerWithFakeOid)
    new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
  }

  /** Build a CRL whose signature algorithm OID is [[UnknownSigOid]]. */
  private def buildCrlWithUnknownSigOid(
      pair: KeyPair
  ): X509CRL = {
    val issuer = new javax.security.auth.x500.X500Principal(
      "CN=PurlUnknownOidTest, O=GoatRodeo"
    )
    val now = new Date()
    val cal = Calendar.getInstance()
    cal.setTime(now)
    cal.add(Calendar.YEAR, 1)
    val nextUpdate = cal.getTime
    val builder = new JcaX509v2CRLBuilder(issuer, nextUpdate)
    val realSigner = new JcaContentSignerBuilder("SHA256withRSA")
      .setProvider("BC")
      .build(pair.getPrivate)
    val signerWithFakeOid = new ContentSigner {
      override def getAlgorithmIdentifier: AlgorithmIdentifier =
        new AlgorithmIdentifier(
          new ASN1ObjectIdentifier(UnknownSigOid),
          null
        )
      override def getOutputStream: OutputStream = realSigner.getOutputStream
      override def getSignature: Array[Byte] = realSigner.getSignature
    }
    val holder = builder.build(signerWithFakeOid)
    val cf = java.security.cert.CertificateFactory.getInstance("X.509", "BC")
    cf.generateCRL(
      new java.io.ByteArrayInputStream(holder.getEncoded)
    ).asInstanceOf[X509CRL]
  }
}
