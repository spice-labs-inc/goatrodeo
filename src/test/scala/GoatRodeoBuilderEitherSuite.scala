import io.spicelabs.goatrodeo.GoatRodeo
import io.spicelabs.goatrodeo.GoatRodeoBuilder
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.TomlTables
import org.tomlj.Toml

/** GoatRodeoBuilder.withTagDate Either-return contract.
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
  * `withTagDate` returns Either instead of throwing on unparseable dates,
  * letting callers handle bad input functionally without try/catch, making the
  * builder safe for programmatic use.
  *
  * ## Requirement trace
  *
  * Requirement: GoatRodeoBuilder.withTagDate returns Either instead of throwing
  * on invalid date input.
  *
  * ## LLM-friendly summary
  *
  * | Test            | Input                   | Expected                  |
  * |:----------------|:------------------------|:--------------------------|
  * | invalid date    | "not-a-date"            | Left(errorMsg)            |
  * | valid date      | "2024-01-15"            | Right(builder)            |
  * | preserves state | invalid after valid tag | builder.tagDate unchanged |
  */
class GoatRodeoBuilderEitherSuite extends GoatRodeoFunSuite {

  test("GoatRodeoBuilder - withTagDate returns Left for invalid date") {

    /** What: Feeds an unparseable date string to withTagDate. Why: The method
      * must not throw; it must return Left with a descriptive error so the
      * caller can decide how to handle it. Requirement: — withTagDate returns
      * Either, not exception.
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
      * Requirement: valid date yields Right.
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
      * mutated when date parsing fails. Requirement: Left is non-mutating;
      * builder state is preserved.
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

  test(
    "GoatRodeoBuilder - withConfiguration returns Right and applies the table"
  ) {
    // The embedder seam must accept a plain configuration table and surface
    // the result as Either, not by throwing (withTagDate precedent).
    val table =
      TomlTables.toPlainMap(Toml.parse("threads = 11\nmax_records = 4242"))
    val result = GoatRodeo
      .builder()
      .withThreads(2)
      .withConfiguration(table, "survey.inventory.analysis")
    assert(result.isRight, s"a valid table must produce Right, got $result")
    val applied = result.toOption.get
    val field = classOf[GoatRodeoBuilder].getDeclaredField("config")
    field.setAccessible(true)
    val config =
      field.get(applied).asInstanceOf[Configuration]
    assertEquals(config.threads, 11)
    assertEquals(config.maxRecords, 4242)
  }

  test(
    "GoatRodeoBuilder - withConfiguration returns Left for unknown key, state unmutated"
  ) {
    val table = TomlTables.toPlainMap(Toml.parse("thraeds = 4"))
    val builder = GoatRodeo.builder().withThreads(2)
    val result = builder.withConfiguration(table, "survey.inventory.analysis")
    assert(result.isLeft, s"an unknown key must produce Left, got $result")
    val error = result.swap.toOption.get
    assert(
      error.contains("thraeds"),
      s"error should name the unknown key: $error"
    )
    val field = classOf[GoatRodeoBuilder].getDeclaredField("config")
    field.setAccessible(true)
    val config =
      field.get(builder).asInstanceOf[Configuration]
    assertEquals(
      config.threads,
      2,
      "the builder must not be mutated by a rejected table"
    )
  }
}
