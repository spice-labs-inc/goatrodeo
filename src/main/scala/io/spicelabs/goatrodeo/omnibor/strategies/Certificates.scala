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
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?
import io.spicelabs.goatrodeo.omnibor.strategies.CertificatesOidMaps.*
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.ECPublicKey
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.regex.Pattern
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

/** Strategy entry-point for X.509 certificates, CRLs, Java keystores
  * (JKS/JCEKS/PKCS#12/BKS), PEM bundles, SSH public keys, OpenSSH
  * CA-issued certificates, PGP keys, and private keys.
  *
  * Phase 3 lands the X.509 single-cert path. Phase 4 adds keystores,
  * PEM bundles, and CRLs. Phases 5-7 add SSH, PGP, and private keys.
  *
  * ## Hard rules
  *
  *   - Never emit raw private-key material in any Item body, metadata
  *     value, log message, or debug output. Enforced by the
  *     defensive leak sweep in [[CertificatesState.finalAugmentation]].
  *   - Keystores produce one Item with all contained pURLs and
  *     metadata flat — no child Items.
  *   - No new `EdgeType` values added.
  *   - All qualifier values are lowercase with hyphens.
  *   - Ad-hoc metadata keys use `:` as the separator.
  */
object Certificates {

  // Register the BC provider once at object init. Idempotent.
  private val _bcInit: Unit = {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider())
    }
  }

  // ---------- claim variants -------------------------------------------

  /** A successfully-parsed artifact ready for emission. The variant
    * tells `getPurls` / `getMetadata` how to dispatch. */
  sealed trait ClaimedContent

  /** Phase-3: single X.509 certificate. */
  final case class SingleCert(cert: X509Certificate) extends ClaimedContent

  /** Phase-4: Java/BC keystore.
    * @param ks         opened keystore (`null` password); `None` if
    *                   the null-password load failed (encrypted)
    * @param format     `"jks"`, `"jceks"`, `"pkcs12"`, `"bks"`
    * @param entryCount alias count if loaded; 0 if encrypted
    */
  final case class Keystore(
      ks: Option[KeyStore],
      format: String,
      entryCount: Int,
  ) extends ClaimedContent

  /** Phase-4: multi-block PEM bundle. */
  final case class Bundle(certs: Vector[X509Certificate]) extends ClaimedContent

  /** Phase-4: X.509 Certificate Revocation List. */
  final case class Crl(crl: X509CRL) extends ClaimedContent

  /** Phase-5: plain OpenSSH public key (single line). */
  final case class SshPubkey(
      wireBytes: Array[Byte],
      algName: String,
      comment: Option[String],
      rsaModulusBits: Option[Int],
  ) extends ClaimedContent

  /** Phase-5: OpenSSH CA-issued certificate (user or host). */
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
      comment: Option[String],
  ) extends ClaimedContent

  /** Phase-6: a single PGP key inside a PGP key ring — primary or
    * subkey. The strategy emits one pURL and one namespaced metadata
    * block per key, matching Phase 5's "every key is its own crypto
    * identity" stance. */
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
      userIds: Vector[String],
  )

  /** Phase-6: one or more PGP keys parsed from an `application/pgp-keys`
    * artifact (armored or binary). The first user-id (if any) on the
    * primary key feeds `MKC.NAME`. */
  final case class PgpKeyRing(
      keys: Vector[PgpKey],
      primaryUserId: Option[String],
  ) extends ClaimedContent

  /** Phase-7: an unencrypted PKCS#8 or legacy-PEM private key from
    * which the public key has been derived. The strategy emits a
    * `pkg:x509/spki-sha256@{hex}` pURL — same shape as Phase 3's SPKI
    * pURL — plus full pubkey-style metadata with
    * `Certificates:DerivedFromPrivateKey=true` and
    * `Certificates:Envelope=plaintext`.
    *
    * Hard-rule invariant: `spkiBytes` is the public SubjectPublicKeyInfo
    * DER. No private-key material is carried here. The leak sweep will
    * re-verify before emit.
    */
  final case class PrivateKeyPlaintextPem(
      spkiBytes: Array[Byte],
      canonicalAlg: String,
      keySize: Option[Int],
      curve: Option[String],
      params: Option[String],
  ) extends ClaimedContent

  /** Phase-7: an unencrypted OpenSSH-v1 private key. The wire-format
    * public-key blob is stored alongside the private region in the
    * clear (per RFC-style openssh-key-v1 format), so we read it
    * directly rather than re-deriving from any private scalar. The
    * emitted pURL matches Phase 5's `pkg:ssh/sha256@{b64}` shape.
    */
  final case class PrivateKeyPlaintextOpenSsh(
      wireBytes: Array[Byte],
      algName: String,
      rsaModulusBits: Option[Int],
  ) extends ClaimedContent

  /** Phase-7: an unencrypted PGP secret key ring. The public-key
    * portion of every secret key is derivable via
    * `PGPSecretKey.getPublicKey`; we collect those and reuse Phase 6's
    * emitters. `Certificates:DerivedFromPrivateKey=true` distinguishes
    * the metadata from a plain Phase-6 public-key claim.
    */
  final case class PrivateKeyPlaintextPgp(
      ring: PgpKeyRing,
  ) extends ClaimedContent

  /** Phase-7: an encrypted private key (any of PKCS#8-encrypted,
    * legacy-PEM-encrypted, OpenSSH-encrypted, or PGP-encrypted-secret).
    * Emits envelope-only metadata; **no pURL**, **no decryption
    * attempt**, **no password guessing**.
    *
    * Per Phase 7 plan, salt and IV are part of the envelope (not
    * private material — they're public KDF/cipher parameters). They
    * are stored here as hex strings.
    */
  final case class PrivateKeyEncrypted(
      envelope: String,
      kdfAlgorithm: Option[String],
      kdfIterations: Option[Long],
      kdfPrf: Option[String],
      cipher: Option[String],
      salt: Option[String] = None,
      iv: Option[String] = None,
  ) extends ClaimedContent

  // ---------- claim & parse dispatch -----------------------------------

  private val singleCertMimes: Set[String] = Set(
    "application/pkix-cert",
    "application/x-x509-ca-cert",
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

  /** Phase-3 + Phase-4 claim logic: classify each candidate artifact
    * by MIME priority, parse, and emit `ToProcess` instances for
    * those that successfully decode.
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

  /** Priority-ordered classification then parse. Order matters: PEM
    * bundle wins over single cert; keystore wins over single cert
    * (when MIMEs disagree, e.g., dual-emission case from CryptoDetector). */
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
    }
    else None
  }

  /** True if the artifact's MIME set indicates a single X.509 cert
    * (Phase-3 claim). Excludes bundle / keystore / SSH / PGP /
    * private-key / CRL forms. */
  private[strategies] def isSingleCertCandidate(mimes: Set[String]): Boolean = {
    val anySingleCert = mimes.intersect(singleCertMimes).nonEmpty
    val pemNonBundle = mimes.contains(pemFileMime) && !mimes.contains(pemBundleMime)
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
    artifact.withFile { f =>
      val bytes = java.nio.file.Files.readAllBytes(f.toPath)
      tryParsePem(bytes).orElse(tryParseDer(bytes))
    }
  }

  private val converter: JcaX509CertificateConverter =
    new JcaX509CertificateConverter().setProvider("BC")

  private def tryParsePem(bytes: Array[Byte]): Option[X509Certificate] = Try {
    Using.resource(new PEMParser(new InputStreamReader(
      new ByteArrayInputStream(bytes), "ISO-8859-1"
    ))) { parser =>
      val obj = parser.readObject()
      obj match {
        case h: X509CertificateHolder => converter.getCertificate(h)
        case _ => null
      }
    }
  }.toOption.filter(_ != null)

  private def tryParseDer(bytes: Array[Byte]): Option[X509Certificate] = Try {
    val cf = CertificateFactory.getInstance("X.509", "BC")
    cf.generateCertificate(new ByteArrayInputStream(bytes))
      .asInstanceOf[X509Certificate]
  }.toOption

  /** Parse a multi-block PEM bundle. Iterates `PEMParser.readObject`
    * to EOF, collecting every `X509CertificateHolder`. Skips other
    * block types (private keys, CSRs, etc.) — Hard rule #1: a PEM
    * bundle that mixes certs and private keys yields ONLY the certs;
    * the private-key blocks are silently ignored at parse time so
    * they cannot reach metadata. */
  private[strategies] def parseBundle(
      artifact: ArtifactWrapper
  ): Option[Bundle] = {
    val collected = artifact.withFile { f =>
      val bytes = java.nio.file.Files.readAllBytes(f.toPath)
      Try {
        Using.resource(new PEMParser(new InputStreamReader(
          new ByteArrayInputStream(bytes), "ISO-8859-1"
        ))) { parser =>
          val acc = scala.collection.mutable.ListBuffer[X509Certificate]()
          var obj = parser.readObject()
          while (obj != null) {
            obj match {
              case h: X509CertificateHolder =>
                acc += converter.getCertificate(h)
              case _ => // skip non-cert blocks (private keys, CSRs, etc.)
            }
            obj = parser.readObject()
          }
          acc.toVector
        }
      }.getOrElse(Vector.empty)
    }
    if (collected.isEmpty) None else Some(Bundle(collected))
  }

  /** Parse a keystore. Tries `null` password only — per Hard rule:
    * never guess passwords. If the null-password load fails, returns
    * a `Keystore` with `ks = None` (envelope-only path).
    *
    * Catches every failure type: `IOException` wrapping
    * `UnrecoverableKeyException`, MAC errors on PKCS#12, BadPadding
    * on JKS, etc. Any failure → encrypted-envelope path. */
  private[strategies] def parseKeystore(
      artifact: ArtifactWrapper,
      format: String,
  ): Option[Keystore] = {
    val canonicalFormat = format.toLowerCase match {
      case "jks" => "jks"
      case "jceks" => "jceks"
      case "pkcs12" => "pkcs12"
      case "bks" => "bks"
      case other => other.toLowerCase
    }
    val outcome: Try[KeyStore] = Try {
      artifact.withFile { f =>
        val bytes = java.nio.file.Files.readAllBytes(f.toPath)
        val ks = KeyStore.getInstance(format, "BC")
        ks.load(new ByteArrayInputStream(bytes), null)
        ks
      }
    }
    outcome match {
      case scala.util.Success(ks) if canonicalFormat == "bks" =>
        // BC's BKS provider accepts a null password for cert-only reads even
        // when the store was generated with a real password. We can't tell
        // from a successful null-load whether the store was actually
        // unencrypted, so BKS always takes the envelope-only path.
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
    artifact.withFile { f =>
      val bytes = java.nio.file.Files.readAllBytes(f.toPath)
      Try {
        val cf = CertificateFactory.getInstance("X.509", "BC")
        cf.generateCRL(new ByteArrayInputStream(bytes))
          .asInstanceOf[X509CRL]
      }.toOption.map(Crl.apply)
    }
  }

  // ---------- Phase-5: SSH parsing -------------------------------------

  /** OpenSSH wire algorithm name → (canonical alg, optional companion
    * (qualKey, qualValue), security-key flag). The `sk-*` variants set
    * the third element so the emitter can attach `sk=true`. */
  private[strategies] val sshAlgMap: Map[String, (String, Option[(String, String)], Boolean)] = Map(
    "ssh-rsa" -> ("rsa", None, false),
    "ssh-dss" -> ("dsa", Some(("size", "1024")), false),
    "ssh-ed25519" -> ("ed25519", None, false),
    "ssh-ed448" -> ("ed448", None, false),
    "ecdsa-sha2-nistp256" -> ("ec", Some(("curve", "p-256")), false),
    "ecdsa-sha2-nistp384" -> ("ec", Some(("curve", "p-384")), false),
    "ecdsa-sha2-nistp521" -> ("ec", Some(("curve", "p-521")), false),
    "sk-ssh-ed25519@openssh.com" -> ("ed25519", None, true),
    "sk-ecdsa-sha2-nistp256@openssh.com" -> ("ec", Some(("curve", "p-256")), true),
  )

  /** SSH cert type tokens end with `-cert-v01@openssh.com`. Strip the
    * suffix to get the underlying signed-key alg name. */
  private[strategies] def signedKeyAlgFromCertName(certName: String): Option[String] = {
    val suffix = "-cert-v01@openssh.com"
    if (certName.endsWith(suffix))
      Some(certName.substring(0, certName.length - suffix.length))
    else None
  }

  /** Parse an OpenSSH plain public-key file.
    *
    * Per Phase 5 plan: "single-line OpenSSH wire format" — a line whose
    * first whitespace-separated token is the algorithm name (e.g.,
    * `ssh-rsa`, `ssh-ed25519`, `ecdsa-sha2-nistp256`).
    *
    * **Known limitation (G7):** `authorized_keys` lines may have options
    * before the algorithm token, e.g.
    * `from="1.2.3.4",no-pty ssh-rsa AAAA... user@host`. Those lines fail
    * the algorithm-token check (the first token is the option string)
    * and silently return `None`. The CryptoDetector also won't tag them
    * with the SSH MIME, so they remain unclaimed. This matches the
    * plan's strict reading; if a future requirement is to inventory
    * keys inside option-prefixed authorized_keys files, extend
    * `SshWireReader.parseFirstKeyLine` to scan tokens left-to-right
    * for the first recognized algorithm name. */
  private[strategies] def parseSshPubkey(
      artifact: ArtifactWrapper
  ): Option[SshPubkey] = {
    artifact.withFile { f =>
      Try {
        val raw = new String(
          java.nio.file.Files.readAllBytes(f.toPath),
          java.nio.charset.StandardCharsets.UTF_8,
        )
        io.spicelabs.goatrodeo.util.SshWireReader.parseFirstKeyLine(raw).flatMap {
          case (alg, wire, comment) =>
            // Sanity: the wire blob's first string must equal the alg
            // token. If it doesn't, decline (probably an `authorized_keys`
            // options line or a corrupted file).
            val r = new io.spicelabs.goatrodeo.util.SshWireReader(wire)
            val innerAlg = r.readUtf8String()
            if (innerAlg != alg) None
            else if (!sshAlgMap.contains(alg)) None
            else {
              val rsaBits =
                if (alg == "ssh-rsa") {
                  val _e = r.readMpint()
                  val n = r.readMpint()
                  Some(io.spicelabs.goatrodeo.util.SshWireReader.mpintBitLength(n))
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
    artifact.withFile { f =>
      Try {
        val raw = new String(
          java.nio.file.Files.readAllBytes(f.toPath),
          java.nio.charset.StandardCharsets.UTF_8,
        )
        io.spicelabs.goatrodeo.util.SshWireReader.parseFirstKeyLine(raw).flatMap {
          case (certTypeName, certBytes, comment) =>
            signedKeyAlgFromCertName(certTypeName).flatMap { signedAlg =>
              if (!sshAlgMap.contains(signedAlg)) None
              else parseSshCertBlob(certTypeName, signedAlg, certBytes, comment)
            }
        }
      }.toOption.flatten
    }
  }

  /** Decode the cert wire blob. The first string is the cert-type-name
    * (matches the file's first token). What follows is `nonce` then
    * algorithm-specific public-key fields, then the metadata fields. */
  private def parseSshCertBlob(
      certTypeName: String,
      signedAlg: String,
      certBytes: Array[Byte],
      comment: Option[String],
  ): Option[SshCert] = Try {
    import io.spicelabs.goatrodeo.util.SshWireReader
    val r = new SshWireReader(certBytes)
    val innerType = r.readUtf8String()
    require(innerType == certTypeName, s"cert-type mismatch: $innerType vs $certTypeName")
    val _nonce = r.readString()

    // Read the signed key's algorithm-specific fields and reconstruct the
    // plain-pubkey wire blob: `string(signedAlg) | <fields>`.
    val keyFieldsBuf = new java.io.ByteArrayOutputStream()
    var rsaBits: Option[Int] = None

    def writeString(out: java.io.ByteArrayOutputStream, b: Array[Byte]): Unit = {
      val len = b.length
      out.write((len >>> 24) & 0xff)
      out.write((len >>> 16) & 0xff)
      out.write((len >>>  8) & 0xff)
      out.write(len & 0xff)
      out.write(b)
    }
    writeString(keyFieldsBuf, signedAlg.getBytes(java.nio.charset.StandardCharsets.UTF_8))

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
        throw new IllegalArgumentException(s"unsupported SSH cert key alg: $other")
    }

    val signedKeyWire = keyFieldsBuf.toByteArray
    val serialL = r.readUInt64()
    val serial = if (serialL >= 0) BigInt(serialL) else BigInt(java.lang.Long.toUnsignedString(serialL))
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
    Some(SshCert(
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
      comment = comment,
    ))
  }.toOption.flatten

  /** SHA-256 base64-no-padding fingerprint over an SSH wire blob (the
    * `ssh-keygen -lf` form minus the `SHA-256:` prefix). */
  private[strategies] def sshFingerprintB64(wire: Array[Byte]): String = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(wire)
    java.util.Base64.getEncoder.nn.withoutPadding.nn.encodeToString(digest).nn
  }

  /** Render an OpenSSH cert validity timestamp.
    *
    * OpenSSH uses two sentinel values for unbounded validity:
    *   - `0` (epoch start) → cert is valid "always" from the past
    *   - `0xFFFFFFFFFFFFFFFFL` ("never expires") → cert is valid "forever"
    *
    * Both come through `readUInt64()` as `Long` — the 0xFFFF…FFFFL form
    * is `-1L` after sign-extension. Naively converting via
    * `Instant.ofEpochSecond` gives `1970-01-01T00:00:00Z` and
    * `1969-12-31T23:59:59Z` respectively — semantically wrong. Detect
    * both and emit the literal `sentinelLabel` instead. */
  private[strategies] def sshCertTimeLabel(
      epochSec: Long,
      sentinelLabel: String,
  ): String = {
    val isUnsignedMax = epochSec == -1L
    val isZero = epochSec == 0L
    if (isUnsignedMax || isZero) sentinelLabel
    else isoUtc(java.util.Date.from(java.time.Instant.ofEpochSecond(epochSec)))
  }

  /** Companion-qualifier helper for SSH: `size=N` for RSA, `curve=p-256`
    * for EC, none otherwise. Optional `sk=true` from the alg map. */
  private[strategies] def sshKeyQualifiers(
      algName: String,
      rsaBits: Option[Int],
  ): Vector[String] = {
    val (canon, companion, sk) = sshAlgMap(algName)
    val parts = scala.collection.mutable.ListBuffer[String](s"alg=$canon")
    companion.foreach { case (k, v) => parts += s"$k=$v" }
    if (algName == "ssh-rsa") {
      rsaBits.foreach(b => parts += s"size=$b")
    }
    if (sk) parts += "sk=true"
    parts.toVector
  }

  // ---------- Phase-6: PGP parsing -------------------------------------

  /** PGP algorithm-id (RFC 4880 §9.1 + RFC 9580 additions) → canonical
    * alg name. Per Appendix A, `ec` is the unified EC alg name; the
    * curve disambiguates ECDH vs ECDSA. EdDSA legacy (22), Ed25519 (27),
    * and Ed448 (28) all map to canonical Ed25519/Ed448 names. */
  private[strategies] val pgpAlgIdMap: Map[Int, String] = Map(
    1 -> "rsa",       // RSA (Encrypt or Sign)
    2 -> "rsa",       // RSA (Encrypt-Only)  — deprecated
    3 -> "rsa",       // RSA (Sign-Only)     — deprecated
    16 -> "elgamal",  // ElGamal (Encrypt-Only)
    17 -> "dsa",      // DSA
    18 -> "ec",       // ECDH
    19 -> "ec",       // ECDSA
    20 -> "elgamal",  // ElGamal (Sign + Encrypt) — deprecated
    22 -> "ed25519",  // EdDSA Legacy
    25 -> "x25519",   // X25519
    26 -> "x448",     // X448
    27 -> "ed25519",  // Ed25519 (RFC 9580)
    28 -> "ed448",    // Ed448  (RFC 9580)
  )

  /** PGP curve OID → canonical curve name. Plus a friendly-name
    * fallback for keys that report a textual curve name (e.g. v6
    * Ed25519/X25519 use named curves at the algorithm-id level rather
    * than via the legacy ECDH/ECDSA OID. */
  private[strategies] val pgpCurveOidMap: Map[String, String] = Map(
    "1.2.840.10045.3.1.7" -> "p-256",
    "1.3.132.0.34" -> "p-384",
    "1.3.132.0.35" -> "p-521",
    "1.3.36.3.3.2.8.1.1.7" -> "brainpoolp256r1",
    "1.3.36.3.3.2.8.1.1.11" -> "brainpoolp384r1",
    "1.3.36.3.3.2.8.1.1.13" -> "brainpoolp512r1",
    "1.3.6.1.4.1.3029.1.5.1" -> "curve25519",
    "1.3.6.1.4.1.11591.15.1" -> "ed25519",
  )

  /** Phase-7 dispatch wrapper: try the Phase-6 public-key path first;
    * if that returns None (e.g. a `BEGIN PGP PRIVATE KEY BLOCK` file
    * which yields a `PGPSecretKeyRing` from the BC factory), fall
    * through to the Phase-7 secret-key path.
    *
    * The Phase-6 white-box contract is preserved: `parsePgpKeyRing`
    * itself still returns `None` for secret-key input. The wrapper
    * sits at dispatch level. */
  private[strategies] def parsePgpKeyOrSecretKeyRing(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] =
    parsePgpKeyRing(artifact).orElse(parsePgpSecretKeyRing(artifact))

  /** Parse a PGP key ring (armored or binary). Iterates every key in
    * every ring (primary + each subkey).
    *
    * Multi-ring handling (G8): an armored file may contain multiple
    * concatenated `BEGIN PGP PUBLIC KEY BLOCK` segments. `PGPUtil.getDecoderStream`
    * decodes only the first, so we split on the armor start marker and
    * parse each segment independently. Binary input is parsed as a
    * single byte stream (binary PGP packets concatenate naturally). */
  private[strategies] def parsePgpKeyRing(
      artifact: ArtifactWrapper
  ): Option[PgpKeyRing] = {
    artifact.withFile { f =>
      Try {
        val raw = java.nio.file.Files.readAllBytes(f.toPath)
        val rings = scala.collection.mutable.ListBuffer[
          org.bouncycastle.openpgp.PGPPublicKeyRing
        ]()
        val segments = splitArmoredBlocks(raw)
        segments.foreach { seg =>
          parsePgpStream(seg, rings)
        }
        if (rings.isEmpty) None
        else Some(buildPgpKeyRing(rings.toVector))
      }.toOption.flatten
    }
  }

  /** Split a raw byte buffer into one segment per `BEGIN PGP …` armor
    * block. If the buffer contains no armor marker, returns the whole
    * buffer as a single segment (binary input). */
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
        acc += text.substring(idx, end).getBytes(
          java.nio.charset.StandardCharsets.ISO_8859_1
        )
        idx = next
      }
      acc.toVector
    }
  }

  /** Read every `PGPPublicKeyRing` (or collection thereof) from a single
    * decoder stream, appending to `acc`. */
  private def parsePgpStream(
      bytes: Array[Byte],
      acc: scala.collection.mutable.ListBuffer[
        org.bouncycastle.openpgp.PGPPublicKeyRing
      ],
  ): Unit = {
    val decoded = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(
      new java.io.ByteArrayInputStream(bytes)
    )
    val factory = new org.bouncycastle.openpgp.PGPObjectFactory(
      decoded,
      new org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator(),
    )
    var obj = factory.nextObject()
    while (obj != null) {
      obj match {
        case kr: org.bouncycastle.openpgp.PGPPublicKeyRing =>
          acc += kr
        case krc: org.bouncycastle.openpgp.PGPPublicKeyRingCollection =>
          krc.getKeyRings.asScala.foreach(acc += _)
        case _ => ()
      }
      obj = factory.nextObject()
    }
  }

  /** Project a sequence of `PGPPublicKeyRing`s into a single `PgpKeyRing`
    * holding every primary + subkey. The first ring's primary key
    * supplies the `primaryUserId` for `MKC.NAME`. */
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
        if (it != null && it.hasNext) Some(it.next().nn.toString.nn) else None
      }
    }
    PgpKeyRing(keys, primaryUid)
  }

  /** Phase-7: parse a PGP secret-key block (armored or binary). Each
    * `PGPSecretKey` exposes its public-key portion via `getPublicKey`,
    * AND an S2K specifier (`getS2K`) describing how the private key
    * material is protected.
    *
    * If ANY secret key in the ring has an active S2K (encrypted), the
    * entire artifact takes the **encrypted** path: envelope-only
    * metadata, no pURL. Otherwise (every secret key has no S2K
    * protection — i.e., the secret material is stored in the clear),
    * the **unencrypted** path applies: derive `PGPPublicKey` from each
    * secret key and reuse Phase 6's emitters with
    * `Envelope=plaintext` + `DerivedFromPrivateKey=true`.
    *
    * Hard rule reinforcement: this method never reads or serializes
    * the private-key bytes. `getPublicKey` returns the public portion
    * which is stored separately inside the secret-key packet. The S2K
    * specifier itself is metadata (algo, salt, iteration count) — not
    * private-key material. */
  private[strategies] def parsePgpSecretKeyRing(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    artifact.withFile { f =>
      Try {
        val raw = java.nio.file.Files.readAllBytes(f.toPath)
        val rings = scala.collection.mutable.ListBuffer[
          org.bouncycastle.openpgp.PGPSecretKeyRing
        ]()
        val segments = splitArmoredBlocks(raw)
        segments.foreach { seg => parsePgpSecretStream(seg, rings) }
        if (rings.isEmpty) None
        else Some(buildPgpSecretClaim(rings.toVector))
      }.toOption.flatten
    }
  }

  /** Read every `PGPSecretKeyRing` from a single decoder stream,
    * appending to `acc`. Mirror of `parsePgpStream` for secret keys. */
  private def parsePgpSecretStream(
      bytes: Array[Byte],
      acc: scala.collection.mutable.ListBuffer[
        org.bouncycastle.openpgp.PGPSecretKeyRing
      ],
  ): Unit = {
    val decoded = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(
      new java.io.ByteArrayInputStream(bytes)
    )
    val factory = new org.bouncycastle.openpgp.PGPObjectFactory(
      decoded,
      new org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator(),
    )
    var obj = factory.nextObject()
    while (obj != null) {
      obj match {
        case sr: org.bouncycastle.openpgp.PGPSecretKeyRing =>
          acc += sr
        case src: org.bouncycastle.openpgp.PGPSecretKeyRingCollection =>
          src.getKeyRings.asScala.foreach(acc += _)
        case _ => ()
      }
      obj = factory.nextObject()
    }
  }

  /** Build a Phase-7 ClaimedContent from PGPSecretKeyRing(s). */
  private def buildPgpSecretClaim(
      rings: Vector[org.bouncycastle.openpgp.PGPSecretKeyRing]
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

  /** True if the secret key has an active S2K specifier (private-key
    * material is encrypted). BC's `getS2K` returns null for unencrypted
    * keys; `getEncAlgorithm == 0` is the SymmetricKeyAlgorithmTags
    * value for "no encryption". */
  private def isSecretKeyEncrypted(
      sk: org.bouncycastle.openpgp.PGPSecretKey
  ): Boolean = {
    val s2k = Try(sk.getS2K).toOption.flatMap(Option.apply)
    val encAlg = Try(sk.getKeyEncryptionAlgorithm).toOption.getOrElse(0)
    s2k.isDefined || encAlg != 0
  }

  /** Build the encrypted-PGP-secret claim from a representative
    * encrypted `PGPSecretKey`. We extract:
    *   - cipher = `getKeyEncryptionAlgorithm` mapped to a canonical
    *     name via `pgpSymmetricCipherNameMap`
    *   - kdfAlgorithm = `"s2k-iterated"` if the S2K type is iterated-
    *     and-salted (BC returns S2K with `getType == 3`), else simple
    *     `"s2k"`
    *   - kdfIterations = S2K iteration count (BC `getIterationCount`
    *     returns the encoded count; we expose the raw int)
    *   - kdfPrf = canonical hash name from S2K's `getHashAlgorithm`
    *
    * No private-key bytes are ever read. */
  private def pgpSecretEncryptedClaim(
      sk: org.bouncycastle.openpgp.PGPSecretKey
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
        if (saltBytes != null && saltBytes.length > 0) bytesToHex(saltBytes)
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
      iv = None,
    )
  }

  /** PGP SymmetricKeyAlgorithmTag → canonical cipher name. Per
    * RFC 4880 §9.2 / RFC 9580 §9.3. */
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
    13 -> "camellia-256",
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
    14 -> "sha3-512",
  )

  /** Project a single `PGPPublicKey` into our `PgpKey` value.
    *
    * v5 policy (claim #20 in `phase-6-claims.md`, also gap N6): we
    * read whatever `pk.getVersion` returns and store it. There is no
    * v5 fixture in the corpus and no contributor request for one. If
    * BC parses a v5 key successfully, the strategy emits `version=5`
    * and a 64-hex SHA-256 fingerprint (same length as v6). If BC
    * raises on a v5 packet, the surrounding `Try` in `parsePgpKeyRing`
    * swallows it and the artifact is unclaimed. Both behaviors are
    * acceptable; revisit if a v5 fixture is added or BC's v5 support
    * exhibits issues. */
  private def pgpKeyOf(
      pk: org.bouncycastle.openpgp.PGPPublicKey
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
            .asInstanceOf[org.bouncycastle.bcpg.ECPublicBCPGKey]
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
            .asInstanceOf[org.bouncycastle.bcpg.EdDSAPublicBCPGKey]
          val oid = key.getCurveOID.getId
          pgpCurveOidMap.getOrElse(oid, oid)
        }.toOption
      case _ => None
    }

    val userIds: Vector[String] = {
      val it = pk.getUserIDs
      if (it == null) Vector.empty
      else it.asScala.toVector.map(_.toString.nn)
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
      userIds = userIds,
    )
  }

  /** Build the `pkg:pgp/fingerprint@{hex}?...` pURL for a single PGP key. */
  private[strategies] def purlForPgpKey(
      key: PgpKey
  ): PackageURL = {
    val parts = scala.collection.mutable.ListBuffer[String](
      s"alg=${key.canonicalAlg}",
      s"version=${key.version}",
    )
    key.keySize.foreach(s => parts += s"size=$s")
    key.curve.foreach(c => parts += s"curve=$c")
    val qual = parts.sorted.mkString("&")
    new PackageURL(s"pkg:pgp/fingerprint@${key.fingerprintHex}?$qual")
  }

  /** First 8 hex chars of the key fingerprint — matches PGP short-ID
    * convention used as the metadata namespace token. */
  private[strategies] def pgpFp8(key: PgpKey): String =
    key.fingerprintHex.take(8)

  // ---------- canonical mappings (Appendix A) ---------------------------

  /** Public-key OIDs → (canonical alg, optional params token). */
  // OID maps `pubkeyOidMap`, `sigAlgOidMap`, `ecCurveMap`, `ekuOidMap`
  // moved to `CertificatesOidMaps.scala` (Phase-7 second-pass refactor
  // to comply with inv #9 token limit; pure relocation).

  // ---------- per-cert derivation helpers ------------------------------

  /** SHA-256, lowercase hex. */
  private[strategies] def sha256Hex(bytes: Array[Byte]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map(b => f"${b & 0xff}%02x").mkString
  }

  /** (alg, qualifier-map) for a public key. */
  private[strategies] def keyAlgAndQualifier(
      pub: java.security.PublicKey | Null,
      cert: X509Certificate,
  ): (String, Map[String, String]) = {
    if (pub == null) {
      // JCA returned no PublicKey — composite or other unsupported algorithm.
      // Resolve via raw SPKI OID.
      spkiAlgFromCert(cert).getOrElse(("unknown", Map.empty))
    } else pub match {
      case rsa: RSAPublicKey =>
        ("rsa", Map("size" -> rsa.getModulus.bitLength.toString))
      case dsa: DSAPublicKey =>
        ("dsa", Map("size" -> dsa.getY.bitLength.toString))
      case _: ECPublicKey =>
        val curveName = bcCurveNameFromCert(cert).getOrElse {
          val raw = pub.getAlgorithm.toLowerCase
          ecCurveMap.getOrElse(raw, raw)
        }
        val canonical = ecCurveMap.getOrElse(curveName.toLowerCase, curveName.toLowerCase)
        ("ec", Map("curve" -> canonical))
      case _ =>
        pub.getAlgorithm.toLowerCase match {
          case "ed25519" | "1.3.101.112" => ("ed25519", Map.empty)
          case "ed448" | "1.3.101.113"   => ("ed448", Map.empty)
          case "x25519"                   => ("x25519", Map.empty)
          case "x448"                     => ("x448", Map.empty)
          case _ => spkiAlgFromCert(cert).getOrElse(("unknown", Map.empty))
        }
    }
  }

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

  /** Extract SPKI DER bytes from a certificate without going through
    * `cert.getPublicKey()` — JCA returns null for OIDs the JVM doesn't
    * have a `KeyFactory` for (e.g., composite hybrid keys). BC's
    * raw cert ASN.1 always has the bytes. */
  private[strategies] def spkiBytesFromCert(cert: X509Certificate): Array[Byte] = {
    val bcCert = org.bouncycastle.asn1.x509.Certificate.getInstance(cert.getEncoded)
    bcCert.getSubjectPublicKeyInfo.getEncoded
  }

  /** Extract the SPKI's algorithm OID from a certificate without going
    * through `cert.getPublicKey()`. */
  private[strategies] def spkiAlgOidFromCert(cert: X509Certificate): String = {
    val bcCert = org.bouncycastle.asn1.x509.Certificate.getInstance(cert.getEncoded)
    bcCert.getSubjectPublicKeyInfo.getAlgorithm.getAlgorithm.getId
  }

  private def spkiAlgFromCert(
      cert: X509Certificate
  ): Option[(String, Map[String, String])] = Try {
    val oid = spkiAlgOidFromCert(cert)
    pubkeyOidMap.get(oid).map { case (alg, params) =>
      val quals = params.map(p => "params" -> p).toMap
      (alg, quals)
    }
  }.toOption.flatten

  private[strategies] def canonicalSigAlg(cert: X509Certificate): String = {
    sigAlgOidMap.getOrElse(
      cert.getSigAlgOID,
      s"<unknown-sig-oid-${cert.getSigAlgOID}>"
    )
  }

  private[strategies] def canonicalSigAlgCrl(crl: X509CRL): String = {
    sigAlgOidMap.getOrElse(
      crl.getSigAlgOID,
      s"<unknown-sig-oid-${crl.getSigAlgOID}>"
    )
  }

  private[strategies] def isSelfSigned(cert: X509Certificate): Boolean = {
    if (cert.getSubjectX500Principal != cert.getIssuerX500Principal) false
    else {
      val pub = cert.getPublicKey
      if (pub == null) {
        // Composite / unsupported alg — JCA can't verify. Accept the
        // subject==issuer signal alone (consistent with how Phase 0b's
        // `pqc_x509_sidecar` handles the same case in Python).
        true
      } else Try {
        cert.verify(pub, "BC")
        true
      }.getOrElse(false)
    }
  }

  private[strategies] def isoUtc(d: java.util.Date): String = {
    val instant = d.toInstant
    DateTimeFormatter.ISO_INSTANT
      .withZone(ZoneOffset.UTC)
      .format(instant)
      .replaceAll("\\.\\d+Z$", "Z")
  }

  private[strategies] def keyUsageNames(cert: X509Certificate): Option[String] = {
    val ku = cert.getKeyUsage
    if (ku == null) None
    else {
      val labels = Seq(
        "digital-signature", "non-repudiation", "key-encipherment",
        "data-encipherment", "key-agreement", "key-cert-sign",
        "crl-sign", "encipher-only", "decipher-only",
      )
      val present = labels.zipWithIndex.collect {
        case (label, idx) if idx < ku.length && ku(idx) => label
      }
      if (present.isEmpty) None else Some(present.mkString(","))
    }
  }

  private[strategies] def ekuNames(cert: X509Certificate): Option[String] = Try {
    val eku = cert.getExtendedKeyUsage
    if (eku == null) None
    else {
      val ids = eku.asScala.toSeq
      val mapped = ids.map(o => ekuOidMap.getOrElse(o, o))
      if (mapped.isEmpty) None else Some(mapped.mkString(","))
    }
  }.toOption.flatten

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
            case 1 => Some(s"email:$value")
            case 2 => Some(s"DNS:$value")
            case 6 => Some(s"URI:$value")
            case 7 => Some(s"IP:$value")
            case 0 => Some("OTHER:OtherName")
            case 3 => Some("OTHER:X400Address")
            case 4 => Some("OTHER:DirectoryName")
            case 5 => Some("OTHER:EDIPartyName")
            case 8 => Some("OTHER:RegisteredID")
            case other => Some(s"OTHER-$other")
          }
        }
      }
      if (entries.isEmpty) None else Some(entries.mkString(","))
    }
  }.toOption.flatten

  /** RFC2253-style DN rendering that decodes JDK's `#hex` fallback for OID
    * values the JDK doesn't recognize back to their text form. We keep
    * JDK's output (most-specific-first ordering, escape rules) and only
    * substitute the `#XXYY…` runs that decode to a text ASN.1 string type
    * (PrintableString 0x13, UTF8String 0x0c, IA5String 0x16, TeletexString
    * 0x14, BMPString 0x1e). Other types stay as `#hex`. */
  private[strategies] def dnString(name: javax.security.auth.x500.X500Principal): String = {
    val rfc2253 = name.getName(javax.security.auth.x500.X500Principal.RFC2253)
    val hexRun = "#([0-9a-fA-F]{4,})".r
    hexRun.replaceAllIn(rfc2253, m => {
      val hex: String = m.group(1).nn
      decodeAsn1HexString(hex) match {
        case Some(decoded) => java.util.regex.Matcher.quoteReplacement(decoded)
        case None => java.util.regex.Matcher.quoteReplacement(s"#$hex")
      }
    })
  }

  private def decodeAsn1HexString(hex: String): Option[String] = Try {
    val bytes = hex.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    if (bytes.length < 2) None
    else {
      val tag = bytes(0) & 0xFF
      val lenByte = bytes(1) & 0xFF
      val (len, dataOff) =
        if ((lenByte & 0x80) == 0) (lenByte, 2)
        else {
          val numLen = lenByte & 0x7F
          if (numLen == 0 || numLen > 4 || bytes.length < 2 + numLen) (-1, -1)
          else {
            var v = 0
            (0 until numLen).foreach(i => v = (v << 8) | (bytes(2 + i) & 0xFF))
            (v, 2 + numLen)
          }
        }
      if (len < 0 || dataOff < 0 || bytes.length != dataOff + len) None
      else {
        val payload = bytes.slice(dataOff, dataOff + len)
        tag match {
          case 0x13 | 0x16 | 0x14 =>
            Some(new String(payload, java.nio.charset.StandardCharsets.US_ASCII))
          case 0x0C =>
            Some(new String(payload, java.nio.charset.StandardCharsets.UTF_8))
          case 0x1E =>
            Some(new String(payload, java.nio.charset.StandardCharsets.UTF_16BE))
          case _ => None
        }
      }
    }
  }.toOption.flatten

  private[strategies] def cnOrDn(name: javax.security.auth.x500.X500Principal): String = {
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
    "openssh-key-v1",
  ).map(p => Pattern.compile(p))

  /** Metadata keys that MAY legitimately carry long lowercase-hex
    * values (fingerprints, SHA-256 digests, X.509 serial numbers,
    * comma-separated revoked-serial lists). Per Appendix C, every
    * other metadata key with a 32+ char hex run is suspect (could
    * be a serialized private scalar).
    *
    * The allowlist patterns use regex so that the namespaced forms
    * emitted by Phases 4-6 (bundles use `:Cert:N:`, keystores use
    * `:Entry:alias:`, PGP rings use `:Key:fp8:`) are accepted. */
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
    // Phase-7 envelope-only metadata: salt + IV are PUBLIC components
    // of the encryption envelope, not private-key material. Plan §
    // "Encrypted path" enumerates them as KDF parameters to extract.
    "^Certificates:KdfSalt$",
    "^Certificates:Iv$",
    // Phase-4 bundle namespacing: Certificates:Cert:0:Field, Cert:1:Field, …
    "^Certificates:Cert:[0-9]+:(SpkiSha256|CertSha256|Serial|Fingerprint)$",
    // Phase-4 keystore namespacing: Certificates:Entry:alias:Field
    "^Certificates:Entry:[^:]+:(SpkiSha256|CertSha256|Serial|Fingerprint)$",
    // Phase-6 PGP per-key namespacing: Certificates:Key:fp8:Fingerprint
    "^Certificates:Key:[0-9a-f]+:Fingerprint$",
  ).map(p => Pattern.compile(p))

  /** Long-hex run pattern from Appendix C: 32+ consecutive lowercase
    * hex characters. The leak sweep rejects any value matching this
    * pattern UNLESS the metadata key is on the allowlist. */
  private[strategies] val longHexPattern: Pattern =
    Pattern.compile("[0-9a-f]{32,}")

  private[strategies] def assertNoLeak(
      metadata: TreeMap[String, TreeSet[StringOrPair]]
  ): Unit = {
    metadata.foreach { case (key, values) =>
      val keyAllowsLongHex = longHexAllowedKeys.exists(_.matcher(key).matches())
      values.foreach { v =>
        val text = v match {
          case io.spicelabs.goatrodeo.omnibor.StringOf(s)        => s
          case io.spicelabs.goatrodeo.omnibor.PairOf(_, s2)      => s2
        }
        forbiddenPatterns.foreach { pat =>
          if (pat.matcher(text).find()) {
            throw new RuntimeException(
              s"Certificates leak guard: metadata key '$key' value matched " +
                s"forbidden pattern /${pat.pattern}/ — refusing to emit"
            )
          }
        }
        // Appendix C long-hex check: serialized private scalars
        // (Ed25519 seeds, ECDSA P-256 scalars, etc.) appear as long
        // hex runs. Only fingerprint-like keys may carry them.
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

  // ---------- Phase-7: private-key parsing -----------------------------
  //
  // Two paths per Phase 7 plan:
  //   - Unencrypted: derive public key, compute SPKI / SSH wire blob,
  //     emit pURL identical in shape to the public-key counterpart.
  //   - Encrypted:   parse the envelope only — KDF, cipher, iterations.
  //                  No decryption, no password guessing, no pURL.
  //
  // Routing detail: the PEM detector emits `application/x-pem-private-key`
  // for both legacy-unencrypted and legacy-encrypted (Proc-Type:4)
  // bodies, because the BEGIN-banner alone doesn't disambiguate. The
  // discrimination happens at parse time in `parsePemPrivateKey`.

  /** Parse a PEM-private-key MIME (`application/x-pem-private-key`).
    * Disambiguates legacy-unencrypted vs legacy-encrypted by checking
    * for `Proc-Type: 4,ENCRYPTED` (and the companion `DEK-Info:` line)
    * BEFORE invoking BC's PEMParser. PKCS#8 unencrypted bodies
    * (`-----BEGIN PRIVATE KEY-----`) take the unencrypted path. */
  private[strategies] def parsePemPrivateKey(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    artifact.withFile { f =>
      val raw = java.nio.file.Files.readAllBytes(f.toPath)
      val text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1)
      if (procTypeEncryptedHeaderPresent(text)) {
        Some(legacyEncryptedPemClaim(text))
      } else {
        parseUnencryptedPemBytes(raw)
      }
    }
  }

  /** Parse a PEM-encrypted-private-key MIME (always encrypted PKCS#8 —
    * `-----BEGIN ENCRYPTED PRIVATE KEY-----`). Extracts envelope-only
    * metadata via the outer ASN.1 `EncryptedPrivateKeyInfo`; never
    * decrypts, never guesses passwords. */
  private[strategies] def parsePemEncryptedPrivateKey(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    artifact.withFile { f =>
      val raw = java.nio.file.Files.readAllBytes(f.toPath)
      Try {
        Using.resource(new PEMParser(new InputStreamReader(
          new ByteArrayInputStream(raw),
          java.nio.charset.StandardCharsets.ISO_8859_1,
        ))) { parser =>
          parser.readObject() match {
            case epki: org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo =>
              Some(pkcs8EncryptedClaimFrom(epki))
            case _ => None
          }
        }
      }.toOption.flatten
    }
  }

  /** True if the PEM text contains a legacy `Proc-Type: 4,ENCRYPTED`
    * header (RFC 1421 / OpenSSL legacy private-key format). The
    * detector itself can't tell from the BEGIN line alone — the
    * disambiguation header lives BELOW the BEGIN banner. */
  private def procTypeEncryptedHeaderPresent(text: String): Boolean =
    text.contains("Proc-Type: 4,ENCRYPTED") ||
      text.contains("Proc-Type:4,ENCRYPTED")

  /** Build the legacy-PEM-encrypted claim. Parses the `DEK-Info:` line
    * (if present) for the cipher name. Legacy PEM has no KDF
    * descriptor: the password→key derivation is OpenSSL's MD5-based
    * EVP_BytesToKey, hardcoded by convention. We do NOT name a KDF
    * here; the `Certificates:KdfAlgorithm` field is omitted. */
  private def legacyEncryptedPemClaim(text: String): PrivateKeyEncrypted = {
    val (cipher, iv) = legacyDekCipherAndIv(text)
    PrivateKeyEncrypted(
      envelope = "pem-legacy-encrypted",
      kdfAlgorithm = None,
      kdfIterations = None,
      kdfPrf = None,
      cipher = cipher,
      salt = None,
      iv = iv,
    )
  }

  /** Extract canonical cipher name AND IV (lowercase hex) from the
    * `DEK-Info:` line. Format per RFC 1421:
    *   `DEK-Info: AES-256-CBC,1A2B3C…` → cipher = `aes-256-cbc`, IV = `1a2b3c…`
    */
  private def legacyDekCipherAndIv(text: String): (Option[String], Option[String]) = {
    val rx = "(?m)^DEK-Info:\\s*([A-Z0-9-]+),([0-9A-Fa-f]+)".r
    rx.findFirstMatchIn(text) match {
      case Some(m) =>
        val cipher = Option(m.group(1)).map(_.nn.toLowerCase)
        val iv = Option(m.group(2)).map(_.nn.toLowerCase)
        (cipher, iv)
      case None =>
        // Fall back to cipher-only match (legacy formats may omit IV).
        val rxCipher = "(?m)^DEK-Info:\\s*([A-Z0-9-]+)".r
        val cipher = rxCipher.findFirstMatchIn(text)
          .flatMap(m => Option(m.group(1)).map(_.nn.toLowerCase))
        (cipher, None)
    }
  }

  /** Parse PEM-bytes as either:
    *   - `PEMKeyPair` (legacy unencrypted: `BEGIN RSA/EC/DSA PRIVATE KEY`)
    *   - `PrivateKeyInfo` (PKCS#8 unencrypted: `BEGIN PRIVATE KEY`)
    *
    * Either way, project to a `PrivateKeyPlaintextPem` carrying the
    * derived public-key SPKI bytes + algorithm + qualifiers.
    */
  private def parseUnencryptedPemBytes(
      raw: Array[Byte]
  ): Option[PrivateKeyPlaintextPem] = Try {
    Using.resource(new PEMParser(new InputStreamReader(
      new ByteArrayInputStream(raw),
      java.nio.charset.StandardCharsets.ISO_8859_1,
    ))) { parser =>
      val obj = parser.readObject()
      val spki: org.bouncycastle.asn1.x509.SubjectPublicKeyInfo | Null =
        obj match {
          case kp: org.bouncycastle.openssl.PEMKeyPair =>
            kp.getPublicKeyInfo
          case pki: org.bouncycastle.asn1.pkcs.PrivateKeyInfo =>
            spkiFromPrivateKeyInfo(pki)
          case _ => null
        }
      Option(spki).map { s =>
        val spkiBytes = s.getEncoded
        val (alg, sz, curve, params) = algAndQualifierFromSpki(s)
        PrivateKeyPlaintextPem(
          spkiBytes = spkiBytes,
          canonicalAlg = alg,
          keySize = sz,
          curve = curve,
          params = params,
        )
      }
    }
  }.toOption.flatten

  /** Derive SPKI from PKCS#8 `PrivateKeyInfo` without exposing private
    * material. RSA: take public (modulus, e). EC: Q = d·G via
    * `ECPoint.multiply` (private scalar consumed inside). EdDSA / X*:
    * BC's `*PrivateKeyParameters.generatePublicKey()`. DSA: y = g^x
    * mod p. See `phase-7-claims.md` claim #8 for the audit. */
  private def spkiFromPrivateKeyInfo(
      pki: org.bouncycastle.asn1.pkcs.PrivateKeyInfo
  ): org.bouncycastle.asn1.x509.SubjectPublicKeyInfo | Null = {
    import org.bouncycastle.crypto.params.*
    Try {
      val priv = org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(pki)
      val pub: AsymmetricKeyParameter | Null = priv match {
        case r: RSAPrivateCrtKeyParameters =>
          new RSAKeyParameters(false, r.getModulus, r.getPublicExponent)
        case ec: ECPrivateKeyParameters =>
          val q = ec.getParameters.getG.multiply(ec.getD).normalize
          new ECPublicKeyParameters(q, ec.getParameters)
        case ed: Ed25519PrivateKeyParameters =>
          ed.generatePublicKey
        case ed: Ed448PrivateKeyParameters =>
          ed.generatePublicKey
        case x: X25519PrivateKeyParameters =>
          x.generatePublicKey
        case x: X448PrivateKeyParameters =>
          x.generatePublicKey
        case dsa: DSAPrivateKeyParameters =>
          // y = g^x mod p
          val params = dsa.getParameters
          val y = params.getG.modPow(dsa.getX, params.getP)
          new DSAPublicKeyParameters(y, params)
        case _ => null
      }
      if (pub == null) null
      else
        org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
          .createSubjectPublicKeyInfo(pub)
          .asInstanceOf[org.bouncycastle.asn1.x509.SubjectPublicKeyInfo | Null]
    }.toOption.orNull
  }

  /** Project SPKI ASN.1 → (canonical alg, size?, curve?, params?).
    * Mirrors `keyAlgAndQualifier` but works without an X.509
    * certificate context (private-key derivation has no cert). */
  private[strategies] def algAndQualifierFromSpki(
      spki: org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
  ): (String, Option[Int], Option[String], Option[String]) = {
    val algOid = spki.getAlgorithm.getAlgorithm.getId
    pubkeyOidMap.get(algOid) match {
      case Some((alg, paramsTok)) => (alg, None, None, paramsTok)
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
            ("rsa", sz, None, None)
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
            ("ec", None, curve, None)
          case "1.2.840.10040.4.1" =>
            // DSA — size from parameter P
            val sz = Try {
              val params = spki.getAlgorithm.getParameters
              val dsaParams =
                org.bouncycastle.asn1.x509.DSAParameter.getInstance(params)
              dsaParams.getP.bitLength
            }.toOption
            ("dsa", sz, None, None)
          case "1.3.101.112" => ("ed25519", None, None, None)
          case "1.3.101.113" => ("ed448", None, None, None)
          case "1.3.101.110" => ("x25519", None, None, None)
          case "1.3.101.111" => ("x448", None, None, None)
          case _ => ("unknown", None, None, None)
        }
    }
  }

  /** Build the encrypted-PKCS#8 claim from a parsed
    * `PKCS8EncryptedPrivateKeyInfo`. Inspects the algorithm OID and
    * the KDF/cipher inside `PBES2-params` (RFC 8018) when present. */
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
                Try(p.getIterationCount.intValue.toLong).toOption)
              val prf = pbkdf2.flatMap { p =>
                val prfOid = Try(p.getPrf.getAlgorithm.getId).toOption
                prfOid.flatMap(prfOidToCanonicalHash.get)
              }
              val salt = pbkdf2.flatMap(p =>
                Try(bytesToHex(p.getSalt)).toOption.filter(_.nonEmpty))
              (Some("pbkdf2"), iters, prf, salt)
            case "1.3.6.1.4.1.11591.4.11" =>
              // scrypt — salt is in ScryptParams
              val scrypt = Try {
                org.bouncycastle.asn1.misc.ScryptParams.getInstance(
                  kdf.getParameters
                )
              }.toOption
              val salt = scrypt.flatMap(p =>
                Try(bytesToHex(p.getSalt)).toOption.filter(_.nonEmpty))
              val n = scrypt.flatMap(p =>
                Try(p.getCostParameter.longValueExact).toOption)
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
            val asOctet = org.bouncycastle.asn1.ASN1OctetString.getInstance(rawParams)
            bytesToHex(asOctet.getOctets)
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
        iv = cipherIv,
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
        Try(bytesToHex(p.getSalt)).toOption.filter(_.nonEmpty))
      val iters = pbes1.flatMap(p =>
        Try(p.getIterationCount.longValueExact).toOption)
      val cipher = pbes1OidToCanonicalCipher.get(algOid)
      PrivateKeyEncrypted(
        envelope = "pkcs8-encrypted",
        kdfAlgorithm = if (pbes1.isDefined) Some("pbes1") else None,
        kdfIterations = iters,
        kdfPrf = pbes1OidToPrf.get(algOid),
        cipher = cipher,
        salt = salt,
        iv = None,
      )
    }
  }

  /** Lowercase-hex encoder. Salt and IV are emitted in hex per the
    * canonical convention (Phase 3-6 fingerprints all use lowercase
    * hex). */
  private def bytesToHex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  // PKCS#5/PKCS#12 PBES1/PBES2 OID maps (`pbes1OidToCanonicalCipher`,
  // `pbes1OidToPrf`, `prfOidToCanonicalHash`, `cipherOidToName`) moved
  // to `CertificatesOidMaps.scala` (Phase-7 second-pass refactor to
  // comply with inv #9; pure relocation).

  /** Parse an OpenSSH-private-key MIME (`openssh-key-v1` envelope; see
    * OpenSSH PROTOCOL.key for wire format). `kdfname == "none"` →
    * unencrypted (publickey blob is in the clear); else encrypted
    * (envelope-only emission). */
  private[strategies] def parseOpenSshPrivateKey(
      artifact: ArtifactWrapper
  ): Option[ClaimedContent] = {
    artifact.withFile { f =>
      val raw = java.nio.file.Files.readAllBytes(f.toPath)
      Try {
        val envelope = decodeOpenSshArmor(raw)
        envelope.flatMap(parseOpenSshV1Envelope)
      }.toOption.flatten
    }
  }

  /** Strip the `-----BEGIN OPENSSH PRIVATE KEY-----` / `-----END...-----`
    * armor and base64-decode the inner body. Returns the binary
    * `openssh-key-v1\0…` envelope bytes. */
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

  /** Parse an `openssh-key-v1` envelope. */
  private def parseOpenSshV1Envelope(
      env: Array[Byte]
  ): Option[ClaimedContent] = {
    val magic = "openssh-key-v1\u0000".getBytes(
      java.nio.charset.StandardCharsets.US_ASCII
    )
    if (env.length < magic.length) None
    else if (!java.util.Arrays.equals(
              java.util.Arrays.copyOfRange(env, 0, magic.length),
              magic)) None
    else {
      val body = java.util.Arrays.copyOfRange(env, magic.length, env.length)
      val r = new io.spicelabs.goatrodeo.util.SshWireReader(body)
      val cipherName = Try(r.readUtf8String()).toOption.getOrElse("")
      val kdfName = Try(r.readUtf8String()).toOption.getOrElse("")
      val kdfOptions = Try(r.readString()).toOption.getOrElse(Array.empty[Byte])
      val numKeys = Try(r.readUInt32()).toOption.getOrElse(0L)
      if (kdfName == "none" && cipherName == "none") {
        // Unencrypted: read first public-key blob in the clear.
        if (numKeys < 1) None
        else {
          Try(r.readString()).toOption.flatMap { pubWire =>
            val pr = new io.spicelabs.goatrodeo.util.SshWireReader(pubWire)
            Try(pr.readUtf8String()).toOption.flatMap { algName =>
              if (!sshAlgMap.contains(algName)) None
              else {
                val rsaBits = if (algName == "ssh-rsa") {
                  Try {
                    val _e = pr.readMpint()
                    val n = pr.readMpint()
                    io.spicelabs.goatrodeo.util.SshWireReader.mpintBitLength(n)
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
              val k = new io.spicelabs.goatrodeo.util.SshWireReader(kdfOptions)
              val saltBytes = k.readString()
              val rounds = k.readUInt32()
              (Some(rounds), Some(bytesToHex(saltBytes)))
            }.toOption.getOrElse((None, None))
          } else (None, None)
        val canonicalCipher: Option[String] =
          if (cipherName.nonEmpty && cipherName != "none") Some(cipherName)
          else None
        Some(PrivateKeyEncrypted(
          envelope = "openssh-encrypted",
          kdfAlgorithm = canonicalKdf,
          kdfIterations = kdfIters,
          kdfPrf = None,
          cipher = canonicalCipher,
          salt = kdfSalt,
          iv = None,
        ))
      }
    }
  }

  // ---------- emission helpers per claim variant -----------------------

  /** Emit the (spki, cert) pURL pair for a single X.509 cert. */
  private[strategies] def purlsForCert(c: X509Certificate): Vector[PackageURL] = {
    val derBytes = c.getEncoded
    val spkiBytes = spkiBytesFromCert(c)
    val certSha = sha256Hex(derBytes)
    val spkiSha = sha256Hex(spkiBytes)
    val (alg, qualMap) = keyAlgAndQualifier(c.getPublicKey, c)
    val sigAlg = canonicalSigAlg(c)
    val selfSigned = isSelfSigned(c)
    val version = c.getVersion

    val companion: String =
      (qualMap.get("size").map("size=" + _) ++
       qualMap.get("curve").map("curve=" + _) ++
       qualMap.get("params").map("params=" + _)).mkString("&")

    val spkiQuals: Seq[String] = Seq(
      Some(s"alg=$alg"),
      if (companion.nonEmpty) Some(companion) else None,
      Some(s"version=$version"),
    ).flatten
    val certQuals: Seq[String] = Seq(
      Some(s"alg=$alg"),
      if (companion.nonEmpty) Some(companion) else None,
      Some(s"sig-alg=$sigAlg"),
      Some(s"self-signed=$selfSigned"),
      Some(s"version=$version"),
    ).flatten

    Vector(
      new PackageURL(s"pkg:x509/spki-sha256@$spkiSha?${spkiQuals.mkString("&")}"),
      new PackageURL(s"pkg:x509/cert-sha256@$certSha?${certQuals.mkString("&")}"),
    )
  }

  /** Per-cert metadata block — Phase 3's inner table. Used by Phase 3
    * single-cert AND Phase 4 keystore/bundle namespaced entries. */
  private[strategies] def perCertMetadata(
      adHoc: String => String,
      c: X509Certificate,
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val derBytes = c.getEncoded
    val spkiBytes = spkiBytesFromCert(c)
    val certSha = sha256Hex(derBytes)
    val spkiSha = sha256Hex(spkiBytes)
    val (alg, qualMap) = keyAlgAndQualifier(c.getPublicKey, c)
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
      Some(adHoc("Serial") -> TreeSet(StringOrPair(c.getSerialNumber.toString(16)))) +?
      Some(adHoc("NotBefore") -> TreeSet(StringOrPair(isoUtc(c.getNotBefore)))) +?
      Some(adHoc("NotAfter") -> TreeSet(StringOrPair(isoUtc(c.getNotAfter)))) +?
      Some(adHoc("KeyAlgorithm") -> TreeSet(StringOrPair(alg))) +?
      Some(adHoc("SigAlgorithm") -> TreeSet(StringOrPair(sigAlg))) +?
      Some(adHoc("SpkiSha256") -> TreeSet(StringOrPair(spkiSha))) +?
      Some(adHoc("CertSha256") -> TreeSet(StringOrPair(certSha))) +?
      Some(adHoc("IsCA") -> TreeSet(StringOrPair(isCa.toString))) +?
      Some(adHoc("SelfSigned") -> TreeSet(StringOrPair(selfSigned.toString))) +?
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

  private[strategies] def urlEncodeAlias(alias: String): String = {
    java.net.URLEncoder.encode(alias, "UTF-8")
  }
}

/** A single artifact claimed by the Certificates strategy.
  *
  * @param artifact the file the strategy claimed
  * @param claim    the parsed cryptographic content (Phase 3 single
  *                 cert; Phase 4 keystore / bundle / CRL)
  */
class Certificates(
    artifact: ArtifactWrapper,
    claim: Certificates.ClaimedContent,
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

