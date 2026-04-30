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
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?
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

  /** Parse an OpenSSH plain public-key file. */
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

  // ---------- canonical mappings (Appendix A) ---------------------------

  /** Public-key OIDs → (canonical alg, optional params token). */
  private val pubkeyOidMap: Map[String, (String, Option[String])] = Map(
    "2.16.840.1.101.3.4.3.17" -> ("ml-dsa", Some("44")),
    "2.16.840.1.101.3.4.3.18" -> ("ml-dsa", Some("65")),
    "2.16.840.1.101.3.4.3.19" -> ("ml-dsa", Some("87")),
    "2.16.840.1.101.3.4.3.32" -> ("ml-dsa", Some("44")),
    "2.16.840.1.101.3.4.3.33" -> ("ml-dsa", Some("65")),
    "2.16.840.1.101.3.4.3.34" -> ("ml-dsa", Some("87")),
    // composite signatures (IETF draft-ounsworth-pq-composite-sigs):
    // SPKI uses the same composite OID as the signature; alg=composite,
    // no params (sig-alg distinguishes the specific hybrid).
    "1.3.6.1.5.5.7.6.37" -> ("composite", None),
    "1.3.6.1.5.5.7.6.39" -> ("composite", None),
    "1.3.6.1.5.5.7.6.40" -> ("composite", None),
    "1.3.6.1.5.5.7.6.41" -> ("composite", None),
    "1.3.6.1.5.5.7.6.49" -> ("composite", None),
    "2.16.840.1.101.3.4.3.20" -> ("slh-dsa", Some("128s")),
    "2.16.840.1.101.3.4.3.21" -> ("slh-dsa", Some("128f")),
    "2.16.840.1.101.3.4.3.22" -> ("slh-dsa", Some("192s")),
    "2.16.840.1.101.3.4.3.23" -> ("slh-dsa", Some("192f")),
    "2.16.840.1.101.3.4.3.24" -> ("slh-dsa", Some("256s")),
    "2.16.840.1.101.3.4.3.25" -> ("slh-dsa", Some("256f")),
    "2.16.840.1.101.3.4.3.26" -> ("slh-dsa", Some("shake-128s")),
    "2.16.840.1.101.3.4.3.27" -> ("slh-dsa", Some("shake-128f")),
    "2.16.840.1.101.3.4.3.28" -> ("slh-dsa", Some("shake-192s")),
    "2.16.840.1.101.3.4.3.29" -> ("slh-dsa", Some("shake-192f")),
    "2.16.840.1.101.3.4.3.30" -> ("slh-dsa", Some("shake-256s")),
    "2.16.840.1.101.3.4.3.31" -> ("slh-dsa", Some("shake-256f")),
    "1.3.9999.3.11" -> ("falcon", Some("512")),
    "1.3.9999.3.14" -> ("falcon", Some("1024")),
  )

  private val sigAlgOidMap: Map[String, String] = Map(
    "1.2.840.113549.1.1.4" -> "md5-rsa",
    "1.2.840.113549.1.1.5" -> "sha1-rsa",
    "1.2.840.113549.1.1.11" -> "sha256-rsa",
    "1.2.840.113549.1.1.12" -> "sha384-rsa",
    "1.2.840.113549.1.1.13" -> "sha512-rsa",
    "1.2.840.113549.1.1.10" -> "rsa-pss",
    "1.2.840.10045.4.1" -> "sha1-ecdsa",
    "1.2.840.10045.4.3.2" -> "sha256-ecdsa",
    "1.2.840.10045.4.3.3" -> "sha384-ecdsa",
    "1.2.840.10045.4.3.4" -> "sha512-ecdsa",
    "1.3.101.112" -> "ed25519",
    "1.3.101.113" -> "ed448",
    "1.2.840.10040.4.3" -> "sha1-dsa",
    "2.16.840.1.101.3.4.3.2" -> "sha256-dsa",
    "2.16.840.1.101.3.4.3.17" -> "ml-dsa-44",
    "2.16.840.1.101.3.4.3.18" -> "ml-dsa-65",
    "2.16.840.1.101.3.4.3.19" -> "ml-dsa-87",
    "2.16.840.1.101.3.4.3.32" -> "ml-dsa-44-prehash-sha512",
    "2.16.840.1.101.3.4.3.33" -> "ml-dsa-65-prehash-sha512",
    "2.16.840.1.101.3.4.3.34" -> "ml-dsa-87-prehash-sha512",
    "2.16.840.1.101.3.4.3.20" -> "slh-dsa-sha2-128s",
    "2.16.840.1.101.3.4.3.21" -> "slh-dsa-sha2-128f",
    "2.16.840.1.101.3.4.3.22" -> "slh-dsa-sha2-192s",
    "2.16.840.1.101.3.4.3.23" -> "slh-dsa-sha2-192f",
    "2.16.840.1.101.3.4.3.24" -> "slh-dsa-sha2-256s",
    "2.16.840.1.101.3.4.3.25" -> "slh-dsa-sha2-256f",
    "2.16.840.1.101.3.4.3.26" -> "slh-dsa-shake-128s",
    "2.16.840.1.101.3.4.3.27" -> "slh-dsa-shake-128f",
    "2.16.840.1.101.3.4.3.28" -> "slh-dsa-shake-192s",
    "2.16.840.1.101.3.4.3.29" -> "slh-dsa-shake-192f",
    "2.16.840.1.101.3.4.3.30" -> "slh-dsa-shake-256s",
    "2.16.840.1.101.3.4.3.31" -> "slh-dsa-shake-256f",
    "1.3.9999.3.11" -> "falcon-512",
    "1.3.9999.3.14" -> "falcon-1024",
    "1.3.6.1.5.5.7.6.37" -> "mldsa44-rsa2048-pss-sha256",
    "1.3.6.1.5.5.7.6.39" -> "mldsa44-ed25519-sha512",
    "1.3.6.1.5.5.7.6.40" -> "mldsa44-ecdsa-p256-sha256",
    "1.3.6.1.5.5.7.6.41" -> "mldsa65-rsa3072-pss-sha512",
    "1.3.6.1.5.5.7.6.49" -> "mldsa87-ecdsa-p384-sha512",
  )

  private val ecCurveMap: Map[String, String] = Map(
    "secp256r1" -> "p-256",
    "prime256v1" -> "p-256",
    "secp384r1" -> "p-384",
    "secp521r1" -> "p-521",
    "secp256k1" -> "secp256k1",
    "brainpoolp256r1" -> "brainpoolp256r1",
    "brainpoolp384r1" -> "brainpoolp384r1",
    "brainpoolp512r1" -> "brainpoolp512r1",
  )

  private val ekuOidMap: Map[String, String] = Map(
    "1.3.6.1.5.5.7.3.1" -> "server-auth",
    "1.3.6.1.5.5.7.3.2" -> "client-auth",
    "1.3.6.1.5.5.7.3.3" -> "code-signing",
    "1.3.6.1.5.5.7.3.4" -> "email-protection",
    "1.3.6.1.5.5.7.3.8" -> "time-stamping",
    "1.3.6.1.5.5.7.3.9" -> "ocsp-signing",
  )

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

  private[strategies] def assertNoLeak(
      metadata: TreeMap[String, TreeSet[StringOrPair]]
  ): Unit = {
    metadata.foreach { case (key, values) =>
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
    }
    purls -> this
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
    }
    Certificates.assertNoLeak(tm)
    tm -> this
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
        TreeSet(StringOrPair(isoUtc(java.util.Date.from(java.time.Instant.ofEpochSecond(c.validAfter)))))) +?
      Some(adHoc("SshCertValidBefore") ->
        TreeSet(StringOrPair(isoUtc(java.util.Date.from(java.time.Instant.ofEpochSecond(c.validBefore)))))) +?
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
