package io.spicelabs.goatrodeo

import io.spicelabs.cilantro.AssemblyWalker
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File

/** Cilantro 0.3.1 nested-assembly recursion (GRW-7).
  *
  * WHAT: a .NET assembly that embeds other .NET assemblies as resources
  * (the Costura-style "embed referenced DLLs" pattern) must have those
  * nested DLLs recursed as containers: their classes/resources/certs
  * surface in the graph, not just the outer assembly's entries.
  *
  * Fixture: test_data/dotnet/OuterApp.dll embeds InnerLib1.dll,
  * InnerLib2.dll, InnerLib3.dll as EmbeddedResource entries (real,
  * Docker-built .NET SDK output — verified each inner is a PE).
  *
  * WHY: the container model must traverse .NET assemblies like any other
  * container (zip-in-zip): an embedded resource that is itself an assembly
  * is a container, and FileWalker recurses into it.
  *
  * LLM note: this pins the recursion gap fix. The inner DLLs arrive as
  * `pe/resource`-mimed wrappers (the EmbeddedResource hint) — not the
  * dotnet MIME — so the recursion must be triggered by the cheap
  * DotnetAssemblyProbe (content), not just the MIME gate.
  */
class DotnetNestedAssemblySuite extends FunSuite {

  given Configuration = Configuration(
    tempDir = None,
    threads = 4,
    maxRecords = 10000,
    blockList = None,
    fsFilePaths = true
  )

  test("nested-1 OuterApp.dll embeds the 3 inner DLLs (fixture sanity)") {
    val innerNames = AssemblyWalker
      .withinAssemblyStream(new File("test_data/dotnet/OuterApp.dll")) {
        entries =>
          entries
            .filter(_.kind.toString == "EmbeddedResource")
            .map(_.name)
      }(None)
      .get
    assert(innerNames.nonEmpty, "OuterApp must have embedded resources")
    innerNames.foreach { n =>
      assert(
        n.endsWith(".dll"),
        s"every embedded resource should be a DLL (nested assembly), got $n"
      )
    }
    assert(innerNames.size >= 3, s"expected 3 inner DLLs, got ${innerNames.size}")
  }

  test("nested-3 the full graph recurses into embedded assemblies") {
    val wrapper =
      FileWrapper(new File("test_data/dotnet/OuterApp.dll"), "OuterApp.dll", None)
    val store = ToProcess.buildGraphFromArtifactWrapper(wrapper)
    // OuterApp's own class:
    val outerClass = store.keys().exists { k =>
      store.read(k).exists(_.bodyAsItemMetaData.exists(
        _.mimeType.contains("cilantro/type")
      ))
    }
    assert(outerClass, "OuterApp's own class must be in the graph")
    // The nested InnerLib1's class (Lib1) must ALSO be in the graph with
    // cilantro/type — proving recursion into the embedded assembly.
    val nestedClasses = store.keys().count { k =>
      store.read(k).exists { item =>
        item.bodyAsItemMetaData.exists { m =>
          m.mimeType.contains("cilantro/type") &&
          m.fileNames.exists(_.contains("Lib1"))
        }
      }
    }
    assert(
      nestedClasses >= 1,
      s"InnerLib1's class must be recursed (cilantro/type), got $nestedClasses nested class items"
    )
  }
}