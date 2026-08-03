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
import io.spicelabs.goatrodeo.util.FileWrapper
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.json4s.native.Printer

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/** One-shot materializer: replaces `<computed in Phase N>` placeholders in
  * pem-bundle, CRL, and SSH sidecars with the actual canonical pURL strings
  * (and metadata values) the Certificates strategy emits.
  *
  * Run via: `sbt "Test/runMain
  * io.spicelabs.goatrodeo.omnibor.strategies.MaterializeSidecars"`
  *
  * Equivalent to Phase 0b's `cert_sidecar.py` canonicalization step but sourced
  * from the strategy's own emitters so the sidecars match what the strategy
  * produces by construction.
  *
  * Originally `MaterializePhase4Sidecars`; renamed in Phase 5 (gap G8) once it
  * grew to cover SSH. Will continue to grow with Phase 6 (PGP) and Phase 7
  * (private keys).
  */
object MaterializeSidecars {

  private val logger = Logger(getClass)

  private val corpusRoot: Path = Paths.get("test_data/certificates")

  private def wrap(file: File): FileWrapper =
    FileWrapper(file, file.getName, None)

  private def materializeBundle(fixture: File): Option[Vector[String]] = {
    val bundle = Certificates.parseBundle(wrap(fixture))
    bundle.map { b =>
      b.certs.flatMap(Certificates.purlsForCert).map(_.toCanonical())
    }
  }

  private def materializeCrl(fixture: File): Option[Vector[String]] = {
    val w = wrap(fixture)
    Certificates.parseCrl(w).map { c =>
      val state = new CertificatesState(w)
      Vector(state.purlForCrl(c.crl).toCanonical())
    }
  }

  /** Decide pubkey vs cert from the file's first token. */
  private def materializeSsh(fixture: File): Option[Vector[String]] = {
    sshClaim(fixture).map { case (state, content) =>
      content match {
        case p: Certificates.SshPubkey =>
          Vector(state.purlForSshPubkey(p).toCanonical())
        case c: Certificates.SshCert =>
          state.purlsForSshCert(c).map(_.toCanonical())
        case _ => Vector.empty
      }
    }
  }

  /** Parse the SSH fixture and return its claim wrapped in a state for
    * downstream metadata extraction.
    */
  private def sshClaim(
      fixture: File
  ): Option[(CertificatesState, Certificates.ClaimedContent)] = {
    val w = wrap(fixture)
    val state = new CertificatesState(w)
    val firstLine = scala.util
      .Try {
        val src = scala.io.Source.fromFile(fixture, "UTF-8")
        try src.getLines().find(_.trim.nonEmpty).getOrElse("")
        finally src.close()
      }
      .getOrElse("")
    val firstToken = firstLine.trim.split("\\s+", 2).headOption.getOrElse("")
    if (firstToken.endsWith("-cert-v01@openssh.com")) {
      Certificates.parseSshCert(w).map(c => state -> c)
    } else {
      Certificates.parseSshPubkey(w).map(p => state -> p)
    }
  }

  /** For each metadata field whose value is a `<computed>` or stale
    * `StringOf(…)` placeholder, substitute from the supplied emitted map. Other
    * metadata entries are left alone.
    */
  private def patchMetadataPlaceholders(
      sidecar: JValue,
      emitted: Map[String, String]
  ): JValue = {
    sidecar.transformField { case ("metadata", JObject(mfields)) =>
      "metadata" -> JObject(mfields.map {
        case ("mustContain", JObject(kvs)) =>
          "mustContain" -> JObject(kvs.map {
            case (k, JString(v))
                if v.startsWith("<computed") || v.startsWith("StringOf(") =>
              val replacement = emitted.getOrElse(k, v)
              k -> JString(replacement)
            case other => other
          })
        case other => other
      })
    }
  }

  /** Run the SSH metadata builder and project to a Map[String,String]. */
  private def materializeSshMetadata(
      fixture: File,
      sidecar: JValue
  ): JValue = {
    sshClaim(fixture) match {
      case None => sidecar
      case Some((state, content)) =>
        val tm = content match {
          case p: Certificates.SshPubkey =>
            state.invokeSshPubkeyMetadata(wrap(fixture), p)
          case c: Certificates.SshCert =>
            state.invokeSshCertMetadata(wrap(fixture), c)
          case _ =>
            scala.collection.immutable.TreeMap
              .empty[String, scala.collection.immutable.TreeSet[
                io.spicelabs.goatrodeo.omnibor.StringOrPair
              ]]
        }
        val emitted = tm.iterator.flatMap { case (k, vs) =>
          vs.headOption.map(v => k -> v.value)
        }.toMap
        patchMetadataPlaceholders(sidecar, emitted)
    }
  }

  // ---------- Phase-7: private-key materialization ---------------------

  private def materializePrivateKey(fixture: File): Option[Vector[String]] = {
    val w = wrap(fixture)
    Certificates.classifyAndParse(w).map {
      case p: Certificates.PrivateKeyPlaintextPem =>
        val state = new CertificatesState(w, Some(p))
        Vector(state.purlForPrivateKeyPem(p).toCanonical())
      case p: Certificates.PrivateKeyPlaintextOpenSsh =>
        val state = new CertificatesState(w, Some(p))
        Vector(state.purlForPrivateKeyOpenSsh(p).toCanonical())
      case _: Certificates.PrivateKeyEncrypted =>
        Vector.empty // envelope-only, no pURL
      case _ => Vector.empty
    }
  }

  private def materializePrivateKeyMetadata(
      fixture: File,
      sidecar: JValue
  ): JValue = {
    val w = wrap(fixture)
    Certificates.classifyAndParse(w) match {
      case None => sidecar
      case Some(claim) =>
        val state = new CertificatesState(w, Some(claim))
        val tm = claim match {
          case p: Certificates.PrivateKeyPlaintextPem =>
            state.privateKeyPemMetadata(w, p)
          case p: Certificates.PrivateKeyPlaintextOpenSsh =>
            state.privateKeyOpenSshMetadata(w, p)
          case p: Certificates.PrivateKeyEncrypted =>
            state.privateKeyEncryptedMetadata(w, p)
          case _ =>
            scala.collection.immutable.TreeMap
              .empty[String, scala.collection.immutable.TreeSet[
                io.spicelabs.goatrodeo.omnibor.StringOrPair
              ]]
        }
        val emitted = tm.iterator.flatMap { case (k, vs) =>
          vs.headOption.map(v => k -> v.value)
        }.toMap
        patchMetadataPlaceholders(sidecar, emitted)
    }
  }

  // ---------- Phase-6: PGP materialization -----------------------------

  private def materializePgp(fixture: File): Option[Vector[String]] = {
    val w = wrap(fixture)
    Certificates.parsePgpKeyRing(w).map { ring =>
      ring.keys.map(k => Certificates.purlForPgpKey(k).toCanonical())
    }
  }

  private def materializePgpMetadata(
      fixture: File,
      sidecar: JValue
  ): JValue = {
    val w = wrap(fixture)
    Certificates.parsePgpKeyRing(w) match {
      case None => sidecar
      case Some(ring) =>
        val state = new CertificatesState(w)
        val tm = state.invokePgpKeyRingMetadata(w, ring)
        val emitted = tm.iterator.flatMap { case (k, vs) =>
          vs.headOption.map(v => k -> v.value)
        }.toMap
        patchMetadataPlaceholders(sidecar, emitted)
    }
  }

  private def updateSidecar(
      sidecarPath: Path,
      compute: File => Option[Vector[String]],
      metadataPatch: Option[(File, JValue) => JValue] = None
  ): Boolean = {
    val fixturePath = sidecarPath.toString.stripSuffix(".expected.json")
    val fixture = new File(fixturePath)
    if (!fixture.exists()) return false
    val raw =
      new String(Files.readAllBytes(sidecarPath), StandardCharsets.UTF_8)
    val json = parse(raw)
    compute(fixture) match {
      case None =>
        logger.info(s"SKIP (parse failed): $sidecarPath")
        false
      case Some(purls) =>
        val replacement = JArray(purls.map(JString.apply).toList)
        val withPurls = json.transformField { case ("purls", JObject(fields)) =>
          "purls" -> JObject(fields.map {
            case ("mustContain", _) => "mustContain" -> replacement
            case other              => other
          })
        }
        val updated = metadataPatch match {
          case Some(fn) => fn(fixture, withPurls)
          case None     => withPurls
        }
        val pretty = Printer.pretty(render(updated)) + "\n"
        Files.write(sidecarPath, pretty.getBytes(StandardCharsets.UTF_8))
        logger.info(s"updated: $sidecarPath  (${purls.size} pURLs)")
        true
    }
  }

  private def walkSidecars(root: Path): Seq[Path] = {
    if (!Files.exists(root)) return Seq.empty
    val all = Files.walk(root).iterator().asScala.toSeq
    all.filter(p => p.toString.endsWith(".expected.json"))
  }

  def main(args: Array[String]): Unit = {
    val bundlesRoot = corpusRoot.resolve("pem-bundles")
    val crlsRoot = corpusRoot.resolve("crls")
    val sshRoot = corpusRoot.resolve("ssh")
    val pgpRoot = corpusRoot.resolve("pgp")
    val privateKeyRoot = corpusRoot.resolve("private-keys")
    var bundleHits = 0
    var crlHits = 0
    var sshHits = 0
    var pgpHits = 0
    var pkHits = 0
    walkSidecars(bundlesRoot).foreach { sc =>
      if (updateSidecar(sc, materializeBundle)) bundleHits += 1
    }
    walkSidecars(crlsRoot).foreach { sc =>
      if (updateSidecar(sc, materializeCrl)) crlHits += 1
    }
    walkSidecars(sshRoot).foreach { sc =>
      if (updateSidecar(sc, materializeSsh, Some(materializeSshMetadata)))
        sshHits += 1
    }
    walkSidecars(pgpRoot).foreach { sc =>
      if (updateSidecar(sc, materializePgp, Some(materializePgpMetadata)))
        pgpHits += 1
    }
    walkSidecars(privateKeyRoot).foreach { sc =>
      if (
        updateSidecar(
          sc,
          materializePrivateKey,
          Some(materializePrivateKeyMetadata)
        )
      ) pkHits += 1
    }
    logger.info(s"\nbundles updated: $bundleHits")
    logger.info(s"crls updated:    $crlHits")
    logger.info(s"ssh updated:     $sshHits")
    logger.info(s"pgp updated:     $pgpHits")
    logger.info(s"pk updated:      $pkHits")
  }
}
