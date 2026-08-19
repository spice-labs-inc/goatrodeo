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
import io.spicelabs.goatrodeo.util.CipherSuiteResolver
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers

import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects and inventories cryptographic settings in service configuration
  * files: OpenVPN, strongSwan/IPsec, Mosquitto, HAProxy, Redis, PostgreSQL,
  * MySQL/MariaDB, WireGuard, and Kerberos `krb5.conf`.
  *
  * Emits `ServiceCrypto:` metadata (service, cipher suites, resolved
  * algorithms, protocol bounds, cert/key file paths, and **presence-only**
  * flags for secrets) and `Kerberos:` metadata (enctype inventory).
  *
  * Hard constraint: secret VALUES (WireGuard `PrivateKey`/`PresharedKey`,
  * Mosquitto `psk_file` contents, any embedded key material) are never echoed
  * into metadata — only their presence is recorded.
  */
object ServiceCryptoStrategy {
  private val logger = Logger(getClass())

  // ── Service detection ───────────────────────────────────────────────────

  /** Return the service id when the file name/path matches a supported dialect,
    * else None (no claim, no crash).
    */
  private[strategies] def detectService(path: String): Option[String] = {
    val fileName = path.split('/').lastOption.getOrElse(path)
    if (fileName.endsWith(".ovpn") || path.contains("openvpn/")) Some("openvpn")
    else if (
      fileName == "ipsec.conf" || fileName == "strongswan.conf" || fileName == "swanctl.conf"
    )
      Some("strongswan")
    else if (fileName == "mosquitto.conf") Some("mosquitto")
    else if (fileName == "haproxy.cfg") Some("haproxy")
    else if (fileName == "redis.conf") Some("redis")
    else if (
      fileName == "postgresql.conf" || fileName == "postgresql.conf.sample"
    )
      Some("postgresql")
    else if (
      fileName == "my.cnf" || fileName == "my.ini" || fileName == "mariadb.cnf"
    )
      Some("mysql")
    else if (fileName.startsWith("wg") && fileName.endsWith(".conf"))
      Some("wireguard")
    else if (fileName == "krb5.conf") Some("kerberos")
    else None
  }

  /** Compute service crypto config files to process at a layer. */
  def computeServiceCryptoFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine =
      byUUID.values.filter(a => detectService(a.path()).isDefined).toVector

    val uuids = mine.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new ServiceCryptoToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "ServiceCrypto"
    )
  }

  // ── Regexes per dialect ─────────────────────────────────────────────────

  private val DataCiphers = "^\\s*(data-ciphers|ncp-ciphers)\\s+(.+)$".r
  private val OvpnCipher = "^\\s*cipher\\s+(\\S+)$".r
  private val OvpnTlsCipher = "^\\s*tls-cipher\\s+(.+)$".r
  private val OvpnAuth = "^\\s*auth\\s+(\\S+)$".r
  private val OvpnDh = "^\\s*dh\\s+(\\S+)$".r
  private val OvpnPath = "^\\s*(ca|cert|key|dh)\\s+(\\S+)$".r

  private val IkeEsp = "^\\s*(ike|esp)\\s*=\\s*(.+)$".r

  private val MosTlsCiphers = "^\\s*tls_ciphers\\s+(.+)$".r
  private val MosPskFile = "^\\s*psk_file\\s+(\\S+)$".r
  private val MosPaths = "^\\s*(certfile|keyfile)\\s+(\\S+)$".r

  private val HapCiphers = "^\\s*ssl-default-bind-ciphers\\s+(.+)$".r
  private val HapCipherSuites = "^\\s*ssl-default-bind-ciphersuites\\s+(.+)$".r
  private val HapBindCrt = "^\\s*bind\\s+\\S+\\s+ssl.*\\bcrt\\s+(\\S+)".r

  private val RedisTlsCiphers = "^\\s*tls-ciphers\\s+(.+)$".r
  private val RedisPaths = "^\\s*tls-(cert|key)-file\\s+(\\S+)$".r

  private val PgSsl = "^\\s*ssl\\s*=\\s*(on|off)$".r
  private val PgMinProto = "^\\s*ssl_min_protocol_version\\s*=\\s*(\\S+)$".r
  private val PgPaths = "^\\s*ssl_(cert|key)_file\\s*=\\s*(\\S+)$".r

  private val MyCipher = "^\\s*ssl-cipher\\s*=\\s*(.+)$".r
  private val MyTlsVersion = "^\\s*tls_version\\s*=\\s*(.+)$".r
  private val MyPaths = "^\\s*ssl-(ca|cert|key)\\s*=\\s*(\\S+)$".r

  private val WgSecret = "^\\s*(PrivateKey|PresharedKey)\\s*=\\s*\\S+\\s*$".r

  private val KrbEnctypes =
    "^\\s*(default_tkt_enctypes|default_tgs_enctypes|permitted_enctypes)\\s*=\\s*(.+)$".r

  // ── Transform grammar (strongSwan proposals) ────────────────────────────

  private val TransformParts: Map[String, String] = Map(
    "aes128" -> "aes-128",
    "aes192" -> "aes-192",
    "aes256" -> "aes-256",
    "aes128gcm16" -> "aes-128-gcm",
    "aes256gcm16" -> "aes-256-gcm",
    "aes128ccm16" -> "aes-128-ccm",
    "aes256ccm16" -> "aes-256-ccm",
    "chacha20poly1305" -> "chacha20-poly1305",
    "sha1" -> "sha-1",
    "sha256" -> "sha-256",
    "sha384" -> "sha-384",
    "sha512" -> "sha-512",
    "sha3_256" -> "sha3-256",
    "sha3_384" -> "sha3-384",
    "sha3_512" -> "sha3-512",
    "blake2b256" -> "blake2b-256",
    "blake2b512" -> "blake2b-512",
    "prfsha1" -> "sha-1",
    "prfsha256" -> "sha-256",
    "prfsha384" -> "sha-384",
    "modp1536" -> "ffdhe-1536",
    "modp2048" -> "ffdhe-2048",
    "modp3072" -> "ffdhe-3072",
    "modp4096" -> "ffdhe-4096",
    "modp6144" -> "ffdhe-6144",
    "ecp256" -> "secp256r1",
    "ecp384" -> "secp384r1",
    "ecp521" -> "secp521r1",
    "x25519" -> "x25519",
    "x448" -> "x448"
  )

  /** Decompose a strongSwan transform string (`aes256-sha256-modp2048`) into
    * its constituent algorithms, dropping unknown parts (no invention).
    */
  private[strategies] def transformAlgorithms(
      transform: String
  ): Vector[String] = {
    transform
      .split('-')
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(part => TransformParts.get(part).toVector)
      .distinct
  }

  /** Union of standalone-algorithm and suite resolution for a cipher value. */
  private[strategies] def algorithmsForCipherValue(
      value: String
  ): Vector[String] = {
    val standalone = CipherSuiteResolver.resolveAlgorithmList(value)
    val suites =
      CipherSuiteResolver.resolveCipherString(value).flatMap(_.algorithms)
    (standalone ++ suites).distinct
  }

  // ── Parsed result ───────────────────────────────────────────────────────

  private[strategies] final case class ParsedConfig(
      cipherValues: Vector[String] = Vector.empty,
      transforms: Vector[String] = Vector.empty,
      protocolMin: Option[String] = None,
      protocolMax: Option[String] = None,
      protocolRaw: Option[String] = None,
      certFile: Option[String] = None,
      keyFile: Option[String] = None,
      pskPresent: Boolean = false,
      privateKeyPresent: Boolean = false,
      authAlgorithm: Option[String] = None,
      enctypes: Vector[String] = Vector.empty
  ) {
    def isEmpty: Boolean =
      cipherValues.isEmpty && transforms.isEmpty && protocolMin.isEmpty &&
        protocolMax.isEmpty && protocolRaw.isEmpty && certFile.isEmpty &&
        keyFile.isEmpty && !pskPresent && !privateKeyPresent && authAlgorithm.isEmpty &&
        enctypes.isEmpty
  }

  private[strategies] def parseText(
      service: String,
      text: String
  ): ParsedConfig =
    service match {
      case "openvpn"    => parseOpenVpn(text)
      case "strongswan" => parseStrongSwan(text)
      case "mosquitto"  => parseMosquitto(text)
      case "haproxy"    => parseHaproxy(text)
      case "redis"      => parseRedis(text)
      case "postgresql" => parsePostgres(text)
      case "mysql"      => parseMysql(text)
      case "wireguard"  => parseWireGuard(text)
      case "kerberos"   => parseKerberos(text)
      case _            => ParsedConfig()
    }

  private def parseOpenVpn(text: String): ParsedConfig = {
    val ciphers = Vector.newBuilder[String]
    var cert: Option[String] = None
    var key: Option[String] = None
    var auth: Option[String] = None
    text.linesIterator.foreach { line =>
      line.trim match {
        case DataCiphers(_, v) => ciphers += Option(v).getOrElse("").trim
        case OvpnCipher(v)     => ciphers += Option(v).getOrElse("").trim
        case OvpnTlsCipher(v)  => ciphers += Option(v).getOrElse("").trim
        case OvpnAuth(v) =>
          auth = Some(Option(v).getOrElse("").trim.toUpperCase)
        case OvpnDh(_)           => ()
        case OvpnPath("cert", v) => cert = Some(Option(v).getOrElse("").trim)
        case OvpnPath("key", v)  => key = Some(Option(v).getOrElse("").trim)
        case OvpnPath("ca", _)   => ()
        case _                   =>
      }
    }
    ParsedConfig(
      cipherValues = ciphers.result(),
      certFile = cert,
      keyFile = key,
      authAlgorithm = auth
    )
  }

  private def parseStrongSwan(text: String): ParsedConfig = {
    val proposals = Vector.newBuilder[String]
    text.linesIterator.foreach { line =>
      line.trim match {
        case IkeEsp(_, value) =>
          val first =
            Option(value).getOrElse("").split(',').map(_.trim).find(_.nonEmpty)
          first.foreach(proposals += _)
        case _ =>
      }
    }
    ParsedConfig(transforms = proposals.result())
  }

  private def parseMosquitto(text: String): ParsedConfig = {
    var cipher: Option[String] = None
    var psk = false
    var cert: Option[String] = None
    var key: Option[String] = None
    text.linesIterator.foreach { line =>
      line.trim match {
        case MosTlsCiphers(v) => cipher = Some(Option(v).getOrElse("").trim)
        case MosPskFile(_)    => psk = true // presence only
        case MosPaths("certfile", v) =>
          cert = Some(Option(v).getOrElse("").trim)
        case MosPaths("keyfile", v) => key = Some(Option(v).getOrElse("").trim)
        case _                      =>
      }
    }
    ParsedConfig(
      cipherValues = cipher.toVector,
      pskPresent = psk,
      certFile = cert,
      keyFile = key
    )
  }

  private def parseHaproxy(text: String): ParsedConfig = {
    val ciphers = Vector.newBuilder[String]
    var cert: Option[String] = None
    text.linesIterator.foreach { line =>
      line.trim match {
        case HapCiphers(v)      => ciphers += Option(v).getOrElse("").trim
        case HapCipherSuites(v) => ciphers += Option(v).getOrElse("").trim
        case HapBindCrt(v)      => cert = Some(Option(v).getOrElse("").trim)
        case _                  =>
      }
    }
    ParsedConfig(cipherValues = ciphers.result(), certFile = cert)
  }

  private def parseRedis(text: String): ParsedConfig = {
    var cipher: Option[String] = None
    var cert: Option[String] = None
    var key: Option[String] = None
    text.linesIterator.foreach { line =>
      line.trim match {
        case RedisTlsCiphers(v) => cipher = Some(Option(v).getOrElse("").trim)
        case RedisPaths("cert", v) => cert = Some(Option(v).getOrElse("").trim)
        case RedisPaths("key", v)  => key = Some(Option(v).getOrElse("").trim)
        case _                     =>
      }
    }
    ParsedConfig(
      cipherValues = cipher.toVector,
      certFile = cert,
      keyFile = key
    )
  }

  private def parsePostgres(text: String): ParsedConfig = {
    var minProto: Option[String] = None
    var cert: Option[String] = None
    var key: Option[String] = None
    text.linesIterator.foreach { line =>
      line.trim match {
        case PgSsl(_)           => () // tls enabled/disabled flag
        case PgMinProto(v)      => minProto = Some(Option(v).getOrElse("").trim)
        case PgPaths("cert", v) => cert = Some(Option(v).getOrElse("").trim)
        case PgPaths("key", v)  => key = Some(Option(v).getOrElse("").trim)
        case _                  =>
      }
    }
    ParsedConfig(
      protocolMin = minProto,
      certFile = cert,
      keyFile = key
    )
  }

  private def parseMysql(text: String): ParsedConfig = {
    var cipher: Option[String] = None
    var tlsVersion: Option[String] = None
    var cert: Option[String] = None
    var key: Option[String] = None
    var inMysqld = false
    text.linesIterator.foreach { line =>
      val t = line.trim
      if (t.startsWith("[")) {
        inMysqld = t.startsWith("[mysqld]")
      } else if (inMysqld) {
        t match {
          case MyCipher(v) => cipher = Some(Option(v).getOrElse("").trim)
          case MyTlsVersion(v) =>
            tlsVersion = Some(Option(v).getOrElse("").trim)
          case MyPaths("cert", v) => cert = Some(Option(v).getOrElse("").trim)
          case MyPaths("key", v)  => key = Some(Option(v).getOrElse("").trim)
          case MyPaths("ca", _)   => ()
          case _                  =>
        }
      }
    }
    val (minV, maxV) = tlsVersion match {
      case Some(v) =>
        val parts = v.split(',').toVector.map(_.trim).filter(_.nonEmpty)
        (parts.headOption, if (parts.length > 1) parts.lastOption else None)
      case None => (None, None)
    }
    ParsedConfig(
      cipherValues = cipher.toVector,
      protocolMin = minV,
      protocolMax = maxV,
      protocolRaw = tlsVersion,
      certFile = cert,
      keyFile = key
    )
  }

  private def parseWireGuard(text: String): ParsedConfig = {
    var priv = false
    var psk = false
    text.linesIterator.foreach { line =>
      line.trim match {
        case WgSecret("PrivateKey")   => priv = true
        case WgSecret("PresharedKey") => psk = true
        case _                        =>
      }
    }
    ParsedConfig(privateKeyPresent = priv, pskPresent = psk)
  }

  private def parseKerberos(text: String): ParsedConfig = {
    val enctypes = Vector.newBuilder[String]
    text.linesIterator.foreach { line =>
      line.trim match {
        case KrbEnctypes(_, value) =>
          Option(value)
            .getOrElse("")
            .split("\\s+")
            .toVector
            .map(_.trim)
            .filter(_.nonEmpty)
            .foreach(
              enctypes += _
            )
        case _ =>
      }
    }
    ParsedConfig(enctypes = enctypes.result().distinct)
  }

}

class ServiceCryptoToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = ServiceCryptoState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new ServiceCryptoState(artifact)
}

class ServiceCryptoState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, ServiceCryptoState] {

  private val adHoc = MKC.adHoc("ServiceCrypto")
  private val krbAdHoc = MKC.adHoc("Kerberos")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): ServiceCryptoState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, ServiceCryptoState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], ServiceCryptoState) = {
    val meta = buildMetadata(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, ServiceCryptoState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): ServiceCryptoState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): ServiceCryptoState = this

  private def buildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val path = artifact.path()
    val service = ServiceCryptoStrategy.detectService(path).getOrElse("")
    val text = Try {
      artifact.withStream { stream =>
        new String(Helpers.slurpInput(stream), StandardCharsets.ISO_8859_1)
      }
    }.getOrElse("")

    val parsed = ServiceCryptoStrategy.parseText(service, text)
    if (parsed.isEmpty) TreeMap.empty[String, TreeSet[StringOrPair]]
    else {
      var tm = TreeMap[String, TreeSet[StringOrPair]](
        adHoc("service") -> TreeSet(StringOrPair(service)),
        adHoc("FilePath") -> TreeSet(StringOrPair("/" + path))
      )

      val cipherAlgs =
        parsed.cipherValues.flatMap(
          ServiceCryptoStrategy.algorithmsForCipherValue
        )
      val transformAlgs = parsed.transforms.flatMap(
        ServiceCryptoStrategy.transformAlgorithms
      )
      val authAlgs =
        parsed.authAlgorithm
          .flatMap(CipherSuiteResolver.resolveAlgorithmName)
          .toVector
      val allAlgs = (cipherAlgs ++ transformAlgs ++ authAlgs).distinct.sorted

      if (parsed.cipherValues.nonEmpty) {
        tm = tm + (adHoc("cipher_suite") -> TreeSet(
          StringOrPair(parsed.cipherValues.mkString(":"))
        ))
      }
      parsed.transforms.zipWithIndex.foreach { case (t, i) =>
        tm = tm + (adHoc(s"transform:$i") -> TreeSet(StringOrPair(t)))
      }
      if (allAlgs.nonEmpty) {
        tm = tm + (adHoc("algorithms") -> TreeSet.from(
          allAlgs.map(StringOrPair(_))
        ))
      }
      parsed.protocolRaw.foreach(v =>
        tm = tm + (adHoc("protocol") -> TreeSet(StringOrPair(v)))
      )
      parsed.protocolMin.foreach(v =>
        tm = tm + (adHoc("protocol_min") -> TreeSet(StringOrPair(v)))
      )
      parsed.protocolMax.foreach(v =>
        tm = tm + (adHoc("protocol_max") -> TreeSet(StringOrPair(v)))
      )
      parsed.authAlgorithm.foreach(v =>
        tm = tm + (adHoc("auth") -> TreeSet(StringOrPair(v)))
      )
      parsed.certFile.foreach(v =>
        tm = tm + (adHoc("cert_file") -> TreeSet(StringOrPair(v)))
      )
      parsed.keyFile.foreach(v =>
        tm = tm + (adHoc("key_file") -> TreeSet(StringOrPair(v)))
      )
      if (parsed.pskPresent) {
        tm = tm + (adHoc("psk_present") -> TreeSet(StringOrPair("true")))
      }
      if (parsed.privateKeyPresent) {
        tm =
          tm + (adHoc("private_key_present") -> TreeSet(StringOrPair("true")))
      }
      if (parsed.enctypes.nonEmpty) {
        tm = tm + (krbAdHoc("enctypes") -> TreeSet.from(
          parsed.enctypes.map(StringOrPair(_))
        ))
        parsed.enctypes.distinct.foreach { e =>
          tm = tm + (krbAdHoc(s"enctype:$e") -> TreeSet(StringOrPair("true")))
        }
      }
      tm
    }
  }

  /** Test-accessible alias for buildMetadata. */
  private[strategies] def invokeBuildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = buildMetadata(artifact)
}
