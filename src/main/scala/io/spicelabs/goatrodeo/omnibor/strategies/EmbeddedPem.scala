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

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.PurlSet
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.Helpers.sha256Hex
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.Base64
import javax.security.auth.x500.X500Principal
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects and inventories certificates and private keys embedded as base64 or
  * inline PEM inside text configuration files (kubeconfig, terraform, YAML,
  * JSON, `.env`).
  *
  * Emits `Certificates:` metadata for parsed X.509 certificates (same keys the
  * `Certificates` strategy uses) and an `EmbeddedKey:` family describing the
  * discovery source.
  *
  * Hard constraint: private keys are decoded ONLY to derive
  * algorithm/size/public-SPKI and to set `Certificates:DerivedFromPrivateKey=true`;
  * the decoded key bytes are then discarded and never appear in metadata.
  */
object EmbeddedPemStrategy {
  private val logger = Logger(getClass())

  /** Decoded blob size cap (bounds CPU/memory; oversized blobs are skipped). */
  val MaxDecodeBytes: Int = 256 * 1024

  /** Bytes read for content probing during claiming. */
  val DetectReadBytes: Int = 256 * 1024

  /** Bytes read for parsing during state processing. */
  val MaxReadBytes: Int = 1024 * 1024

  // Inline PEM block: header + base64 body through END.
  private val PemBlockRe =
    "(?s)-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\s]+)-----END \\1-----".r
  // Base64 `…-data` fields (kubeconfig / config tools).
  private val DataFieldRe =
    "\\b([A-Za-z0-9_.-]+-data)\\s*[:=]\\s*[\"']?([A-Za-z0-9+/=_-]{20,})[\"']?".r

  // Spelled algorithm → canonical lowercase name for private keys.
  private val KeyAlgorithmNames: Map[String, String] = Map(
    "1.2.840.113549.1.1.1" -> "rsa",
    "1.2.840.10040.4.1" -> "dsa",
    "1.2.840.10045.2.1" -> "ec",
    "1.3.101.112" -> "ed25519",
    "1.3.101.113" -> "ed448"
  )
  private val EllipticCurves: Map[String, Int] = Map(
    "1.2.840.10045.3.1.7" -> 256, // prime256v1
    "1.3.132.0.34" -> 384,        // secp384r1
    "1.3.132.0.35" -> 521         // secp521r1
  )

  // ── Base64 / PEM helpers ────────────────────────────────────────────────

  private[strategies] def base64Decode(s: String): Option[Array[Byte]] = {
    val cleaned = s.replaceAll("\\s", "")
    val padded = cleaned + ("=" * ((4 - (cleaned.length % 4)) % 4))
    Try {
      if (cleaned.contains('-') || cleaned.contains('_'))
        Base64.getUrlDecoder.decode(padded)
      else Base64.getDecoder.decode(padded)
    }.toOption
  }

  private def compact(s: String): String = s.filterNot(_.isWhitespace)

  // ── Inline PEM / base64 extraction ──────────────────────────────────────

  private[strategies] def inlinePemBlobs(text: String): Vector[(String, Array[Byte])] = {
    PemBlockRe
      .findAllMatchIn(text)
      .toVector
      .flatMap { m =>
        val header = Option(m.group(1)).getOrElse("")
        val body = Option(m.group(2)).map(compact).getOrElse("")
        base64Decode(body).map(header -> _)
      }
  }

  private[strategies] def base64DataValues(text: String): Vector[Array[Byte]] = {
    DataFieldRe
      .findAllMatchIn(text)
      .toVector
      .flatMap { m =>
        val value = Option(m.group(2)).getOrElse("")
        base64Decode(value).toVector
      }
  }

  private[strategies] def detects(text: String): Boolean =
    inlinePemBlobs(text).nonEmpty || base64DataValues(text).nonEmpty ||
      text.contains("-----BEGIN ")

  // ── Classification ──────────────────────────────────────────────────────

  private[strategies] def parseCertificate(bytes: Array[Byte]): Option[X509Certificate] = Try {
    val cf = CertificateFactory.getInstance("X.509").asInstanceOf[CertificateFactory]
    cf.generateCertificate(new ByteArrayInputStream(bytes)).asInstanceOf[X509Certificate]
  }.toOption

  // ── Private key metadata (bytes discarded after derivation) ─────────────

  private[strategies] final case class DerivedKey(
      algorithm: String,
      keySize: Option[Int] = None,
      derivedSpkiSha256: Option[String] = None
  )

  private def algorithmForOid(oid: String): String =
    KeyAlgorithmNames.getOrElse(oid, oid)

  private def ecSize(algId: org.bouncycastle.asn1.x509.AlgorithmIdentifier): Option[Int] =
    Option(algId.getParameters)
      .collect { case oid: org.bouncycastle.asn1.ASN1ObjectIdentifier => oid.getId }
      .flatMap(EllipticCurves.get)

  private def sizeFor(pri: PrivateKeyInfo): Option[Int] =
    Option(pri.getPrivateKeyAlgorithm).flatMap { alg =>
      algorithmForOid(alg.getAlgorithm.getId) match {
        case "rsa" =>
          Option(pri.parsePrivateKey).flatMap { pk =>
            Try(RSAPrivateKey.getInstance(pk).getModulus.bitLength).toOption
          }
        case "ec"  => ecSize(alg)
        case "ed25519" => Some(256)
        case "ed448"   => Some(448)
        case _         => None
      }
    }

  private def rsaSpki(pri: PrivateKeyInfo): Option[String] =
    for {
      pk <- Option(pri.parsePrivateKey)
      rsa <- Try(RSAPrivateKey.getInstance(pk)).toOption
      pub <- Try(
        KeyFactory
          .getInstance("RSA")
          .generatePublic(new RSAPublicKeySpec(rsa.getModulus, rsa.getPublicExponent))
      ).toOption
    } yield sha256Hex(pub.getEncoded)

  private[strategies] def parsePrivateKeyPem(pemText: String): Option[DerivedKey] = {
    val parser = new PEMParser(new StringReader(pemText))
    try {
      Option(parser.readObject()).flatMap {
        case kp: PEMKeyPair =>
          Option(kp.getPrivateKeyInfo).map { pri =>
            val alg = algorithmForOid(pri.getPrivateKeyAlgorithm.getAlgorithm.getId)
            val spki = Option(kp.getPublicKeyInfo)
              .flatMap(p => Try(p.getEncoded).toOption)
              .map(sha256Hex)
            DerivedKey(alg, sizeFor(pri), spki)
          }
        case pri: PrivateKeyInfo =>
          Option(pri.getPrivateKeyAlgorithm).map { algInfo =>
            val alg = algorithmForOid(algInfo.getAlgorithm.getId)
            val spki = if (alg == "rsa") rsaSpki(pri) else None
            DerivedKey(alg, sizeFor(pri), spki)
          }
        case _ => None // encrypted / unsupported envelopes carry no derived info
      }
    } finally parser.close()
  }

  private[strategies] def parsePrivateKeyDer(bytes: Array[Byte]): Option[DerivedKey] =
    Try(
      PrivateKeyInfo.getInstance(
        org.bouncycastle.asn1.ASN1Primitive.fromByteArray(bytes)
      )
    ).toOption.flatMap { pri =>
      Option(pri.getPrivateKeyAlgorithm).map { algInfo =>
        val alg = algorithmForOid(algInfo.getAlgorithm.getId)
        val spki = if (alg == "rsa") rsaSpki(pri) else None
        DerivedKey(alg, sizeFor(pri), spki)
      }
    }

  // ── Certificate metadata (public SPKI only) ─────────────────────────────

  private def dn(name: X500Principal): String =
    name.getName(X500Principal.RFC2253)

  private def isoUtc(d: java.util.Date): String =
    DateTimeFormatter.ISO_INSTANT
      .withZone(ZoneOffset.UTC)
      .format(d.toInstant)
      .replaceAll("\\.\\d+Z$", "Z")

  private def keyAlgAndSize(pub: java.security.PublicKey): (String, Option[Int], Option[String]) =
    pub match {
      case rsa: RSAPublicKey =>
        ("rsa", Some(rsa.getModulus.bitLength), None)
      case ec: ECPublicKey =>
        ("ec", Some(ec.getParams.getCurve.getField.getFieldSize), None)
      case _ =>
        pub.getAlgorithm.toLowerCase match {
          case "ed25519" => ("ed25519", Some(256), None)
          case "ed448"   => ("ed448", Some(448), None)
          case a         => (a, None, None)
        }
    }

  private[strategies] def certificateMetadata(
      cert: X509Certificate
  ): Map[String, TreeSet[StringOrPair]] = {
    import java.util.Base64
    val certAdHoc = MKC.adHoc("Certificates")
    val pub = Option(cert.getPublicKey)
    val (alg, size, _) = pub.map(keyAlgAndSize).getOrElse(("unknown", None, None))
    val subject = cert.getSubjectX500Principal
    val issuer = cert.getIssuerX500Principal
    val selfSigned = Try(cert.verify(pub.orNull, "BC")).isSuccess && subject == issuer
    var m = Map[String, TreeSet[StringOrPair]](
      certAdHoc("SubjectDN") -> TreeSet(StringOrPair(dn(subject))),
      certAdHoc("IssuerDN") -> TreeSet(StringOrPair(dn(issuer))),
      certAdHoc("Serial") -> TreeSet(
        StringOrPair(cert.getSerialNumber.toString(16))
      ),
      certAdHoc("NotBefore") -> TreeSet(StringOrPair(isoUtc(cert.getNotBefore))),
      certAdHoc("NotAfter") -> TreeSet(StringOrPair(isoUtc(cert.getNotAfter))),
      certAdHoc("KeyAlgorithm") -> TreeSet(StringOrPair(alg)),
      certAdHoc("SigAlgorithm") -> TreeSet(
        StringOrPair(
          Option(cert.getSigAlgName).getOrElse(
            cert.getSigAlgOID
          )
        )
      ),
      certAdHoc("SpkiSha256") -> TreeSet(
        StringOrPair(pub.map(p => sha256Hex(p.getEncoded)).getOrElse(""))
      ),
      certAdHoc("CertSha256") -> TreeSet(
        StringOrPair(sha256Hex(cert.getEncoded))
      ),
      certAdHoc("IsCA") -> TreeSet(
        StringOrPair((cert.getBasicConstraints >= 0).toString)
      ),
      certAdHoc("SelfSigned") -> TreeSet(StringOrPair(selfSigned.toString)),
      certAdHoc("Version") -> TreeSet(StringOrPair(cert.getVersion.toString))
    )
    size.foreach(s =>
      m = m + (certAdHoc("KeySize") -> TreeSet(StringOrPair(s.toString)))
    )
    m
  }

  // ── Pipeline ─────────────────────────────────────────────────────────────

  /** Compute embedded-cert/key config files to process at a layer. */
  def computeEmbeddedPemFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter { a =>
      // Text configs only: binaries with embedded PEM delimiters remain the
      // EmbeddedCertificates domain.
      !a.mimeType.exists(CryptoFootprintStrategy.BinaryMimes.contains) &&
      Try(detects(probeText(a))).getOrElse(false)
    }.toVector

    val uuids = mine.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new EmbeddedPemToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "EmbeddedPem"
    )
  }

  private def probeText(a: ArtifactWrapper): String = {
    val bytes = a.withStream { s =>
      val buf = new Array[Byte](DetectReadBytes)
      val n = s.read(buf, 0, DetectReadBytes)
      if (n <= 0) Array.emptyByteArray else java.util.Arrays.copyOf(buf, n)
    }
    new String(bytes, StandardCharsets.ISO_8859_1)
  }

  private[strategies] def contentOf(a: ArtifactWrapper): String = {
    val bytes = a.withStream { s =>
      val buf = new Array[Byte](MaxReadBytes)
      val n = s.read(buf, 0, MaxReadBytes)
      if (n <= 0) Array.emptyByteArray else java.util.Arrays.copyOf(buf, n)
    }
    new String(bytes, StandardCharsets.ISO_8859_1)
  }
}

class EmbeddedPemToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = EmbeddedPemState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new EmbeddedPemState(artifact)
}

class EmbeddedPemState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, EmbeddedPemState] {

  private val certAdHoc = MKC.adHoc("Certificates")
  private val keyAdHoc = MKC.adHoc("EmbeddedKey")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): EmbeddedPemState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, EmbeddedPemState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], EmbeddedPemState) = {
    val meta = buildMetadata(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, EmbeddedPemState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): EmbeddedPemState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): EmbeddedPemState = this

  private def buildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val text = Try(EmbeddedPemStrategy.contentOf(artifact)).getOrElse("")
    val path = artifact.path()

    val blobs = EmbeddedPemStrategy.inlinePemBlobs(text) ++
      EmbeddedPemStrategy
        .base64DataValues(text)
        .map(b => "" -> b)

    val pairs = Vector.newBuilder[(String, StringOrPair)]

    def emitKey(derived: EmbeddedPemStrategy.DerivedKey): Unit = {
      pairs += (keyAdHoc("source") -> StringOrPair(path))
      pairs += (keyAdHoc("kind") -> StringOrPair("private-key"))
      pairs += (certAdHoc("DerivedFromPrivateKey") -> StringOrPair("true"))
      pairs += (keyAdHoc("key_algorithm") -> StringOrPair(derived.algorithm))
      derived.keySize.foreach(s =>
        pairs += (keyAdHoc("key_size") -> StringOrPair(s.toString))
      )
      derived.derivedSpkiSha256.foreach(s =>
        pairs += (keyAdHoc("derived_spki_sha256") -> StringOrPair(s))
      )
    }

    def emitCert(cert: X509Certificate): Unit = {
      EmbeddedPemStrategy.certificateMetadata(cert).foreach { case (k, vs) =>
        vs.foreach(v => pairs += (k -> v))
      }
      pairs += (keyAdHoc("source") -> StringOrPair(path))
      pairs += (keyAdHoc("kind") -> StringOrPair("certificate"))
    }

    blobs.foreach { case (_header, bytes) =>
      if (bytes.length <= EmbeddedPemStrategy.MaxDecodeBytes) {
        val asText = new String(bytes, StandardCharsets.ISO_8859_1)
        if (asText.contains("-----BEGIN")) {
          // Full PEM text (kubeconfig-style base64 of the whole PEM), or an
          // inline PEM whose body is DER below.
          if (asText.toUpperCase.contains("PRIVATE KEY")) {
            EmbeddedPemStrategy.parsePrivateKeyPem(asText).foreach(emitKey)
          } else {
            EmbeddedPemStrategy.parseCertificate(bytes).foreach(emitCert)
          }
        } else {
          // DER payload (either a DER cert or a PKCS#8 private key body).
          EmbeddedPemStrategy.parseCertificate(bytes) match {
            case Some(cert) => emitCert(cert)
            case None =>
              EmbeddedPemStrategy.parsePrivateKeyDer(bytes).foreach(emitKey)
          }
        }
      }
    }

    val collected = pairs.result()
    if (collected.isEmpty) TreeMap.empty[String, TreeSet[StringOrPair]]
    else {
      val grouped = collected
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2).to(TreeSet))
        .toMap
      TreeMap.from(grouped)
    }
  }

  /** Test-accessible alias for buildMetadata. */
  private[strategies] def invokeBuildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = buildMetadata(artifact)
}