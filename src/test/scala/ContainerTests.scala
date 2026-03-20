import io.spicelabs.goatrodeo.util.Containers

class ContainerTests extends munit.FunSuite {
  test("can get names") {
    val names = Containers.factoryNames()
    assertEquals(3, names.length)
  }

  test("can remove one") {
    val beforeRemoveLength = Containers.factoryNames().length
    Containers.removeContainerFactory("Zip Container")
    val afterRemoveLength = Containers.factoryNames().length
    Containers.reset()
    val afterResetLength = Containers.factoryNames().length
    assert(beforeRemoveLength > afterRemoveLength)
    assertEquals(beforeRemoveLength, afterResetLength)
  }

}
