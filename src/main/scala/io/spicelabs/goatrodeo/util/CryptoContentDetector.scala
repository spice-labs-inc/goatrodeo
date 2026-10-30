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

import io.spicelabs.goatrodeo.omnibor.strategies.CloudKeyStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.CryptoFootprintStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.CryptoTokenStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.EmbeddedPemStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.ServiceCryptoStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.ServiceTlsConfigStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.ShadowPasswordStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.UsignKeysStrategy

import java.nio.charset.StandardCharsets
import java.util.Arrays
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
  * The read is skipped only when this detector's own `mimeRule` finds the MIME
  * set impossible for it (class files, whose constant pool carries no PEM
  * text). No other augmenter's decision can skip this one, and this one's
  * decision skips nothing but itself: a MIME type is an additive claim, not a
  * verdict that nothing further can be found.
  */
object CryptoContentDetector {

  /** Bytes read per artifact for content probing. Matches the detectors'
    * existing `DetectReadBytes` budgets.
    */
  val ReadBytes: Int = MimeProbe.MaxBytes

  val CryptoTokensMime = "application/x-goatrodeo-crypto-token"
  val UsignKeyMime = "application/x-goatrodeo-usign-key"
  val CryptoFootprintMime = "application/x-goatrodeo-crypto-footprint"
  val EmbeddedPemMime = "application/x-goatrodeo-embedded-pem"
  val ShadowPasswordMime = "application/x-goatrodeo-shadow-password"
  val TlsConfigMime = "application/x-goatrodeo-tls-config"
  val GradleLockfileMime = "application/x-goatrodeo-gradle-lockfile"
  val JvmReleaseMime = "application/x-goatrodeo-jvm-release"
  val CloudKeyMime = "application/x-goatrodeo-cloud-key"
  val DbEncryptionMime = "application/x-goatrodeo-db-encryption"

  private val ShadowPaths: Vector[String] =
    Vector("etc/shadow", "etc/gshadow", "etc/passwd", "etc/group")

  private val GradleNames: Set[String] =
    Set("gradle.lockfile", "buildscript-gradle.lockfile")

  private def probe(a: ArtifactWrapper): String = {
    Try {
      val bytes = a.withStream { s =>
        val buf = new Array[Byte](ReadBytes)
        val n = s.read(buf, 0, ReadBytes)
        if (n <= 0) Array.emptyByteArray else Arrays.copyOf(buf, n)
      }
      new String(bytes, StandardCharsets.ISO_8859_1)
    }.getOrElse("")
  }

  /** Both sides come from [[MimeFamilies]], the same pair the consumer of this
    * augmenter's MIME type uses, so a change to what this augmenter can handle
    * changes its consumer at the same moment.
    */
  private[goatrodeo] def mimeRule(
      mimes: Set[String],
      name: String = ""
  ): Boolean =
    !MimeFamilies.contradicts(mimes, MimeFamilies.cannotBeTextContent) &&
      MimeFamilies.couldBe(mimes, MimeFamilies.couldBeTextContent)

  val augmenter: MimeAugmenter = MimeAugmenter(
    id = "CryptoContentDetector",
    rule = mimeRule(_, _),
    budget = ProbeBudget(),
    fn = (artifact, currentMimes, evidence, probe) => {
      val path = artifact.path()
      val name = artifact.filenameWithNoPath
      val text =
        new String(probe.bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
      if (text.isEmpty) (currentMimes, evidence)
      else {
        val isBinary =
          currentMimes.exists(CryptoFootprintStrategy.BinaryMimes.contains)
        val shadowPath = ShadowPaths.exists(path.endsWith)
        val tlsPath = ServiceTlsConfigStrategy.isTlsConfigPath(path)
        val gradleName =
          GradleNames.contains(name) ||
            (name.endsWith(".lockfile") && path.contains("dependency-locks"))
        val jvmName = name == "release"
        val cloudKeyName = CloudKeyStrategy.cloudKeyName(name)

        var out = Set[String]()
        if (CryptoTokenStrategy.detects(text)) out += CryptoTokensMime
        if (UsignKeysStrategy.detects(text)) out += UsignKeyMime
        if (isBinary && CryptoFootprintStrategy.detects(text))
          out += CryptoFootprintMime
        if (!isBinary && EmbeddedPemStrategy.detects(text))
          out += EmbeddedPemMime
        if (shadowPath && ShadowPasswordStrategy.containsHash(text))
          out += ShadowPasswordMime
        if (tlsPath && ServiceTlsConfigStrategy.containsTlsConfiguration(text))
          out += TlsConfigMime
        if (gradleName) out += GradleLockfileMime
        if (
          jvmName &&
          (text.contains("JAVA_VERSION") || text
            .contains("JAVA_RUNTIME_VERSION"))
        ) out += JvmReleaseMime
        if (cloudKeyName && CloudKeyStrategy.detects(text)) out += CloudKeyMime
        if (!isBinary && ServiceCryptoStrategy.detectsSqlcipher(text))
          out += DbEncryptionMime

        // Every MIME this detector could assert is asserted here or not at all;
        // nothing is left indeterminate, so no evidence is recorded.
        (currentMimes ++ out, evidence)
      }
    }
  )
}
