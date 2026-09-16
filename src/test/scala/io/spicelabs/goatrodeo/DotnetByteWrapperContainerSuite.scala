package io.spicelabs.goatrodeo

import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File
import java.nio.file.Files

/** Regression: a .NET assembly held in memory (a `ByteWrapper`, which is what
  * every DLL extracted from a jar/zip/nupkg/outer assembly under the in-memory
  * cap becomes) must be expanded as a container exactly like a `FileWrapper`.
  *
  * Pins `FileWalker.asDotnetAssemblyContainer`: it evaluates `in.withFile(f =>
  * f.toPath)` and lets the `Path` escape the `withFile` scope.
  * `ByteWrapper.withFile` deletes its temp file in `finally`, so Cilantro is
  * handed a path to a file that no longer exists and the failure is swallowed
  * into `None`.
  */
class DotnetByteWrapperContainerSuite extends GoatRodeoFunSuite {
  private given Configuration = Configuration()

  private val outer = new File("test_data/dotnet/OuterApp.dll")

  private def nestedDllPresent(store: Storage): Boolean =
    store.keys().exists { k =>
      store.read(k).exists { item =>
        item.bodyAsItemMetaData.exists(
          _.fileNames.exists(_.contains("InnerLib1"))
        )
      }
    }

  test("control: OuterApp.dll as a FileWrapper expands its nested DLLs") {
    val store = ToProcess.buildGraphFromArtifactWrapper(
      FileWrapper(outer, "OuterApp.dll", None)
    )
    assert(
      nestedDllPresent(store),
      "fixture sanity: the on-disk OuterApp.dll must yield InnerLib1.dll"
    )
  }

  test("OuterApp.dll as a ByteWrapper expands its nested DLLs") {
    val bytes = Files.readAllBytes(outer.toPath)
    val store = ToProcess.buildGraphFromArtifactWrapper(
      ByteWrapper(bytes, "OuterApp.dll", None)
    )
    assert(
      nestedDllPresent(store),
      "an in-memory assembly must be expanded as a container (InnerLib1.dll missing)"
    )
  }
}
