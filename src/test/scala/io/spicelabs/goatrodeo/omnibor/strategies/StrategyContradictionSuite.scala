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

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

/** Tests for the contradiction half of the strategy applicability pair.
  *
  * WHAT: every content strategy refuses an artifact whose MIME set claims it is
  * a container — a JAR, a ZIP, an APK, a DEB, an RPM, a tar, a gzip or zstd
  * wrapper, an ISO image, an OOXML document — or a class file.
  *
  * WHY: a container is expanded into children, and a child artifact carries the
  * content claim. If a container whose stored bytes happen to hold a PEM block
  * or a config file were claimed as *that file*, the archive would inherit the
  * content's identity: a JAR would become a TLS configuration, and the purl,
  * the metadata and the certificate would all be attributed to the wrong
  * artifact. The child is claimed on its own when the walker produces it.
  *
  * THEORY: this is the contradiction predicate of the two-sided contract,
  * applied at claim time rather than at augmentation time. A claim in the MIME
  * set is authoritative ("a JAR is a JAR"), so a container claim beats a
  * content claim on the same artifact. The admission half is already pinned by
  * each strategy's own suite; this test pins the refusal half, per strategy, so
  * a new strategy that forgets it fails here.
  *
  * LLM note: test ids are SC-n. `SC-1` is the table; `SC-2` pins the deliberate
  * exception (the IPA strategy claims ZIP containers, because that archive *is*
  * the artifact it describes); `SC-3` pins that a child artifact is unaffected.
  */
class StrategyContradictionSuite extends GoatRodeoFunSuite {

  /** name -> the strategy's contradiction predicate. */
  private val gates: Seq[(String, Set[String] => Boolean)] = Seq(
    "Certificates" -> Certificates.notAContainer,
    "EmbeddedCertificates" -> EmbeddedCertificatesStrategy.notAContainer,
    "CloudKey" -> CloudKeyStrategy.notAContainer,
    "CryptoFootprint" -> CryptoFootprintStrategy.notAContainer,
    "CryptoTokens" -> CryptoTokenStrategy.notAContainer,
    "EmbeddedPem" -> EmbeddedPemStrategy.notAContainer,
    "ServiceTlsConfig" -> ServiceTlsConfigStrategy.notAContainer,
    "ShadowPassword" -> ShadowPasswordStrategy.notAContainer,
    "UsignKeys" -> UsignKeysStrategy.notAContainer,
    "JavaSecurity" -> JavaSecurityToProcess.notAContainer,
    "OpenSSLConfig" -> OpenSSLConfigToProcess.notAContainer,
    "ServiceCrypto" -> ServiceCryptoStrategy.notAContainer,
    "CryptoDependency" -> CryptoDependencyStrategy.notAContainer,
    "MobileTls" -> MobileTlsStrategy.notAContainer,
    "SSHKeys" -> SSHKeysStrategy.notAContainer,
    "GradleLockfile" -> GradleLockfile.notAContainer,
    "JvmDistribution" -> JvmDistribution.notAContainer,
    "Dotnet" -> DotnetFile.notAContainer
  )

  private val containers: Seq[String] = Seq(
    "application/java-archive",
    "application/vnd.android.package-archive",
    "application/zip",
    "application/x-zip-compressed",
    "application/x-debian-package",
    "application/x-rpm",
    "application/x-archive",
    "application/x-gtar",
    "application/gzip",
    "application/zstd",
    // Disk and guest claims are deliberately absent: they come from another
    // augmenter's probe and accumulate with the artifact's own claims, and an ELF
    // tagged as a Saffron disk must still be claimable as a shared library.

    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/java-vm"
  )

  // SC-1 — every content strategy refuses every container claim: an archive or a
  // class file is expanded into children, and a child carries the content claim.
  test("SC-1 every content strategy refuses container claims") {
    gates.foreach { case (name, gate) =>
      containers.foreach { m =>
        assert(
          !gate(Set(m)),
          s"$name must refuse a $m claim: the container is walked, not claimed"
        )
      }
    // The admission half is not uniform by design: a text-config consumer
    // refuses binaries, a ZIP consumer refuses text. It is pinned per consumer
    // by that strategy's own suite, so this test pins only the shared refusal.
    }
  }

  // SC-2 — the IPA strategy is the deliberate exception: an `.ipa` *is* a ZIP
  // container, and that container is the artifact the strategy describes, so ZIP
  // claims are not contradicted there. It still refuses class files and packages
  // that cannot be an IPA.
  test("SC-2 the IPA strategy claims ZIP containers by design") {
    assert(IpaStrategy.notAContainer(Set("application/zip")))
    assert(IpaStrategy.notAContainer(Set("application/x-zip-compressed")))
    assert(!IpaStrategy.notAContainer(Set("application/java-vm")))
    assert(!IpaStrategy.notAContainer(Set("application/x-debian-package")))
  }

  // SC-3 — a child artifact is not a container, so the same content that was
  // refused on the archive is claimed on the child. This is the behaviour that
  // makes the contradiction safe rather than lossy.
  test("SC-3 a child artifact carrying the content is admitted") {
    val child = Set("text/plain", "application/x-goatrodeo-embedded-pem")
    assert(EmbeddedPemStrategy.notAContainer(child), "a child PEM is claimable")
    assert(Certificates.notAContainer(child), "a child is claimable")
    val archive = child + "application/java-archive"
    assert(
      !EmbeddedPemStrategy.notAContainer(archive),
      "the archive itself is not"
    )
    assert(!Certificates.notAContainer(archive), "the archive itself is not")
  }
}
