import goatrodeo.toplevel.SilentReaper
import java.io.File
class SilentReaperTests extends munit.FunSuite {
  test("example test that succeeds") {
    val obtained = 42
    val expected = 42
    assertEquals(obtained, expected)
  }
  var (
    artifactToContainer: Map[String, String],
    containerToArtifacts: Map[String, List[String]],
    artifactSet: Set[String]
  ) = SilentReaper.readGrim(new File("data"))

  test("Read grim") {

    assert(artifactToContainer.size > 20, "Have to read some stuff")
    assert(
      artifactToContainer.size > containerToArtifacts.size,
      "Flat is larger than deep"
    )

  }

  test("hidden2.jar") {
    val res = SilentReaper.testAFile(
      new File("test_data/hidden2.jar"),
      artifactToContainer,
      containerToArtifacts,
      artifactSet
    )
    assert(res.size > 0, "There must be some problems found")
    assert(
      res.contains(
        "gitoid:blob:sha256:b2197d2875cc31da10a1ba848e2c615f4b5cb3ecfdd1653475164864486425ba"
      ),
      "Must find spring URI component"
    )
  }

  test("test_data/log4j-core-2.22.1.jar") {
    val res = SilentReaper.testAFile(
      new File("test_data/log4j-core-2.22.1.jar"),
      artifactToContainer,
      containerToArtifacts,
      artifactSet
    )
    assert(res.size == 0, "log4j 2.22 should be clean")
  }

}
