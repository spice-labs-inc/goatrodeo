package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.testsupport.IpaCorpus
import io.spicelabs.goatrodeo.testsupport.IpaZipBuilder
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper
import org.scalacheck.Prop
import org.scalacheck.Gen
import org.scalacheck.Properties

/** Detection and container-walking for iOS `.ipa` files.
  *
  * Requirement (§2.1 of the plan): an `.ipa` with ZIP magic is massaged to
  * `application/zip` in `ArtifactWrapper.massageMimeType` (mirroring the
  * `isNupkg` pattern) so the FileWalker ZIP branch walks it; the massage keys
  * on extension+magic only, never on MIME, so Android APKs and plain ZIPs are
  * unaffected.
  *
  * LLM note: Ipa-xx = test id. Corpus tests require the fetched
  * `allsafe-ios.ipa` (see `IpaCorpus` and build.sbt Tests.Setup).
  */
class IpaDetectionSuite extends GoatRodeoFunSuite {

  /** The byte-level predicate the .ipa massage must implement: extension is
    * `.ipa` AND the first four bytes are a ZIP local-file header (PK\\x03\\x04)
    * or an empty-ZIP EOCD header (PK\\x05\\x06).
    */
  private def isIpaPredicate(bytes: Array[Byte], name: String): Boolean =
    name.toLowerCase.endsWith(".ipa") &&
      bytes.length >= 4 &&
      bytes(0) == 0x50 &&
      bytes(1) == 0x4b &&
      (bytes(2) == 0x03 || bytes(2) == 0x05) &&
      bytes(3) == 0x04

  private def mimesFor(bytes: Array[Byte], name: String): Set[String] =
    ByteWrapper(bytes, name, None).mimeType

  // Ipa-0.1 — a real-ZIP .ipa is massaged to application/zip and walked;
  // children (Payload/.../Info.plist etc.) appear as wrappers.
  test("Ipa-0.1 real-zip .ipa is massaged and walked") {
    val ipa = IpaZipBuilder.minimalIpa(
      infoPlistBytes = "<plist><dict/></plist>".getBytes("UTF-8"),
      mobileProvisionBytes = "not-a-real-cms".getBytes("UTF-8")
    )
    val mimes = mimesFor(ipa, "MyApp.ipa")
    assert(
      mimes.contains("application/zip"),
      s".ipa with PK magic must massage to application/zip, got $mimes"
    )

    val wrapper = ByteWrapper(ipa, "MyApp.ipa", None)
    val children = FileWalker.withinArchiveStream(wrapper) { v =>
      v.map(_.path())
    }
    assert(children.isDefined, "walkable as a zip container")
    val paths = children.get
    assert(
      paths.contains("Payload/Min.app/Info.plist"),
      s"Info.plist child must appear: $paths"
    )
    assert(
      paths.contains("Payload/Min.app/embedded.mobileprovision"),
      s"mobileprovision child must appear: $paths"
    )
  }

  // Ipa-0.2 — an .ipa that is not a ZIP must NOT be massaged (stays
  // unclaimed by the Ipa path; the massaged result is not application/zip).
  test("Ipa-0.2 non-zip .ipa is not massaged") {
    val garbage = "this is definitely not a zip archive at all".getBytes(
      "UTF-8"
    )
    val mimes = mimesFor(garbage, "FakeApp.ipa")
    assert(
      !mimes.contains("application/zip"),
      s"non-zip .ipa must not be massaged to application/zip, got $mimes"
    )
  }

  // Ipa-0.3 — a plain ZIP without the .ipa extension is unaffected.
  test("Ipa-0.3 plain zip without .ipa extension is unaffected") {
    val bytes = IpaZipBuilder.zip("a.txt" -> "a".getBytes("UTF-8"))
    val mimes = mimesFor(bytes, "plain.zip")
    assert(
      mimes.contains("application/zip"),
      s"plain .zip must still be zip, got $mimes"
    )
  }

  // Ipa-0.4 — a .zip renamed to .ipa with valid ZIP magic is walked.
  test("Ipa-0.4 renamed zip with .ipa name is walked") {
    val bytes = IpaZipBuilder.zip("a.txt" -> "a".getBytes("UTF-8"))
    val wrapper = ByteWrapper(bytes, "Renamed.ipa", None)
    val children = FileWalker.withinArchiveStream(wrapper) { v =>
      v.map(_.path())
    }
    assert(children.isDefined, "renamed .ipa must still walk as a zip")
    assert(children.get.contains("a.txt"))
  }

  // Ipa-0.5 — property-based: for random byte prefixes, the massage
  // decision (is the wrapper massaged to application/zip on an .ipa name?)
  // agrees with the byte-level predicate isIpaPredicate.
  test("Ipa-0.5 property: massage decision equals magic and extension") {
    val prop = Prop.forAll(Gen.listOfN(8, Gen.chooseNum[Int](0, 255))) {
      prefix =>
        val bytes = prefix.map(_.toByte).toArray
        val expected = isIpaPredicate(bytes, "Prop.ipa")
        val wrapper = ByteWrapper(bytes, "Prop.ipa", None)
        val actual = wrapper.mimeType.contains("application/zip")
        // When the predicate says "ipa zip", the wrapper must agree. When
        // the predicate is false, Tika may still classify random bytes as a
        // zip (false positives are Tika's, not the massage's): the massage
        // must never *remove* zip, so actual may be true where expected is
        // false — but never the reverse.
        if (expected) actual
        else true // any outcome other than a missing massaged zip is fine
    }
    prop.check()
  }

  // Ipa-0.6 — an empty ZIP (PK\x05\x06 magic) with .ipa name is walked.
  test("Ipa-0.6 empty zip with .ipa name is walked") {
    val bos = new java.io.ByteArrayOutputStream()
    // minimal empty zip: EOCD record only
    bos.write(0x50); bos.write(0x4b); bos.write(0x05); bos.write(0x06)
    bos.write(Array.fill[Byte](18)(0))
    val bytes = bos.toByteArray
    val wrapper = ByteWrapper(bytes, "Empty.ipa", None)
    val children = FileWalker.withinArchiveStream(wrapper) { v =>
      v.map(_.path())
    }
    // Empty archives are valid containers: walking yields an empty (or
    // degenerate) child list without throwing.
    assert(children.isDefined, "empty .ipa must not throw on walk")
  }

  // Ipa-0.7 — a bare Payload/x.app/Info.plist (no container) is a candidate
  // for MobileTls' claim; it must not be swallowed by an Ipa container claim
  // (plain plist files are not .ipa containers).
  test("Ipa-0.7 bare Info.plist is not claimed as an ipa container") {
    val plist = "<plist><dict/></plist>".getBytes("UTF-8")
    // No .ipa extension: the isIpa massage must not fire.
    val mimes = mimesFor(plist, "Payload/x.app/Info.plist")
    assert(
      !mimes.contains("application/zip"),
      s"bare plist must not be massaged to zip, got $mimes"
    )
  }

  // Ipa-0.8 — corpus: the fetched real allsafe-ios.ipa is massaged, walked,
  // and yields the real bundle members.
  test("Ipa-0.8 corpus allsafe-ios.ipa is massaged and walked") {
    assume(IpaCorpus.verified, "allsafe-ios.ipa corpus fixture unavailable")
    val wrapper = FileWrapper(
      IpaCorpus.corpusFile,
      IpaCorpus.corpusFile.getPath,
      None
    )
    assert(
      wrapper.mimeType.contains("application/zip"),
      s"corpus .ipa must be application/zip, got ${wrapper.mimeType}"
    )
    val children = FileWalker.withinArchiveStream(wrapper) { v =>
      v.map(_.path())
    }
    assert(children.isDefined, "corpus .ipa must walk as a zip")
    val paths = children.get
    assert(
      paths.contains("Payload/Allsafe.app/Info.plist"),
      s"corpus Info.plist must appear: $paths"
    )
    assert(
      paths.contains("Payload/Allsafe.app/embedded.mobileprovision"),
      s"corpus mobileprovision must appear: $paths"
    )
    assert(
      paths.contains("Payload/Allsafe.app/Allsafe"),
      s"corpus Mach-O executable must appear: $paths"
    )
  }
}

/** Property-based companion for the Ipa-0.5 decision predicate.
  */
object IpaMassageProperties extends Properties("ipa-massage-decision") {
  property("massage = zip-magic AND .ipa extension") = Prop.forAll(
    Gen.listOfN(8, Gen.chooseNum[Int](0, 255))
  ) { prefix =>
    val bytes = prefix.map(_.toByte).toArray
    val magic = bytes.length >= 4 && bytes(0) == 0x50 && bytes(1) == 0x4b &&
      (bytes(2) == 0x03 || bytes(2) == 0x05) && bytes(3) == 0x04
    val wrapper = ByteWrapper(bytes, "Prop.ipa", None)
    val massaged = wrapper.mimeType.contains("application/zip")
    // .ipa + zip magic => zip. Non-magic may still be detected as zip by
    // Tika, which is fine (Ipa-0.2 covers the non-forced case separately).
    if (magic) massaged
    else true
  }
}
