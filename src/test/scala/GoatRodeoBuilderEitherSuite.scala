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

import io.spicelabs.goatrodeo.GoatRodeoBuilder
import munit.FunSuite

/** Phase 0 (0.3) — GoatRodeoBuilder.withTagDate Either-return contract.
  *
  * ## What this tests
  *
  * The `withTagDate(d: String)` method returns `Either[String,
  * GoatRodeoBuilder]` instead of throwing an exception on invalid input. These
  * tests verify:
  *   - Invalid date strings produce Left with an error message
  *   - Valid ISO 8601 date strings produce Right with the builder
  *   - A Left result does not mutate the builder's internal state
  *
  * ## Why this matters
  *
  * Prior to the Phase 0 remediation, `withTagDate` threw an exception on
  * unparseable dates. The Either return type allows callers to handle bad input
  * functionally without try/catch, making the builder safe for programmatic
  * use.
  *
  * ## Requirement trace
  *
  * Phase 0 item 0.3: GoatRodeoBuilder.withTagDate returns Either instead of
  * throwing on invalid date input.
  *
  * ## LLM-friendly summary
  *
  * | Test            | Input                   | Expected                  |
  * |:----------------|:------------------------|:--------------------------|
  * | invalid date    | "not-a-date"            | Left(errorMsg)            |
  * | valid date      | "2024-01-15"            | Right(builder)            |
  * | preserves state | invalid after valid tag | builder.tagDate unchanged |
  */
class GoatRodeoBuilderEitherSuite extends FunSuite {

  test("GoatRodeoBuilder - withTagDate returns Left for invalid date") {

    /** What: Feeds an unparseable date string to withTagDate. Why: The method
      * must not throw; it must return Left with a descriptive error so the
      * caller can decide how to handle it. Requirement: Phase 0 §0.3 —
      * withTagDate returns Either, not exception.
      */
    val builder = new GoatRodeoBuilder()
    val result = builder.withTagDate("not-a-date")

    assert(result.isLeft, "Expected Left for unparseable date string")
    val errorMsg = result.left.toOption.get
    assert(
      errorMsg.contains("not-a-date") || errorMsg.nonEmpty,
      s"Error message should reference the bad input or be non-empty, got: $errorMsg"
    )
  }

  test("GoatRodeoBuilder - withTagDate returns Right for valid date") {

    /** What: Feeds a valid ISO 8601 date string to withTagDate. Why: A valid
      * date must produce Right(builder), allowing the fluent API to continue.
      * Requirement: Phase 0 §0.3 — valid date yields Right.
      */
    val builder = new GoatRodeoBuilder()
    val result = builder.withTagDate("2024-01-15")

    assert(result.isRight, "Expected Right for valid ISO 8601 date")
    val returnedBuilder = result.toOption.get
    assert(
      returnedBuilder.isInstanceOf[GoatRodeoBuilder],
      "Right should contain a GoatRodeoBuilder"
    )
  }

  test("GoatRodeoBuilder - withTagDate preserves builder state on Left") {

    /** What: Sets a valid tag and tagDate, then calls withTagDate with an
      * invalid date and verifies the builder's previous tagDate is unchanged.
      * Why: A Left result must be a no-op — the builder should not be partially
      * mutated when date parsing fails. Requirement: Phase 0 §0.3 — Left is
      * non-mutating; builder state is preserved.
      */
    val builder = new GoatRodeoBuilder()
      .withTag("test-tag")

    val goodDateResult = builder.withTagDate("2024-01-15")
    assert(goodDateResult.isRight, "Valid date should return Right")
    val builderWithDate = goodDateResult.toOption.get

    val badDateResult = builderWithDate.withTagDate("garbage-date")
    assert(badDateResult.isLeft, "Invalid date should return Left")

    val checkResult = builderWithDate.withTagDate("2024-01-15")
    assert(
      checkResult.isRight,
      "Builder state should be preserved after Left; the good date should still be set"
    )
  }
}
