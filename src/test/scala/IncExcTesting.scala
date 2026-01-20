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

  // ==================== Additional IncludeExclude Tests ====================

  test("IncludeExclude - :+ operator adds include exact") {
    val includer = IncludeExclude() :+ "+specific"
    assert(includer.shouldInclude("specific"))
  }

  test("IncludeExclude - :+ operator adds exclude exact") {
    val includer = IncludeExclude() :+ "-excluded"
    assert(!includer.shouldInclude("excluded"))
    assert(includer.shouldInclude("other"))
  }

  test("IncludeExclude - :+ operator adds include regex") {
    val includer = IncludeExclude() :+ "*test.*"
    assert(includer.shouldInclude("testing"))
    assert(includer.shouldInclude("test123"))
  }

  test("IncludeExclude - :+ operator adds exclude regex") {
    val includer = IncludeExclude() :+ "/debug.*"
    assert(!includer.shouldInclude("debugging"))
    assert(includer.shouldInclude("info"))
  }

  test("IncludeExclude - ++ operator chains multiple predicates") {
    val includer = IncludeExclude() ++ Vector("+allowed", "-forbidden", "/temp.*")
    assert(includer.shouldInclude("allowed"))
    assert(!includer.shouldInclude("forbidden"))
    assert(!includer.shouldInclude("temporary"))
    assert(includer.shouldInclude("permanent"))
  }

  test("IncludeExclude - include overrides exclude for specific item") {
    val includer = IncludeExclude() ++ Vector("/.*\\.log", "+important.log")
    assert(!includer.shouldInclude("error.log"))
    assert(includer.shouldInclude("important.log"))
  }

  test("IncludeExclude - default constructor allows everything") {
    val includer = new IncludeExclude()
    assert(includer.shouldInclude("anything"))
    assert(includer.shouldInclude(""))
    assert(includer.shouldInclude("test/path/file.txt"))
  }

  test("IncludeExclude - empty predicate string is ignored") {
    val (incE, incR, excE, excR) = IncludeExclude.aggregatePredicate(
      "",
      (Set[String](), Vector[Regex](), Set[String](), Vector[Regex]())
    )
    assertEquals(incE.size, 0)
    assertEquals(incR.size, 0)
    assertEquals(excE.size, 0)
    assertEquals(excR.size, 0)
  }

  test("RegexPredicate - matches with exact set") {
    val predicate = RegexPredicate(Set("exact1", "exact2"), Vector[Regex]())
    assert(predicate.matches("exact1"))
    assert(predicate.matches("exact2"))
    assert(!predicate.matches("exact3"))
  }

  test("RegexPredicate - matches with regex only") {
    val predicate = RegexPredicate(Set[String](), Vector("foo\\d+".r, "bar.*".r))
    assert(predicate.matches("foo123"))
    assert(predicate.matches("bar"))
    assert(predicate.matches("barbaz"))
    assert(!predicate.matches("baz"))
  }

  test("RegexPredicate - matches with both exact and regex") {
    val predicate = RegexPredicate(Set("exact"), Vector("pattern.*".r))
    assert(predicate.matches("exact"))
    assert(predicate.matches("pattern123"))
    assert(!predicate.matches("other"))
  }
}
