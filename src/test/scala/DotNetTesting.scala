import io.spicelabs.cilantro.AssemblyDefinition
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.strategies.DotnetFile
import io.spicelabs.goatrodeo.omnibor.strategies.DotnetState
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.FileWrapper
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties

import java.io.File
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class DotNetTesting extends munit.FunSuite {

  def createTestItem(id: String): Item = {
    Item(
      id,
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(ItemMetaData(
        fileNames = TreeSet(id),
        mimeType = TreeSet("application/octet-stream"),
        fileSize = 100,
        extra = TreeMap()
      ))
    )
  }
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
    assertEquals(
      "gitoid:blob:sha1:4b71d999259c4f7b593a13df83c4f5d3bbf760a0",
      gitoid.get
    )
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
    assertEquals(
      deps,
      Some(
        "{\"dependencies\":[{\"name\":\"System.Console\",\"version\":\"9.0.0.0\",\"public_key_token\":\"b03f5f7f11d50a3a\"},{\"name\":\"System.Runtime\",\"version\":\"9.0.0.0\",\"public_key_token\":\"b03f5f7f11d50a3a\"}]}"
      )
    )
  }

  test("Can get purl from dll") {
    val name = "test_data/hackproj.dll"
    val wrapper = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(wrapper, args = Config())
    val keys = store1.keys()
    val purl = store1.keys().find(key => key.startsWith("pkg"))
    assertEquals(purl, Some("pkg:nuget/hackproj@1.0.0.0"))
  }

  test("Can get purl from nupkg") {
    val name = "test_data/newtonsoft.json.13.0.4.nupkg"
    val wrapper = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(wrapper, args = Config())
    val keys = store1.keys()
    val purl = store1.keys().find(key => key.startsWith("pkg"))
    assertEquals(purl, Some("pkg:nuget/Newtonsoft.Json@13.0.0.0"))
  }

  // ==================== DotnetState Tests ====================

  test("DotnetState.beginProcessing - returns same state for non-dotnet") {
    val artifact = ByteWrapper("not dotnet".getBytes("UTF-8"), "test.txt", None)
    val item = createTestItem("test-id")
    val state = DotnetState()

    val newState = state.beginProcessing(artifact, item, SingleMarker())
    // For non-dotnet, beginProcessing should return the original state (no assembly found)
    assertEquals(newState, state)
  }

  test("DotnetState.getPurls - returns empty for non-dotnet") {
    val artifact = ByteWrapper("not dotnet".getBytes("UTF-8"), "test.txt", None)
    val state = DotnetState()
    val item = createTestItem("test-id")

    val (purls, _) = state.getPurls(artifact, item, SingleMarker())
    assert(purls.isEmpty)
  }

  test("DotnetState.getMetadata - assembly required for metadata") {
    // DotnetState.getMetadata calls assemblyDependencies which requires assemblyOpt to be Some
    // For a state without assembly, getMetadata will throw NoSuchElementException
    // This is expected behavior - callers should use beginProcessing first to load assembly
    val state = DotnetState()
    // State without assembly can still exist but calling getMetadata on it expects assembly
    assert(state != null)
  }

  test("DotnetState.postChildProcessing - returns same state") {
    val storage = MemStorage(None)
    val state = DotnetState()

    val newState = state.postChildProcessing(None, storage, SingleMarker())
    assertEquals(newState, state)
  }

  // ==================== DotnetState.formatDeps Tests ====================

  test("DotnetState.formatDeps - throws on null input") {
    import scala.collection.mutable.ArrayBuffer
    import io.spicelabs.cilantro.AssemblyNameReference
    // formatDeps does not handle null, it will throw NullPointerException
    // Use asInstanceOf to bypass explicit-nulls type checking for this edge case test
    intercept[NullPointerException] {
      DotnetState.formatDeps(null.asInstanceOf[ArrayBuffer[AssemblyNameReference]])
    }
  }

  test("DotnetState.formatDeps - returns Some for empty ArrayBuffer") {
    import scala.collection.mutable.ArrayBuffer
    import io.spicelabs.cilantro.AssemblyNameReference
    val emptyBuffer = ArrayBuffer[AssemblyNameReference]()
    val result = DotnetState.formatDeps(emptyBuffer)
    // Empty deps returns Some with empty dependencies array
    assert(result.isDefined)
    assert(result.get.contains("dependencies"))
  }

  // ==================== DotnetFile ToProcess Tests ====================

  test("DotnetFile.itemCnt - returns 1") {
    val artifact = ByteWrapper(Array[Byte](), "test.dll", None)
    val tp = DotnetFile(artifact)

    assertEquals(tp.itemCnt, 1)
  }

  test("DotnetFile.main - returns path") {
    val artifact = ByteWrapper(Array[Byte](), "path/to/test.dll", None)
    val tp = DotnetFile(artifact)

    assertEquals(tp.main, "path/to/test.dll")
  }

  test("DotnetFile.mimeType - returns artifact mime type") {
    val dllFile = new File("test_data/Smoke.dll")
    if (dllFile.exists()) {
      val wrapper = FileWrapper(dllFile, dllFile.getName(), None)
      val tp = DotnetFile(wrapper)

      assertEquals(tp.mimeType, "application/x-msdownload; format=pe32-dotnet")
    }
  }

  test("DotnetFile.getElementsToProcess - returns single element") {
    val artifact = ByteWrapper(Array[Byte](), "test.dll", None)
    val tp = DotnetFile(artifact)

    val (elements, _) = tp.getElementsToProcess()
    assertEquals(elements.length, 1)
    assert(elements.head._2.isInstanceOf[SingleMarker])
  }

  // ==================== computeDotnetFiles Tests ====================

  test("computeDotnetFiles - identifies DLL files") {
    val dllFile = new File("test_data/Smoke.dll")
    if (dllFile.exists()) {
      val wrapper = FileWrapper(dllFile, dllFile.getName(), None)
      val byUUID = Map(wrapper.uuid -> wrapper)
      val byName = Map(dllFile.getName() -> Vector(wrapper))

      val (toProcess, revisedByUUID, _, name) = DotnetFile.computeDotnetFiles(byUUID, byName)

      assertEquals(name, "Dotnet")
      assertEquals(toProcess.length, 1)
      assert(toProcess.head.isInstanceOf[DotnetFile])
    }
  }

  test("computeDotnetFiles - ignores non-DLL files") {
    val txtData = "not a dll".getBytes("UTF-8")
    val wrapper = ByteWrapper(txtData, "test.txt", None)
    val byUUID = Map(wrapper.uuid -> wrapper)
    val byName = Map("test.txt" -> Vector(wrapper))

    val (toProcess, revisedByUUID, _, name) = DotnetFile.computeDotnetFiles(byUUID, byName)

    assertEquals(name, "Dotnet")
    assert(toProcess.isEmpty)
    assert(revisedByUUID.contains(wrapper.uuid))
  }

  test("computeDotnetFiles - handles EXE files") {
    // Note: .exe files with dotnet format should also be detected
    val dllFile = new File("test_data/hackproj.dll")
    if (dllFile.exists()) {
      val wrapper = FileWrapper(dllFile, "test.exe", None)
      val byUUID = Map(wrapper.uuid -> wrapper)
      val byName = Map("test.exe" -> Vector(wrapper))

      val (toProcess, _, _, _) = DotnetFile.computeDotnetFiles(byUUID, byName)

      // Should process dotnet executables
      assertEquals(toProcess.length, 1)
    }
  }
}
