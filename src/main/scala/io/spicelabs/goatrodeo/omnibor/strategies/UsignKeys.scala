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
import io.spicelabs.goatrodeo.util.CryptoContentDetector
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects OpenWrt/LEDE `usign` package-signing public keys in `/etc/opkg/keys/
  * *` and similar `signify`/`minisign` style public-key files.
  */
object UsignKeysStrategy {
  private val logger = Logger(this.getClass())

  /** Compute usign key files to process at a layer. */
  def computeUsignKeyFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter { artifact =>
      val path = artifact.path()
      path.contains("/etc/opkg/keys/") ||
      artifact.mimeType.contains(CryptoContentDetector.UsignKeyMime)
    }.toVector

    val uuids = mine.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new UsignKeyToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "UsignKeys"
    )
  }

  /** True when the content looks like a usign/signify public key; used for
    * claiming (via the MIME-augmentation pass) and by tests.
    */
  private[goatrodeo] def detects(text: String): Boolean = {
    text.contains("untrusted comment:") &&
    text.linesIterator.exists(_.startsWith("RW"))
  }

  /** True if the artifact looks like a usign/signify public key by content. */
  private def isUsignKeyFile(artifact: ArtifactWrapper): Boolean = {
    Try {
      artifact.withStream { stream =>
        detects(new String(stream.readNBytes(64), StandardCharsets.UTF_8))
      }
    }.getOrElse(false)
  }
}

class UsignKeyToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = UsignKeyState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new UsignKeyState(artifact)
}

class UsignKeyState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, UsignKeyState] {

  private val adHoc = MKC.adHoc("Usign")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): UsignKeyState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, UsignKeyState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], UsignKeyState) = {
    val meta = parseArtifact(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, UsignKeyState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): UsignKeyState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): UsignKeyState = this

  private def parseArtifact(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val path = artifact.path()
    val keyId = path.split('/').lastOption.getOrElse(path)

    val text = Try(artifact.withStream { stream =>
      new String(Helpers.slurpInput(stream), StandardCharsets.UTF_8)
    }).getOrElse("")
    val lines = text.linesIterator.toVector
    val comment = lines
      .find(_.startsWith("untrusted comment:"))
      .map(_.stripPrefix("untrusted comment:").trim)

    val keyLine =
      lines.find(l => l.trim.nonEmpty && !l.startsWith("untrusted comment:"))
    val parsedKey = keyLine.flatMap(decodeUsignKey)

    var tm = TreeMap[String, TreeSet[StringOrPair]](
      MKC.NAME -> TreeSet(StringOrPair(keyId)),
      MKC.DESCRIPTION -> TreeSet(StringOrPair("OpenWrt usign public key")),
      adHoc("KeyId") -> TreeSet(StringOrPair(keyId)),
      adHoc("KeyAlgorithm") -> TreeSet(StringOrPair("ed25519")),
      adHoc("KeySize") -> TreeSet(StringOrPair("256")),
      adHoc("KeyFormat") -> TreeSet(StringOrPair("usign")),
      adHoc("FilePath") -> TreeSet(StringOrPair("/" + path))
    )
    parsedKey.foreach { case (fp, _) =>
      tm = tm + (adHoc("KeyFingerprint") -> TreeSet(StringOrPair(fp)))
    }
    comment.foreach { c =>
      tm = tm + (adHoc("Comment") -> TreeSet(StringOrPair(c)))
    }
    tm
  }

  /** Decode a usign/signify public-key line. The raw key is 32 bytes for
    * Ed25519; returns its SHA-256 hex fingerprint on success.
    */
  private def decodeUsignKey(line: String): Option[(String, Array[Byte])] = {
    Try {
      val raw = Base64.getDecoder.decode(line.trim)
      if (raw.length == 32) {
        val fp = Helpers.sha256Hex(raw)
        Some(fp -> raw)
      } else None
    }.toOption.flatten
  }
}
