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
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

/** Tests for the two-sided per-augmenter applicability contract.
  *
  * WHAT: every augmenter's `mimeRule` is the AND of two predicates over the
  * artifact's MIME set.
  *
  *   1. **Contradiction** -- if the set contains anything this augmenter
  *      provably cannot use, the augmenter is skipped. These lists are long and
  *      aggressive on purpose: a wrong entry costs only a lost optimisation on
  *      something that could never have worked. 2. **Admission** -- if the set
  *      contains nothing this augmenter could possibly use, it is skipped. This
  *      list must never be wrong in the restrictive direction, because a miss
  *      here is a silent false negative: the artifact is never probed and
  *      nothing reports it.
  *
  * WHY: the augmenter chain is the expensive part of the MIME pass (a bounded
  * probe per artifact, ~700-1,350µs measured), and on a real run most artifacts
  * are either obviously not this augmenter's business (a JAR is not a PE image,
  * a PNG is not a disk) or obviously nothing anyone can use (a font). Both
  * rejects are pure set arithmetic with no I/O, so they happen before any byte
  * is read.
  *
  * THEORY: a MIME claim is authoritative about what the artifact *is*. A JAR is
  * a zip with a particular suffix, so it is handled as a JAR: a PE image inside
  * it is a *child* artifact discovered by walking, never the same bytes
  * reinterpreted. The tests therefore pin both directions for every augmenter,
  * plus the third case the block-list design could not express -- a type that
  * contradicts nothing but which no augmenter can use, which must be skipped by
  * the admission predicate rather than probed "just in case".
  *
  * Requirements: "Reject if the listed items contains something that is known
  * not to be possibly something the augmenter cares about... Reject if the
  * listed items do not contain something that COULD be augmented."
  *
  * LLM note: test ids are AA-n. Each test names the augmenter, the admissible
  * spellings, the contradicted spellings, and the no-admission case.
  */
class AugmenterAdmissionSuite extends GoatRodeoFunSuite {

  private def one(mime: String): Set[String] = Set(mime)

  /** Asserts every spelling in `admitted` is accepted, every spelling in
    * `contradicted` is refused, and a type nothing in this augmenter's world
    * could use is refused.
    */
  private def pins(
      name: String,
      rule: (Set[String], String) => Boolean,
      admitted: Seq[String],
      contradicted: Seq[String],
      nothingAdmissible: Seq[String]
  ): Unit = {
    admitted.foreach { m =>
      assert(rule(one(m), "artifact.bin"), s"$name must accept $m")
    }
    contradicted.foreach { m =>
      assert(!rule(one(m), "artifact.bin"), s"$name must refuse $m")
    }
    nothingAdmissible.foreach { m =>
      assert(
        !rule(one(m), "artifact.bin"),
        s"$name must refuse $m: nothing it could use is present"
      )
    }
  }

  // AA-1 -- DotnetDetector: a PE image. Every spelling Tika actually produces
  // for a PE must be accepted, including the parameterised forms this repo
  // observes; everything that provably cannot start with `MZ` is refused, and an
  // unknown type with no PE claim is refused by admission rather than probed.
  test("AA-1 dotnet accepts PE spellings only") {
    pins(
      "DotnetDetector",
      DotnetDetector.mimeRule(_, _),
      admitted = Seq(
        "application/x-msdownload",
        "application/x-msdownload; format=pe32",
        "application/x-msdownload; format=pe64",
        "application/vnd.microsoft.portable-executable",
        "application/x-dosexec",
        "application/octet-stream"
      ),
      contradicted = Seq(
        "application/java-archive",
        "application/java-vm",
        "application/zip",
        "text/plain",
        "application/xml",
        "image/png",
        "application/x-debian-package",
        "application/x-iso9660-image"
      ),
      nothingAdmissible = Seq("application/x-some-unknown-fragment")
    )
  }

  // AA-2 -- SaffronDetector: a disk image or a container Saffron understands.
  // The two documented Tika misreads must stay admissible or a `.raw`/`.vhd`
  // cloud image is surveyed as one opaque file while the run reports success.
  test("AA-2 saffron accepts disks and their misreadings") {
    pins(
      "SaffronDetector",
      SaffronDetector.mimeRule(_, _),
      admitted = Seq(
        "application/octet-stream",
        "application/gzip",
        "application/x-iso9660-image",
        "text/x-vhdl",
        "image/x-raw-panasonic",
        "multipart/appledouble"
      ),
      contradicted = Seq(
        "application/java-vm",
        "application/java-archive",
        "application/zip",
        "application/x-rpm",
        "audio/mpeg",
        "video/mp4",
        // A text artifact is not a disk. `text/*` used to be admitted here for the
        // single `.vhd` misreading; the misreading itself is admitted by name
        // (`text/x-vhdl`), and prose is refused by the shared negative pair.
        "text/plain"
      ),
      nothingAdmissible =
        Seq("image/png", "application/x-some-unknown-fragment")
    )
  }

  // AA-3 -- CarvedCertAugmenter: a binary carrier whose sections can hold a DER
  // object. Text families cannot hold DER; archives are walked into children
  // instead of being carved as one blob.
  test("AA-3 carve accepts executable carriers only") {
    pins(
      "CarvedCertAugmenter",
      CarvedCertAugmenter.mimeRule(_, _),
      admitted = Seq(
        "application/octet-stream",
        "application/x-executable",
        "application/x-sharedlib",
        "application/x-pie-executable",
        "application/x-object",
        "application/x-msdownload",
        "application/x-msdownload; format=pe32"
      ),
      contradicted = Seq(
        "text/plain",
        "application/xml",
        "application/json",
        "application/java-vm",
        "application/java-archive",
        "image/png"
      ),
      nothingAdmissible = Seq("application/x-some-unknown-fragment")
    )
  }

  // AA-4 -- CryptoDetector: DER/PEM/SSH/PGP material, which can sit in text
  // (base64 PEM in a pom.xml) or in any binary carrier.
  test("AA-4 crypto accepts text and binary carriers") {
    pins(
      "CryptoDetector",
      CryptoDetector.mimeRule(_, _),
      admitted = Seq(
        "application/octet-stream",
        "text/plain",
        "application/xml",
        "application/x-sharedlib",
        "application/pdf",
        "application/pkix-cert",
        "application/pgc-keys-does-not-exist",
        "application/pgp-keys"
      ).filterNot(_ == "application/pgc-keys-does-not-exist"),
      contradicted = Seq("application/java-vm", "audio/mpeg", "video/mp4"),
      nothingAdmissible = Seq("application/x-some-unknown-fragment")
    )
  }

  // AA-5 -- CryptoContentDetector: PEM text, shadow files, TLS configs, lock
  // files. octet-stream matters most here: inner package fragments and
  // extensionless files (`etc/shadow`, `release`, `*.lockfile`) arrive that way.
  test("AA-5 crypto content accepts text, fragments and archives") {
    pins(
      "CryptoContentDetector",
      CryptoContentDetector.mimeRule(_, _),
      admitted = Seq(
        "application/octet-stream",
        "text/plain",
        "text/x-java-properties",
        "application/xml",
        "application/x-sh"
      ),
      contradicted = Seq(
        "application/java-vm",
        "audio/mpeg",
        "video/mp4",
        // A JAR is expanded into children, and the stored file carries the claim.
        "application/java-archive"
      ),
      nothingAdmissible = Seq("application/x-some-unknown-fragment")
    )
  }

  // AA-6 -- JavaArchiveDetector: a zip. Only zip-family claims are worth a magic
  // check; a JAR is already claimed, and text/XML/class files cannot be zips.
  test("AA-6 java archive accepts zip family only") {
    pins(
      "JavaArchiveDetector",
      JavaArchiveDetector.mimeRule(_, _),
      admitted = Seq(
        "application/octet-stream",
        "application/zip",
        "application/x-zip-compressed",
        "application/java-archive",
        "application/vnd.android.package-archive",
        "application/epub+zip"
      ),
      contradicted = Seq(
        "text/plain",
        "application/xml",
        "application/java-vm",
        "application/x-sharedlib",
        "application/x-iso9660-image"
      ),
      nothingAdmissible = Seq("application/x-some-unknown-fragment")
    )
  }

  // AA-7 -- JavaSecurityDetector: java.security property syntax, which is text.
  test("AA-7 java security accepts text only") {
    pins(
      "JavaSecurityDetector",
      JavaSecurityDetector.mimeRule(_, _),
      admitted = Seq(
        "application/octet-stream",
        "text/plain",
        "text/x-java-properties"
      ),
      contradicted = Seq(
        "application/java-vm",
        "application/java-archive",
        "application/xml",
        "image/png",
        "audio/mpeg",
        "application/x-msdownload",
        "application/x-executable"
      ),
      nothingAdmissible = Seq("application/x-some-unknown-fragment")
    )
  }

  // AA-8 -- OpenSSLConfigDetector: OpenSSL config syntax, which is text.
  test("AA-8 openssl config accepts text only") {
    pins(
      "OpenSSLConfigDetector",
      OpenSSLConfigDetector.mimeRule(_, _),
      admitted = Seq("application/octet-stream", "text/plain"),
      contradicted = Seq(
        "application/java-vm",
        "application/java-archive",
        "application/xml",
        "image/png",
        "audio/mpeg",
        "application/x-executable"
      ),
      nothingAdmissible = Seq("application/x-some-unknown-fragment")
    )
  }

  // AA-11 -- the two predicates are exact complements, and they agree on the
  // whole matching vocabulary: exact names, `family/*`, a bare `family/` and a
  // partial-segment prefix such as `image/x-raw-*`. They must stay consistent
  // because every rule is written as `noneOf(...) && anyOf(...)`; a divergence in
  // the matching rules would make some artifact both refused and admitted.
  test("AA-11 anyOf is the exact complement of noneOf") {
    val sets = Seq(
      Set("text/plain"),
      Set("image/x-raw-panasonic"),
      Set("application/java-archive"),
      Set("application/x-msdownload; format=pe32"),
      Set("application/octet-stream")
    )
    val entries = Seq(
      "text/*",
      "text/",
      "image/x-raw-*",
      "application/java-archive",
      "application/x-msdownload",
      "application/octet-stream",
      "application/x-nothing-matches-this"
    )
    sets.foreach { mimes =>
      entries.foreach { e =>
        assertEquals(
          ArtifactWrapper.anyOf(e)(mimes),
          !ArtifactWrapper.noneOf(e)(mimes),
          s"anyOf and noneOf disagree on entry $e for ${mimes.mkString(",")}"
        )
      }
    }
  }

  // AA-12 -- only a suffix entry scans the MIME set; an exact candidate is
  // answered by a hash lookup. This is a structural property of the shared
  // combinator, pinned behaviourally: a candidate list of many exact names must
  // reach a decision using the same membership rule as the set itself, i.e. the
  // answer depends on set membership only, never on candidate position.
  test("AA-12 admission depends on membership, not candidate order") {
    val exact = (1 to 200).map(i => s"application/x-filler-$i")
    val mimes = Set("application/octet-stream")
    val forward = (exact :+ "application/octet-stream").toSeq
    val reverse = forward.reverse
    assert(ArtifactWrapper.anyOf(forward*)(mimes))
    assert(ArtifactWrapper.anyOf(reverse*)(mimes))
    assert(!ArtifactWrapper.anyOf(exact*)(mimes))
  }

  // AA-9 -- a claim dominates: a JAR that also carries an octet-stream claim is
  // still a JAR, so the PE and disk-image augmenters refuse it. The PE inside it
  // is a child artifact reached by walking, not the same bytes reinterpreted.
  test("AA-9 a JAR claim beats an octet-stream claim") {
    val mixed = Set("application/java-archive", "application/octet-stream")
    assert(!DotnetDetector.mimeRule(mixed), "a JAR is not a PE image")
    assert(!SaffronDetector.mimeRule(mixed), "a JAR is not a disk image")
    assert(
      !CarvedCertAugmenter.mimeRule(mixed),
      "a JAR is walked into children, not carved as one blob"
    )
    assert(JavaArchiveDetector.mimeRule(mixed), "a JAR is a zip, so it admits")
  }

  // AA-10 -- a family that no augmenter can use is skipped everywhere without a
  // single read. Fonts are the clean case: not text, not a PE, not a disk, not a
  // zip, not a carrier.
  test("AA-10 a family nothing can use is skipped by every augmenter") {
    val font = one("application/x-font-ttf")
    val runnable: Seq[(Set[String], String) => Boolean] = Seq(
      DotnetDetector.mimeRule(_, _),
      SaffronDetector.mimeRule(_, _),
      CarvedCertAugmenter.mimeRule(_, _),
      CryptoDetector.mimeRule(_, _),
      CryptoContentDetector.mimeRule(_, _),
      JavaArchiveDetector.mimeRule(_, _),
      JavaSecurityDetector.mimeRule(_, _),
      OpenSSLConfigDetector.mimeRule
    ).filter(gate => gate(font, "font.ttf"))
    assertEquals(
      runnable.length,
      0,
      s"a font must be probed by nothing, but ${runnable.length} augmenter(s) admit it"
    )
  }
}
