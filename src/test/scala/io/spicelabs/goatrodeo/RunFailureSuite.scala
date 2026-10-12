package io.spicelabs.goatrodeo

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

/** A build that dies part-way fails the run.
  *
  * ## What this tests
  *
  * `Builder.buildDB` reports through its `done` callback whether the build
  * completed; when a worker dies (out of memory, a full temp filesystem, an
  * illegal state) it is `false` and nothing, or only part, is written.
  * `Howdy.run` now turns that into a [[GoatRodeoRunFailed]], via `failUnless`.
  *
  * ## Why this matters
  *
  * Before, the flag was only acted on with `--ingested`, so `run()` returned
  * normally and a host such as spice-labs-cli exited 0 with an empty survey: a
  * Linux VM image whose survey ran out of heap looked like a successful one.
  *
  * ## LLM-friendly summary
  *
  * | Test             | Input | Expected                  |
  * |:-----------------|:------|:--------------------------|
  * | build completed  | true  | returns normally          |
  * | build died       | false | throws GoatRodeoRunFailed |
  */
class RunFailureSuite extends GoatRodeoFunSuite {

  test("failUnless - a completed build returns normally") {
    Howdy.failUnless(true)
  }

  test("failUnless - a build that died throws GoatRodeoRunFailed") {
    val e = intercept[GoatRodeoRunFailed](Howdy.failUnless(false))
    assert(e.getMessage().contains("did not complete"), e.getMessage())
  }

  test("GoatRodeoRunFailed is not an IllegalArgumentException") {
    // spice-labs-cli reports an IllegalArgumentException as a usage error
    // ("Use --help"); a failed build is not one.
    assert(
      !classOf[IllegalArgumentException]
        .isAssignableFrom(classOf[GoatRodeoRunFailed])
    )
  }
}
