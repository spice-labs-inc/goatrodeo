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

import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects Unix password hash files and emits metadata for each hash found.
  *
  * Handles `/etc/shadow`, `/etc/gshadow`, `/etc/passwd`, and `/etc/group`. Only
  * `/etc/shadow`-style files contain password hashes; the others are included
  * as identity files so they appear in the inventory but carry no hash entries.
  */
object ShadowPasswordStrategy {
  private val logger = Logger(this.getClass())

  /** Compute shadow/password files to process at a layer. */
  def computeShadowPasswordFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter { artifact =>
      val path = artifact.path()
      val isPasswordFile =
        path.endsWith("etc/shadow") ||
          path.endsWith("etc/gshadow") ||
          path.endsWith("etc/passwd") ||
          path.endsWith("etc/group")
      isPasswordFile && hasPasswordHash(artifact)
    }.toVector

    val uuids = mine.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new ShadowPasswordToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "ShadowPassword"
    )
  }

  /** True if the artifact contains at least one non-empty, non-locked password
    * hash. This avoids emitting placeholder files (e.g. `/etc/passwd` and
    * `/etc/group` that only contain `x` or `*`) as cryptographic assets.
    */
  /** True if the artifact contains at least one non-empty, non-locked password
    * hash. This avoids emitting placeholder files (e.g. `/etc/passwd` and
    * `/etc/group` that only contain `x` or `*`) as cryptographic assets.
    */
  private def hasPasswordHash(artifact: ArtifactWrapper): Boolean = {
    Try(artifact.withStream { stream =>
      val text = new String(Helpers.slurpInput(stream), StandardCharsets.UTF_8)
      text.split("\n").exists { line =>
        line.trim.split(":") match {
          case Array(_, hash, _*)
              if hash.nonEmpty && hashAlgorithm(hash) != "locked" =>
            true
          case _ => false
        }
      }
    }).getOrElse(false)
  }

  /** Map a crypt(3) hash prefix to a human-readable algorithm name. */
  def hashAlgorithm(hash: String): String = hashDetails(hash).algorithm

  /** Parsed crypt(3) envelope details. */
  final case class HashDetails(
      algorithm: String,
      cost: Option[String] = None,
      params: Option[String] = None,
      salt: Option[String] = None
  )

  /** Parse a crypt(3) hash into algorithm, cost/params, and salt.
    *
    * Supported prefixes:
    *   - `$1$` MD5 (no cost)
    *   - `$2a$`, `$2b$`, `$2y$` bcrypt (cost is the decimal rounds field)
    *   - `$5$` SHA-256 (no cost)
    *   - `$6$` SHA-512 (no cost)
    *   - `$y$` yescrypt (params string, e.g. `j9s`)
    *   - `$7$` scrypt (`N=2^N,r,p` as params)
    */
  def hashDetails(hash: String): HashDetails = {
    if (
      hash == "*" || hash == "!" || hash == "!!" || hash == "x" || hash.isEmpty
    ) {
      HashDetails("locked")
    } else if (hash.startsWith("$1$")) {
      HashDetails("md5", salt = extractSalt(hash, 1))
    } else if (
      hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith(
        "$2y$"
      )
    ) {
      val cost = extractField(hash, 2)
      HashDetails("bcrypt", cost = cost, salt = extractSalt(hash, 2))
    } else if (hash.startsWith("$5$")) {
      HashDetails("sha256", salt = extractSalt(hash, 1))
    } else if (hash.startsWith("$6$")) {
      HashDetails("sha512", salt = extractSalt(hash, 1))
    } else if (hash.startsWith("$y$")) {
      HashDetails(
        "yescrypt",
        params = extractField(hash, 2),
        salt = extractSalt(hash, 2)
      )
    } else if (hash.startsWith("$7$")) {
      HashDetails(
        "scrypt",
        params = extractField(hash, 2),
        salt = extractSalt(hash, 2)
      )
    } else {
      HashDetails("other")
    }
  }

  /** Extract the field at the given dollar-delimited position (1-based). */
  private def extractField(hash: String, idx: Int): Option[String] = {
    val parts = hash.split("\\$")
    if (parts.length > idx) Some(parts(idx)) else None
  }

  /** Extract the salt from a crypt(3) hash. The salt is the field immediately
    * preceding the final hash value for most algorithms. For bcrypt the salt
    * and hash are combined in the final field, so that field is returned as the
    * salt.
    */
  private def extractSalt(hash: String, paramFields: Int): Option[String] = {
    val parts = hash.split("\\$").filter(_.nonEmpty)
    if (parts.length < 2) None
    else if (hash.startsWith("$2")) Some(parts.last)
    else if (parts.length >= 3) Some(parts(parts.length - 2))
    else None
  }
}

class ShadowPasswordToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = ShadowPasswordState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new ShadowPasswordState(artifact)
}

class ShadowPasswordState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, ShadowPasswordState] {

  private val adHoc = MKC.adHoc("PasswordHash")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): ShadowPasswordState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, ShadowPasswordState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], ShadowPasswordState) = {
    val meta = parseArtifact(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, ShadowPasswordState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): ShadowPasswordState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): ShadowPasswordState = this

  private def parseArtifact(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val path = artifact.path()
    val fileName = path.split('/').lastOption.getOrElse(path)

    var tm = TreeMap[String, TreeSet[StringOrPair]](
      MKC.NAME -> TreeSet(StringOrPair(fileName)),
      MKC.DESCRIPTION -> TreeSet(StringOrPair("Password hash file")),
      adHoc("FilePath") -> TreeSet(StringOrPair("/" + path))
    )

    if (path.endsWith("etc/shadow") || path.endsWith("etc/gshadow")) {
      tm = parseShadow(artifact, tm)
    }

    tm
  }

  private def parseShadow(
      artifact: ArtifactWrapper,
      base: TreeMap[String, TreeSet[StringOrPair]]
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val text = Try(artifact.withStream { stream =>
      new String(Helpers.slurpInput(stream), StandardCharsets.UTF_8)
    }).getOrElse("")

    text.split("\n").toVector.foldLeft(base) { (tm, line) =>
      line.trim.split(":") match {
        case Array(user, hash, _*)
            if user.nonEmpty && ShadowPasswordStrategy
              .hashAlgorithm(hash) != "locked" =>
          val details = ShadowPasswordStrategy.hashDetails(hash)
          val withUser = tm.updatedWith(adHoc("User")) {
            case Some(set) => Some(set + StringOrPair(user))
            case None      => Some(TreeSet(StringOrPair(user)))
          }
          val withAlg = withUser.updatedWith(adHoc("Algorithm")) {
            case Some(set) => Some(set + StringOrPair(details.algorithm))
            case None      => Some(TreeSet(StringOrPair(details.algorithm)))
          }
          val withCost = details.cost.fold(withAlg)(c =>
            withAlg.updatedWith(adHoc("Cost")) {
              case Some(set) => Some(set + StringOrPair(c))
              case None      => Some(TreeSet(StringOrPair(c)))
            }
          )
          val withParams = details.params.fold(withCost)(p =>
            withCost.updatedWith(adHoc("Params")) {
              case Some(set) => Some(set + StringOrPair(p))
              case None      => Some(TreeSet(StringOrPair(p)))
            }
          )
          val withSalt = details.salt.fold(withParams)(s =>
            withParams.updatedWith(adHoc("Salt")) {
              case Some(set) => Some(set + StringOrPair(s))
              case None      => Some(TreeSet(StringOrPair(s)))
            }
          )
          withSalt
        case _ => tm
      }
    }
  }
}
