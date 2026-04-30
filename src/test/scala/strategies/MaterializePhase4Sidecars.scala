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

import io.spicelabs.goatrodeo.util.FileWrapper
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.json4s.native.Printer

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** One-shot materializer: replaces `<computed in Phase 4>` placeholders in
  * pem-bundle and CRL sidecars with the actual canonical pURL strings the
  * Certificates strategy emits. Run via:
  *
  * `sbt "Test/runMain strategies.MaterializePhase4Sidecars"`
  *
  * Equivalent to Phase 0b's `cert_sidecar.py` canonicalization step but
  * sourced from the strategy's own emitters so the sidecars match what
  * the strategy produces by construction. */
object MaterializePhase4Sidecars {

  private val corpusRoot: Path = Paths.get("test_data/certificates")

  private def wrap(file: File): FileWrapper =
    FileWrapper(file, file.getName, None)

  private def materializeBundle(fixture: File): Option[Vector[String]] = {
    val bundle = Certificates.parseBundle(wrap(fixture))
    bundle.map { b =>
      b.certs.flatMap(Certificates.purlsForCert).map(_.canonicalize().nn)
    }
  }

  private def materializeCrl(fixture: File): Option[Vector[String]] = {
    val w = wrap(fixture)
    Certificates.parseCrl(w).map { c =>
      val state = new CertificatesState(w)
      Vector(state.purlForCrl(c.crl).canonicalize().nn)
    }
  }

  /** Decide pubkey vs cert from the file's first token. */
  private def materializeSsh(fixture: File): Option[Vector[String]] = {
    sshClaim(fixture).map { case (state, content) =>
      content match {
        case p: Certificates.SshPubkey =>
          Vector(state.purlForSshPubkey(p).canonicalize().nn)
        case c: Certificates.SshCert =>
          state.purlsForSshCert(c).map(_.canonicalize().nn)
        case _ => Vector.empty
      }
    }
  }

  /** Parse the SSH fixture and return its claim wrapped in a state for
    * downstream metadata extraction. */
  private def sshClaim(
      fixture: File,
  ): Option[(CertificatesState, Certificates.ClaimedContent)] = {
    val w = wrap(fixture)
    val state = new CertificatesState(w)
    val firstLine = scala.util.Try {
      val src = scala.io.Source.fromFile(fixture, "UTF-8")
      try src.getLines().find(_.trim.nonEmpty).getOrElse("")
      finally src.close()
    }.getOrElse("")
    val firstToken = firstLine.trim.split("\\s+", 2).headOption.getOrElse("")
    if (firstToken.endsWith("-cert-v01@openssh.com")) {
      Certificates.parseSshCert(w).map(c => state -> c)
    } else {
      Certificates.parseSshPubkey(w).map(p => state -> p)
    }
  }

  /** For each metadata field whose value is the placeholder
    * `<computed in Phase 5>`, run the strategy to compute the real value
    * and substitute. Other metadata entries are left alone. */
  private def materializeSshMetadata(
      fixture: File,
      sidecar: JValue,
  ): JValue = {
    sshClaim(fixture) match {
      case None => sidecar
      case Some((state, content)) =>
        val emitted: Map[String, String] = {
          val tm = content match {
            case p: Certificates.SshPubkey =>
              state.invokeSshPubkeyMetadata(wrap(fixture), p)
            case c: Certificates.SshCert =>
              state.invokeSshCertMetadata(wrap(fixture), c)
            case _ => scala.collection.immutable.TreeMap.empty[String, scala.collection.immutable.TreeSet[io.spicelabs.goatrodeo.omnibor.StringOrPair]]
          }
          tm.iterator.flatMap { case (k, vs) =>
            vs.headOption.map(v => k -> v.value)
          }.toMap
        }
        sidecar.transformField {
          case ("metadata", JObject(mfields)) =>
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
  }

  private def updateSidecar(
      sidecarPath: Path,
      compute: File => Option[Vector[String]],
      metadataPatch: Option[(File, JValue) => JValue] = None,
  ): Boolean = {
    val sidecarFile = sidecarPath.toFile
    val fixturePath = sidecarPath.toString.stripSuffix(".expected.json")
    val fixture = new File(fixturePath)
    if (!fixture.exists()) return false
    val raw = new String(Files.readAllBytes(sidecarPath), StandardCharsets.UTF_8)
    val json = parse(raw)
    compute(fixture) match {
      case None =>
        println(s"SKIP (parse failed): $sidecarPath")
        false
      case Some(purls) =>
        val replacement = JArray(purls.map(JString.apply).toList)
        val withPurls = json.transformField {
          case ("purls", JObject(fields)) =>
            "purls" -> JObject(fields.map {
              case ("mustContain", _) => "mustContain" -> replacement
              case other => other
            })
        }
        val updated = metadataPatch match {
          case Some(fn) => fn(fixture, withPurls)
          case None => withPurls
        }
        val pretty = Printer.pretty(render(updated)) + "\n"
        Files.write(sidecarPath, pretty.getBytes(StandardCharsets.UTF_8))
        println(s"updated: $sidecarPath  (${purls.size} pURLs)")
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
    var bundleHits = 0
    var crlHits = 0
    var sshHits = 0
    walkSidecars(bundlesRoot).foreach { sc =>
      if (updateSidecar(sc, materializeBundle)) bundleHits += 1
    }
    walkSidecars(crlsRoot).foreach { sc =>
      if (updateSidecar(sc, materializeCrl)) crlHits += 1
    }
    walkSidecars(sshRoot).foreach { sc =>
      if (updateSidecar(sc, materializeSsh, Some(materializeSshMetadata))) sshHits += 1
    }
    println(s"\nbundles updated: $bundleHits")
    println(s"crls updated:    $crlHits")
    println(s"ssh updated:     $sshHits")
  }
}
