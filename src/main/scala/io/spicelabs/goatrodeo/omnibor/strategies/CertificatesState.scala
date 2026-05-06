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

import com.github.packageurl.PackageURL
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?

import java.security.KeyStore
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*
import scala.util.Try

/* Phase-7 second-pass remediation: split out of Certificates.scala
 * to bring the parent file under the inv #9 token limit. CLAUDE.md
 * inv #9: "no document contains more than 20000 tokens. This applies
 * to source code files as well." This file's contents are unchanged
 * vs. the pre-split state — this is a pure relocation. */

/** Per-artifact processing state.
  *
  * Construction shapes:
  *   - `new CertificatesState(artifact)` — empty state; used by the
  *     Phase-1 [STUB] tests in `CertificatesStubTests`. All five
  *     methods pass through (identity / empty).
  *   - `new CertificatesState(artifact, Some(claim))` — Phase-3+
  *     production path; dispatches on claim variant.
  *
  * @param artifact the artifact under processing
  * @param claim    the parsed claim variant (Phase 3 SingleCert,
  *                 Phase 4 Keystore / Bundle / Crl) or `None` for
  *                 Phase-1 stub state
  */
class CertificatesState(
    artifact: ArtifactWrapper,
    claim: Option[Certificates.ClaimedContent] = None,
) extends ProcessingState[SingleMarker, CertificatesState] {

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): CertificatesState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (Vector[PackageURL], CertificatesState) = {
    import Certificates.*
    val purls: Vector[PackageURL] = claim match {
      case None => Vector.empty
      case Some(SingleCert(c)) => purlsForCert(c)
      case Some(Bundle(certs)) =>
        certs.flatMap(purlsForCert).distinctBy(_.canonicalize())
      case Some(ks @ Keystore(Some(keystore), _, _)) =>
        ksAllCerts(keystore).flatMap(purlsForCert).distinctBy(_.canonicalize())
      case Some(Keystore(None, _, _)) =>
        Vector.empty // encrypted → envelope-only; no pURLs
      case Some(Crl(crl)) =>
        Vector(purlForCrl(crl))
      case Some(p: SshPubkey) =>
        Vector(purlForSshPubkey(p))
      case Some(c: SshCert) =>
        purlsForSshCert(c)
      case Some(r: PgpKeyRing) =>
        r.keys.map(purlForPgpKey).distinctBy(_.canonicalize())
      case Some(p: PrivateKeyPlaintextPem) =>
        Vector(purlForPrivateKeyPem(p))
      case Some(p: PrivateKeyPlaintextOpenSsh) =>
        Vector(purlForPrivateKeyOpenSsh(p))
      case Some(p: PrivateKeyPlaintextPgp) =>
        p.ring.keys.map(purlForPgpKey).distinctBy(_.canonicalize())
      case Some(_: PrivateKeyEncrypted) =>
        Vector.empty // envelope-only; no pURL
    }
    purls -> this
  }

  /** Phase-7 unencrypted-PEM private key → SPKI pURL (same shape as
    * Phase 3's spki-sha256 pURL: `pkg:x509/spki-sha256@{hex}?alg=…`).
    * No `cert-sha256` companion because there is no certificate. */
  private[strategies] def purlForPrivateKeyPem(
      p: Certificates.PrivateKeyPlaintextPem
  ): PackageURL = {
    import Certificates.*
    val spkiSha = sha256Hex(p.spkiBytes)
    val parts = scala.collection.mutable.ListBuffer[String](s"alg=${p.canonicalAlg}")
    p.keySize.foreach(s => parts += s"size=$s")
    p.curve.foreach(c => parts += s"curve=$c")
    p.params.foreach(pa => parts += s"params=$pa")
    val qual = parts.sorted.mkString("&")
    new PackageURL(s"pkg:x509/spki-sha256@$spkiSha?$qual")
  }

  /** Phase-7 unencrypted-OpenSSH private key → SSH pURL (same shape as
    * Phase 5's plain-pubkey pURL: `pkg:ssh/sha256@{b64}?alg=…`). */
  private[strategies] def purlForPrivateKeyOpenSsh(
      p: Certificates.PrivateKeyPlaintextOpenSsh
  ): PackageURL = {
    import Certificates.*
    val fp = sshFingerprintB64(p.wireBytes)
    val quals = sshKeyQualifiers(p.algName, p.rsaModulusBits)
    val qualStr = quals.sorted.mkString("&")
    new PackageURL(s"pkg:ssh/sha256@$fp?$qualStr")
  }

  /** SSH plain-pubkey pURL: `pkg:ssh/sha256@{b64}?alg=...&{companion}`. */
  private[strategies] def purlForSshPubkey(p: Certificates.SshPubkey): PackageURL = {
    import Certificates.*
    val fp = sshFingerprintB64(p.wireBytes)
    val quals = sshKeyQualifiers(p.algName, p.rsaModulusBits)
    val qualStr = quals.sorted.mkString("&")
    new PackageURL(s"pkg:ssh/sha256@$fp?$qualStr")
  }

  /** SSH cert pURLs: cert-sha256 (cert wire blob) + sha256 (signed-key
    * fingerprint). Returns both, in stable canonical-form order. */
  private[strategies] def purlsForSshCert(c: Certificates.SshCert): Vector[PackageURL] = {
    import Certificates.*
    val certHex = sha256Hex(c.certBytes)
    val signedKeyFp = sshFingerprintB64(c.signedKeyWire)
    val keyQuals = sshKeyQualifiers(c.signedKeyAlgName, c.rsaModulusBits)
    val signedKeyQualStr = keyQuals.sorted.mkString("&")
    val certTypeLabel = c.certType match {
      case 1L => "user"
      case 2L => "host"
      case other => s"unknown-$other"
    }
    val certQuals = (keyQuals ++ Vector(
      s"cert-type=$certTypeLabel",
      s"sig-alg=${c.caSigAlgName}",
    )).sorted
    Vector(
      new PackageURL(s"pkg:ssh/cert-sha256@$certHex?${certQuals.mkString("&")}"),
      new PackageURL(s"pkg:ssh/sha256@$signedKeyFp?$signedKeyQualStr"),
    )
  }

  /** Extract every X.509 cert from a loaded keystore, including key-
    * entry chain certs. NEVER calls `getKey(alias)` — that returns
    * private-key material. */
  private def ksAllCerts(ks: KeyStore): Vector[X509Certificate] = {
    Try {
      val acc = scala.collection.mutable.ListBuffer[X509Certificate]()
      val aliases = ks.aliases().asScala
      aliases.foreach { alias =>
        if (ks.isCertificateEntry(alias)) {
          ks.getCertificate(alias) match {
            case x: X509Certificate => acc += x
            case _ => ()
          }
        } else if (ks.isKeyEntry(alias)) {
          val chain = Option(ks.getCertificateChain(alias))
            .map(_.toIndexedSeq).getOrElse(IndexedSeq.empty)
          chain.collect { case x: X509Certificate => x }
            .foreach(acc += _)
        }
      }
      acc.toVector
    }.getOrElse(Vector.empty)
  }

  /** Build the single CRL pURL: `pkg:x509/crl-sha256@{hex}?sig-alg=...`
    * (qualifiers alphabetical). The `issuer-spki-sha256` qualifier is
    * omitted because deriving it requires the issuer's certificate,
    * which a CRL alone doesn't carry — per plan: "If the AKI extension
    * is absent or doesn't include the key identifier hash, omit the
    * qualifier". */
  private[strategies] def purlForCrl(crl: X509CRL): PackageURL = {
    import Certificates.*
    val derBytes = crl.getEncoded
    val crlSha = sha256Hex(derBytes)
    val sigAlg = canonicalSigAlgCrl(crl)
    new PackageURL(s"pkg:x509/crl-sha256@$crlSha?sig-alg=$sigAlg")
  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], CertificatesState) = {
    import Certificates.*
    val tm: TreeMap[String, TreeSet[StringOrPair]] = claim match {
      case None => TreeMap.empty[String, TreeSet[StringOrPair]]
      case Some(SingleCert(c)) => singleCertMetadata(c)
      case Some(Bundle(certs)) => bundleMetadata(artifact, certs)
      case Some(k @ Keystore(_, _, _)) => keystoreMetadata(artifact, k)
      case Some(Crl(crl)) => crlMetadata(artifact, crl)
      case Some(p: SshPubkey) => sshPubkeyMetadata(artifact, p)
      case Some(c: SshCert) => sshCertMetadata(artifact, c)
      case Some(r: PgpKeyRing) => pgpKeyRingMetadata(artifact, r)
      case Some(p: PrivateKeyPlaintextPem) =>
        privateKeyPemMetadata(artifact, p)
      case Some(p: PrivateKeyPlaintextOpenSsh) =>
        privateKeyOpenSshMetadata(artifact, p)
      case Some(p: PrivateKeyPlaintextPgp) =>
        privateKeyPgpMetadata(artifact, p)
      case Some(p: PrivateKeyEncrypted) =>
        privateKeyEncryptedMetadata(artifact, p)
    }
    Certificates.assertNoLeak(tm)
    tm -> this
  }

  /** Phase-7: metadata for an unencrypted PEM private key. Public-key
    * fields only (algorithm, size/curve/params, SPKI sha-256), plus
    * `Certificates:DerivedFromPrivateKey=true` and
    * `Certificates:Envelope=plaintext`. No certificate-specific fields
    * (Subject, Issuer, NotBefore/After, Serial) — the input is a key,
    * not a cert. */
  private[strategies] def privateKeyPemMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.PrivateKeyPlaintextPem,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val spkiSha = sha256Hex(p.spkiBytes)
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(filenameStem(artifact.path())))) +?
      Some(MKC.DESCRIPTION ->
        TreeSet(StringOrPair("Unencrypted private key (public key derived)"))) +?
      Some(adHoc("Envelope") -> TreeSet(StringOrPair("plaintext"))) +?
      Some(adHoc("DerivedFromPrivateKey") -> TreeSet(StringOrPair("true"))) +?
      Some(adHoc("KeyAlgorithm") -> TreeSet(StringOrPair(p.canonicalAlg))) +?
      Some(adHoc("SpkiSha256") -> TreeSet(StringOrPair(spkiSha)))
    p.keySize.foreach(s =>
      tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(s.toString))))
    p.curve.foreach(c =>
      tm = tm + (adHoc("Curve") -> TreeSet(StringOrPair(c))))
    p.params.foreach(pa =>
      tm = tm + (adHoc("Params") -> TreeSet(StringOrPair(pa))))
    tm
  }

  /** Phase-7: metadata for an unencrypted OpenSSH private key. Public-
    * key fields only — Phase 5's plain-pubkey shape minus the comment
    * (the OpenSSH v1 envelope's comment lives in the encrypted region;
    * we never read it on the unencrypted path either, by symmetry) —
    * plus the two Phase 7 envelope markers. */
  private[strategies] def privateKeyOpenSshMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.PrivateKeyPlaintextOpenSsh,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val (canon, companion, sk) = sshAlgMap(p.algName)
    val fpFull = s"SHA-256:${sshFingerprintB64(p.wireBytes)}"
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(filenameStem(artifact.path())))) +?
      Some(MKC.DESCRIPTION ->
        TreeSet(StringOrPair("Unencrypted OpenSSH private key (public key derived)"))) +?
      Some(adHoc("Envelope") -> TreeSet(StringOrPair("plaintext"))) +?
      Some(adHoc("DerivedFromPrivateKey") -> TreeSet(StringOrPair("true"))) +?
      Some(adHoc("KeyAlgorithm") -> TreeSet(StringOrPair(canon))) +?
      Some(adHoc("SshFingerprintSha256") -> TreeSet(StringOrPair(fpFull)))
    companion match {
      case Some(("size", _)) =>
        // RSA — use real modulus bits
        p.rsaModulusBits.foreach { b =>
          tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(b.toString)))
        }
      case Some(("curve", v)) =>
        tm = tm + (adHoc("Curve") -> TreeSet(StringOrPair(v)))
      case _ =>
    }
    if (p.algName == "ssh-dss") {
      // DSS is fixed-size 1024 per the sshAlgMap entry; no rsaModulusBits.
      companion.foreach { case (k, v) =>
        if (k == "size")
          tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(v)))
      }
    }
    if (sk) {
      tm = tm + (adHoc("SshIsSecurityKey") -> TreeSet(StringOrPair("true")))
    }
    tm
  }

  /** Phase-7: metadata for an unencrypted PGP secret-key ring.
    *
    * Reuses Phase 6's `pgpKeyRingMetadata` (which builds the per-key
    * namespaced fields plus `MKC.NAME` and `Certificates:PgpKeyCount`)
    * and ADDS the two Phase-7 envelope markers (`Envelope=plaintext`
    * and `DerivedFromPrivateKey=true`).
    *
    * Hard-rule reinforcement: the PGP keys inside `p.ring` were
    * derived via `PGPSecretKey.getPublicKey` — the public portion is
    * stored separately inside the secret-key packet and is not the
    * private key material. The leak sweep in `getMetadata`'s call site
    * still runs after this method returns. */
  private[strategies] def privateKeyPgpMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.PrivateKeyPlaintextPgp,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val base = pgpKeyRingMetadata(artifact, p.ring)
    base +
      (adHoc("Envelope") -> TreeSet(StringOrPair("plaintext"))) +
      (adHoc("DerivedFromPrivateKey") -> TreeSet(StringOrPair("true")))
  }

  /** Phase-7: envelope-only metadata for any encrypted private key.
    * Emits `Certificates:Envelope`, `Certificates:KdfAlgorithm` (when
    * available), `Certificates:KdfIterations`, `Certificates:KdfPrf`,
    * `Certificates:Cipher`. NO key-derived fields. */
  private[strategies] def privateKeyEncryptedMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.PrivateKeyEncrypted,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(filenameStem(artifact.path())))) +?
      Some(MKC.DESCRIPTION ->
        TreeSet(StringOrPair("Encrypted private key (envelope metadata only)"))) +?
      Some(adHoc("Envelope") -> TreeSet(StringOrPair(p.envelope)))
    p.kdfAlgorithm.foreach(v =>
      tm = tm + (adHoc("KdfAlgorithm") -> TreeSet(StringOrPair(v))))
    p.kdfIterations.foreach(v =>
      tm = tm + (adHoc("KdfIterations") -> TreeSet(StringOrPair(v.toString))))
    p.kdfPrf.foreach(v =>
      tm = tm + (adHoc("KdfPrf") -> TreeSet(StringOrPair(v))))
    p.cipher.foreach(v =>
      tm = tm + (adHoc("Cipher") -> TreeSet(StringOrPair(v))))
    p.salt.foreach(v =>
      tm = tm + (adHoc("KdfSalt") -> TreeSet(StringOrPair(v))))
    p.iv.foreach(v =>
      tm = tm + (adHoc("Iv") -> TreeSet(StringOrPair(v))))
    tm
  }

  /** Phase-6 metadata: per-key namespaced under `Certificates:Key:{fp8}:`
    * plus a top-level `Certificates:PgpKeyCount`. */
  private def pgpKeyRingMetadata(
      artifact: ArtifactWrapper,
      r: Certificates.PgpKeyRing,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val nameSource = r.primaryUserId.getOrElse(filenameStem(artifact.path()))
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(nameSource))) +?
      Some(MKC.DESCRIPTION ->
        TreeSet(StringOrPair(s"PGP key ring (${r.keys.length} key${if (r.keys.length == 1) "" else "s"})"))) +?
      Some(adHoc("PgpKeyCount") -> TreeSet(StringOrPair(r.keys.length.toString)))

    r.keys.foreach { key =>
      val fp8 = pgpFp8(key)
      def k(field: String): String = adHoc(s"Key:$fp8:$field")
      tm = tm + (k("Fingerprint") -> TreeSet(StringOrPair(key.fingerprintHex)))
      tm = tm + (k("Version") -> TreeSet(StringOrPair(key.version.toString)))
      tm = tm + (k("KeyAlgorithm") -> TreeSet(StringOrPair(key.canonicalAlg)))
      key.keySize.foreach(s =>
        tm = tm + (k("KeySize") -> TreeSet(StringOrPair(s.toString))))
      key.curve.foreach(c =>
        tm = tm + (k("Curve") -> TreeSet(StringOrPair(c))))
      tm = tm + (k("IsPrimary") -> TreeSet(StringOrPair(key.isPrimary.toString)))
      tm = tm + (k("CreationTime") -> TreeSet(StringOrPair(isoUtc(key.creationTime))))
      key.expirationTime.foreach(d =>
        tm = tm + (k("ExpirationTime") -> TreeSet(StringOrPair(isoUtc(d)))))
      if (key.isPrimary && key.userIds.nonEmpty) {
        tm = tm + (k("UserIds") -> TreeSet(StringOrPair(key.userIds.mkString(","))))
      }
    }
    tm
  }

  /** Test-accessible alias for the otherwise-private SSH metadata
    * builders. Used by the sidecar materializer to ensure the sidecars
    * stay in lockstep with the strategy. */
  private[strategies] def invokeSshPubkeyMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.SshPubkey,
  ): TreeMap[String, TreeSet[StringOrPair]] = sshPubkeyMetadata(artifact, p)

  private[strategies] def invokeSshCertMetadata(
      artifact: ArtifactWrapper,
      c: Certificates.SshCert,
  ): TreeMap[String, TreeSet[StringOrPair]] = sshCertMetadata(artifact, c)

  private[strategies] def invokePgpKeyRingMetadata(
      artifact: ArtifactWrapper,
      r: Certificates.PgpKeyRing,
  ): TreeMap[String, TreeSet[StringOrPair]] = pgpKeyRingMetadata(artifact, r)

  /** Plain-pubkey metadata table. */
  private def sshPubkeyMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.SshPubkey,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val (canon, companion, sk) = sshAlgMap(p.algName)
    val nameSource = p.comment.getOrElse(filenameStem(artifact.path()))
    val fpFull = s"SHA-256:${sshFingerprintB64(p.wireBytes)}"
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(nameSource))) +?
      Some(MKC.DESCRIPTION ->
        TreeSet(StringOrPair(s"OpenSSH public key ($canon)"))) +?
      Some(adHoc("KeyAlgorithm") -> TreeSet(StringOrPair(canon))) +?
      Some(adHoc("SshFingerprintSha256") -> TreeSet(StringOrPair(fpFull)))
    if (canon == "rsa") {
      p.rsaModulusBits.foreach { b =>
        tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(b.toString)))
      }
    }
    companion match {
      case Some(("size", v)) =>
        tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(v)))
      case Some(("curve", v)) =>
        tm = tm + (adHoc("Curve") -> TreeSet(StringOrPair(v)))
      case _ => ()
    }
    if (sk) {
      tm = tm + (adHoc("SshIsSecurityKey") -> TreeSet(StringOrPair("true")))
    }
    p.comment.foreach { c =>
      tm = tm + (adHoc("SshComment") -> TreeSet(StringOrPair(c)))
    }
    tm
  }

  /** OpenSSH cert metadata table — plain-pubkey fields for the signed
    * key plus cert-specific fields. */
  private def sshCertMetadata(
      artifact: ArtifactWrapper,
      c: Certificates.SshCert,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val (canon, companion, sk) = sshAlgMap(c.signedKeyAlgName)
    val signedFp = s"SHA-256:${sshFingerprintB64(c.signedKeyWire)}"
    val caFp = s"SHA-256:${sshFingerprintB64(c.caKeyWire)}"
    val certHex = sha256Hex(c.certBytes)
    val certTypeLabel = c.certType match {
      case 1L => "user"
      case 2L => "host"
      case other => s"unknown-$other"
    }
    val nameSource = c.comment.getOrElse(filenameStem(artifact.path()))
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(nameSource))) +?
      Some(MKC.DESCRIPTION ->
        TreeSet(StringOrPair(s"OpenSSH $certTypeLabel certificate ($canon)"))) +?
      Some(adHoc("KeyAlgorithm") -> TreeSet(StringOrPair(canon))) +?
      Some(adHoc("SshFingerprintSha256") -> TreeSet(StringOrPair(signedFp))) +?
      Some(adHoc("SshCertSha256") -> TreeSet(StringOrPair(certHex))) +?
      Some(adHoc("SshCertType") -> TreeSet(StringOrPair(certTypeLabel))) +?
      Some(adHoc("SshCertSerial") -> TreeSet(StringOrPair(c.serial.toString))) +?
      Some(adHoc("SshCertKeyId") -> TreeSet(StringOrPair(c.keyId))) +?
      Some(adHoc("SshCertValidAfter") ->
        TreeSet(StringOrPair(sshCertTimeLabel(c.validAfter, sentinelLabel = "always")))) +?
      Some(adHoc("SshCertValidBefore") ->
        TreeSet(StringOrPair(sshCertTimeLabel(c.validBefore, sentinelLabel = "forever")))) +?
      Some(adHoc("SshCertCaFingerprint") -> TreeSet(StringOrPair(caFp))) +?
      Some(adHoc("SshCertSigAlgorithm") -> TreeSet(StringOrPair(c.caSigAlgName)))

    if (canon == "rsa") {
      c.rsaModulusBits.foreach { b =>
        tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(b.toString)))
      }
    }
    companion match {
      case Some(("size", v)) =>
        tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(v)))
      case Some(("curve", v)) =>
        tm = tm + (adHoc("Curve") -> TreeSet(StringOrPair(v)))
      case _ => ()
    }
    if (sk) {
      tm = tm + (adHoc("SshIsSecurityKey") -> TreeSet(StringOrPair("true")))
    }
    if (c.principals.nonEmpty) {
      tm = tm + (adHoc("SshCertPrincipals") ->
        TreeSet(StringOrPair(c.principals.mkString(","))))
    }
    if (c.criticalOptions.nonEmpty) {
      tm = tm + (adHoc("SshCertCriticalOptions") ->
        TreeSet(StringOrPair(c.criticalOptions.mkString(","))))
    }
    if (c.extensions.nonEmpty) {
      tm = tm + (adHoc("SshCertExtensions") ->
        TreeSet(StringOrPair(c.extensions.mkString(","))))
    }
    c.comment.foreach { co =>
      tm = tm + (adHoc("SshComment") -> TreeSet(StringOrPair(co)))
    }
    tm
  }

  // --- variant-specific metadata builders ---

  private def singleCertMetadata(
      c: X509Certificate
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val perCert = perCertMetadata(adHoc, c)
    val subject = c.getSubjectX500Principal
    val issuer = c.getIssuerX500Principal
    val version = c.getVersion
    val (alg, _) = keyAlgAndQualifier(c.getPublicKey, c)
    // PQC and composite certs append the alg suffix so the inventory makes
    // PQC presence obvious; classical algs stay bare to match the
    // historical sidecar contract from Phase 0b's `cert_sidecar.py`.
    val pqcAlgs = Set("ml-dsa", "slh-dsa", "falcon", "composite")
    val descSuffix = if (pqcAlgs.contains(alg)) s" ($alg)" else ""
    perCert +? Some(MKC.NAME -> TreeSet(StringOrPair(cnOrDn(subject)))) +?
      Some(MKC.PUBLISHER -> TreeSet(StringOrPair(cnOrDn(issuer)))) +?
      Some(MKC.DESCRIPTION -> TreeSet(StringOrPair(s"X.509 v$version certificate$descSuffix")))
  }

  private def bundleMetadata(
      artifact: ArtifactWrapper,
      certs: Vector[X509Certificate],
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val stem = filenameStem(artifact.path())
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(stem))) +?
      Some(adHoc("KeystoreType") -> TreeSet(StringOrPair("pem-bundle"))) +?
      Some(adHoc("EntryCount") -> TreeSet(StringOrPair(certs.length.toString))) +?
      Some(adHoc("CertCount") -> TreeSet(StringOrPair(certs.length.toString))) +?
      Some(adHoc("KeyEntryCount") -> TreeSet(StringOrPair("0")))
    certs.zipWithIndex.foreach { case (c, idx) =>
      val perCertAdHoc: String => String = sub =>
        MKC.adHoc("Certificates")(s"Cert:$idx:$sub")
      tm = tm ++ perCertMetadata(perCertAdHoc, c)
    }
    tm
  }

  private def keystoreMetadata(
      artifact: ArtifactWrapper,
      k: Certificates.Keystore,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val stem = filenameStem(artifact.path())
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(stem))) +?
      Some(adHoc("KeystoreType") -> TreeSet(StringOrPair(k.format)))
    k.ks match {
      case None =>
        // Encrypted / failed null-password load → envelope-only
        tm = tm + (adHoc("KeystoreEncrypted") -> TreeSet(StringOrPair("true")))
      case Some(ks) =>
        val aliases = Try(ks.aliases().asScala.toList).getOrElse(Nil)
        var certCount = 0
        var keyEntryCount = 0
        aliases.foreach { alias =>
          val perEntryPrefix = s"Entry:${urlEncodeAlias(alias)}:"
          val perEntryAdHoc: String => String = sub =>
            MKC.adHoc("Certificates")(s"$perEntryPrefix$sub")
          if (Try(ks.isCertificateEntry(alias)).getOrElse(false)) {
            certCount += 1
            ks.getCertificate(alias) match {
              case x: X509Certificate =>
                tm = tm ++ perCertMetadata(perEntryAdHoc, x)
              case _ => ()
            }
          } else if (Try(ks.isKeyEntry(alias)).getOrElse(false)) {
            keyEntryCount += 1
            // Hard rule: NEVER call ks.getKey(alias) — only the chain
            val chain = Option(ks.getCertificateChain(alias))
              .map(_.toIndexedSeq).getOrElse(IndexedSeq.empty)
            chain.zipWithIndex.foreach {
              case (x: X509Certificate, ci) =>
                val chainAdHoc: String => String = sub =>
                  MKC.adHoc("Certificates")(s"${perEntryPrefix}Chain:$ci:$sub")
                tm = tm ++ perCertMetadata(chainAdHoc, x)
              case _ => ()
            }
            certCount += chain.length
          }
        }
        tm = tm +
          (adHoc("EntryCount") -> TreeSet(StringOrPair(aliases.length.toString))) +
          (adHoc("CertCount") -> TreeSet(StringOrPair(certCount.toString))) +
          (adHoc("KeyEntryCount") -> TreeSet(StringOrPair(keyEntryCount.toString)))
    }
    tm
  }

  private def crlMetadata(
      artifact: ArtifactWrapper,
      crl: X509CRL,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val derBytes = crl.getEncoded
    val crlSha = sha256Hex(derBytes)
    val sigAlg = canonicalSigAlgCrl(crl)
    val issuer = crl.getIssuerX500Principal
    val stem = filenameStem(artifact.path())

    val revoked = Option(crl.getRevokedCertificates).map(_.asScala.toSeq).getOrElse(Seq.empty)
    val cap = 10000
    val serials = revoked.take(cap).map(r => r.getSerialNumber.toString(16))
    val truncated = revoked.length > cap

    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
      Some(MKC.NAME -> TreeSet(StringOrPair(stem))) +?
      Some(MKC.PUBLISHER -> TreeSet(StringOrPair(cnOrDn(issuer)))) +?
      Some(MKC.DESCRIPTION -> TreeSet(StringOrPair("X.509 Certificate Revocation List"))) +?
      Some(adHoc("IssuerDN") -> TreeSet(StringOrPair(dnString(issuer)))) +?
      Some(adHoc("ThisUpdate") -> TreeSet(StringOrPair(isoUtc(crl.getThisUpdate)))) +?
      Some(adHoc("SigAlgorithm") -> TreeSet(StringOrPair(sigAlg))) +?
      Some(adHoc("CrlSha256") -> TreeSet(StringOrPair(crlSha))) +?
      Some(adHoc("RevokedCount") -> TreeSet(StringOrPair(revoked.length.toString)))

    Option(crl.getNextUpdate).foreach { d =>
      tm = tm + (adHoc("NextUpdate") -> TreeSet(StringOrPair(isoUtc(d))))
    }
    crlNumber(crl).foreach { n =>
      tm = tm + (adHoc("CrlNumber") -> TreeSet(StringOrPair(n)))
    }
    if (serials.nonEmpty) {
      tm = tm + (adHoc("RevokedSerials") -> TreeSet(StringOrPair(serials.mkString(","))))
    }
    if (truncated) {
      tm = tm + (adHoc("RevokedTruncated") -> TreeSet(StringOrPair("true")))
    }
    tm
  }

  /** Decode the CRL Number extension (OID 2.5.29.20). */
  private def crlNumber(crl: X509CRL): Option[String] = Try {
    val ext = crl.getExtensionValue("2.5.29.20")
    if (ext == null) None
    else {
      val asn1 = org.bouncycastle.asn1.ASN1Primitive.fromByteArray(ext)
      val octetStr = asn1.asInstanceOf[org.bouncycastle.asn1.ASN1OctetString]
      val inner = org.bouncycastle.asn1.ASN1Primitive.fromByteArray(octetStr.getOctets)
      Some(inner.asInstanceOf[org.bouncycastle.asn1.ASN1Integer].getValue.toString)
    }
  }.toOption.flatten

  private def filenameStem(path: String): String = {
    val name = path.substring(path.lastIndexOf('/') + 1)
    val dot = name.lastIndexOf('.')
    if (dot < 0) name else name.substring(0, dot)
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, CertificatesState) = item -> this

  /** Hard rule #2: the Certificates strategy never recurses into
    * child Items. */
  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): CertificatesState = this
}
