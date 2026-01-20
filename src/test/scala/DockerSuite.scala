import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.strategies.DockerMarkers
import io.spicelabs.goatrodeo.omnibor.strategies.DockerState
import io.spicelabs.goatrodeo.omnibor.strategies.DockerToProcess
import io.spicelabs.goatrodeo.omnibor.strategies.ManifestInfo
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.FileWrapper
import org.json4s.*
import org.json4s.JsonAST.*
import org.json4s.native.*

import java.io.File
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
class DockerSuite extends munit.FunSuite {
  val logger = Logger(getClass())

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

  test("Can build for a simple Docker file") {
    val name = "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar"

    val nested = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    val result = store1.purls()

    assertEquals(
      result,
      TreeSet("pkg:docker/bigtent@2025_03_22")
    )

    val item = store1.read("pkg:docker/bigtent@2025_03_22").get
    val aliasTo = item.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get

    assertEquals(
      aliasTo,
      "gitoid:blob:sha256:9a6a5302b3ea1ccc5c4a8cd58c032eb5e1b257a5a4837c3ddc4d8e8af9954dbe"
    )

    testLayersAndManifest(aliasTo, store1)

  }

  test("Merging names works") {
    val a = ItemMetaData(TreeSet("foo"), TreeSet(), 1, TreeMap())
    val b = ItemMetaData(TreeSet("bar"), TreeSet(), 1, TreeMap())

    val aGitoids = () => Vector("yak", "moose")
    val bGitoids = () => Vector("dog", "cat")

    val mergedAA = a.merge(a, aGitoids, bGitoids)
    assertEquals(a, mergedAA, "merging with self should be same")

    val mergedAB = a.merge(b, aGitoids, bGitoids)
    assertNotEquals(a, mergedAB, "They should differ")
    assert(
      mergedAB.fileNames.size == 6,
      f"there should be 6 different filenames, but got ${mergedAB.fileNames}"
    )
  }

  test("Can build for a complex file") {
    val name = "test_data/download/docker_tests/grinder_bt_pg_docker.tar"

    val nested = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    val result = store1.purls()
    val expectedpurls = TreeSet(
      "pkg:docker/postgres@16.6",
      "pkg:docker/postgres@9.6.12",
      "pkg:docker/spicelabs%2Fbigtent@0.8.3",
      "pkg:docker/spicelabs%2Fbigtent@latest",
      "pkg:docker/spicelabs%2Fgrinder@0.1.0",
      "pkg:docker/spicelabs%2Fgrinder@latest"
    )

    assertEquals(result, expectedpurls)

    for {
      purl <- expectedpurls
    } {
      val item = store1.read(purl).get
      val aliasTo = item.connections
        .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
        .headOption
        .get

      testLayersAndManifest(aliasTo, store1)
    }
  }

  private def testLayersAndManifest(
      identifier: String,
      store1: Storage
  ): Unit = {
    val item2 = store1.read(identifier).get
    val extraMetadata = item2.body.get.asInstanceOf[ItemMetaData].extra
    val config = extraMetadata.get("docker_config").get
    val manifest = extraMetadata.get("docker_manifest").get

    assertEquals(config.size, 1)
    assertEquals(manifest.size, 1)

    val configJson = parseJsonOpt(config.head.value).get

    val mimeTypes = item2.body.get.asInstanceOf[ItemMetaData].mimeType

    assert(
      mimeTypes.contains("application/vnd.oci.image"),
      s"Should have ${"application/vnd.oci.image"} "
    )

    val layers = for {
      case JArray(layers) <- configJson \ "rootfs" \ "diff_ids"
      case JString(layer_id) <- layers
    } yield layer_id

    assert(layers.length > 0, "Must have at least one layer")

    for {
      layer <- layers
    } {
      val layerItem = antiAlias(layer, store1)
      assert(
        layerItem.connections.size > 3,
        f"Layer ${layer} must have more than 3 files, found ${layerItem.connections.size}"
      )
      assert(
        layerItem.body.get
          .asInstanceOf[ItemMetaData]
          .mimeType
          .contains("application/vnd.oci.image.layer.v1.tar"),
        "layer should have layer mime type"
      )
    }
  }

  private def antiAlias(key: String, store: Storage): Item = {
    val item = store.read(key).get
    val aliasTo = item.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get
    store.read(aliasTo).get
  }

  // ==================== DockerState Tests ====================

  test("DockerState - begins with empty layer mapping") {
    val state = DockerState(Map())
    assert(state.layerToGitoidMapping.isEmpty)
  }

  test("DockerState.beginProcessing - returns same state") {
    val artifact = ByteWrapper(Array[Byte](), "test.tar", None)
    val item = createTestItem("test-id")
    val state = DockerState(Map())

    val newState = state.beginProcessing(artifact, item, DockerMarkers.Manifest)
    assertEquals(newState, state)
  }

  test("DockerState.getPurls - returns empty for Manifest marker") {
    val artifact = ByteWrapper(Array[Byte](), "manifest.json", None)
    val item = createTestItem("test-id")
    val state = DockerState(Map())

    val (purls, _) = state.getPurls(artifact, item, DockerMarkers.Manifest)
    assert(purls.isEmpty)
  }

  test("DockerState.getPurls - returns empty for Layer marker") {
    val artifact = ByteWrapper(Array[Byte](), "layer.tar", None)
    val item = createTestItem("test-id")
    val state = DockerState(Map())

    val (purls, _) = state.getPurls(artifact, item, DockerMarkers.Layer("sha256:abc"))
    assert(purls.isEmpty)
  }

  test("DockerState.getMetadata - returns empty for Manifest marker") {
    val artifact = ByteWrapper(Array[Byte](), "manifest.json", None)
    val item = createTestItem("test-id")
    val state = DockerState(Map())

    val (metadata, _) = state.getMetadata(artifact, item, DockerMarkers.Manifest)
    assert(metadata.isEmpty)
  }

  test("DockerState.getMetadata - returns empty for Layer marker") {
    val artifact = ByteWrapper(Array[Byte](), "layer.tar", None)
    val item = createTestItem("test-id")
    val state = DockerState(Map())

    val (metadata, _) = state.getMetadata(artifact, item, DockerMarkers.Layer("sha256:abc"))
    assert(metadata.isEmpty)
  }

  test("DockerState.postChildProcessing - returns same state") {
    val storage = MemStorage(None)
    val state = DockerState(Map())

    val newState = state.postChildProcessing(None, storage, DockerMarkers.Manifest)
    assertEquals(newState, state)
  }

  // ==================== DockerToProcess Tests ====================

  test("DockerToProcess.itemCnt - calculates total items") {
    val manifest = ByteWrapper(Array[Byte](), "manifest.json", None)
    val tp = DockerToProcess(manifest, List(), Map())

    assertEquals(tp.itemCnt, 1) // just manifest, no config, no layers
  }

  test("DockerToProcess.mimeType - returns manifest mime type") {
    val manifest = ByteWrapper("""[{}]""".getBytes("UTF-8"), "manifest.json", None)
    val tp = DockerToProcess(manifest, List(), Map())

    assertEquals(tp.mimeType, "application/json")
  }

  test("DockerToProcess.main - includes manifest path") {
    val manifest = ByteWrapper(Array[Byte](), "path/manifest.json", None)
    val tp = DockerToProcess(manifest, List(), Map())

    assert(tp.main.contains("path/manifest.json"))
  }

  // ==================== computeDockerFiles Edge Cases ====================

  test("computeDockerFiles - returns empty for no manifest.json") {
    val artifact = ByteWrapper(Array[Byte](), "other.txt", None)
    val byUUID = Map(artifact.uuid -> artifact)
    val byName = Map("other.txt" -> Vector(artifact))

    val (toProcess, _, _, name) = DockerToProcess.computeDockerFiles(byUUID, byName)

    assertEquals(name, "Docker")
    assert(toProcess.isEmpty)
  }

  test("computeDockerFiles - handles invalid JSON manifest") {
    val manifest = ByteWrapper("not json".getBytes("UTF-8"), "manifest.json", None)
    val byUUID = Map(manifest.uuid -> manifest)
    val byName = Map("manifest.json" -> Vector(manifest))

    val (toProcess, _, _, _) = DockerToProcess.computeDockerFiles(byUUID, byName)

    assert(toProcess.isEmpty)
  }

  test("computeDockerFiles - handles empty JSON array manifest") {
    val manifest = ByteWrapper("[]".getBytes("UTF-8"), "manifest.json", None)
    val byUUID = Map(manifest.uuid -> manifest)
    val byName = Map("manifest.json" -> Vector(manifest))

    val (toProcess, _, _, _) = DockerToProcess.computeDockerFiles(byUUID, byName)

    // Empty array should result in no processing
    assert(toProcess.isEmpty)
  }

  // ==================== DockerMarkers Tests ====================

  test("DockerMarkers.Manifest - exists") {
    val marker = DockerMarkers.Manifest
    assert(marker != null)
  }

  test("DockerMarkers.Layer - stores hash") {
    val marker = DockerMarkers.Layer("sha256:abc123")
    marker match {
      case DockerMarkers.Layer(hash) => assertEquals(hash, "sha256:abc123")
      case _ => fail("Expected Layer marker")
    }
  }
}
