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

import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects the *runtime* crypto algorithm footprint of binaries by scanning
  * string tables for crypto API/package identifiers (OpenSSL `EVP_*` symbols,
  * Go `crypto/...` and `golang.org/x/crypto/...` package paths, Rust crate
  * names, and .NET `System.Security.Cryptography.*` types).
  *
  * Emits `CryptoAlgorithms:` keys: `classifier` (evp/golang/rust/dotnet),
  * `value` (the matched identifier), `algorithm` (canonical name), `confidence`
  * (symbol vs identifier), plus `unknown=true` when a matched identifier
  * carries no canonical algorithm (e.g. crypto libraries).
  *
  * Registered after `EmbeddedCertificatesStrategy`, so crypto libraries with
  * embedded certificate delimiters keep their existing `EmbeddedCertificates:`
  * behavior; this strategy inventories algorithm symbols for binaries that
  * carry them.
  */
object CryptoFootprintStrategy {
  private val logger = Logger(getClass())

  /** Binary MIME types scanned for crypto identifiers. */
  val BinaryMimes: Set[String] = Set(
    "application/x-sharedlib",
    "application/x-executable",
    "application/x-pie-executable",
    "application/x-object",
    "application/octet-stream"
  )

  /** Bytes read for content probing during claiming. */
  val DetectReadBytes: Int = 256 * 1024

  /** Bytes read for parsing during state processing. */
  val MaxReadBytes: Int = 1024 * 1024

  private[strategies] final case class FootprintPattern(
      classifier: String,
      needle: String,
      algorithm: Option[String],
      confidence: String
  )

  // Curated identifier → canonical algorithm footprint table. Libraries
  // without a single canonical algorithm (ring, rustls) carry algorithm=None
  // and are flagged unknown at emission.
  private val Patterns: Vector[FootprintPattern] = Vector(
    // OpenSSL EVP symbols
    FootprintPattern("evp", "EVP_sha1", Some("sha-1"), "symbol"),
    FootprintPattern("evp", "EVP_sha224", Some("sha-224"), "symbol"),
    FootprintPattern("evp", "EVP_sha256", Some("sha-256"), "symbol"),
    FootprintPattern("evp", "EVP_sha384", Some("sha-384"), "symbol"),
    FootprintPattern("evp", "EVP_sha512", Some("sha-512"), "symbol"),
    FootprintPattern("evp", "EVP_sha3_224", Some("sha3-224"), "symbol"),
    FootprintPattern("evp", "EVP_sha3_256", Some("sha3-256"), "symbol"),
    FootprintPattern("evp", "EVP_sha3_384", Some("sha3-384"), "symbol"),
    FootprintPattern("evp", "EVP_sha3_512", Some("sha3-512"), "symbol"),
    FootprintPattern("evp", "EVP_sha512_224", Some("sha512-224"), "symbol"),
    FootprintPattern("evp", "EVP_sha512_256", Some("sha512-256"), "symbol"),
    FootprintPattern("evp", "EVP_md5", Some("md5"), "symbol"),
    FootprintPattern("evp", "EVP_md4", Some("md4"), "symbol"),
    FootprintPattern("evp", "EVP_mdc2", Some("mdc2"), "symbol"),
    FootprintPattern("evp", "EVP_sm3", Some("sm3"), "symbol"),
    FootprintPattern("evp", "EVP_blake2b512", Some("blake2b-512"), "symbol"),
    FootprintPattern("evp", "EVP_blake2s256", Some("blake2s-256"), "symbol"),
    FootprintPattern("evp", "EVP_shake128", Some("shake128"), "symbol"),
    FootprintPattern("evp", "EVP_shake256", Some("shake256"), "symbol"),
    FootprintPattern("evp", "EVP_whirlpool", Some("whirlpool"), "symbol"),
    FootprintPattern("evp", "EVP_aes_128_cbc", Some("aes-128-cbc"), "symbol"),
    FootprintPattern("evp", "EVP_aes_256_cbc", Some("aes-256-cbc"), "symbol"),
    FootprintPattern("evp", "EVP_aes_128_gcm", Some("aes-128-gcm"), "symbol"),
    FootprintPattern("evp", "EVP_aes_256_gcm", Some("aes-256-gcm"), "symbol"),
    FootprintPattern("evp", "EVP_aes_128_ccm", Some("aes-128-ccm"), "symbol"),
    FootprintPattern("evp", "EVP_aes_256_ccm", Some("aes-256-ccm"), "symbol"),
    FootprintPattern(
      "evp",
      "EVP_chacha20_poly1305",
      Some("chacha20-poly1305"),
      "symbol"
    ),
    FootprintPattern("evp", "EVP_rc4", Some("rc4"), "symbol"),
    FootprintPattern("evp", "EVP_des_ede3_cbc", Some("3des"), "symbol"),
    FootprintPattern("evp", "EVP_des_cbc", Some("des"), "symbol"),
    FootprintPattern("evp", "EVP_ecdsa", Some("ecdsa"), "symbol"),
    FootprintPattern("evp", "EVP_ed25519", Some("ed25519"), "symbol"),
    FootprintPattern("evp", "EVP_ed448", Some("ed448"), "symbol"),
    FootprintPattern("evp", "EVP_dh", Some("dh"), "symbol"),
    FootprintPattern("evp", "EVP_ripemd160", Some("ripemd160"), "symbol"),
    // Go standard library package paths
    FootprintPattern("golang", "crypto/md5", Some("md5"), "symbol"),
    FootprintPattern("golang", "crypto/sha1", Some("sha-1"), "symbol"),
    FootprintPattern("golang", "crypto/sha256", Some("sha-256"), "symbol"),
    FootprintPattern("golang", "crypto/sha512", Some("sha-512"), "symbol"),
    FootprintPattern("golang", "crypto/aes", Some("aes"), "symbol"),
    FootprintPattern("golang", "crypto/rsa", Some("rsa"), "symbol"),
    FootprintPattern("golang", "crypto/ecdsa", Some("ecdsa"), "symbol"),
    FootprintPattern("golang", "crypto/ed25519", Some("ed25519"), "symbol"),
    FootprintPattern("golang", "crypto/hmac", Some("hmac"), "symbol"),
    FootprintPattern("golang", "crypto/tls", Some("tls"), "symbol"),
    FootprintPattern(
      "golang",
      "golang.org/x/crypto/curve25519",
      Some("curve25519"),
      "symbol"
    ),
    FootprintPattern(
      "golang",
      "golang.org/x/crypto/ed25519",
      Some("ed25519"),
      "symbol"
    ),
    FootprintPattern(
      "golang",
      "golang.org/x/crypto/chacha20poly1305",
      Some("chacha20-poly1305"),
      "symbol"
    ),
    FootprintPattern(
      "golang",
      "golang.org/x/crypto/hkdf",
      Some("hkdf"),
      "symbol"
    ),
    FootprintPattern(
      "golang",
      "golang.org/x/crypto/sha3",
      Some("sha-3"),
      "symbol"
    ),
    FootprintPattern(
      "golang",
      "golang.org/x/crypto/argon2",
      Some("argon2"),
      "symbol"
    ),
    FootprintPattern(
      "golang",
      "golang.org/x/crypto/bcrypt",
      Some("bcrypt"),
      "symbol"
    ),
    // Rust crate identifiers
    FootprintPattern("rust", "aes-gcm", Some("aes-gcm"), "identifier"),
    FootprintPattern(
      "rust",
      "chacha20poly1305",
      Some("chacha20-poly1305"),
      "identifier"
    ),
    FootprintPattern("rust", " sha2 ", Some("sha-2"), "identifier"),
    FootprintPattern("rust", " sha3 ", Some("sha-3"), "identifier"),
    FootprintPattern("rust", "hkdf", Some("hkdf"), "identifier"),
    FootprintPattern("rust", " ring ", None, "identifier"),
    FootprintPattern("rust", " rustls ", None, "identifier"),
    // .NET System.Security.Cryptography types
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.AesGcm",
      Some("aes-gcm"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.Aes",
      Some("aes"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.ECDsa",
      Some("ecdsa"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.RSA",
      Some("rsa"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.HMACSHA256",
      Some("hmac-sha-256"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.SHA256",
      Some("sha-256"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.SHA384",
      Some("sha-384"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.SHA512",
      Some("sha-512"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.SHA3_256",
      Some("sha3-256"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.SHA3_384",
      Some("sha3-384"),
      "symbol"
    ),
    FootprintPattern(
      "dotnet",
      "System.Security.Cryptography.SHA3_512",
      Some("sha3-512"),
      "symbol"
    )
  )

  /** The closed vocabulary of canonical algorithm names the scanner emits. */
  def knownAlgorithms: Set[String] =
    Patterns.flatMap(_.algorithm).toSet

  /** Identifiers the scanner looks for (for tests / precision checks). */
  private[strategies] def allNeedles: Vector[String] = Patterns.map(_.needle)

  /** Doctrine: a plain substring like "aes" must never mint an asset. */
  private[strategies] def isKnownNeedle(value: String): Boolean =
    Patterns.exists(_.needle == value)

  // ── Scanning ────────────────────────────────────────────────────────────

  private[strategies] final case class FootprintHit(
      classifier: String,
      value: String,
      algorithm: Option[String],
      confidence: String
  )

  private[strategies] def scan(content: String): Vector[FootprintHit] = {
    val hits = Vector.newBuilder[FootprintHit]
    Patterns.foreach { p =>
      if (content.contains(p.needle)) {
        hits += FootprintHit(p.classifier, p.needle, p.algorithm, p.confidence)
      }
    }
    hits.result()
  }

  private[strategies] def detects(content: String): Boolean =
    scan(content).nonEmpty

  private def readBounded(a: ArtifactWrapper, limit: Int): String = {
    Try {
      val bytes = a.withStream { s =>
        val buf = new Array[Byte](limit)
        val n = s.read(buf, 0, limit)
        if (n <= 0) Array.emptyByteArray else java.util.Arrays.copyOf(buf, n)
      }
      new String(bytes, StandardCharsets.ISO_8859_1)
    }.getOrElse("")
  }

  private[strategies] def probeText(a: ArtifactWrapper): String =
    readBounded(a, DetectReadBytes)

  private[strategies] def contentOf(a: ArtifactWrapper): String =
    readBounded(a, MaxReadBytes)

  // ── Pipeline ─────────────────────────────────────────────────────────────

  /** Compute binary artifacts carrying a crypto algorithm footprint. */
  def computeCryptoFootprintFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter { a =>
      a.mimeType.exists(BinaryMimes.contains) &&
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
      mine.map(a => new CryptoFootprintToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "CryptoFootprint"
    )
  }
}

class CryptoFootprintToProcess(val artifact: ArtifactWrapper)
    extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = CryptoFootprintState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new CryptoFootprintState(artifact)
}

class CryptoFootprintState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, CryptoFootprintState] {

  private val adHoc = MKC.adHoc("CryptoAlgorithms")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): CryptoFootprintState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, CryptoFootprintState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], CryptoFootprintState) = {
    val meta = buildMetadata(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, CryptoFootprintState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): CryptoFootprintState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): CryptoFootprintState = this

  private def buildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val text = Try(CryptoFootprintStrategy.contentOf(artifact)).getOrElse("")
    val hits = CryptoFootprintStrategy.scan(text)
    if (hits.isEmpty) TreeMap.empty[String, TreeSet[StringOrPair]]
    else {
      var tm = TreeMap[String, TreeSet[StringOrPair]](
        adHoc("classifier") -> TreeSet.from(
          hits.map(_.classifier).distinct.sorted.map(StringOrPair(_))
        ),
        adHoc("value") -> TreeSet.from(
          hits.map(_.value).distinct.sorted.map(StringOrPair(_))
        ),
        adHoc("confidence") -> TreeSet.from(
          hits.map(_.confidence).distinct.sorted.map(StringOrPair(_))
        )
      )
      val algs = hits.flatMap(_.algorithm).distinct.sorted
      if (algs.nonEmpty) {
        tm = tm + (adHoc("algorithm") -> TreeSet.from(
          algs.map(StringOrPair(_))
        ))
      }
      if (hits.exists(_.algorithm.isEmpty)) {
        tm = tm + (adHoc("unknown") -> TreeSet(StringOrPair("true")))
      }
      tm
    }
  }

  /** Test-accessible alias for buildMetadata. */
  private[strategies] def invokeBuildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = buildMetadata(artifact)
}
