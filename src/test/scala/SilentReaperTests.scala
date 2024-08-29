import goatrodeo.toplevel.SilentReaper
import java.io.File
class SilentReaperTests extends munit.FunSuite {
  test("example test that succeeds") {
    val obtained = 42
    val expected = 42
    assertEquals(obtained, expected)
  }

  test("Read grim") {
    val (flat, deep) = SilentReaper.readGrim(new File("data"))
    
    assert(flat.size > 20, "Have to read some stuff")
    assert(flat.size > deep.size, "Flat is larger than deep")

  }
}
