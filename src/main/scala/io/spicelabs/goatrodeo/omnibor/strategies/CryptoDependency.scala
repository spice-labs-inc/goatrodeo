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
import org.json4s.*
import org.json4s.native.JsonMethods.parse

import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Inventories cryptography-related dependencies from lockfiles.
  *
  * Claims `Cargo.lock`, `package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`,
  * `go.sum`, and `requirements.txt` (excluding Gradle lockfiles, owned by the
  * Gradle strategy) and emits `CryptoDependency:` metadata for every dependency
  * that maps to a crypto library: `ecosystem`, `name`, `version` (a set),
  * `algorithms` (canonical family set), and `mapped=false` for recognized
  * crypto libraries with no canonical family.
  *
  * Non-crypto dependencies are not emitted (precision). The family mapping is a
  * curated table; no algorithm is invented for an unmapped library.
  */
object CryptoDependencyStrategy {
  private val logger = Logger(getClass())

  val MaxReadBytes: Int = 1024 * 1024

  private[strategies] def detectEcosystem(path: String): Option[String] = {
    val fileName = path.split('/').lastOption.getOrElse(path)
    if (fileName == "Cargo.lock") Some("cargo")
    else if (fileName == "package-lock.json") Some("npm")
    else if (fileName == "yarn.lock") Some("yarn")
    else if (fileName == "pnpm-lock.yaml") Some("pnpm")
    else if (fileName == "go.sum") Some("go")
    else if (fileName == "requirements.txt") Some("pip")
    else None
  }

  // Crypto library → canonical algorithm families.
  private[strategies] val FamilyTable: Map[String, Vector[String]] = Map(
    "ring" -> Vector("aead", "signature"),
    "rustls" -> Vector("tls", "aead", "signature"),
    "aes-gcm" -> Vector("aead"),
    "chacha20poly1305" -> Vector("aead"),
    "sha2" -> Vector("hash"),
    "sha3" -> Vector("hash"),
    "blake3" -> Vector("hash"),
    "hkdf" -> Vector("kdf"),
    "hmac" -> Vector("mac"),
    "argon2" -> Vector("kdf"),
    "bcrypt" -> Vector("kdf"),
    "scrypt" -> Vector("kdf"),
    "curve25519-dalek" -> Vector("key-agree"),
    "x25519-dalek" -> Vector("key-agree"),
    "ed25519-dalek" -> Vector("signature"),
    "p256" -> Vector("signature", "key-agree"),
    "p384" -> Vector("signature", "key-agree"),
    "jsonwebtoken" -> Vector("signature", "mac"),
    "crypto-js" -> Vector("hash", "block-cipher"),
    "node-forge" -> Vector("pke", "block-cipher"),
    "bcryptjs" -> Vector("kdf"),
    "js-sha256" -> Vector("hash"),
    "tweetnacl" -> Vector("signature", "aead"),
    "libsodium-wrappers" -> Vector("aead", "signature"),
    "golang.org/x/crypto" -> Vector("hash", "key-agree", "kdf"),
    "cryptography" -> Vector("pke", "block-cipher", "hash"),
    "pycryptodome" -> Vector("block-cipher", "hash"),
    "argon2-cffi" -> Vector("kdf"),
    "passlib" -> Vector("kdf"),
    "paramiko" -> Vector("pke", "signature"),
    "pyjwt" -> Vector("signature", "mac")
  )

  // Recognized crypto libraries with no canonical family (kept, mapped=false).
  private[strategies] val KnownCryptoUnmapped: Set[String] = Set(
    "webpki",
    "openssl-sys",
    "nettle",
    "botan",
    "crypto-random-string"
  )

  /** Known crypto package name union (for totality checks). */
  def knownCryptoPackages: Set[String] =
    FamilyTable.keySet ++ KnownCryptoUnmapped

  /** Closed enum of canonical algorithm families the table may emit. */
  def allowedFamilies: Set[String] = Set(
    "hash",
    "mac",
    "kdf",
    "block-cipher",
    "stream-cipher",
    "aead",
    "pke",
    "signature",
    "key-agree",
    "kem",
    "tls"
  )

  // ── Lockfile parsing ────────────────────────────────────────────────────

  private[strategies] def dependenciesFor(
      ecosystem: String,
      text: String
  ): Vector[(String, String)] = ecosystem match {
    case "cargo" => parseCargoLock(text)
    case "npm"   => parseNpmLock(text)
    case "yarn"  => parseYarnLock(text)
    case "pnpm"  => parseYarnLock(text)
    case "go"    => parseGoSum(text)
    case "pip"   => parseRequirements(text)
    case _       => Vector.empty
  }

  private def parseCargoLock(text: String): Vector[(String, String)] = {
    val out = Vector.newBuilder[(String, String)]
    var name: Option[String] = None
    var version: Option[String] = None
    text.linesIterator.foreach { line =>
      val t = line.trim
      if (t == "[[package]]") {
        name.foreach(n => version.foreach(v => out += ((n, v))))
        name = None
        version = None
      } else if (t.startsWith("name = ")) {
        name =
          Some(t.stripPrefix("name = ").stripPrefix("\"").stripSuffix("\""))
      } else if (t.startsWith("version = ")) {
        version =
          Some(t.stripPrefix("version = ").stripPrefix("\"").stripSuffix("\""))
      }
    }
    name.foreach(n => version.foreach(v => out += ((n, v))))
    out.result()
  }

  private def parseNpmLock(text: String): Vector[(String, String)] =
    Try(parse(text)).toOption match {
      case Some(JObject(fields)) =>
        fields.flatMap {
          case ("dependencies", JObject(deps)) =>
            deps.flatMap {
              case (k, JObject(v)) =>
                v.collectFirst { case ("version", JString(s)) =>
                  (k, s)
                }.toVector
              case _ => Vector.empty
            }.toVector
          case ("packages", JObject(pkgs)) =>
            pkgs.flatMap {
              case (k, JObject(v)) =>
                v.collectFirst { case ("version", JString(s)) =>
                  (k.stripPrefix("node_modules/").split('/').head, s)
                }.toVector
              case _ => Vector.empty
            }.toVector
          case _ => Vector.empty
        }.toVector
      case _ => Vector.empty
    }

  /** Best-effort yarn v1 lockfile entries (` "name@spec":` / ` version "x"`).
    */
  private def parseYarnLock(text: String): Vector[(String, String)] = {
    val nameSpec = """^\\s{2}\"?([^\":@]+)(?:@[^\":]*)?@?[^\":]*\"?\\s*:$""".r
    val versionRe = """^\\s{2,4}version \"?([^\"\\s]+)\"?""".r
    val out = Vector.newBuilder[(String, String)]
    var pending: Option[String] = None
    text.linesIterator.foreach { line =>
      line match {
        case nameSpec(n) => pending = Some(Option(n).getOrElse("").trim)
        case versionRe(v) if pending.isDefined =>
          out += ((pending.get, Option(v).getOrElse("")))
          pending = None
        case _ =>
      }
    }
    out.result()
  }

  private def parseGoSum(text: String): Vector[(String, String)] = {
    text.linesIterator.toVector.flatMap { line =>
      val parts = line.trim.split("\\s+")
      if (parts.length >= 2) Some(parts(0) -> parts(1)) else None
    }
  }

  private def parseRequirements(text: String): Vector[(String, String)] = {
    text.linesIterator.toVector.flatMap { line =>
      val t = line.trim
      if (t.isEmpty || t.startsWith("#") || t.startsWith("-")) None
      else {
        val marker =
          Array("==", ">=", "<=", "~=", "!=").find(e => t.contains(e))
        marker match {
          case Some(e) =>
            val idx = t.indexOf(e)
            val name = t.substring(0, idx).trim
            val ver = t.substring(idx + e.length).trim
            if (name.isEmpty) None else Some(name -> ver)
          case None => Some(t -> "")
        }
      }
    }
  }

  private[strategies] def contentOf(a: ArtifactWrapper): String = {
    Try {
      val bytes = a.withStream { s =>
        val buf = new Array[Byte](MaxReadBytes)
        val n = s.read(buf, 0, MaxReadBytes)
        if (n <= 0) Array.emptyByteArray else java.util.Arrays.copyOf(buf, n)
      }
      new String(bytes, StandardCharsets.ISO_8859_1)
    }.getOrElse("")
  }

  /** Compute lockfiles to process at a layer (before GenericFile). */
  def computeCryptoDependencyFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine =
      byUUID.values.filter(a => detectEcosystem(a.path()).isDefined).toVector
    val uuids = mine.map(_.uuid).toSet
    (
      mine.map(a => new CryptoDependencyToProcess(a)).toVector,
      byUUID.filter { case (u, _) => !uuids.contains(u) },
      byName.filter { case (_, as) => !as.exists(a => uuids.contains(a.uuid)) },
      "CryptoDependency"
    )
  }
}

class CryptoDependencyToProcess(val artifact: ArtifactWrapper)
    extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = CryptoDependencyState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new CryptoDependencyState(artifact)
}

class CryptoDependencyState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, CryptoDependencyState] {

  private val adHoc = MKC.adHoc("CryptoDependency")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): CryptoDependencyState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, CryptoDependencyState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], CryptoDependencyState) = {
    val ret = buildMetadata(artifact)
    ret -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, CryptoDependencyState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): CryptoDependencyState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): CryptoDependencyState = this

  private def buildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val path = artifact.path()
    val eco = CryptoDependencyStrategy.detectEcosystem(path)
    val text = Try(CryptoDependencyStrategy.contentOf(artifact)).getOrElse("")

    val deps = eco.toVector
      .flatMap(e => CryptoDependencyStrategy.dependenciesFor(e, text))
      .groupBy(_._1)

    val cryptoDeps = deps.filter { case (name, _) =>
      CryptoDependencyStrategy.knownCryptoPackages.contains(name)
    }

    if (cryptoDeps.isEmpty) TreeMap.empty[String, TreeSet[StringOrPair]]
    else {
      val names =
        TreeSet.from(cryptoDeps.keys.toVector.sorted.map(StringOrPair(_)))
      val versions = TreeSet.from(
        cryptoDeps.values.flatten
          .map(_._2)
          .filter(_.nonEmpty)
          .map(StringOrPair(_))
      )
      val ecosystems = eco.toVector.map(StringOrPair(_))
      val allFamilies = cryptoDeps.keys.toVector
        .flatMap(n =>
          CryptoDependencyStrategy.FamilyTable.get(n).getOrElse(Vector.empty)
        )
        .distinct
        .sorted
      val hasUnmapped = cryptoDeps.keys.exists(
        CryptoDependencyStrategy.KnownCryptoUnmapped.contains
      )

      var tm = TreeMap[String, TreeSet[StringOrPair]](
        adHoc("name") -> names
      )
      if (versions.nonEmpty) {
        tm = tm + (adHoc("version") -> versions)
      }
      if (ecosystems.nonEmpty) {
        tm = tm + (adHoc("ecosystem") -> TreeSet.from(ecosystems))
      }
      if (allFamilies.nonEmpty) {
        tm = tm + (adHoc("algorithms") -> TreeSet.from(
          allFamilies.map(StringOrPair(_))
        ))
      }
      if (hasUnmapped) {
        tm = tm + (adHoc("mapped") -> TreeSet(StringOrPair("false")))
      }
      tm
    }
  }

  /** Test-accessible alias for buildMetadata. */
  private[strategies] def invokeBuildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = buildMetadata(artifact)
}
