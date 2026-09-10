/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Edge
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.FileWrapper

import java.nio.file.Files
import scala.collection.immutable.TreeSet

/** Failure containment for the .NET strategy.
  *
  * '''WHAT:''' `DotnetState.beginProcessing` must NEVER throw when Cilantro
  * cannot parse an artifact. The real-world case is a native (non-managed) DLL
  * embedded in a fat jar, e.g. `applicationinsights-core-native-win64.dll`
  * inside `azure-cosmos-spark_3-3_2-12-*.jar`: the dotnet MIME probe accepts
  * any PE-shaped file, and Cilantro's assembly reader can fail on legitimate
  * but unmapped PE debug types (`ImageDebugType` 13 = POGO). A thrown parse
  * failure escapes the containing zip-container walk and loses the entire JAR.
  *
  * '''WHY:''' per the project rule, expected failures are values, never
  * exceptions; one unparseable inner artifact must not be able to fail its
  * container.
  *
  * '''LLM context:''' This is the unit-level pin of the containment contract;
  * the pipeline-level variant (jar survives with its inner native DLL present)
  * is covered once a real corpus member is available on the download host.
  */
class DotnetFailureContainmentSuite extends GoatRodeoFunSuite {

  test("DotnetState.beginProcessing never throws on a non-assembly artifact") {
    // A file that is not a PE/COFF assembly at all: Cilantro's reader must
    // fail inside its own Try, the failure must be contained in the state
    // value, and nothing may be thrown to the container walk.
    val blob = Files.createTempFile("dotnet-garbage", ".bin").toFile()
    Files.write(blob.toPath, "this is not a PE/COFF assembly".getBytes("UTF-8"))
    val artifact = FileWrapper(blob, blob.getPath, None)
    val item = Item(
      "sha256:" + "ab" * 32,
      TreeSet.empty[Edge],
      Some("application/octet-stream"),
      None
    )
    val state = DotnetState().beginProcessing(artifact, item, SingleMarker())
    // No exception reached here. The failure is a value: the state carries no
    // parsed assembly, and the strategy proceeds as a no-op for this artifact.
    assert(state != null)
  }
}
