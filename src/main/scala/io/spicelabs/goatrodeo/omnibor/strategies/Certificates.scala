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
import com.github.packageurl.PackageURLBuilder
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.omnibor.strategies.CertificatesOidMaps.*
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.Helpers.sha256Hex
import io.spicelabs.goatrodeo.util.SshWireReader
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.bcpg.ECPublicBCPGKey
import org.bouncycastle.bcpg.EdDSAPublicBCPGKey
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.regex.Pattern
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

/** Certificates strategy: X.509 certificates, CRLs, keystores, PEM bundles, SSH
  * keys/certs, PGP keys, and private keys.
  */
object Certificates {

  // ---------- claim variants -------------------------------------------

  /** A successfully-parsed artifact ready for emission. The variant tells
    * `getPurls` / `getMetadata` how to dispatch.
    */
  sealed trait ClaimedContent

  /** Single X.509 certificate. */
  final case class SingleCert(cert: X509Certificate) extends ClaimedContent

  /** Java/BC keystore.
    * @param ks
    *   opened keystore (`null` password); `None` if the null-password load
    *   failed (encrypted)
    * @param format
    *   `"jks"`, `"jceks"`, `"pkcs12"`, `"bks"`
    * @param entryCount
    *   alias count if loaded; 0 if encrypted
    */
  final case class Keystore(
      ks: Option[KeyStore],
      format: String,
      entryCount: Int
  ) extends ClaimedContent

  /** Multi-block PEM bundle. */
  final case class Bundle(certs: Vector[X509Certificate]) extends ClaimedContent

  /** X.509 Certificate Revocation List. */
  final case class Crl(crl: X509CRL) extends ClaimedContent

  /** Plain OpenSSH public key (single line). */
  final case class SshPubkey(
      wireBytes: Array[Byte],
      algName: String,
      comment: Option[String],
      rsaModulusBits: Option[Int]
  ) extends ClaimedContent

  /** OpenSSH CA-issued certificate. */
  final case class SshCert(
      certBytes: Array[Byte],
      certTypeName: String,
      signedKeyWire: Array[Byte],
      signedKeyAlgName: String,
      rsaModulusBits: Option[Int],
      serial: BigInt,
      certType: Long,
      keyId: String,
      principals: Vector[String],
      validAfter: Long,
      validBefore: Long,
      criticalOptions: Vector[String],
      extensions: Vector[String],
      caKeyWire: Array[Byte],
      caSigAlgName: String,
      comment: Option[String]
  ) extends ClaimedContent

  /** A single PGP key (primary or subkey). */
  final case class PgpKey(
      fingerprintHex: String,
      version: Int,
      pgpAlgId: Int,
      canonicalAlg: String,
      keySize: Option[Int],
      curve: Option[String],
      isPrimary: Boolean,
      creationTime: java.util.Date,
      expirationTime: Option[java.util.Date],
      userIds: Vector[String]
  )

  /** One or more PGP keys parsed from an `application/pgp-keys` artifact. */
  final case class PgpKeyRing(
      keys: Vector[PgpKey],
      primaryUserId: Option[String]
  ) extends ClaimedContent

  /** Unencrypted PKCS#8 or legacy-PEM private key with derived public SPKI. */
  final case class PrivateKeyPlaintextPem(
      spkiBytes: Array[Byte],
      canonicalAlg: String,
      keySize: Option[Int],
      curve: Option[String],
      params: Option[String]
  ) extends ClaimedContent

  /** Unencrypted OpenSSH-v1 private key. */
  final case class PrivateKeyPlaintextOpenSsh(
      wireBytes: Array[Byte],
      algName: String,
      rsaModulusBits: Option[Int]
  ) extends ClaimedContent

  /** Unencrypted PGP secret key ring; public keys derived from each secret key.
    */
  final case class PrivateKeyPlaintextPgp(
      ring: PgpKeyRing
  ) extends ClaimedContent

  /** Encrypted private key — envelope metadata only, no pURL. */
  final case class PrivateKeyEncrypted(
      envelope: String,
      kdfAlgorithm: Option[String],
      kdfIterations: Option[Long],
      kdfPrf: Option[String],
      cipher: Option[String],
      salt: Option[String] = None,
      iv: Option[String] = None
  ) extends ClaimedContent

  // ---------- claim & parse dispatch -----------------------------------

  private val singleCertMimes: Set[String] = Set(
    "application/pkix-cert",
    "application/x-x509-ca-cert"
  )
  private val pemFileMime: String = "application/x-pem-file"
  private val pemBundleMime: String = "application/x-pem-bundle"
  private val crlMime: String = "application/pkix-crl"
  private val jksMime: String = "application/x-java-keystore"
  private val jceksMime: String = "application/x-java-jce-keystore"
  private val pkcs12Mime: String = "application/pkcs12"
  private val sshPubkeyMime: String = "application/x-openssh-public-key"
  private val sshCertMime: String = "application/x-openssh-certificate"
  private val pgpKeysMime: String = "application/pgp-keys"
  private val pemPrivateKeyMime: String = "application/x-pem-private-key"
  private val pemEncryptedPrivateKeyMime: String =
    "application/x-pem-encrypted-private-key"
  private val opensshPrivateKeyMime: String =
    "application/x-openssh-private-key"

  /** Classify each candidate artifact by MIME priority, parse, and emit
    * `ToProcess` instances.
    */
  def computeCertificateFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val claimed: Iterable[(ArtifactWrapper, ClaimedContent)] = for {
      (_, wrapper) <- byUUID
      content <- classifyAndParse(wrapper)
    } yield wrapper -> content

    val claimedUuids: Set[String] = claimed.map(_._1.uuid).toSet
    val revisedByUUID = byUUID.filterNot { case (uuid, _) =>
      claimedUuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => claimedUuids.contains(a.uuid))
    }
    val toProcess: Vector[ToProcess] = claimed.map { case (artifact, content) =>
      new Certificates(artifact, content)
    }.toVector

    (toProcess, revisedByUUID, revisedByName, "Certificates")
  }

  /** Classify and parse the artifact. */
  private[strategies] def classifyAndParse(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    val mimes = artifact.mimeType
    val name = artifact.path()
    if (mimes.contains(pemBundleMime)) parseBundle(artifact)
    else if (mimes.contains(jksMime)) parseKeystore(artifact, "JKS")
    else if (mimes.contains(jceksMime)) parseKeystore(artifact, "JCEKS")
    else if (mimes.contains(pkcs12Mime)) parseKeystore(artifact, "PKCS12")
    else if (name.toLowerCase.endsWith(".bks")) parseKeystore(artifact, "BKS")
    else if (mimes.contains(crlMime)) parseCrl(artifact)
    else if (mimes.contains(sshCertMime)) parseSshCert(artifact)
    else if (mimes.contains(sshPubkeyMime)) parseSshPubkey(artifact)
    else if (mimes.contains(pemEncryptedPrivateKeyMime))
      parsePemEncryptedPrivateKey(artifact)
    else if (mimes.contains(opensshPrivateKeyMime))
      parseOpenSshPrivateKey(artifact)
    else if (mimes.contains(pemPrivateKeyMime))
      parsePemPrivateKey(artifact)
    else if (mimes.contains(pgpKeysMime)) parsePgpKeyOrSecretKeyRing(artifact)
    else if (isSingleCertCandidate(mimes)) {
      parseSingleCert(artifact).map(SingleCert.apply)
    } else None
  }

  /** True if the artifact's MIME set indicates a single X.509 cert. Excludes
    * bundle / keystore / SSH / PGP / private-key / CRL forms.
    */
  private[strategies] def isSingleCertCandidate(mimes: Set[String]): Boolean = {
    val anySingleCert = mimes.intersect(singleCertMimes).nonEmpty
    val pemNonBundle =
      mimes.contains(pemFileMime) && !mimes.contains(pemBundleMime)
    (anySingleCert || pemNonBundle) &&
    !mimes.contains(pemBundleMime) &&
    !mimes.exists(m =>
      m == jksMime || m == jceksMime || m == pkcs12Mime ||
        m == "application/x-openssh-public-key" ||
        m == "application/x-openssh-certificate" ||
        m == "application/x-openssh-private-key" ||
        m == "application/pgp-keys" ||
        m == "application/x-pem-private-key" ||
        m == "application/x-pem-encrypted-private-key" ||
        m == "application/x-pem-public-key" ||
        m == crlMime
    )
  }

  /** Parse a single X.509 cert (PEM or DER). */
  private[strategies] def parseSingleCert(
      artifact: ArtifactWrapper
  ): Option[X509Certificate] = {
    artifact.withStream { f =>
      val bytes = Helpers.slurpInput(f)
      tryParsePem(bytes).orElse(tryParseDer(bytes))
    }
  }

  private val converter: JcaX509CertificateConverter =
    new JcaX509CertificateConverter().setProvider("BC")

  private def tryParsePem(bytes: Array[Byte]): Option[X509Certificate] = Try {
    Using.resource(
      new PEMParser(
        new InputStreamReader(
          new ByteArrayInputStream(bytes),
          "ISO-8859-1"
        )
      )
    ) { parser =>
      val obj = parser.readObject()
      obj match {
        case h: X509CertificateHolder => Some(converter.getCertificate(h))
        case _                        => None
      }
    }
  }.toOption.flatten

  private def tryParseDer(bytes: Array[Byte]): Option[X509Certificate] = Try {
    val cf = CertificateFactory.getInstance("X.509", "BC")
    cf.generateCertificate(new ByteArrayInputStream(bytes))
      .asInstanceOf[X509Certificate]
  }.toOption

  private def parserToVec(parser: PEMParser): Vector[Object] = {
    var ret = Vector[Object]()
    var next = parser.readObject()

    while (next != null) {
      ret = ret :+ next
      next = parser.readObject()
    }

    ret
  }

  /** Parse a multi-block PEM bundle; collects X509CertificateHolder objects
    * only.
    */
  private[strategies] def parseBundle(
      artifact: ArtifactWrapper
  ): Option[Bundle] = {
    val collected: Option[Vector[X509Certificate]] = artifact.withStream { f =>
      Try {
        Using.resource(
          new PEMParser(
            new InputStreamReader(
              f,
              "ISO-8859-1"
            )
          )
        ) { parser =>
          val objects = parserToVec(parser)
          objects.foldLeft(Vector[X509Certificate]()) {
            case (v, h: X509CertificateHolder) =>
              v :+ converter.getCertificate(h)
            case (v, _) => v
          }
        }
      }.toOption
    }
    collected.filter(_.nonEmpty).map(Bundle(_))
  }

  /** Parse a keystore. Tries null password only. */
  private[strategies] def parseKeystore(
      artifact: ArtifactWrapper,
      format: String
  ): Option[Keystore] = {
    val canonicalFormat = format.toLowerCase match {
      case "jks"    => "jks"
      case "jceks"  => "jceks"
      case "pkcs12" => "pkcs12"
      case "bks"    => "bks"
      case other    => other.toLowerCase
    }
    val outcome: Try[KeyStore] = Try {
      artifact.withStream { f =>

        val ks = KeyStore.getInstance(format, "BC")
        ks.load(f, null)
        ks
      }
    }
    outcome match {
      case scala.util.Success(ks) if canonicalFormat == "bks" =>
        // BC's BKS accepts null password even when generated with a real one; always envelope-only.
        Some(Keystore(None, canonicalFormat, 0))
      case scala.util.Success(ks) =>
        val count = Try(ks.size()).getOrElse(0)
        Some(Keystore(Some(ks), canonicalFormat, count))
      case scala.util.Failure(_) =>
        Some(Keystore(None, canonicalFormat, 0))
    }
  }

  /** Parse a CRL (PEM or DER). */
  private[strategies] def parseCrl(
      artifact: ArtifactWrapper
  ): Option[Crl] = {
    artifact.withStream { f =>
      Try {
        val cf = CertificateFactory.getInstance("X.509", "BC")
        cf.generateCRL(f)
          .asInstanceOf[X509CRL]
      }.toOption.map(Crl.apply)
    }
  }

  // ---------- SSH parsing ---------------------------------------------

  private[strategies] case class SshAlgMap(
      alg: String,
      data: Option[(String, String)],
      sk: Boolean
  )

  /** SSH wire algorithm name → (canonical alg, optional companion qualifier,
    * security-key flag).
    */
  private[strategies] val sshAlgMap: Map[String, SshAlgMap] = Map(
    "ssh-rsa" -> SshAlgMap("rsa", None, false),
    "ssh-dss" -> SshAlgMap("dsa", Some(("size", "1024")), false),
    "ssh-ed25519" -> SshAlgMap("ed25519", None, false),
    "ssh-ed448" -> SshAlgMap("ed448", None, false),
    "ecdsa-sha2-nistp256" -> SshAlgMap("ec", Some(("curve", "p-256")), false),
    "ecdsa-sha2-nistp384" -> SshAlgMap("ec", Some(("curve", "p-384")), false),
    "ecdsa-sha2-nistp521" -> SshAlgMap("ec", Some(("curve", "p-521")), false),
    "sk-ssh-ed25519@openssh.com" -> SshAlgMap("ed25519", None, true),
    "sk-ecdsa-sha2-nistp256@openssh.com" -> SshAlgMap(
      "ec",
      Some(
        ("curve", "p-256")
      ),
      true
    )
  )

  /** Strip -cert-v01@openssh.com suffix to get the signed-key algorithm name.
    */
  private[strategies] def signedKeyAlgFromCertName(
      certName: String
  ): Option[String] = {
    val suffix = "-cert-v01@openssh.com"
    if (certName.endsWith(suffix))
      Some(certName.substring(0, certName.length - suffix.length))
    else None
  }

  /** Parse an OpenSSH plain public-key file. */
  private[strategies] def parseSshPubkey(
      artifact: ArtifactWrapper
  ): Option[SshPubkey] = {
    artifact.withStream { f =>
      Try {
        val raw = new String(
          Helpers.slurpInput(f),
          java.nio.charset.StandardCharsets.UTF_8
        )
        SshWireReader
          .parseFirstKeyLine(raw)
          .flatMap { case (alg, wire, comment) =>
            // Sanity: the wire blob's first string must equal the alg
            // token. If it doesn't, decline (probably an `authorized_keys`
            // options line or a corrupted file).
            val r = SshWireReader(wire)
            val innerAlg = r.readUtf8String()
            if (innerAlg != alg) None
            else if (!sshAlgMap.contains(alg)) None
            else {
              val rsaBits =
                if (alg == "ssh-rsa") {
                  val _e = r.readMpint()
                  val n = r.readMpint()
                  Some(
                    SshWireReader.mpintBitLength(n)
                  )
                } else None
              Some(SshPubkey(wire, alg, comment, rsaBits))
            }
          }
      }.toOption.flatten
    }
  }

  /** Parse an OpenSSH CA-issued certificate file. */
  private[strategies] def parseSshCert(
      artifact: ArtifactWrapper
  ): Option[SshCert] = {
    artifact.withStream { f =>
      Try {
        val raw = new String(
          Helpers.slurpInput(f),
          java.nio.charset.StandardCharsets.UTF_8
        )
        SshWireReader
          .parseFirstKeyLine(raw)
          .flatMap { case (certTypeName, certBytes, comment) =>
            signedKeyAlgFromCertName(certTypeName).flatMap { signedAlg =>
              if (!sshAlgMap.contains(signedAlg)) None
              else parseSshCertBlob(certTypeName, signedAlg, certBytes, comment)
            }
          }
      }.toOption.flatten
    }
  }

  /** Decode the cert wire blob. */
  private def parseSshCertBlob(
      certTypeName: String,
      signedAlg: String,
      certBytes: Array[Byte],
      comment: Option[String]
  ): Option[SshCert] = Try {
    val r = new SshWireReader(certBytes)
    val innerType = r.readUtf8String()
    require(
      innerType == certTypeName,
      s"cert-type mismatch: $innerType vs $certTypeName"
    )
    val _nonce = r.readString()

    // Read the signed key's algorithm-specific fields and reconstruct the
    // plain-pubkey wire blob: `string(signedAlg) | <fields>`.
    val keyFieldsBuf = new java.io.ByteArrayOutputStream()
    var rsaBits: Option[Int] = None

    def writeString(
        out: java.io.ByteArrayOutputStream,
        b: Array[Byte]
    ): Unit = {
      val len = b.length
      out.write((len >>> 24) & 0xff)
      out.write((len >>> 16) & 0xff)
      out.write((len >>> 8) & 0xff)
      out.write(len & 0xff)
      out.write(b)
    }
    writeString(
      keyFieldsBuf,
      signedAlg.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )

    signedAlg match {
      case "ssh-rsa" =>
        val e = r.readMpint(); writeString(keyFieldsBuf, e)
        val n = r.readMpint(); writeString(keyFieldsBuf, n)
        rsaBits = Some(SshWireReader.mpintBitLength(n))
      case "ssh-dss" =>
        val p = r.readMpint(); writeString(keyFieldsBuf, p)
        val q = r.readMpint(); writeString(keyFieldsBuf, q)
        val g = r.readMpint(); writeString(keyFieldsBuf, g)
        val y = r.readMpint(); writeString(keyFieldsBuf, y)
      case "ssh-ed25519" | "ssh-ed448" =>
        val pk = r.readString(); writeString(keyFieldsBuf, pk)
      case n if n.startsWith("ecdsa-sha2-") =>
        val curve = r.readString(); writeString(keyFieldsBuf, curve)
        val q = r.readString(); writeString(keyFieldsBuf, q)
      case other =>
        throw new IllegalArgumentException(
          s"unsupported SSH cert key alg: $other"
        )
    }

    val signedKeyWire = keyFieldsBuf.toByteArray
    val serialL = r.readUInt64()
    val serial =
      if (serialL >= 0) BigInt(serialL)
      else BigInt(java.lang.Long.toUnsignedString(serialL))
    val certType = r.readUInt32()
    val keyId = r.readUtf8String()
    val principals = r.readStringList()
    val validAfter = r.readUInt64()
    val validBefore = r.readUInt64()
    val criticalOptions = r.readNameDataList().map(_._1)
    val extensions = r.readNameDataList().map(_._1)
    val _reserved = r.readString()
    val caKeyWire = r.readString()
    val signatureBlob = r.readString()
    val sigReader = new SshWireReader(signatureBlob)
    val caSigAlg = sigReader.readUtf8String()
    Some(
      SshCert(
        certBytes = certBytes,
        certTypeName = certTypeName,
        signedKeyWire = signedKeyWire,
        signedKeyAlgName = signedAlg,
        rsaModulusBits = rsaBits,
        serial = serial,
        certType = certType,
        keyId = keyId,
        principals = principals,
        validAfter = validAfter,
        validBefore = validBefore,
        criticalOptions = criticalOptions,
        extensions = extensions,
        caKeyWire = caKeyWire,
        caSigAlgName = caSigAlg,
        comment = comment
      )
    )
  }.toOption.flatten

  /** SHA-256 base64-no-padding fingerprint over an SSH wire blob. */
  private[strategies] def sshFingerprintB64(wire: Array[Byte]): String = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(wire)
    Base64.getEncoder.nn.withoutPadding.nn.encodeToString(digest).nn
  }

  /** Render an OpenSSH cert validity timestamp, using sentinelLabel for
    * unbounded values.
    */
  private[strategies] def sshCertTimeLabel(
      epochSec: Long,
      sentinelLabel: String
  ): String = {
    val isUnsignedMax = epochSec == -1L
    val isZero = epochSec == 0L
    if (isUnsignedMax || isZero) sentinelLabel
    else isoUtc(Date.from(java.time.Instant.ofEpochSecond(epochSec)))
  }

  private[strategies] case class KVPair(key: String, value: String)

  /** Companion-qualifier helper for SSH pURLs. */
  private[strategies] def sshKeyQualifiers(
      algName: String,
      rsaBits: Option[Int]
  ): Vector[KVPair] = {
    val SshAlgMap(canon, companion, sk) = sshAlgMap(algName)
    val r1 = companion.foldLeft(Vector(KVPair("alg", canon))) {
      case (parts, k -> v) => parts :+ KVPair(k, v)
    }

    val r2 = (algName, rsaBits) match {
      case ("ssh-rsa", Some(i)) => r1 :+ KVPair("size", i.toString)
      case _                    => r1
    }

    if (sk) {
      r2 :+ KVPair("sk", "true")
    } else r2
  }

  // ---------- PGP parsing ---------------------------------------------

  /** PGP algorithm-id → canonical alg name. */
  private[strategies] val pgpAlgIdMap: Map[Int, String] = Map(
    1 -> "rsa", // RSA (Encrypt or Sign)
    2 -> "rsa", // RSA (Encrypt-Only)  — deprecated
    3 -> "rsa", // RSA (Sign-Only)     — deprecated
    16 -> "elgamal", // ElGamal (Encrypt-Only)
    17 -> "dsa", // DSA
    18 -> "ec", // ECDH
    19 -> "ec", // ECDSA
    20 -> "elgamal", // ElGamal (Sign + Encrypt) — deprecated
    22 -> "ed25519", // EdDSA Legacy
    25 -> "x25519", // X25519
    26 -> "x448", // X448
    27 -> "ed25519", // Ed25519 (RFC 9580)
    28 -> "ed448" // Ed448  (RFC 9580)
  )

  /** PGP curve OID → canonical curve name. */
  private[strategies] val pgpCurveOidMap: Map[String, String] = Map(
    "1.2.840.10045.3.1.7" -> "p-256",
    "1.3.132.0.34" -> "p-384",
    "1.3.132.0.35" -> "p-521",
    "1.3.36.3.3.2.8.1.1.7" -> "brainpoolp256r1",
    "1.3.36.3.3.2.8.1.1.11" -> "brainpoolp384r1",
    "1.3.36.3.3.2.8.1.1.13" -> "brainpoolp512r1",
    "1.3.6.1.4.1.3029.1.5.1" -> "curve25519",
    "1.3.6.1.4.1.11591.15.1" -> "ed25519"
  )

  /** Try public-key path first; fall through to secret-key path. */
  private[strategies] def parsePgpKeyOrSecretKeyRing(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] =
    parsePgpKeyRing(artifact).orElse(parsePgpSecretKeyRing(artifact))

  /** Parse a PGP key ring (armored or binary). */
  private[strategies] def parsePgpKeyRing(
      artifact: ArtifactWrapper
  ): Option[PgpKeyRing] = {
    artifact.withStream { f =>
      Try {
        val raw = Helpers.slurpInput(f)

        val segments = splitArmoredBlocks(raw)
        val rings = segments.foldLeft(Vector[PGPPublicKeyRing]()) {
          case (rings, seg) => parsePgpStream(seg, rings)
        }

        if (rings.isEmpty) None
        else Some(buildPgpKeyRing(rings.toVector))
      }.toOption.flatten
    }
  }

  /** Split a raw byte buffer into one segment per `BEGIN PGP …` armor block. If
    * the buffer contains no armor marker, returns the whole buffer as a single
    * segment (binary input).
    */
  private def splitArmoredBlocks(raw: Array[Byte]): Vector[Array[Byte]] = {
    val text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1)
    val begin = "-----BEGIN PGP "
    if (!text.contains(begin)) Vector(raw)
    else {
      val acc = scala.collection.mutable.ListBuffer[Array[Byte]]()
      var idx = text.indexOf(begin)
      while (idx >= 0) {
        val next = text.indexOf(begin, idx + begin.length)
        val end = if (next < 0) text.length else next
        acc += text
          .substring(idx, end)
          .getBytes(
            java.nio.charset.StandardCharsets.ISO_8859_1
          )
        idx = next
      }
      acc.toVector
    }
  }

  private def factoryToVec(factory: PGPObjectFactory): Vector[Object] = {
    var ret = Vector[Object]()

    val it = factory.iterator()

    while (it.hasNext()) {
      ret = ret :+ it.next()
    }

    ret
  }

  /** Read every PGPPublicKeyRing from a decoder stream. */
  private def parsePgpStream(
      bytes: Array[Byte],
      acc: Vector[PGPPublicKeyRing]
  ): Vector[PGPPublicKeyRing] = {
    val decoded = PGPUtil.getDecoderStream(
      new ByteArrayInputStream(bytes)
    )
    val factory = PGPObjectFactory(
      decoded,
      new BcKeyFingerprintCalculator()
    )
    val items = factoryToVec(factory)
    items.foldLeft(acc) {
      case (acc, kr: PGPPublicKeyRing) => acc :+ kr
      case (acc, krc: PGPPublicKeyRingCollection) =>
        krc.getKeyRings.asScala.foldLeft(acc) { case (acc, kr) =>
          acc :+ kr
        }
      case (acc, _) => acc
    }
  }

  /** Project PGPPublicKeyRings into a single PgpKeyRing. */
  private def buildPgpKeyRing(
      rings: Vector[org.bouncycastle.openpgp.PGPPublicKeyRing]
  ): PgpKeyRing = {
    val keys = rings.flatMap { ring =>
      ring.getPublicKeys.asScala.toVector.map(pgpKeyOf)
    }
    val primaryUid = rings.headOption.flatMap { ring =>
      val primary = ring.getPublicKey
      Option(primary).flatMap { pk =>
        val it = pk.getUserIDs
        if (it != null && it.hasNext) Option(it.next().toString) else None
      }
    }
    PgpKeyRing(keys, primaryUid)
  }

  /** Parse a PGP secret-key block. If any key is encrypted, takes envelope-only
    * path; otherwise derives public keys.
    */
  private[strategies] def parsePgpSecretKeyRing(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    artifact.withStream { f =>
      Try {
        val raw = Helpers.slurpInput(f)

        val segments = splitArmoredBlocks(raw)
        val rings = segments.foldLeft(Vector[PGPSecretKeyRing]()) {
          case (rings, seg) => parsePgpSecretStream(seg, rings)
        }
        if (rings.isEmpty) None
        else Some(buildPgpSecretClaim(rings))
      }.toOption.flatten
    }
  }

  /** Read every PGPSecretKeyRing from a decoder stream. */
  private def parsePgpSecretStream(
      bytes: Array[Byte],
      acc: Vector[PGPSecretKeyRing]
  ): Vector[PGPSecretKeyRing] = {
    val decoded = PGPUtil.getDecoderStream(
      new ByteArrayInputStream(bytes)
    )
    val factory = new org.bouncycastle.openpgp.PGPObjectFactory(
      decoded,
      new org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator()
    )
    val factoryObjs = factoryToVec(factory)
    factoryObjs.foldLeft(acc) {
      case (acc, sr: PGPSecretKeyRing) => acc :+ sr
      case (acc, src: PGPSecretKeyRingCollection) =>
        acc ++ src.getKeyRings.asScala.toVector
      case (acc, _) => acc
    }
  }

  /** Build a ClaimedContent from PGPSecretKeyRings. */
  private def buildPgpSecretClaim(
      rings: Vector[PGPSecretKeyRing]
  ): ClaimedContent = {
    val secretKeys = rings.flatMap(r => r.getSecretKeys.asScala.toVector)
    if (secretKeys.exists(isSecretKeyEncrypted)) {
      // Encrypted path: extract S2K envelope from the first encrypted key.
      val first = secretKeys.find(isSecretKeyEncrypted).get
      pgpSecretEncryptedClaim(first)
    } else {
      // Unencrypted: derive public-key projection per secret key.
      val pubKeys = secretKeys.map(sk => pgpKeyOf(sk.getPublicKey))
      val primaryUid = rings.headOption.flatMap { r =>
        val pk = r.getPublicKey
        Option(pk).flatMap { p =>
          val it = p.getUserIDs
          if (it != null && it.hasNext) Some(it.next().toString) else None
        }
      }
      PrivateKeyPlaintextPgp(PgpKeyRing(pubKeys, primaryUid))
    }
  }

  /** True if the secret key has active S2K or non-zero encryption algorithm. */
  private def isSecretKeyEncrypted(
      sk: PGPSecretKey
  ): Boolean = {
    val s2k = Try(sk.getS2K).toOption.flatMap(Option.apply)
    val encAlg = Try(sk.getKeyEncryptionAlgorithm).toOption.getOrElse(0)
    s2k.isDefined || encAlg != 0
  }

  /** Build the encrypted-PGP-secret claim from a representative encrypted
    * PGPSecretKey.
    */
  private def pgpSecretEncryptedClaim(
      sk: PGPSecretKey
  ): PrivateKeyEncrypted = {
    val s2kOpt = Option(sk.getS2K)
    val cipher = Try(sk.getKeyEncryptionAlgorithm).toOption
      .flatMap(pgpSymmetricCipherNameMap.get)
    val kdfName: Option[String] = s2kOpt.map { s2k =>
      Try(s2k.getType).toOption match {
        case Some(3) => "s2k-iterated"
        case Some(1) => "s2k-salted"
        case Some(0) => "s2k-simple"
        case _       => "s2k"
      }
    }
    val kdfIters: Option[Long] = s2kOpt.flatMap { s2k =>
      Try(s2k.getIterationCount.toLong).toOption.filter(_ > 0L)
    }
    val kdfPrf: Option[String] = s2kOpt.flatMap { s2k =>
      Try(s2k.getHashAlgorithm).toOption.flatMap(pgpHashAlgNameMap.get)
    }
    val s2kSalt: Option[String] = s2kOpt.flatMap { s2k =>
      Try {
        val saltBytes = s2k.getIV
        if (saltBytes != null && saltBytes.length > 0) Helpers.toHex(saltBytes)
        else ""
      }.toOption.filter(_.nonEmpty)
    }
    PrivateKeyEncrypted(
      envelope = "pgp-encrypted-secret-key",
      kdfAlgorithm = kdfName,
      kdfIterations = kdfIters,
      kdfPrf = kdfPrf,
      cipher = cipher,
      salt = s2kSalt,
      iv = None
    )
  }

  /** PGP SymmetricKeyAlgorithmTag → canonical cipher name. */
  private[strategies] val pgpSymmetricCipherNameMap: Map[Int, String] = Map(
    1 -> "idea",
    2 -> "des-ede3-cbc",
    3 -> "cast5",
    4 -> "blowfish",
    7 -> "aes-128",
    8 -> "aes-192",
    9 -> "aes-256",
    10 -> "twofish",
    11 -> "camellia-128",
    12 -> "camellia-192",
    13 -> "camellia-256"
  )

  /** PGP HashAlgorithmTag → canonical hash name. */
  private[strategies] val pgpHashAlgNameMap: Map[Int, String] = Map(
    1 -> "md5",
    2 -> "sha1",
    3 -> "ripemd160",
    8 -> "sha256",
    9 -> "sha384",
    10 -> "sha512",
    11 -> "sha224",
    12 -> "sha3-256",
    14 -> "sha3-512"
  )

  /** Project a PGPPublicKey into a PgpKey. */
  private def pgpKeyOf(
      pk: PGPPublicKey
  ): PgpKey = {
    val fpHex = pk.getFingerprint.map(b => f"${b & 0xff}%02x").mkString
    val algId = pk.getAlgorithm
    val canonical = pgpAlgIdMap.getOrElse(algId, "unknown")

    val keySize: Option[Int] =
      if (canonical == "rsa" || canonical == "dsa" || canonical == "elgamal") {
        Try(pk.getBitStrength).toOption.filter(_ > 0)
      } else None

    val curve: Option[String] = canonical match {
      case "ec" | "x25519" | "x448" =>
        Try {
          val key = pk.getPublicKeyPacket.getKey
            .asInstanceOf[ECPublicBCPGKey]
          val oid = key.getCurveOID.getId
          pgpCurveOidMap.getOrElse(oid, oid)
        }.toOption
      case "ed25519" if pk.getVersion >= 6 =>
        // v6 native Ed25519 has no curve-OID field; canonical curve is
        // implicit in the alg name itself. Leave curve omitted.
        None
      case "ed25519" =>
        Try {
          val key = pk.getPublicKeyPacket.getKey
            .asInstanceOf[EdDSAPublicBCPGKey]
          val oid = key.getCurveOID.getId
          pgpCurveOidMap.getOrElse(oid, oid)
        }.toOption
      case _ => None
    }

    val userIds: Vector[String] = {
      val it = pk.getUserIDs
      if (it == null) Vector.empty
      else it.asScala.toVector.map(_.toString)
    }

    val expSecs = Try(pk.getValidSeconds).toOption.getOrElse(0L)
    val expiration: Option[java.util.Date] =
      if (expSecs > 0)
        Some(new java.util.Date(pk.getCreationTime.getTime + expSecs * 1000L))
      else None

    PgpKey(
      fingerprintHex = fpHex,
      version = pk.getVersion,
      pgpAlgId = algId,
      canonicalAlg = canonical,
      keySize = keySize,
      curve = curve,
      isPrimary = pk.isMasterKey,
      creationTime = pk.getCreationTime,
      expirationTime = expiration,
      userIds = userIds
    )
  }

  /** Build the pkg:pgp/fingerprint@{hex}?... pURL for a PGP key. */
  private[strategies] def purlForPgpKey(
      key: PgpKey
  ): PackageURL = {
    val parts = Vector(
      KVPair("alg", key.canonicalAlg),
      KVPair("version", key.version.toString())
    ) ++ key.keySize.toVector.map(s => KVPair("size", s.toString())) ++
      key.curve.toVector.map(c => KVPair("curve", c))
    val builder = PackageURLBuilder
      .aPackageURL()
      .withType("pgp")
      .withName("fingerprint")
      .withVersion(key.fingerprintHex)
    parts.foreach(q => builder.withQualifier(q.key, q.value))
    builder.build()
  }

  /** First 8 hex chars of the key fingerprint. */
  private[strategies] def pgpFp8(key: PgpKey): String =
    key.fingerprintHex.take(8)

  // ---------- per-cert derivation helpers ------------------------------

  /** Extract (alg, qualifier-map) from a public key.
    * @param pub
    *   JCA PublicKey
    * @param cert
    *   the certificate (used as fallback via SPKI OID)
    */
  private[strategies] def keyAlgAndQualifier(
      pub: Option[java.security.PublicKey],
      cert: X509Certificate
  ): (String, Map[String, String]) = {
    pub match {
      case None =>
        // JCA returned no PublicKey — composite or other unsupported algorithm.
        // Resolve via raw SPKI OID.
        spkiAlgFromCert(cert).getOrElse(("unknown", Map.empty))

      case Some(rsa: RSAPublicKey) =>
        ("rsa", Map("size" -> rsa.getModulus.bitLength.toString))
      case Some(dsa: DSAPublicKey) =>
        ("dsa", Map("size" -> dsa.getY.bitLength.toString))
      case Some(_: ECPublicKey) =>
        val curveName = bcCurveNameFromCert(cert).getOrElse {
          val raw = pub.map(_.getAlgorithm.toLowerCase).getOrElse("n/a")
          ecCurveMap.getOrElse(raw, raw)
        }
        val canonical =
          ecCurveMap.getOrElse(curveName.toLowerCase, curveName.toLowerCase)
        ("ec", Map("curve" -> canonical))
      case _ =>
        pub.map(_.getAlgorithm.toLowerCase) match {
          case Some("ed25519") | Some("1.3.101.112") => ("ed25519", Map.empty)
          case Some("ed448") | Some("1.3.101.113")   => ("ed448", Map.empty)
          case Some("x25519")                        => ("x25519", Map.empty)
          case Some("x448")                          => ("x448", Map.empty)
          case _ => spkiAlgFromCert(cert).getOrElse(("unknown", Map.empty))
        }

    }
  }

  /** Resolve EC curve name via BC's ECNamedCurveTable. */
  private def bcCurveNameFromCert(cert: X509Certificate): Option[String] = Try {
    val spki = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(
      cert.getPublicKey.getEncoded
    )
    val algOid = spki.getAlgorithm.getAlgorithm
    val params = spki.getAlgorithm.getParameters
    if (algOid.getId == "1.2.840.10045.2.1" && params != null) {
      val curveOid = params.toString
      val name = org.bouncycastle.asn1.x9.ECNamedCurveTable.getName(
        new org.bouncycastle.asn1.ASN1ObjectIdentifier(curveOid)
      )
      Option(name).orElse(Some(curveOid))
    } else None
  }.toOption.flatten

  /** Extract SPKI DER bytes from a certificate (works even when getPublicKey
    * returns null).
    */
  private[strategies] def spkiBytesFromCert(
      cert: X509Certificate
  ): Array[Byte] = {
    val bcCert =
      org.bouncycastle.asn1.x509.Certificate.getInstance(cert.getEncoded)
    bcCert.getSubjectPublicKeyInfo.getEncoded
  }

  /** Extract the SPKI algorithm OID from a certificate. */
  private[strategies] def spkiAlgOidFromCert(cert: X509Certificate): String = {
    val bcCert =
      org.bouncycastle.asn1.x509.Certificate.getInstance(cert.getEncoded)
    bcCert.getSubjectPublicKeyInfo.getAlgorithm.getAlgorithm.getId
  }

  /** Resolve SPKI OID to (alg, qualifiers) via pubkeyOidMap. */
  private def spkiAlgFromCert(
      cert: X509Certificate
  ): Option[(String, Map[String, String])] = Try {
    val oid = spkiAlgOidFromCert(cert)
    pubkeyOidMap.get(oid).map { case (alg, params) =>
      val quals = params.map(p => "params" -> p).toMap
      (alg, quals)
    }
  }.toOption.flatten

  /** Canonical signature algorithm name from a certificate. */
  private[strategies] def canonicalSigAlg(cert: X509Certificate): String = {
    sigAlgOidMap.getOrElse(
      cert.getSigAlgOID,
      s"<unknown-sig-oid-${cert.getSigAlgOID}>"
    )
  }

  /** Canonical signature algorithm name from a CRL. */
  private[strategies] def canonicalSigAlgCrl(crl: X509CRL): String = {
    sigAlgOidMap.getOrElse(
      crl.getSigAlgOID,
      s"<unknown-sig-oid-${crl.getSigAlgOID}>"
    )
  }

  /** True if subject == issuer and the signature verifies. */
  private[strategies] def isSelfSigned(cert: X509Certificate): Boolean = {
    if (cert.getSubjectX500Principal != cert.getIssuerX500Principal) false
    else {
      val pub = cert.getPublicKey
      if (pub == null) {
        true
      } else
        Try {
          cert.verify(pub, "BC")
          true
        }.getOrElse(false)
    }
  }

  /** ISO-8601 UTC date string. */
  private[strategies] def isoUtc(d: java.util.Date): String = {
    val instant = d.toInstant
    DateTimeFormatter.ISO_INSTANT
      .withZone(ZoneOffset.UTC)
      .format(instant)
      .replaceAll("\\.\\d+Z$", "Z")
  }

  /** Key-usage bit names from the X.509 KeyUsage extension. */
  private[strategies] def keyUsageNames(
      cert: X509Certificate
  ): Option[String] = {
    val ku = cert.getKeyUsage
    if (ku == null) None
    else {
      val labels = Seq(
        "digital-signature",
        "non-repudiation",
        "key-encipherment",
        "data-encipherment",
        "key-agreement",
        "key-cert-sign",
        "crl-sign",
        "encipher-only",
        "decipher-only"
      )
      val present = labels.zipWithIndex.collect {
        case (label, idx) if idx < ku.length && ku(idx) => label
      }
      if (present.isEmpty) None else Some(present.mkString(","))
    }
  }

  /** Extended-key-usage OID → canonical name. */
  private[strategies] def ekuNames(cert: X509Certificate): Option[String] =
    Try {
      val eku = cert.getExtendedKeyUsage
      if (eku == null) None
      else {
        val ids = eku.asScala.toSeq
        val mapped = ids.map(o => ekuOidMap.getOrElse(o, o))
        if (mapped.isEmpty) None else Some(mapped.mkString(","))
      }
    }.toOption.flatten

  /** Subject-Alternative-Names entries as type:value pairs. */
  private[strategies] def sanList(cert: X509Certificate): Option[String] = Try {
    val san = cert.getSubjectAlternativeNames
    if (san == null) None
    else {
      val entries = san.asScala.toSeq.flatMap { entry =>
        val list = entry.asInstanceOf[java.util.List[?]].asScala.toSeq
        if (list.length < 2) None
        else {
          val tag = list(0).asInstanceOf[Integer].intValue
          val value = list(1).toString
          // GeneralName tags from RFC 5280. Stringy types (1/2/6/7) emit
          // `type:value`; structured types (0/3/4/5/8) emit just the type
          // label (the inner value isn't a useful identifier on its own).
          tag match {
            case 1     => Some(s"email:$value")
            case 2     => Some(s"DNS:$value")
            case 6     => Some(s"URI:$value")
            case 7     => Some(s"IP:$value")
            case 0     => Some("OTHER:OtherName")
            case 3     => Some("OTHER:X400Address")
            case 4     => Some("OTHER:DirectoryName")
            case 5     => Some("OTHER:EDIPartyName")
            case 8     => Some("OTHER:RegisteredID")
            case other => Some(s"OTHER-$other")
          }
        }
      }
      if (entries.isEmpty) None else Some(entries.mkString(","))
    }
  }.toOption.flatten

  /** RFC2253 DN with JDK #hex fallback decoded to text form. */
  private[strategies] def dnString(
      name: javax.security.auth.x500.X500Principal
  ): String = {
    val rfc2253 = name.getName(javax.security.auth.x500.X500Principal.RFC2253)
    val hexRun = "#([0-9a-fA-F]{4,})".r
    hexRun.replaceAllIn(
      rfc2253,
      m => {
        val hex: String = m.group(1).nn
        decodeAsn1HexString(hex) match {
          case Some(decoded) =>
            java.util.regex.Matcher.quoteReplacement(decoded)
          case None => java.util.regex.Matcher.quoteReplacement(s"#$hex")
        }
      }
    )
  }

  /** Decode an ASN.1 hex string if it represents a text type. */
  private def decodeAsn1HexString(hex: String): Option[String] = Try {
    val bytes = hex.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    if (bytes.length < 2) None
    else {
      val tag = bytes(0) & 0xff
      val lenByte = bytes(1) & 0xff
      val (len, dataOff) =
        if ((lenByte & 0x80) == 0) (lenByte, 2)
        else {
          val numLen = lenByte & 0x7f
          if (numLen == 0 || numLen > 4 || bytes.length < 2 + numLen) (-1, -1)
          else {
            var v = 0
            (0 until numLen).foreach(i => v = (v << 8) | (bytes(2 + i) & 0xff))
            (v, 2 + numLen)
          }
        }
      if (len < 0 || dataOff < 0 || bytes.length != dataOff + len) None
      else {
        val payload = bytes.slice(dataOff, dataOff + len)
        tag match {
          case 0x13 | 0x16 | 0x14 =>
            Some(
              new String(payload, java.nio.charset.StandardCharsets.US_ASCII)
            )
          case 0x0c =>
            Some(new String(payload, java.nio.charset.StandardCharsets.UTF_8))
          case 0x1e =>
            Some(
              new String(payload, java.nio.charset.StandardCharsets.UTF_16BE)
            )
          case _ => None
        }
      }
    }
  }.toOption.flatten

  /** CN if present, else full DN. */
  private[strategies] def cnOrDn(
      name: javax.security.auth.x500.X500Principal
  ): String = {
    val dn = dnString(name)
    val cnRegex = "CN=([^,]+)".r
    cnRegex.findFirstMatchIn(dn).flatMap(m => Option(m.group(1))).getOrElse(dn)
  }

  /** Forbidden private-key patterns from Appendix C. */
  private[strategies] val forbiddenPatterns: Seq[Pattern] = Seq(
    "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "-----BEGIN PGP PRIVATE KEY BLOCK-----",
    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----",
    "MIIEvQIBADAN",
    "MIIEpAIBAAKCAQEA",
    "MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+",
    "openssh-key-v1"
  ).map(p => Pattern.compile(p))

  /** Metadata keys that may legitimately carry long lowercase-hex values. */
  private[strategies] val longHexAllowedKeys: Seq[Pattern] = Seq(
    "^Certificates:SpkiSha256$",
    "^Certificates:CertSha256$",
    "^Certificates:CrlSha256$",
    "^Certificates:Serial$",
    "^Certificates:RevokedSerials$",
    "^Certificates:Fingerprint$",
    "^Certificates:SshFingerprintSha256$",
    "^Certificates:SshCertSha256$",
    "^Certificates:SshCertCaFingerprint$",
    "^Certificates:KdfSalt$",
    "^Certificates:Iv$",
    "^Certificates:Cert:[0-9]+:(SpkiSha256|CertSha256|Serial|Fingerprint)$",
    "^Certificates:Entry:[^:]+:(SpkiSha256|CertSha256|Serial|Fingerprint)$",
    "^Certificates:Key:[0-9a-f]+:Fingerprint$"
  ).map(p => Pattern.compile(p))

  /** Long-hex run pattern: 32+ consecutive lowercase hex characters. */
  private[strategies] val longHexPattern: Pattern =
    Pattern.compile("[0-9a-f]{32,}")

  private[strategies] def assertNoLeak(
      metadata: TreeMap[String, TreeSet[StringOrPair]]
  ): Unit = {
    metadata.foreach { case (key, values) =>
      val keyAllowsLongHex = longHexAllowedKeys.exists(_.matcher(key).matches())
      values.foreach { v =>
        val text = v match {
          case io.spicelabs.goatrodeo.omnibor.StringOf(s)   => s
          case io.spicelabs.goatrodeo.omnibor.PairOf(_, s2) => s2
        }
        forbiddenPatterns.foreach { pat =>
          if (pat.matcher(text).find()) {
            throw new RuntimeException(
              s"Certificates leak guard: metadata key '$key' value matched " +
                s"forbidden pattern /${pat.pattern}/ — refusing to emit"
            )
          }
        }
        if (!keyAllowsLongHex && longHexPattern.matcher(text).find()) {
          throw new RuntimeException(
            s"Certificates leak guard: metadata key '$key' carries a " +
              s"32+ char lowercase-hex run but is not on the long-hex " +
              s"allowlist — possible serialized private scalar; refusing to emit"
          )
        }
      }
    }
  }

  /** Parse a PEM-private-key MIME; disambiguates encrypted vs unencrypted. */
  private[strategies] def parsePemPrivateKey(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    artifact.withStream { f =>
      val raw = Helpers.slurpInput(f)
      val text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1)
      if (procTypeEncryptedHeaderPresent(text)) {
        Some(legacyEncryptedPemClaim(text))
      } else {
        parseUnencryptedPemBytes(raw)
      }
    }
  }

  private def iteratorToVec[T](parser: java.util.Iterator[T]): Vector[T] = {
    val iterator = parser
    var ret = Vector[T]()
    while (iterator.hasNext) {
      ret = ret :+ iterator.next().asInstanceOf[T]
    }

    ret
  }

  /** Parse a PEM-encrypted-private-key MIME; extracts envelope only. */
  private[strategies] def parsePemEncryptedPrivateKey(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    artifact.withStream { f =>
      Try {
        Using.resource(
          new PEMParser(
            new InputStreamReader(
              f,
              StandardCharsets.ISO_8859_1
            )
          )
        ) { parser =>
          val objects = parserToVec(parser)

          objects.headOption match {
            case Some(epki: PKCS8EncryptedPrivateKeyInfo) =>
              Some(pkcs8EncryptedClaimFrom(epki))
            case _ => None
          }
        }
      }.toOption.flatten
    }
  }

  /** True if PEM text contains a legacy Proc-Type: 4,ENCRYPTED header. */
  private def procTypeEncryptedHeaderPresent(text: String): Boolean =
    text.contains("Proc-Type: 4,ENCRYPTED") ||
      text.contains("Proc-Type:4,ENCRYPTED")

  /** Build the legacy-PEM-encrypted claim. */
  private def legacyEncryptedPemClaim(text: String): PrivateKeyEncrypted = {
    val (cipher, iv) = legacyDekCipherAndIv(text)
    PrivateKeyEncrypted(
      envelope = "pem-legacy-encrypted",
      kdfAlgorithm = None,
      kdfIterations = None,
      kdfPrf = None,
      cipher = cipher,
      salt = None,
      iv = iv
    )
  }

  /** Extract cipher name and IV from the DEK-Info line. */
  private def legacyDekCipherAndIv(
      text: String
  ): (Option[String], Option[String]) = {
    val rx = "(?m)^DEK-Info:\\s*([A-Z0-9-]+),([0-9A-Fa-f]+)".r
    rx.findFirstMatchIn(text) match {
      case Some(m) =>
        val cipher = Option(m.group(1)).map(_.toLowerCase)
        val iv = Option(m.group(2)).map(_.toLowerCase)
        (cipher, iv)
      case None =>
        // Fall back to cipher-only match (legacy formats may omit IV).
        val rxCipher = "(?m)^DEK-Info:\\s*([A-Z0-9-]+)".r
        val cipher = rxCipher
          .findFirstMatchIn(text)
          .flatMap(m => Option(m.group(1)).map(_.toLowerCase))
        (cipher, None)
    }
  }

  /** Parse PEM bytes as PEMKeyPair or PrivateKeyInfo; derive public SPKI. */
  private def parseUnencryptedPemBytes(
      raw: Array[Byte]
  ): Option[PrivateKeyPlaintextPem] = Try {
    Using.resource(
      new PEMParser(
        new InputStreamReader(
          new ByteArrayInputStream(raw),
          java.nio.charset.StandardCharsets.ISO_8859_1
        )
      )
    ) { parser =>
      val objects = parserToVec(parser)

      val spki: Option[SubjectPublicKeyInfo] =
        objects.headOption match {
          case Some(kp: PEMKeyPair)      => Some(kp.getPublicKeyInfo)
          case Some(pki: PrivateKeyInfo) => spkiFromPrivateKeyInfo(pki)
          case _                         => None
        }
      spki.map { s =>
        val spkiBytes = s.getEncoded
        val AlgAndQualifier(alg, sz, curve, params) = algAndQualifierFromSpki(s)
        PrivateKeyPlaintextPem(
          spkiBytes = spkiBytes,
          canonicalAlg = alg,
          keySize = sz,
          curve = curve,
          params = params
        )
      }
    }
  }.toOption.flatten

  /** Derive SPKI from PKCS#8 PrivateKeyInfo. */
  private def spkiFromPrivateKeyInfo(
      pki: PrivateKeyInfo
  ): Option[SubjectPublicKeyInfo] = {

    Try {
      val priv = PrivateKeyFactory.createKey(pki)
      val pub: Option[AsymmetricKeyParameter] = priv match {
        case r: RSAPrivateCrtKeyParameters =>
          Some(new RSAKeyParameters(false, r.getModulus, r.getPublicExponent))
        case ec: ECPrivateKeyParameters =>
          val q = ec.getParameters.getG.multiply(ec.getD).normalize
          Some(new ECPublicKeyParameters(q, ec.getParameters))
        case ed: Ed25519PrivateKeyParameters =>
          Some(ed.generatePublicKey)
        case ed: Ed448PrivateKeyParameters =>
          Some(ed.generatePublicKey)
        case x: X25519PrivateKeyParameters =>
          Some(x.generatePublicKey)
        case x: X448PrivateKeyParameters =>
          Some(x.generatePublicKey)
        case dsa: DSAPrivateKeyParameters =>
          // y = g^x mod p
          val params = dsa.getParameters
          val y = params.getG.modPow(dsa.getX, params.getP)
          Some(new DSAPublicKeyParameters(y, params))
        case _ => None
      }
      pub.map(p =>
        org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
          .createSubjectPublicKeyInfo(p)
      )
    }.toOption.flatten
  }
  private[strategies] case class AlgAndQualifier(
      alg: String,
      bitLen: Option[Int],
      curve: Option[String],
      paramsTok: Option[String]
  )

  /** Project SPKI ASN.1 → (alg, size?, curve?, params?). */
  private[strategies] def algAndQualifierFromSpki(
      spki: org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
  ): AlgAndQualifier = {
    val algOid = spki.getAlgorithm.getAlgorithm.getId
    pubkeyOidMap.get(algOid) match {
      case Some((alg, paramsTok)) => AlgAndQualifier(alg, None, None, paramsTok)
      case None =>
        algOid match {
          case "1.2.840.113549.1.1.1" =>
            // RSA — read modulus from inner RSAPublicKey ASN.1
            val sz = Try {
              val keyBytes = spki.getPublicKeyData.getBytes
              val rsaPub = org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(
                org.bouncycastle.asn1.ASN1Primitive.fromByteArray(keyBytes)
              )
              rsaPub.getModulus.bitLength
            }.toOption
            AlgAndQualifier("rsa", sz, None, None)
          case "1.2.840.10045.2.1" =>
            // EC — algorithm parameters are an OID identifying the curve.
            // Resolve OID → standard curve name via BC's ECNamedCurveTable,
            // then to canonical via ecCurveMap.
            val curve = Try {
              val params = spki.getAlgorithm.getParameters
              val asOid =
                org.bouncycastle.asn1.ASN1ObjectIdentifier.getInstance(params)
              val stdName =
                org.bouncycastle.asn1.x9.ECNamedCurveTable.getName(asOid)
              val key =
                if (stdName != null) stdName.toLowerCase
                else asOid.getId
              ecCurveMap.getOrElse(key, key)
            }.toOption
            AlgAndQualifier("ec", None, curve, None)
          case "1.2.840.10040.4.1" =>
            // DSA — size from parameter P
            val sz = Try {
              val params = spki.getAlgorithm.getParameters
              val dsaParams =
                org.bouncycastle.asn1.x509.DSAParameter.getInstance(params)
              dsaParams.getP.bitLength
            }.toOption
            AlgAndQualifier("dsa", sz, None, None)
          case "1.3.101.112" => AlgAndQualifier("ed25519", None, None, None)
          case "1.3.101.113" => AlgAndQualifier("ed448", None, None, None)
          case "1.3.101.110" => AlgAndQualifier("x25519", None, None, None)
          case "1.3.101.111" => AlgAndQualifier("x448", None, None, None)
          case _             => AlgAndQualifier("unknown", None, None, None)
        }
    }
  }

  /** Build encrypted-PKCS#8 claim; inspects PBES2/PBES1 parameters. */
  private def pkcs8EncryptedClaimFrom(
      epki: org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
  ): PrivateKeyEncrypted = {
    val algOid = epki.getEncryptionAlgorithm.getAlgorithm.getId
    // PBES2 = 1.2.840.113549.1.5.13. Its parameters wrap (KDF, cipher).
    if (algOid == "1.2.840.113549.1.5.13") {
      val params = Try {
        org.bouncycastle.asn1.pkcs.PBES2Parameters.getInstance(
          epki.getEncryptionAlgorithm.getParameters
        )
      }.toOption
      val kdfDesc = params.flatMap(p => Option(p.getKeyDerivationFunc))
      val (kdfName, kdfIters, kdfPrf, kdfSalt) = kdfDesc match {
        case Some(kdf) =>
          val kdfOid = kdf.getAlgorithm.getId
          kdfOid match {
            case "1.2.840.113549.1.5.12" =>
              // PBKDF2
              val pbkdf2 = Try {
                org.bouncycastle.asn1.pkcs.PBKDF2Params.getInstance(
                  kdf.getParameters
                )
              }.toOption
              val iters = pbkdf2.flatMap(p =>
                Try(p.getIterationCount.intValue.toLong).toOption
              )
              val prf = pbkdf2.flatMap { p =>
                val prfOid = Try(p.getPrf.getAlgorithm.getId).toOption
                prfOid.flatMap(prfOidToCanonicalHash.get)
              }
              val salt = pbkdf2.flatMap(p =>
                Try(Helpers.toHex(p.getSalt)).toOption.filter(_.nonEmpty)
              )
              (Some("pbkdf2"), iters, prf, salt)
            case "1.3.6.1.4.1.11591.4.11" =>
              // scrypt — salt is in ScryptParams
              val scrypt = Try {
                org.bouncycastle.asn1.misc.ScryptParams.getInstance(
                  kdf.getParameters
                )
              }.toOption
              val salt = scrypt.flatMap(p =>
                Try(Helpers.toHex(p.getSalt)).toOption.filter(_.nonEmpty)
              )
              val n = scrypt.flatMap(p =>
                Try(p.getCostParameter.longValueExact).toOption
              )
              (Some("scrypt"), n, None, salt)
            case other => (Some(other), None, None, None)
          }
        case None => (None, None, None, None)
      }
      val (cipherName, cipherIv) = params match {
        case Some(p) =>
          val oid = Try(p.getEncryptionScheme.getAlgorithm.getId).toOption
          val cipher = oid.map(o => cipherOidToName.getOrElse(o, o))
          // For AES-CBC and DES-EDE3-CBC, the IV is OCTET STRING in
          // EncryptionScheme.parameters. For AES-GCM, the parameters are
          // a SEQUENCE; we only extract IV for the simple OCTET-STRING
          // case (others stay None).
          val iv = Try {
            val rawParams = p.getEncryptionScheme.getParameters
            val asOctet =
              org.bouncycastle.asn1.ASN1OctetString.getInstance(rawParams)
            Helpers.toHex(asOctet.getOctets)
          }.toOption.filter(_.nonEmpty)
          (cipher, iv)
        case None => (None, None)
      }
      PrivateKeyEncrypted(
        envelope = "pkcs8-encrypted",
        kdfAlgorithm = kdfName,
        kdfIterations = kdfIters,
        kdfPrf = kdfPrf,
        cipher = cipherName,
        salt = kdfSalt,
        iv = cipherIv
      )
    } else {
      // PBES1 path. RFC 8018 §A.3 PBES1 algorithms include
      // pbeWithMD5AndDES-CBC (1.2.840.113549.1.5.3),
      // pbeWithSHA1AndDES-CBC, pbeWithSHA1And3-KeyTripleDES-CBC, etc.
      // Their parameters are a SEQUENCE { salt OCTET STRING, iterationCount INTEGER }.
      val pbes1 = Try {
        org.bouncycastle.asn1.pkcs.PBEParameter.getInstance(
          epki.getEncryptionAlgorithm.getParameters
        )
      }.toOption
      val salt = pbes1.flatMap(p =>
        Try(Helpers.toHex(p.getSalt)).toOption.filter(_.nonEmpty)
      )
      val iters =
        pbes1.flatMap(p => Try(p.getIterationCount.longValueExact).toOption)
      val cipher = pbes1OidToCanonicalCipher.get(algOid)
      PrivateKeyEncrypted(
        envelope = "pkcs8-encrypted",
        kdfAlgorithm = if (pbes1.isDefined) Some("pbes1") else None,
        kdfIterations = iters,
        kdfPrf = pbes1OidToPrf.get(algOid),
        cipher = cipher,
        salt = salt,
        iv = None
      )
    }
  }

  /** Parse an OpenSSH-private-key MIME (openssh-key-v1 envelope). */
  private[strategies] def parseOpenSshPrivateKey(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    artifact.withStream { f =>
      val raw = Helpers.slurpInput(f)
      Try {
        val envelope = decodeOpenSshArmor(raw)
        envelope.flatMap(parseOpenSshV1Envelope)
      }.toOption.flatten
    }
  }

  /** Strip PEM armor and base64-decode the inner body. */
  private def decodeOpenSshArmor(raw: Array[Byte]): Option[Array[Byte]] = {
    val text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1)
    val begin = "-----BEGIN OPENSSH PRIVATE KEY-----"
    val end = "-----END OPENSSH PRIVATE KEY-----"
    val bIdx = text.indexOf(begin)
    val eIdx = text.indexOf(end)
    if (bIdx < 0 || eIdx <= bIdx) None
    else {
      val body = text.substring(bIdx + begin.length, eIdx)
      val b64 = body.replaceAll("\\s+", "")
      Try(java.util.Base64.getDecoder.decode(b64)).toOption
    }
  }

  /** Parse an openssh-key-v1 envelope. */
  private def parseOpenSshV1Envelope(
      env: Array[Byte]
  ): Option[ClaimedContent] = {
    val magic = "openssh-key-v1\u0000".getBytes(
      java.nio.charset.StandardCharsets.US_ASCII
    )
    if (env.length < magic.length) None
    else if (
      !java.util.Arrays.equals(
        java.util.Arrays.copyOfRange(env, 0, magic.length),
        magic
      )
    ) None
    else {
      val body = java.util.Arrays.copyOfRange(env, magic.length, env.length)
      val r = SshWireReader(body)
      val cipherName = Try(r.readUtf8String()).toOption.getOrElse("")
      val kdfName = Try(r.readUtf8String()).toOption.getOrElse("")
      val kdfOptions = Try(r.readString()).toOption.getOrElse(Array.empty[Byte])
      val numKeys = Try(r.readUInt32()).toOption.getOrElse(0L)
      if (kdfName == "none" && cipherName == "none") {
        // Unencrypted: read first public-key blob in the clear.
        if (numKeys < 1) None
        else {
          Try(r.readString()).toOption.flatMap { pubWire =>
            val pr = SshWireReader(pubWire)
            Try(pr.readUtf8String()).toOption.flatMap { algName =>
              if (!sshAlgMap.contains(algName)) None
              else {
                val rsaBits = if (algName == "ssh-rsa") {
                  Try {
                    val _e = pr.readMpint()
                    val n = pr.readMpint()
                    SshWireReader.mpintBitLength(n)
                  }.toOption
                } else None
                Some(PrivateKeyPlaintextOpenSsh(pubWire, algName, rsaBits))
              }
            }
          }
        }
      } else {
        // Encrypted: extract envelope only.
        val canonicalKdf = kdfName match {
          case "bcrypt" => Some("bcrypt")
          case "none"   => None
          case other    => Some(other)
        }
        val (kdfIters, kdfSalt): (Option[Long], Option[String]) =
          if (kdfName == "bcrypt") {
            Try {
              val k = SshWireReader(kdfOptions)
              val saltBytes = k.readString()
              val rounds = k.readUInt32()
              (Some(rounds), Some(Helpers.toHex(saltBytes)))
            }.toOption.getOrElse((None, None))
          } else (None, None)
        val canonicalCipher: Option[String] =
          if (cipherName.nonEmpty && cipherName != "none") Some(cipherName)
          else None
        Some(
          PrivateKeyEncrypted(
            envelope = "openssh-encrypted",
            kdfAlgorithm = canonicalKdf,
            kdfIterations = kdfIters,
            kdfPrf = None,
            cipher = canonicalCipher,
            salt = kdfSalt,
            iv = None
          )
        )
      }
    }
  }

  /** Emit the (spki, cert) pURL pair for a single X.509 cert. */
  private[strategies] def purlsForCert(
      c: X509Certificate
  ): Vector[PackageURL] = {
    val derBytes = c.getEncoded
    val spkiBytes = spkiBytesFromCert(c)
    val certSha = sha256Hex(derBytes)
    val spkiSha = sha256Hex(spkiBytes)
    val (alg, qualMap) = keyAlgAndQualifier(Option(c.getPublicKey), c)
    val sigAlg = canonicalSigAlg(c)
    val selfSigned = isSelfSigned(c)
    val version = c.getVersion

    val companion =
      (qualMap.get("size").toVector.map(s => KVPair("size", s)) ++
        qualMap.get("curve").toVector.map(c => KVPair("curve", c)) ++
        qualMap.get("params").toVector.map(KVPair("params", _)))

    val spkiQuals = Vector(
      KVPair("alg", alg),
      KVPair("version", version.toString())
    ) ++ companion

    val certQuals = Vector(
      KVPair("alg", alg),
      KVPair("sig-alg", sigAlg),
      KVPair(s"self-signed", selfSigned.toString()),
      KVPair(s"version", version.toString())
    ) ++ companion
    val spkiBuilder = PackageURLBuilder
      .aPackageURL()
      .withType("x509")
      .withName("spki-sha256")
      .withVersion(spkiSha)
    spkiQuals.foreach(q => spkiBuilder.withQualifier(q.key, q.value))

    val certBuilder = PackageURLBuilder
      .aPackageURL()
      .withType("x509")
      .withName("cert-sha256")
      .withVersion(certSha)
    certQuals.foreach(q => certBuilder.withQualifier(q.key, q.value))
    Vector(
      spkiBuilder.build(),
      certBuilder.build()
    )
  }

  /** Per-cert metadata block. */
  private[strategies] def perCertMetadata(
      adHoc: String => String,
      c: X509Certificate
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val derBytes = c.getEncoded
    val spkiBytes = spkiBytesFromCert(c)
    val certSha = sha256Hex(derBytes)
    val spkiSha = sha256Hex(spkiBytes)
    val (alg, qualMap) = keyAlgAndQualifier(Option(c.getPublicKey), c)
    val sigAlg = canonicalSigAlg(c)
    val selfSigned = isSelfSigned(c)
    val version = c.getVersion
    val isCa = c.getBasicConstraints >= 0
    val subject = c.getSubjectX500Principal
    val issuer = c.getIssuerX500Principal

    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(adHoc("SubjectDN") -> TreeSet(StringOrPair(dnString(subject)))) +?
        Some(adHoc("IssuerDN") -> TreeSet(StringOrPair(dnString(issuer)))) +?
        Some(
          adHoc("Serial") -> TreeSet(
            StringOrPair(c.getSerialNumber.toString(16))
          )
        ) +?
        Some(
          adHoc("NotBefore") -> TreeSet(StringOrPair(isoUtc(c.getNotBefore)))
        ) +?
        Some(
          adHoc("NotAfter") -> TreeSet(StringOrPair(isoUtc(c.getNotAfter)))
        ) +?
        Some(adHoc("KeyAlgorithm") -> TreeSet(StringOrPair(alg))) +?
        Some(adHoc("SigAlgorithm") -> TreeSet(StringOrPair(sigAlg))) +?
        Some(adHoc("SpkiSha256") -> TreeSet(StringOrPair(spkiSha))) +?
        Some(adHoc("CertSha256") -> TreeSet(StringOrPair(certSha))) +?
        Some(adHoc("IsCA") -> TreeSet(StringOrPair(isCa.toString))) +?
        Some(
          adHoc("SelfSigned") -> TreeSet(StringOrPair(selfSigned.toString))
        ) +?
        Some(adHoc("Version") -> TreeSet(StringOrPair(version.toString)))

    qualMap.get("size").foreach { v =>
      tm = tm + (adHoc("KeySize") -> TreeSet(StringOrPair(v)))
    }
    qualMap.get("curve").foreach { v =>
      tm = tm + (adHoc("Curve") -> TreeSet(StringOrPair(v)))
    }
    qualMap.get("params").foreach { v =>
      tm = tm + (adHoc("Params") -> TreeSet(StringOrPair(v)))
    }
    sanList(c).foreach { v =>
      tm = tm + (adHoc("SAN") -> TreeSet(StringOrPair(v)))
    }
    keyUsageNames(c).foreach { v =>
      tm = tm + (adHoc("KeyUsage") -> TreeSet(StringOrPair(v)))
    }
    ekuNames(c).foreach { v =>
      tm = tm + (adHoc("ExtendedKeyUsage") -> TreeSet(StringOrPair(v)))
    }
    tm
  }

  /** URL-encode a keystore alias. */
  private[strategies] def urlEncodeAlias(alias: String): String = {
    java.net.URLEncoder.encode(alias, "UTF-8")
  }
}

/** A single artifact claimed by the Certificates strategy.
  * @param artifact
  *   the file the strategy claimed
  * @param claim
  *   the parsed cryptographic content
  */
class Certificates(
    artifact: ArtifactWrapper,
    claim: Certificates.ClaimedContent
) extends ToProcess {

  override def markSuccessfulCompletion(): Unit = artifact.finished()

  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  override type MarkerType = SingleMarker
  override type StateType = CertificatesState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) ->
      new CertificatesState(artifact, Some(claim))
}
