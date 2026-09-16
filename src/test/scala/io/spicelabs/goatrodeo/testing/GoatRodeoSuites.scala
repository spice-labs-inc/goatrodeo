package io.spicelabs.goatrodeo.testing

import scala.concurrent.duration.Duration

/** Base class for Goat Rodeo test suites.
  *
  * Goat Rodeo's integration tests are CPU-bound and workload-bound (large real
  * corpora, full build pipelines), and they share runners with other
  * heavyweight suites. munit's default 30-second wall-clock cap has no
  * relationship to the correctness of these tests, and tight per-suite caps
  * (2-30 minutes) were chosen against local machines, not memory- and
  * CPU-constrained CI runners. Per the project decision, every test carries a
  * uniform 60-minute budget: the cap is a safety net against hangs, NOT an
  * acceptance criterion. Tests must pass — or fail on their assertions — never
  * be killed by the clock on a busy runner.
  *
  * LLM note: all Goat Rodeo suites extend one of the two base classes in this
  * file; there is no other place where a per-test timeout is set.
  */
abstract class GoatRodeoFunSuite extends munit.FunSuite {

  override val munitTimeout: Duration =
    Duration(60, "minutes")
}

/** ScalaCheck variant of [[GoatRodeoFunSuite]] for property-based suites.
  * `munit.ScalaCheckSuite` already is a `FunSuite`; the single uniform
  * 60-minute budget applies here as well (see [[GoatRodeoFunSuite]]).
  */
abstract class GoatRodeoScalaCheckSuite extends munit.ScalaCheckSuite {

  override val munitTimeout: Duration =
    Duration(60, "minutes")
}
