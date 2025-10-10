import io.spicelabs.goatrodeo.util.IncludeExclude
import io.spicelabs.goatrodeo.util.RegexPredicate

import scala.util.matching.Regex

class IncExcTesting extends munit.FunSuite {
  test("none-accepted") {
    val predicates = RegexPredicate(Set[String](), Vector[Regex]())
    assert(!predicates.matches("anything"))
  }

  test("exact-match") {
    val key = "splunge"
    val predicates = RegexPredicate(Set(key), Vector[Regex]())
    assert(predicates.matches(key))
  }

  test("regex-match") {
    val key = "splunge"
    val predicates = RegexPredicate(Set[String](), Vector("spl\\w*".r))
    assert(predicates.matches(key))
  }

  test("includes-all") {
    val includer = IncludeExclude(
      Set[String](),
      Vector[Regex](),
      Set[String](),
      Vector[Regex]()
    )
    assert(includer.shouldInclude("all"))
  }

  test("excludes-all-text-MIME") {
    val includer = IncludeExclude(
      Set[String](),
      Vector[Regex](),
      Set[String](),
      Vector("text\\/.*".r)
    )
    assert(!includer.shouldInclude("text/html"))
    assert(!includer.shouldInclude("text/plain"))
    assert(!includer.shouldInclude("text/json"))
  }

  test("excludes-all-text-MIME-but-HTML") {
    val includer = IncludeExclude(
      Set("text/html"),
      Vector[Regex](),
      Set[String](),
      Vector("text\\/.*".r)
    )
    assert(includer.shouldInclude("text/html"))
    assert(!includer.shouldInclude("text/plain"))
    assert(!includer.shouldInclude("text/json"))
  }

  test("excludes-all-text-MIME-but-hanything") {
    val includer = IncludeExclude(
      Set("text/html"),
      Vector("text\\/h.*".r),
      Set[String](),
      Vector("text\\/.*".r)
    )
    assert(includer.shouldInclude("text/html"))
    assert(includer.shouldInclude("text/howdy"))
    assert(!includer.shouldInclude("text/plain"))
    assert(!includer.shouldInclude("text/json"))
  }

  test("comment") {
    val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
      "# this is a comment",
      (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
    )
    assertEquals(incE.size, 0)
    assertEquals(incR.size, 0)
    assertEquals(excE.size, 0)
    assertEquals(excR.size, 0)
  }

  test("include-exact") {
    val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
      "+splunge",
      (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
    )
    assertEquals(incE.size, 1)
    assertEquals(incR.size, 0)
    assertEquals(excE.size, 0)
    assertEquals(excR.size, 0)
  }

  test("include-regx") {
    val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
      "*foo.*",
      (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
    )
    assertEquals(incE.size, 0)
    assertEquals(incR.size, 1)
    assertEquals(excE.size, 0)
    assertEquals(excR.size, 0)
  }

  test("exclude-exact") {
    val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
      "-foo",
      (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
    )
    assertEquals(incE.size, 0)
    assertEquals(incR.size, 0)
    assertEquals(excE.size, 1)
    assertEquals(excR.size, 0)
  }

  test("exclude-regex") {
    val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
      "/foo.*",
      (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
    )
    assertEquals(incE.size, 0)
    assertEquals(incR.size, 0)
    assertEquals(excE.size, 0)
    assertEquals(excR.size, 1)
  }

  test("bad-command") {
    intercept[IllegalArgumentException] {
      val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
        "&foo.*",
        (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
      )
    }
  }

  test("empty-comment") {
    val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
      "#",
      (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
    )
  }

  test("empty-command") {
    val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
      "+",
      (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
    )
    assertEquals(incE.size, 0)
    assertEquals(incR.size, 0)
    assertEquals(excE.size, 0)
    assertEquals(excR.size, 0)
  }
}
