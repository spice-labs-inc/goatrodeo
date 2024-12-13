import goatrodeo.toplevel.HiddenReaper
import java.io.File
class HiddenReaperTests extends munit.FunSuite {
  var (
    artifactToContainer: Map[String, String],
    containerToArtifacts: Map[String, List[String]],
    artifactSet: Set[String]
  ) = HiddenReaper.readGrim(new File("data"))

  test("Read grim") {

    assert(artifactToContainer.size > 20, "Have to read some stuff")
    assert(
      artifactToContainer.size > containerToArtifacts.size,
      "Flat is larger than deep"
    )

  }

  test("hidden2.jar") {
    val res = HiddenReaper.testAFile(
      new File("test_data/hidden2.jar"),
      artifactToContainer,
      containerToArtifacts,
      artifactSet
    )
    assert(res.get.foundMarkers.size > 0, "There must be some problems found")
    assert(
      res.get.foundMarkers.contains(
        "gitoid:blob:sha256:b2197d2875cc31da10a1ba848e2c615f4b5cb3ecfdd1653475164864486425ba"
      ),
      "Must find spring URI component"
    )
  }

  test("test_data/log4j-core-2.22.1.jar") {
    val res = HiddenReaper.testAFile(
      new File("test_data/log4j-core-2.22.1.jar"),
      artifactToContainer,
      containerToArtifacts,
      artifactSet
    )
    assert(res.isEmpty, "log4j 2.22 should be clean")
  }

}
