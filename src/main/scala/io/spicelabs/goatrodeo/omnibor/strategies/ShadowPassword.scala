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
    artifact.withStream { stream =>
      val text = new String(Helpers.slurpInput(stream), StandardCharsets.UTF_8)
      text.split("\n").exists { line =>
        line.trim.split(":") match {
          case Array(_, hash, _*)
              if hash.nonEmpty && hashAlgorithm(hash) != "locked" =>
            true
          case _ => false
        }
      }
    }
  }

  /** Map a crypt(3) hash prefix to a human-readable algorithm name. */
  def hashAlgorithm(hash: String): String = {
    if (
      hash == "*" || hash == "!" || hash == "!!" || hash == "x" || hash.isEmpty
    ) {
      "locked"
    } else if (hash.startsWith("$1$")) {
      "md5"
    } else if (
      hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith(
        "$2y$"
      )
    ) {
      "bcrypt"
    } else if (hash.startsWith("$5$")) {
      "sha256"
    } else if (hash.startsWith("$6$")) {
      "sha512"
    } else if (hash.startsWith("$y$")) {
      "yescrypt"
    } else if (hash.startsWith("$7$")) {
      "scrypt"
    } else {
      "other"
    }
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

    if (path.endsWith("/etc/shadow") || path.endsWith("/etc/gshadow")) {
      tm = parseShadow(artifact, tm)
    }

    tm
  }

  private def parseShadow(
      artifact: ArtifactWrapper,
      base: TreeMap[String, TreeSet[StringOrPair]]
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val text = artifact.withStream { stream =>
      new String(Helpers.slurpInput(stream), StandardCharsets.UTF_8)
    }

    text.split("\n").toVector.foldLeft(base) { (tm, line) =>
      line.trim.split(":") match {
        case Array(user, hash, _*)
            if user.nonEmpty && ShadowPasswordStrategy
              .hashAlgorithm(hash) != "locked" =>
          val alg = ShadowPasswordStrategy.hashAlgorithm(hash)
          val withUser = tm.updatedWith(adHoc("User")) {
            case Some(set) => Some(set + StringOrPair(user))
            case None      => Some(TreeSet(StringOrPair(user)))
          }
          val withAlg = withUser.updatedWith(adHoc("Algorithm")) {
            case Some(set) => Some(set + StringOrPair(alg))
            case None      => Some(TreeSet(StringOrPair(alg)))
          }
          withAlg
        case _ => tm
      }
    }
  }
}
