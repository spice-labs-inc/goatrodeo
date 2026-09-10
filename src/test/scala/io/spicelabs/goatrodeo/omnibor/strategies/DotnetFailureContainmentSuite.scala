/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Edge
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*

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
  * is the `azure-cosmos-spark jar survives` test below, fed from the public
  * test-data host (downloaded by the build setup into `test_data/download/`).
  */
class DotnetFailureContainmentSuite extends GoatRodeoFunSuite {

  private given Configuration = Configuration()

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

  test(
    "the azure-cosmos-spark jar survives with its inner native DLL present"
  ) {
    // The real-world case that motivated the containment work: every version
    // of azure-cosmos-spark carries applicationinsights-core-native-win64.dll,
    // a PE-shaped native binary whose debug type (13 = POGO) Cilantro 0.4.0
    // cannot map. Before containment, the parse failure escaped the zip walk
    // and the whole JAR (tens of thousands of entries) was lost from the
    // output. This test walks the real corpus member end to end and demands
    // the container survive:
    //   - the run-wide container-failure counter stays at zero,
    //   - the storage holds far more than the handful of items a walk dying
    //     at the DLL would produce,
    //   - the DLL itself is an item in the storage.
    val jar =
      File("test_data/download/azure-cosmos-spark_3-3_2-12-4.18.1.jar")
    assert(
      jar.exists(),
      "the azure jar must be present; the build setup downloads it from https://public-test-data.spice-labs.dev/ when missing"
    )

    val storage = MemStorage(None)
    val failedContainers = new AtomicInteger(0)
    val toProcess = ToProcess
      .strategiesForArtifacts(
        Vector(FileWrapper(jar, jar.getName(), None)),
        _ => (),
        false
      )
      .head
    toProcess.process(
      None,
      storage,
      ParentScope.forAndWith("azure-cosmos-spark", None, Map()),
      tag = None,
      failedContainers = failedContainers
    )

    assertEquals(
      failedContainers.get(),
      0,
      "the jar's walk must not fail the container wholesale"
    )
    val itemCount = storage.keys().size
    assert(
      itemCount > 1000,
      f"a surviving walk yields thousands of inner items, got ${itemCount}"
    )
    val dllIdentifier = readInnerDllIdentifier(jar)
    assert(
      storage.keys().contains(dllIdentifier),
      f"the inner native DLL must be an item in the storage (${dllIdentifier})"
    )
  }

  /** The content gitoid of the native DLL inside the jar, computed with the
    * same canonical hashing the walk itself uses (a gitoid is the git-framed
    * digest, not a raw content digest), so the pin cannot drift from the
    * pipeline's own identifier scheme.
    */
  private def readInnerDllIdentifier(jar: File): String = {
    import io.spicelabs.goatrodeo.util.GitOIDUtils
    val zip = new ZipFile(jar)
    try {
      val entry = zip
        .entries()
        .asScala
        .find(_.getName().endsWith("applicationinsights-core-native-win64.dll"))
        .getOrElse(
          throw new RuntimeException(
            "applicationinsights-core-native-win64.dll not found inside the azure jar"
          )
        )
      val bytes =
        try zip.getInputStream(entry).readAllBytes()
        finally zip.getInputStream(entry).close()
      val tmp = Files.createTempFile("azure-dll", ".dll")
      try {
        Files.write(tmp, bytes)
        val (id, _) = GitOIDUtils.computeAllHashes(
          FileWrapper(
            tmp.toFile(),
            "applicationinsights-core-native-win64.dll",
            None
          )
        )
        id
      } finally Files.delete(tmp)
    } finally zip.close()
  }
}
