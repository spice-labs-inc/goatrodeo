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

package io.spicelabs.goatrodeo.util

import io.spicelabs.goatrodeo.omnibor.strategies.CryptoFootprintStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.CryptoTokenStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.EmbeddedPemStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.ServiceTlsConfigStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.ShadowPasswordStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.UsignKeysStrategy

import java.nio.charset.StandardCharsets
import scala.util.Try

/** MIME augmentation for content-based strategy claiming.
  *
  * Performs ONE bounded read per artifact during the MIME precompute pass and
  * runs every content detector over the shared bytes, emitting a dedicated MIME
  * per strategy family. The strategies then claim purely by
  * `mimeType.contains(...)` — no file reads during strategy selection.
  *
  * Detectors fed from the shared buffer:
  *   - CryptoTokenStrategy (JWT/JWK) — every artifact
  *   - UsignKeysStrategy (usign/signify) — every artifact
  *   - CryptoFootprintStrategy (needles) — binary artifacts only
  *   - EmbeddedPemStrategy (PEM in text) — non-binary artifacts only
  *   - ShadowPasswordStrategy (path-gated) — etc/shadow, etc/passwd, …
  *   - ServiceTlsConfigStrategy (path-gated)
  *   - GradleLockfile (name-gated)
  *   - JvmDistribution (name-gated: `release`)
  *
  * The read is skipped entirely for MIME sets the artifact pipeline already
  * short-circuits (image/audio/video/terminal binaries — see
  * `ArtifactWrapper.augmentationCannotApply`).
  */
object CryptoContentDetector {

  /** Bytes read per artifact for content probing. Matches the detectors'
    * existing `DetectReadBytes` budgets.
    */
  val ReadBytes: Int = 256 * 1024

  val CryptoTokensMime = "application/x-goatrodeo-crypto-token"
  val UsignKeyMime = "application/x-goatrodeo-usign-key"
  val CryptoFootprintMime = "application/x-goatrodeo-crypto-footprint"
  val EmbeddedPemMime = "application/x-goatrodeo-embedded-pem"
  val ShadowPasswordMime = "application/x-goatrodeo-shadow-password"
  val TlsConfigMime = "application/x-goatrodeo-tls-config"
  val GradleLockfileMime = "application/x-goatrodeo-gradle-lockfile"
  val JvmReleaseMime = "application/x-goatrodeo-jvm-release"

  private val ShadowPaths: Vector[String] =
    Vector("etc/shadow", "etc/gshadow", "etc/passwd", "etc/group")

  private val GradleNames: Set[String] =
    Set("gradle.lockfile", "buildscript-gradle.lockfile")

  private def probe(a: ArtifactWrapper): String = {
    Try {
      val bytes = a.withStream { s =>
        val buf = new Array[Byte](ReadBytes)
        val n = s.read(buf, 0, ReadBytes)
        if (n <= 0) Array.emptyByteArray else java.util.Arrays.copyOf(buf, n)
      }
      new String(bytes, StandardCharsets.ISO_8859_1)
    }.getOrElse("")
  }

  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    val path = artifact.path()
    val name = artifact.filenameWithNoPath
    val isBinary =
      currentMimes.exists(CryptoFootprintStrategy.BinaryMimes.contains)

    val shadowPath = ShadowPaths.exists(path.endsWith)
    val tlsPath = ServiceTlsConfigStrategy.isTlsConfigPath(path)
    val gradleName =
      GradleNames.contains(name) ||
        (name.endsWith(".lockfile") && path.contains("dependency-locks"))
    val jvmName = name == "release"

    val text = probe(artifact)
    if (text.isEmpty) {
      return currentMimes
    }

    var out = currentMimes
    if (CryptoTokenStrategy.detects(text)) out += CryptoTokensMime
    if (UsignKeysStrategy.detects(text)) out += UsignKeyMime
    if (isBinary && CryptoFootprintStrategy.detects(text))
      out += CryptoFootprintMime
    if (!isBinary && EmbeddedPemStrategy.detects(text)) out += EmbeddedPemMime
    if (shadowPath && ShadowPasswordStrategy.containsHash(text))
      out += ShadowPasswordMime
    if (tlsPath && ServiceTlsConfigStrategy.containsTlsConfiguration(text))
      out += TlsConfigMime
    if (gradleName) out += GradleLockfileMime
    if (
      jvmName &&
      (text.contains("JAVA_VERSION") || text.contains("JAVA_RUNTIME_VERSION"))
    ) out += JvmReleaseMime
    out
  }
}
