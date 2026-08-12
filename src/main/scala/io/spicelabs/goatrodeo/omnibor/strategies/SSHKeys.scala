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
import io.spicelabs.goatrodeo.util.SshWireReader

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects SSH/Dropbear key files that are not already claimed by the
  * `Certificates` strategy via MIME type. This covers `authorized_keys`, empty
  * or placeholder host keys, and private key files without recognized MIME
  * metadata.
  */
object SSHKeysStrategy {
  private val logger = Logger(this.getClass())

  val SshAlgs = Set(
    "ssh-rsa",
    "ssh-ed25519",
    "ssh-dss",
    "ecdsa-sha2-nistp256",
    "ecdsa-sha2-nistp384",
    "ecdsa-sha2-nistp521",
    "sk-ssh-ed25519@openssh.com",
    "sk-ecdsa-sha2-nistp256@openssh.com"
  )

  /** Compute remaining SSH/Dropbear key files to process at a layer. */
  def computeSSHKeyFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter(isSSHKeyPath).toVector

    val uuids = mine.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new SSHKeyToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "SSHKeys"
    )
  }

  private def isSSHKeyPath(artifact: ArtifactWrapper): Boolean = {
    val path = artifact.path()
    val fileName = path.split('/').lastOption.getOrElse(path)
    val dir = path.split('/').dropRight(1).mkString("/")
    path.endsWith(".ssh/authorized_keys") ||
    (path.contains("etc/ssh/") && fileName == "authorized_keys") ||
    (path.contains("etc/ssh/") && fileName.startsWith("ssh_host_")) ||
    (path.contains("etc/dropbear/") && fileName.endsWith("_host_key")) ||
    (path.contains(".ssh/") && fileName == "authorized_keys") ||
    path.endsWith(".pub")
  }

  def sshAlgorithm(alg: String): String =
    if (SshAlgs.contains(alg)) alg else "other"
}

class SSHKeyToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = SSHKeyState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new SSHKeyState(artifact)
}

class SSHKeyState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, SSHKeyState] {

  private val adHoc = MKC.adHoc("SSH")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): SSHKeyState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, SSHKeyState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], SSHKeyState) = {
    val meta = parseArtifact(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, SSHKeyState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): SSHKeyState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): SSHKeyState = this

  private def parseArtifact(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val path = artifact.path()
    val fileName = path.split('/').lastOption.getOrElse(path)
    val text = Try(artifact.withStream { stream =>
      new String(Helpers.slurpInput(stream), StandardCharsets.UTF_8)
    }).getOrElse("")

    val isPrivate = !path.endsWith(".pub") && !path.endsWith("authorized_keys")
    val isEmpty = text.trim.isEmpty

    if (isPrivate && isEmpty) {
      return TreeMap[String, TreeSet[StringOrPair]](
        MKC.NAME -> TreeSet(StringOrPair(fileName)),
        MKC.DESCRIPTION -> TreeSet(StringOrPair("SSH host key placeholder")),
        adHoc("MaterialType") -> TreeSet(
          StringOrPair("private-key-placeholder")
        ),
        adHoc("FilePath") -> TreeSet(StringOrPair("/" + path))
      )
    }

    if (isPrivate) {
      return TreeMap[String, TreeSet[StringOrPair]](
        MKC.NAME -> TreeSet(StringOrPair(fileName)),
        MKC.DESCRIPTION -> TreeSet(StringOrPair("SSH private key")),
        adHoc("MaterialType") -> TreeSet(StringOrPair("private-key")),
        adHoc("FilePath") -> TreeSet(StringOrPair("/" + path))
      )
    }

    // Public key / authorized_keys file: parse each line for algorithm,
    // key size, curve, and fingerprint.
    val parsed = text.split("\n").toVector.flatMap(parseSshLine)
    val keyTypes = TreeSet.from(parsed.map(_.keyType).map(StringOrPair(_)))
    val keyAlgs = TreeSet.from(parsed.map(_.canonicalAlg).map(StringOrPair(_)))
    val keySizes = TreeSet.from(
      parsed.flatMap(_.keySize).map(s => StringOrPair(s.toString))
    )
    val curves = TreeSet.from(parsed.flatMap(_.curve).map(StringOrPair(_)))
    val fingerprints = TreeSet.from(
      parsed.map(_.fingerprint).map(StringOrPair(_))
    )
    val comments = TreeSet.from(parsed.flatMap(_.comment).map(StringOrPair(_)))

    var tm = TreeMap[String, TreeSet[StringOrPair]](
      MKC.NAME -> TreeSet(StringOrPair(fileName)),
      MKC.DESCRIPTION -> TreeSet(StringOrPair("SSH public key")),
      adHoc("MaterialType") -> TreeSet(StringOrPair("public-key")),
      adHoc("FilePath") -> TreeSet(StringOrPair("/" + path))
    )
    if (keyTypes.nonEmpty) {
      tm = tm + (adHoc("KeyType") -> keyTypes)
    }
    if (keyAlgs.nonEmpty) {
      tm = tm + (adHoc("KeyAlgorithm") -> keyAlgs)
    }
    if (keySizes.nonEmpty) {
      tm = tm + (adHoc("KeySize") -> keySizes)
    }
    if (curves.nonEmpty) {
      tm = tm + (adHoc("Curve") -> curves)
    }
    if (fingerprints.nonEmpty) {
      tm = tm + (adHoc("KeyFingerprintSha256") -> fingerprints)
    }
    if (comments.nonEmpty) {
      tm = tm + (adHoc("Comment") -> comments)
    }
    tm
  }

  private case class ParsedSshKey(
      keyType: String,
      canonicalAlg: String,
      keySize: Option[Int],
      curve: Option[String],
      fingerprint: String,
      comment: Option[String]
  )

  /** Parse a single OpenSSH public-key line. Returns `None` for blank/comment
    * lines or unsupported algorithms.
    */
  private def parseSshLine(line: String): Option[ParsedSshKey] = {
    val trimmed = line.trim
    if (trimmed.isEmpty || trimmed.startsWith("#")) return None
    SshWireReader.parseFirstKeyLine(trimmed).flatMap {
      case (algName, wire, optComment) =>
        if (!SSHKeysStrategy.SshAlgs.contains(algName)) None
        else {
          val algInfo = Certificates.sshAlgMap(algName)
          val canon = algInfo.alg
          val companion = algInfo.data
          val (size, curve) = canon match {
            case "rsa" =>
              val r = SshWireReader(wire)
              val bits = for {
                _ <- r.readMpint()
                n <- r.readMpint()
              } yield SshWireReader.mpintBitLength(n)
              (bits, None)
            case "dsa" =>
              (Some(1024), None)
            case "ec" =>
              val c = companion.flatMap {
                case ("curve", v) => Some(v)
                case _            => None
              }
              (None, c)
            case _ =>
              (None, None)
          }
          val fp = sshFingerprintB64(wire)
          Some(
            ParsedSshKey(
              keyType = algName,
              canonicalAlg = canon,
              keySize = size,
              curve = curve,
              fingerprint = fp,
              comment = optComment
            )
          )
        }
    }
  }

  /** SHA-256 base64-no-padding fingerprint over an SSH wire blob. */
  private def sshFingerprintB64(wire: Array[Byte]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(wire)
    s"SHA-256:${Base64.getEncoder.withoutPadding.encodeToString(digest)}"
  }
}
