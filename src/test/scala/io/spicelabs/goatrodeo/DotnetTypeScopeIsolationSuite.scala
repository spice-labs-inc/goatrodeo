package io.spicelabs.goatrodeo

import io.spicelabs.goatrodeo.omnibor.Edge
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.strategies.DotnetState
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File
import scala.collection.immutable.TreeSet

/** Regression: a nested assembly's `cilantro/type` children belong to the
  * nested assembly only. The outer assembly must not report them as its own
  * types.
  *
  * Pins `DotnetState.generateParentScope.accumulateInfo`, which ignores
  * `parentId`. `ParentScope.passToParent` offers every child to both its own
  * scope and the grandparent scope, so without a `parentId == scopeFor()` guard
  * the outer assembly's `cilantro:TypeJson` absorbs the inner one's.
  */
class DotnetTypeScopeIsolationSuite extends GoatRodeoFunSuite {
  private given Configuration = Configuration()

  private val typeJsonKey =
    MetadataKeyConstants.adHoc("cilantro")("TypeJson")

  private def item(id: String): Item =
    Item(id, TreeSet.empty[Edge], Some("application/octet-stream"), None)

  test("a nested assembly's types are not accumulated on the outer assembly") {
    val store = MemStorage(None)
    val marker = SingleMarker()
    val outerArt =
      FileWrapper(
        new File("test_data/dotnet/OuterApp.dll"),
        "OuterApp.dll",
        None
      )
    val innerArt =
      FileWrapper(
        new File("test_data/dotnet/InnerLib1.dll"),
        "InnerLib1.dll",
        None
      )
    val outerItem = item("outer")
    val innerItem = item("inner")
    store.write("outer", _ => Some(outerItem), _ => "test")
    store.write("inner", _ => Some(innerItem), _ => "test")

    val outerState = DotnetState().beginProcessing(outerArt, outerItem, marker)
    val outerScope =
      outerState.generateParentScope(
        outerArt,
        outerItem,
        store,
        marker,
        None,
        Map()
      )
    val innerState = DotnetState().beginProcessing(innerArt, innerItem, marker)
    val innerScope = innerState.generateParentScope(
      innerArt,
      innerItem,
      store,
      marker,
      Some(outerScope),
      Map()
    )

    // A class child of the INNER assembly.
    val classArt = ByteWrapper(
      """{"name":"Lib1"}""".getBytes("UTF-8"),
      "Lib1",
      None,
      Set("cilantro/type")
    )
    val classItem = item("class-lib1")
    innerScope.passToParent(Some("inner"), classItem, classArt, store)

    innerState.applyAccumulatedAugmentation(innerItem, innerArt, store)
    outerState.applyAccumulatedAugmentation(outerItem, outerArt, store)

    def hasTypeJson(id: String): Boolean =
      store
        .read(id)
        .flatMap(_.bodyAsItemMetaData)
        .exists(_.extra.contains(typeJsonKey))

    assert(
      hasTypeJson("inner"),
      "control: the inner assembly must own the type"
    )
    assert(
      !hasTypeJson("outer"),
      "the outer assembly must not absorb the nested assembly's cilantro/type children"
    )
  }
}
