package io.spicelabs.goatrodeo.util

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.util.UUID

/** Regression: the producer-stamped MIME hint is documented as authoritative
  * and must survive a failing augmenter.
  *
  * Pins `ArtifactWrapper._mimeType`: on an augmenter exception it falls back to
  * `base` (the detected set) instead of `base2` (hint ++ detected), so the hint
  * is dropped exactly when augmentation misbehaves.
  */
class MimeHintSurvivesAugmenterFailureSuite extends GoatRodeoFunSuite {

  test("a MIME hint is kept when an augmenter throws") {
    // The rule keys on a unique hint so this augmenter never fires for any
    // other artifact in the test run.
    val hint = "application/x-goatrodeo-test-hint-" + UUID.randomUUID()
    ArtifactWrapper.addMimeTypeAugmenter(_.contains(hint)) { (_, _) =>
      throw new RuntimeException("augmenter failure")
    }
    val wrapper =
      ByteWrapper("hello".getBytes("UTF-8"), "x.bin", None, Set(hint))
    assert(
      wrapper.mimeType.contains(hint),
      s"the hint must survive a failing augmenter; got ${wrapper.mimeType}"
    )
  }
}
