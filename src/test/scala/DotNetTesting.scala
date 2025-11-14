import io.spicelabs.goatrodeo.util.ArtifactWrapper
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties

import java.io.File
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.cilantro.AssemblyDefinition
import io.spicelabs.goatrodeo.omnibor.strategies.DotnetState

class DotNetTesting extends munit.FunSuite {
  test("get-me-a-mime") {
    val path = "test_data/Smoke.dll"
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path)
    val input = TikaInputStream.get(File(path), metadata)
    val mime = ArtifactWrapper.mimeTypeFor(input, path)
    assertEquals("application/x-msdownload; format=pe32-dotnet", mime)
  }
  test("get-me-a-mime-exe") {
    val path = "test_data/hackproj.dll"
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path)
    val input = TikaInputStream.get(File(path), metadata)
    val mime = ArtifactWrapper.mimeTypeFor(input, path)
    assertEquals("application/x-msdownload; format=pe32-dotnet", mime)
  }

test("Can build for a simple dotnet file") {
    val name = "test_data/Smoke.dll"
    val wrapper = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(wrapper, args = Config())
    val gitoid = store1.keys().find(key => key.startsWith("gitoid"))
    assertEquals("gitoid:blob:sha1:4b71d999259c4f7b593a13df83c4f5d3bbf760a0", gitoid.get)
  }

  test("mime-from-nupkg") {
    val path = "test_data/awesomeassertions.9.3.0.nupkg"
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path)
    val input = TikaInputStream.get(File(path), metadata)
    val mime = ArtifactWrapper.mimeTypeFor(input, path);
    assertEquals("application/zip", mime)
  }

  test("assembly-references") {
    val name = "test_data/hackproj.dll"
    val assembly = AssemblyDefinition.readAssembly(name)
    assert(assembly != null)
    val deps = DotnetState.formatDeps(assembly.mainModule.assemblyReferences)
    assertEquals(deps, "{ \"dependencies\": [ { \"name\": \"System.Console\", \"version\": \"9.0.0.0\", \"public_key_token\": \"b03f5f7f11d50a3a\" }, { \"name\": \"System.Runtime\", \"version\": \"9.0.0.0\", \"public_key_token\": \"b03f5f7f11d50a3a\" } ] }")
  }
}
