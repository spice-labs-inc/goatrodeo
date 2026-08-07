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
import org.json4s.*
import org.json4s.native.JsonMethods.parse

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects and inventories JSON Web Tokens (JWTs) and JSON Web Keys (JWKs) in
  * artifacts.
  *
  * Emits `JWT:` metadata (signing `alg` headers, canonical signature algorithm,
  * and an `alg:none` security finding) and `JWK:` metadata (kty/crv/use/key
  * size, and a private-members presence flag).
  *
  * Hard constraint: JWT payloads/signatures and JWK key material (`n`, `e`,
  * `d`, `p`, `q`, `k`) are never echoed into metadata — only algorithm tags
  * and a private-members presence flag are recorded.
  */
object CryptoTokenStrategy {
  private val logger = Logger(getClass())

  /** Bytes read for content probing during claiming. */
  val DetectReadBytes: Int = 256 * 1024

  /** Bytes read for parsing during state processing. */
  val MaxReadBytes: Int = 1024 * 1024

  // A JWT bears `eyJ...` (base64url of `{"`); a JWK JSON object carries "kty".
  private val DetectRegex = "(?i)\\beyJ[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]*){1,2}\\b".r
  private val HeaderSegment = "\\A[^.]{1,262144}(?=\\.)".r

  // JOSE `alg` → canonical signature-algorithm category.
  private val JoseAlgorithms: Map[String, String] = Map(
    "HS256" -> "hmac-sha-256",
    "HS384" -> "hmac-sha-384",
    "HS512" -> "hmac-sha-512",
    "RS256" -> "rsa-sha-256",
    "RS384" -> "rsa-sha-384",
    "RS512" -> "rsa-sha-512",
    "ES256" -> "ecdsa-sha-256",
    "ES384" -> "ecdsa-sha-384",
    "ES512" -> "ecdsa-sha-512",
    "PS256" -> "rsa-pss-sha-256",
    "PS384" -> "rsa-pss-sha-384",
    "PS512" -> "rsa-pss-sha-512",
    "EdDSA" -> "eddsa",
    "none" -> "none"
  )

  private[strategies] def canonicalJoseAlg(alg: String): String =
    JoseAlgorithms.getOrElse(alg, alg.toLowerCase)

  // ── Base64url ────────────────────────────────────────────────────────────

  private[strategies] def base64UrlDecode(s: String): Option[Array[Byte]] = Try {
    val pad = (4 - (s.length % 4)) % 4
    Base64.getUrlDecoder.decode(s + ("=" * pad))
  }.toOption

  // ── Content detection ────────────────────────────────────────────────────

  private[strategies] def looksLikeJwk(text: String): Boolean = {
    text.contains("\"kty\"") &&
    (text.contains("\"n\"") || text.contains("\"crv\"") ||
      text.contains("\"k\"") || text.contains("\"d\""))
  }

  private[strategies] def jwtCandidate(text: String): Option[String] =
    DetectRegex.findFirstMatchIn(text).flatMap(m => Option(m.matched))

  /** True when the bounded content contains a JWT or a JWK; used for claiming. */
  private[strategies] def detects(text: String): Boolean =
    jwtCandidate(text).isDefined || looksLikeJwk(text)

  /** Read up to `limit` bytes from an artifact as UTF-8/ISO-8859-1. */
  private def boundedText(a: ArtifactWrapper, limit: Int): String = {
    val bytes = a.withStream { s =>
      val buf = new Array[Byte](limit)
      val n = s.read(buf, 0, limit)
      if (n <= 0) Array.emptyByteArray else java.util.Arrays.copyOf(buf, n)
    }
    new String(bytes, StandardCharsets.ISO_8859_1)
  }

  private[strategies] def probeText(a: ArtifactWrapper): String =
    boundedText(a, DetectReadBytes)

  private[strategies] def fullBoundedText(a: ArtifactWrapper): String =
    boundedText(a, MaxReadBytes)

  // ── JWT header parsing ───────────────────────────────────────────────────

  private[strategies] final case class JwtInfo(algs: Vector[(String, String)])

  private[strategies] def parseJwts(text: String): JwtInfo = {
    val algs = Vector.newBuilder[(String, String)]
    DetectRegex.findAllMatchIn(text).foreach { m =>
      val header = Option(m.matched).flatMap { matchedToken =>
        HeaderSegment
          .findFirstMatchIn(matchedToken)
          .flatMap(h => Option(h.matched))
      }
      header.flatMap(base64UrlDecode).foreach { raw =>
        Try(parse(new String(raw, StandardCharsets.UTF_8))).toOption.foreach { jv =>
          jv \ "alg" match {
            case JString(alg) =>
              val a = if (alg == "none") "none" else alg
              algs += ((a, canonicalJoseAlg(a)))
            case _ =>
          }
        }
      }
    }
    JwtInfo(algs.result().distinct)
  }

  // ── JWK parsing ──────────────────────────────────────────────────────────

  private[strategies] final case class JwkInfo(
      kty: String,
      crv: Option[String] = None,
      use: Option[String] = None,
      sizeBits: Option[Int] = None,
      privatePresent: Boolean = false
  )

  private[strategies] def parseJwk(text: String): Option[JwkInfo] = {
    if (!looksLikeJwk(text)) None
    else
      Try(parse(text)).toOption.flatMap { jv =>
        jv \ "kty" match {
          case JString(kty) =>
            val crv = jv \ "crv" match {
              case JString(c) => Some(c)
              case _          => None
            }
            val use = jv \ "use" match {
              case JString(u) => Some(u)
              case _          => None
            }
            val sizeBits = jv \ "n" match {
              case JString(n) =>
                base64UrlDecode(n).map(b => b.length * 8)
              case _ => None
            }
            val privatePresent = Vector("d", "p", "q", "k").exists { key =>
              jv \ key match {
                case JString(v) => v.nonEmpty
                case _          => false
              }
            }
            Some(JwkInfo(kty, crv, use, sizeBits, privatePresent))
          case _ => None
        }
      }
  }

  // ── Pipeline ─────────────────────────────────────────────────────────────

  /** Compute JWT/JWK artifacts to process at a layer. */
  def computeCryptoTokenFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter { a =>
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
      mine.map(a => new CryptoTokenToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "CryptoToken"
    )
  }
}

class CryptoTokenToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = CryptoTokenState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new CryptoTokenState(artifact)
}

class CryptoTokenState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, CryptoTokenState] {

  private val jwtAdHoc = MKC.adHoc("JWT")
  private val jwkAdHoc = MKC.adHoc("JWK")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): CryptoTokenState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, CryptoTokenState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], CryptoTokenState) = {
    val meta = buildMetadata(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, CryptoTokenState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): CryptoTokenState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): CryptoTokenState = this

  private def buildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val text = Try(CryptoTokenStrategy.fullBoundedText(artifact)).getOrElse("")
    val jwt = CryptoTokenStrategy.parseJwts(text)
    val jwk = CryptoTokenStrategy.parseJwk(text)

    if (jwt.algs.isEmpty && jwk.isEmpty) TreeMap.empty[String, TreeSet[StringOrPair]]
    else {
      var tm = TreeMap[String, TreeSet[StringOrPair]]()
      if (jwt.algs.nonEmpty) {
        tm = tm + (jwtAdHoc("alg") -> TreeSet.from(
          jwt.algs.map(_._1).map(StringOrPair(_))
        ))
        tm = tm + (jwtAdHoc("signature_algorithm") -> TreeSet.from(
          jwt.algs.map(_._2).map(StringOrPair(_))
        ))
        if (jwt.algs.exists(_._1 == "none")) {
          tm = tm + (jwtAdHoc("none_present") -> TreeSet(StringOrPair("true")))
        }
      }
      jwk.foreach { info =>
        tm = tm + (jwkAdHoc("kty") -> TreeSet(StringOrPair(info.kty)))
        info.crv.foreach(c =>
          tm = tm + (jwkAdHoc("crv") -> TreeSet(StringOrPair(c)))
        )
        info.use.foreach(u =>
          tm = tm + (jwkAdHoc("use") -> TreeSet(StringOrPair(u)))
        )
        info.sizeBits.foreach(s =>
          tm = tm + (jwkAdHoc("size") -> TreeSet(StringOrPair(s.toString)))
        )
        if (info.privatePresent) {
          tm = tm + (jwkAdHoc("private_present") -> TreeSet(StringOrPair("true")))
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