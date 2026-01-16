import io.spicelabs.goatrodeo.util.Config
import scopt.OParser

class ArgumentTests extends munit.FunSuite {
  test("component-arg-simplest") {
    val args = Array("--component", "foo")
    val parsed = OParser.parse(Config.parser1, args, Config())
    assert(parsed.isDefined)
    parsed.map(c => {
      assert(!c.componentArgs.isEmpty)
      val fooargs = c.componentArgs.getOrElse("foo", Vector[Array[String]]())
      assert(fooargs.length == 1)
      assert(fooargs(0).isEmpty)
    })
  }

  test("component-arg-multi") {
    val args = Array("--component", "foo,bar,baz")
    val parsed = OParser.parse(Config.parser1, args, Config())
    assert(parsed.isDefined)
    parsed.map(c => {
      assert(!c.componentArgs.isEmpty)
      val fooargs = c.componentArgs.getOrElse("foo", Vector[Array[String]]())
      assert(fooargs.length == 1)
      assert(fooargs(0).length == 2)
      assertEquals(fooargs(0)(0), "bar")
      assertEquals(fooargs(0)(1), "baz")
    })
  }

  test("component-arg-multi-multi") {
    val args = Array("--component", "foo,bar,baz", "--component", "foo,b,c,d")
    val parsed = OParser.parse(Config.parser1, args, Config())
    assert(parsed.isDefined)
    parsed.map(c => {
      assert(!c.componentArgs.isEmpty)
      val fooargs = c.componentArgs.getOrElse("foo", Vector[Array[String]]())
      assert(fooargs.length == 2)
      assert(fooargs(0).length == 2)
      assertEquals(fooargs(0)(0), "bar")
      assertEquals(fooargs(0)(1), "baz")
      assert(fooargs(1).length == 3)
      assertEquals(fooargs(1)(0), "b")
      assertEquals(fooargs(1)(1), "c")
      assertEquals(fooargs(1)(2), "d")
    })
  }

  test("component-arg-multi-multi-multi") {
    val args = Array("--component", "foo,bar,baz", "--component", "bing,b,c,d")
    val parsed = OParser.parse(Config.parser1, args, Config())
    assert(parsed.isDefined)
    parsed.map(c => {
      assert(!c.componentArgs.isEmpty)
      val fooargs = c.componentArgs.getOrElse("foo", Vector[Array[String]]())
      assert(fooargs.length == 1)
      assert(fooargs(0).length == 2)
      assertEquals(fooargs(0)(0), "bar")
      assertEquals(fooargs(0)(1), "baz")
      val bingargs = c.componentArgs.getOrElse("bing", Vector[Array[String]]())
      assert(bingargs.length == 1)
      assert(bingargs(0).length == 3)
      assertEquals(bingargs(0)(0), "b")
      assertEquals(bingargs(0)(1), "c")
      assertEquals(bingargs(0)(2), "d")
    })
  }

}
