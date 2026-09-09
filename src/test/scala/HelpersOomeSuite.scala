import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Helpers

import scala.collection.immutable.TreeSet

/** Phase 0 (0.5) — Helpers.computeAssociatedSource OOME-safe path.
  *
  * ## What this tests
  *
  * `computeAssociatedSource` is called for every artifact to find source file
  * associations. When the artifact is not a Java class file (i.e., its MIME
  * type does not intersect `javaClassMimeTypes`), the method must return an
  * empty TreeSet without attempting BCEL parsing — avoiding the
  * OutOfMemoryError that corrupted class files could trigger.
  *
  * ## Why this matters
  *
  * Before the Phase 0 remediation, a corrupted class file that forced BCEL to
  * allocate a huge array could cause an OOME crashing the entire process. The
  * non-class path must short-circuit safely.
  *
  * ## Requirement trace
  *
  * Phase 0 item 0.5: computeAssociatedSource returns empty TreeSet for
  * non-class MIME types without invoking BCEL.
  *
  * ## LLM-friendly summary
  *
  * | Test           | Input MIME | Expected      |
  * |:---------------|:-----------|:--------------|
  * | non-class MIME | text/plain | TreeSet.empty |
  *
  * Note: Testing the actual OOME catch path requires crafting a corrupt class
  * file that triggers BCEL OOME, which is not feasible in a unit test. The
  * non-class path is tested here as the primary guard.
  */
class HelpersOomeSuite extends GoatRodeoFunSuite {

  test("Helpers - computeAssociatedSource returns empty for non-class MIME") {

    /** What: Creates an ArtifactWrapper with a non-class MIME type and calls
      * computeAssociatedSource. Why: Non-class artifacts must not enter the
      * BCEL parsing path at all. The method should return an empty TreeSet
      * immediately. Requirement: Phase 0 §0.5 — non-class MIME returns
      * TreeSet.empty without BCEL invocation.
      */
    val artifact = ByteWrapper(
      "some text content".getBytes("UTF-8"),
      "readme.txt",
      None
    )
    val associatedFiles = Map("readme.txt" -> "gitoid:blob:sha256:abcdef")

    val result = Helpers.computeAssociatedSource(artifact, associatedFiles)

    assertEquals(
      result,
      TreeSet.empty[String],
      "Non-class MIME type should yield empty TreeSet"
    )
  }
}
