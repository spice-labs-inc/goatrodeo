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
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.strategies.Certificates.KVPair
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers.sha256Hex
import io.spicelabs.goatrodeo.util.PURLHelpers
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?

import java.security.KeyStore
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Per-artifact processing state for the Certificates strategy. */

/** Per-artifact processing state.
  *
  * @param artifact
  *   the artifact under processing
  * @param claim
  *   the parsed claim variant, or `None` for stub state
  */
class CertificatesState(
    artifact: ArtifactWrapper,
    claim: Option[Certificates.ClaimedContent] = None
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
  ): (Vector[String], CertificatesState) = {
    import Certificates.*
    val purls: Vector[Purl] = claim match {
      case None                => Vector.empty
      case Some(SingleCert(c)) => purlsForCert(c)
      case Some(Bundle(certs)) =>
        certs.flatMap(purlsForCert).distinctBy(_.toCanonical())
      case Some(ks @ Keystore(Some(keystore), _, _)) =>
        ksAllCerts(keystore).flatMap(purlsForCert).distinctBy(_.toCanonical())
      case Some(Keystore(None, _, _)) =>
        Vector.empty // encrypted → envelope-only; no pURLs
      case Some(Crl(crl)) =>
        Vector(purlForCrl(crl))
      case Some(p: SshPubkey) =>
        Vector(purlForSshPubkey(p))
      case Some(c: SshCert) =>
        purlsForSshCert(c)
      case Some(r: PgpKeyRing) =>
        r.keys.map(purlForPgpKey).distinctBy(_.toCanonical())
      case Some(p: PrivateKeyPlaintextPem) =>
        Vector(purlForPrivateKeyPem(p))
      case Some(p: PrivateKeyPlaintextOpenSsh) =>
        Vector(purlForPrivateKeyOpenSsh(p))
      case Some(p: PrivateKeyPlaintextPgp) =>
        p.ring.keys.map(purlForPgpKey).distinctBy(_.toCanonical())
      case Some(_: PrivateKeyEncrypted) =>
        Vector.empty // envelope-only; no pURL
    }
    purls.map(_.toCanonical().nn) -> this
  }

  /** Unencrypted-PEM private key → SPKI pURL. */
  private[strategies] def purlForPrivateKeyPem(
      p: Certificates.PrivateKeyPlaintextPem
  ): Purl = {
    val spkiSha = sha256Hex(p.spkiBytes)
    val parts =
      Vector(KVPair("alg", p.canonicalAlg)) ++ p.keySize.toVector.map(s =>
        KVPair("size", s.toString())
      ) ++
        p.curve.toVector.map(c => KVPair("curve", c)) ++ p.params.toVector.map(
          p => KVPair("params", p)
        )
    PURLHelpers.purl(
      `type` = "generic",
      name = "spki-sha256",
      namespace = "x509",
      version = spkiSha,
      qualifiers = parts.map(p => p.key -> p.value)
    )
  }

  /** Unencrypted-OpenSSH private key → SSH pURL. */
  private[strategies] def purlForPrivateKeyOpenSsh(
      p: Certificates.PrivateKeyPlaintextOpenSsh
  ): Purl = {
    import Certificates.*
    val fp = sshFingerprintB64(p.wireBytes)
    val quals = sshKeyQualifiers(p.algName, p.rsaModulusBits)
    PURLHelpers.purl(
      `type` = "generic",
      name = "sha256",
      namespace = "ssh",
      version = fp,
      qualifiers = quals.map(q => q.key -> q.value)
    )
  }

  /** `pkg:generic/ssh/sha256@{b64}?alg=...&{companion}` */
  private[strategies] def purlForSshPubkey(
      p: Certificates.SshPubkey
  ): Purl = {
    import Certificates.*
    val fp = sshFingerprintB64(p.wireBytes)
    val quals = sshKeyQualifiers(p.algName, p.rsaModulusBits)
    PURLHelpers.purl(
      `type` = "generic",
      name = "sha256",
      namespace = "ssh",
      version = fp,
      qualifiers = quals.map(q => q.key -> q.value)
    )
  }

  /** SSH cert pURLs: cert-sha256 + sha256 (signed-key fingerprint). */
  private[strategies] def purlsForSshCert(
      c: Certificates.SshCert
  ): Vector[Purl] = {
    import Certificates.*
    val certHex = sha256Hex(c.certBytes)
    val signedKeyFp = sshFingerprintB64(c.signedKeyWire)
    val keyQuals = sshKeyQualifiers(c.signedKeyAlgName, c.rsaModulusBits)
    val keyPurl = PURLHelpers.purl(
      `type` = "generic",
      name = "sha256",
      namespace = "ssh",
      version = signedKeyFp,
      qualifiers = keyQuals.map(q => q.key -> q.value)
    )

    val certTypeLabel = c.certType match {
      case 1L    => "user"
      case 2L    => "host"
      case other => s"unknown-$other"
    }
    val certQuals = (keyQuals ++ Vector(
      KVPair("cert-type", certTypeLabel),
      KVPair("sig-alg", c.caSigAlgName)
    ))

    val certPurl = PURLHelpers.purl(
      `type` = "generic",
      name = "cert-sha256",
      namespace = "ssh",
      version = certHex,
      qualifiers = certQuals.map(cq => cq.key -> cq.value)
    )

    Vector(certPurl, keyPurl)
  }

  /** Extract every X.509 cert from a loaded keystore, including key-entry chain
    * certs.
    */
  private def ksAllCerts(ks: KeyStore): Vector[X509Certificate] = {
    Try {

      val aliases = ks.aliases().asScala
      val acc: Vector[X509Certificate] =
        aliases.foldLeft(Vector[X509Certificate]()) { case (acc, alias) =>
          if (ks.isCertificateEntry(alias)) {
            ks.getCertificate(alias) match {
              case x: X509Certificate => acc :+ x
              case _                  => acc
            }
          } else if (ks.isKeyEntry(alias)) {
            val chain = Option(ks.getCertificateChain(alias))
              .map(_.toIndexedSeq)
              .getOrElse(IndexedSeq.empty)
            acc ++ chain
              .collect { case x: X509Certificate => x }

          } else acc
        }
      acc
    }.getOrElse(Vector())
  }

  /** Build the single CRL pURL. */
  private[strategies] def purlForCrl(crl: X509CRL): Purl = {
    import Certificates.*
    val derBytes = crl.getEncoded
    val crlSha = sha256Hex(derBytes)
    val sigAlg = canonicalSigAlgCrl(crl)

    PURLHelpers.purl(
      `type` = "generic",
      name = "crl-sha256",
      namespace = "x509",
      version = crlSha,
      qualifiers = Seq("sig-alg" -> sigAlg)
    )

  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], CertificatesState) = {
    import Certificates.*
    val tm: TreeMap[String, TreeSet[StringOrPair]] = claim match {
      case None                => TreeMap.empty[String, TreeSet[StringOrPair]]
      case Some(SingleCert(c)) => singleCertMetadata(c)
      case Some(Bundle(certs)) => bundleMetadata(artifact, certs)
      case Some(k @ Keystore(_, _, _)) => keystoreMetadata(artifact, k)
      case Some(Crl(crl))              => crlMetadata(artifact, crl)
      case Some(p: SshPubkey)          => sshPubkeyMetadata(artifact, p)
      case Some(c: SshCert)            => sshCertMetadata(artifact, c)
      case Some(r: PgpKeyRing)         => pgpKeyRingMetadata(artifact, r)
      case Some(p: PrivateKeyPlaintextPem) =>
        privateKeyPemMetadata(artifact, p)
      case Some(p: PrivateKeyPlaintextOpenSsh) =>
        privateKeyOpenSshMetadata(artifact, p)
      case Some(p: PrivateKeyPlaintextPgp) =>
        privateKeyPgpMetadata(artifact, p)
      case Some(p: PrivateKeyEncrypted) =>
        privateKeyEncryptedMetadata(artifact, p)
    }
    Certificates.filterLeaks(tm) -> this
  }

  /** Metadata for an unencrypted PEM private key. */
  private[strategies] def privateKeyPemMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.PrivateKeyPlaintextPem
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val adHoc = MKC.adHoc("Certificates")
    val spkiSha = sha256Hex(p.spkiBytes)
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(
          MKC.NAME -> TreeSet(StringOrPair(filenameStem(artifact.path())))
        ) +?
        Some(
          MKC.DESCRIPTION ->
            TreeSet(
              StringOrPair("Unencrypted private key (public key derived)")
            )
        ) +?
        Some(adHoc("Envelope") -> TreeSet(StringOrPair("plaintext"))) +?
        Some(adHoc("DerivedFromPrivateKey") -> TreeSet(StringOrPair("true"))) +?
        Some(adHoc("KeyAlgorithm") -> TreeSet(StringOrPair(p.canonicalAlg))) +?
        Some(adHoc("SpkiSha256") -> TreeSet(StringOrPair(spkiSha)))
    p.keySize.foreach(s =>
      tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(s.toString)))
    )
    p.curve.foreach(c => tm = tm + (adHoc("Curve") -> TreeSet(StringOrPair(c))))
    p.params.foreach(pa =>
      tm = tm + (adHoc("Params") -> TreeSet(StringOrPair(pa)))
    )
    tm
  }

  /** Metadata for an unencrypted OpenSSH private key. */
  private[strategies] def privateKeyOpenSshMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.PrivateKeyPlaintextOpenSsh
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val SshAlgMap(canon, companion, sk) = sshAlgMap(p.algName)
    val fpFull = s"SHA-256:${sshFingerprintB64(p.wireBytes)}"
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(
          MKC.NAME -> TreeSet(StringOrPair(filenameStem(artifact.path())))
        ) +?
        Some(
          MKC.DESCRIPTION ->
            TreeSet(
              StringOrPair(
                "Unencrypted OpenSSH private key (public key derived)"
              )
            )
        ) +?
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

  /** Metadata for an unencrypted PGP secret key ring; reuses Phase 6 PGP
    * metadata plus envelope markers.
    */
  private[strategies] def privateKeyPgpMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.PrivateKeyPlaintextPgp
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val adHoc = MKC.adHoc("Certificates")
    val base = pgpKeyRingMetadata(artifact, p.ring)
    base +
      (adHoc("Envelope") -> TreeSet(StringOrPair("plaintext"))) +
      (adHoc("DerivedFromPrivateKey") -> TreeSet(StringOrPair("true")))
  }

  /** Envelope-only metadata for encrypted private keys. */
  private[strategies] def privateKeyEncryptedMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.PrivateKeyEncrypted
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val adHoc = MKC.adHoc("Certificates")
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(
          MKC.NAME -> TreeSet(StringOrPair(filenameStem(artifact.path())))
        ) +?
        Some(
          MKC.DESCRIPTION ->
            TreeSet(
              StringOrPair("Encrypted private key (envelope metadata only)")
            )
        ) +?
        Some(adHoc("Envelope") -> TreeSet(StringOrPair(p.envelope)))
    p.kdfAlgorithm.foreach(v =>
      tm = tm + (adHoc("KdfAlgorithm") -> TreeSet(StringOrPair(v)))
    )
    p.kdfIterations.foreach(v =>
      tm = tm + (adHoc("KdfIterations") -> TreeSet(StringOrPair(v.toString)))
    )
    p.kdfPrf.foreach(v =>
      tm = tm + (adHoc("KdfPrf") -> TreeSet(StringOrPair(v)))
    )
    p.cipher.foreach(v =>
      tm = tm + (adHoc("Cipher") -> TreeSet(StringOrPair(v)))
    )
    p.salt.foreach(v =>
      tm = tm + (adHoc("KdfSalt") -> TreeSet(StringOrPair(v)))
    )
    p.iv.foreach(v => tm = tm + (adHoc("Iv") -> TreeSet(StringOrPair(v))))
    tm
  }

  /** Per-key namespaced under `Certificates:Key:{fp8}:` plus a top-level
    * `Certificates:PgpKeyCount`.
    */
  private def pgpKeyRingMetadata(
      artifact: ArtifactWrapper,
      r: Certificates.PgpKeyRing
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val nameSource = r.primaryUserId.getOrElse(filenameStem(artifact.path()))
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(MKC.NAME -> TreeSet(StringOrPair(nameSource))) +?
        Some(
          MKC.DESCRIPTION ->
            TreeSet(StringOrPair(s"PGP key ring (${r.keys.length} key${
                if (r.keys.length == 1) "" else "s"
              })"))
        ) +?
        Some(
          adHoc("PgpKeyCount") -> TreeSet(StringOrPair(r.keys.length.toString))
        )

    r.keys.foreach { key =>
      val fp8 = pgpFp8(key)
      def k(field: String): String = adHoc(s"Key:$fp8:$field")
      tm = tm + (k("Fingerprint") -> TreeSet(StringOrPair(key.fingerprintHex)))
      tm = tm + (k("Version") -> TreeSet(StringOrPair(key.version.toString)))
      tm = tm + (k("KeyAlgorithm") -> TreeSet(StringOrPair(key.canonicalAlg)))
      key.keySize.foreach(s =>
        tm = tm + (k("KeySize") -> TreeSet(StringOrPair(s.toString)))
      )
      key.curve.foreach(c => tm = tm + (k("Curve") -> TreeSet(StringOrPair(c))))
      tm =
        tm + (k("IsPrimary") -> TreeSet(StringOrPair(key.isPrimary.toString)))
      tm = tm + (k("CreationTime") -> TreeSet(
        StringOrPair(isoUtc(key.creationTime))
      ))
      key.expirationTime.foreach(d =>
        tm = tm + (k("ExpirationTime") -> TreeSet(StringOrPair(isoUtc(d))))
      )
      if (key.isPrimary && key.userIds.nonEmpty) {
        tm = tm + (k("UserIds") -> TreeSet(
          StringOrPair(key.userIds.mkString(","))
        ))
      }
    }
    tm
  }

  /** Test-accessible alias for sshPubkeyMetadata. */
  private[strategies] def invokeSshPubkeyMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.SshPubkey
  ): TreeMap[String, TreeSet[StringOrPair]] = sshPubkeyMetadata(artifact, p)

  /** Test-accessible alias for sshCertMetadata. */
  private[strategies] def invokeSshCertMetadata(
      artifact: ArtifactWrapper,
      c: Certificates.SshCert
  ): TreeMap[String, TreeSet[StringOrPair]] = sshCertMetadata(artifact, c)

  /** Test-accessible alias for pgpKeyRingMetadata. */
  private[strategies] def invokePgpKeyRingMetadata(
      artifact: ArtifactWrapper,
      r: Certificates.PgpKeyRing
  ): TreeMap[String, TreeSet[StringOrPair]] = pgpKeyRingMetadata(artifact, r)

  private def sshPubkeyMetadata(
      artifact: ArtifactWrapper,
      p: Certificates.SshPubkey
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val SshAlgMap(canon, companion, sk) = sshAlgMap(p.algName)
    val nameSource = p.comment.getOrElse(filenameStem(artifact.path()))
    val fpFull = s"SHA-256:${sshFingerprintB64(p.wireBytes)}"
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(MKC.NAME -> TreeSet(StringOrPair(nameSource))) +?
        Some(
          MKC.DESCRIPTION ->
            TreeSet(StringOrPair(s"OpenSSH public key ($canon)"))
        ) +?
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

  private def sshCertMetadata(
      artifact: ArtifactWrapper,
      c: Certificates.SshCert
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val SshAlgMap(canon, companion, sk) = sshAlgMap(c.signedKeyAlgName)
    val signedFp = s"SHA-256:${sshFingerprintB64(c.signedKeyWire)}"
    val caFp = s"SHA-256:${sshFingerprintB64(c.caKeyWire)}"
    val certHex = sha256Hex(c.certBytes)
    val certTypeLabel = c.certType match {
      case 1L    => "user"
      case 2L    => "host"
      case other => s"unknown-$other"
    }
    val nameSource = c.comment.getOrElse(filenameStem(artifact.path()))
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(MKC.NAME -> TreeSet(StringOrPair(nameSource))) +?
        Some(
          MKC.DESCRIPTION ->
            TreeSet(
              StringOrPair(s"OpenSSH $certTypeLabel certificate ($canon)")
            )
        ) +?
        Some(adHoc("KeyAlgorithm") -> TreeSet(StringOrPair(canon))) +?
        Some(
          adHoc("SshFingerprintSha256") -> TreeSet(StringOrPair(signedFp))
        ) +?
        Some(adHoc("SshCertSha256") -> TreeSet(StringOrPair(certHex))) +?
        Some(adHoc("SshCertType") -> TreeSet(StringOrPair(certTypeLabel))) +?
        Some(
          adHoc("SshCertSerial") -> TreeSet(StringOrPair(c.serial.toString))
        ) +?
        Some(adHoc("SshCertKeyId") -> TreeSet(StringOrPair(c.keyId))) +?
        Some(
          adHoc("SshCertValidAfter") ->
            TreeSet(
              StringOrPair(
                sshCertTimeLabel(c.validAfter, sentinelLabel = "always")
              )
            )
        ) +?
        Some(
          adHoc("SshCertValidBefore") ->
            TreeSet(
              StringOrPair(
                sshCertTimeLabel(c.validBefore, sentinelLabel = "forever")
              )
            )
        ) +?
        Some(adHoc("SshCertCaFingerprint") -> TreeSet(StringOrPair(caFp))) +?
        Some(
          adHoc("SshCertSigAlgorithm") -> TreeSet(StringOrPair(c.caSigAlgName))
        )

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

  private def singleCertMetadata(
      c: X509Certificate
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val perCert = perCertMetadata(adHoc, c)
    val subject = c.getSubjectX500Principal
    val issuer = c.getIssuerX500Principal
    val version = c.getVersion
    val (alg, _) = keyAlgAndQualifier(Option(c.getPublicKey), c)
    // PQC and composite certs append the alg suffix so the inventory makes
    // PQC presence obvious; classical algs stay bare to match the
    // historical sidecar contract from Phase 0b's `cert_sidecar.py`.
    val pqcAlgs = Set("ml-dsa", "slh-dsa", "falcon", "composite")
    val descSuffix = if (pqcAlgs.contains(alg)) s" ($alg)" else ""
    perCert +? Some(MKC.NAME -> TreeSet(StringOrPair(cnOrDn(subject)))) +?
      Some(MKC.PUBLISHER -> TreeSet(StringOrPair(cnOrDn(issuer)))) +?
      Some(
        MKC.DESCRIPTION -> TreeSet(
          StringOrPair(s"X.509 v$version certificate$descSuffix")
        )
      )
  }

  private def bundleMetadata(
      artifact: ArtifactWrapper,
      certs: Vector[X509Certificate]
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val stem = filenameStem(artifact.path())
    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(MKC.NAME -> TreeSet(StringOrPair(stem))) +?
        Some(adHoc("KeystoreType") -> TreeSet(StringOrPair("pem-bundle"))) +?
        Some(
          adHoc("EntryCount") -> TreeSet(StringOrPair(certs.length.toString))
        ) +?
        Some(
          adHoc("CertCount") -> TreeSet(StringOrPair(certs.length.toString))
        ) +?
        Some(adHoc("KeyEntryCount") -> TreeSet(StringOrPair("0")))
    certs.zipWithIndex.foreach { case (c, idx) =>
      val perCertAdHoc: String => String =
        sub => MKC.adHoc("Certificates")(s"Cert:$idx:$sub")
      tm = tm ++ perCertMetadata(perCertAdHoc, c)
    }
    tm
  }

  private def keystoreMetadata(
      artifact: ArtifactWrapper,
      k: Certificates.Keystore
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
          val perEntryAdHoc: String => String =
            sub => MKC.adHoc("Certificates")(s"$perEntryPrefix$sub")
          if (Try(ks.isCertificateEntry(alias)).getOrElse(false)) {
            certCount += 1
            ks.getCertificate(alias) match {
              case x: X509Certificate =>
                tm = tm ++ perCertMetadata(perEntryAdHoc, x)
              case _ => ()
            }
          } else if (Try(ks.isKeyEntry(alias)).getOrElse(false)) {
            keyEntryCount += 1
            // only the chain, not the key material
            val chain = Option(ks.getCertificateChain(alias))
              .map(_.toIndexedSeq)
              .getOrElse(IndexedSeq.empty)
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
          (adHoc("EntryCount") -> TreeSet(
            StringOrPair(aliases.length.toString)
          )) +
          (adHoc("CertCount") -> TreeSet(StringOrPair(certCount.toString))) +
          (adHoc("KeyEntryCount") -> TreeSet(
            StringOrPair(keyEntryCount.toString)
          ))
    }
    tm
  }

  private def crlMetadata(
      artifact: ArtifactWrapper,
      crl: X509CRL
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    import Certificates.*
    val adHoc = MKC.adHoc("Certificates")
    val derBytes = crl.getEncoded
    val crlSha = sha256Hex(derBytes)
    val sigAlg = canonicalSigAlgCrl(crl)
    val issuer = crl.getIssuerX500Principal
    val stem = filenameStem(artifact.path())

    val revoked = Option(crl.getRevokedCertificates)
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)
    val cap = 10000
    val serials = revoked.take(cap).map(r => r.getSerialNumber.toString(16))
    val truncated = revoked.length > cap

    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(MKC.NAME -> TreeSet(StringOrPair(stem))) +?
        Some(MKC.PUBLISHER -> TreeSet(StringOrPair(cnOrDn(issuer)))) +?
        Some(
          MKC.DESCRIPTION -> TreeSet(
            StringOrPair("X.509 Certificate Revocation List")
          )
        ) +?
        Some(adHoc("IssuerDN") -> TreeSet(StringOrPair(dnString(issuer)))) +?
        Some(
          adHoc("ThisUpdate") -> TreeSet(
            StringOrPair(isoUtc(crl.getThisUpdate))
          )
        ) +?
        Some(adHoc("SigAlgorithm") -> TreeSet(StringOrPair(sigAlg))) +?
        Some(adHoc("CrlSha256") -> TreeSet(StringOrPair(crlSha))) +?
        Some(
          adHoc("RevokedCount") -> TreeSet(
            StringOrPair(revoked.length.toString)
          )
        )

    Option(crl.getNextUpdate).foreach { d =>
      tm = tm + (adHoc("NextUpdate") -> TreeSet(StringOrPair(isoUtc(d))))
    }
    crlNumber(crl).foreach { n =>
      tm = tm + (adHoc("CrlNumber") -> TreeSet(StringOrPair(n)))
    }
    if (serials.nonEmpty) {
      tm = tm + (adHoc("RevokedSerials") -> TreeSet(
        StringOrPair(serials.mkString(","))
      ))
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
      val inner =
        org.bouncycastle.asn1.ASN1Primitive.fromByteArray(octetStr.getOctets)
      Some(
        inner.asInstanceOf[org.bouncycastle.asn1.ASN1Integer].getValue.toString
      )
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

  /** Certificates never recurses into child Items. */
  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): CertificatesState = this
}
