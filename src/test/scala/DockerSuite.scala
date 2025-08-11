import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.FileWrapper
import org.json4s.*
import org.json4s.JsonAST.*
import org.json4s.native.*

import java.io.File
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class DockerSuite extends munit.FunSuite {
  val logger = Logger(getClass())

  test("Can build for a simple Docker file") {
    val name = "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar"

    val nested = FileWrapper(File(name), name, None)
    val store1 = ToProcess.buildGraphFromArtifactWrapper(nested)

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
      "gitoid:blob:sha256:7070a741d71c9e9e95c4b514a1fafc1b35275d512d9e95ea29fc2b075c03660f"
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
    val store1 = ToProcess.buildGraphFromArtifactWrapper(nested)

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
      mimeTypes.contains("application/vnd.oci.image.config.v1+json"),
      s"Should have ${"application/vnd.oci.image.config.v1+json"} "
    )
    assert(
      mimeTypes.contains("application/vnd.oci.image.manifest.v1+json"),
      s"Should have ${"application/vnd.oci.image.manifest.v1+json"} "
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

    val configSha = "sha256:" + (parseJson(manifest.head.value) \ "Config")
      .asInstanceOf[JString]
      .s
      .substring(13)
    val configItem = antiAlias(configSha, store = store1)
    assertEquals(item2, configItem, "Manifest should refer to this item")
  }

  private def antiAlias(key: String, store: Storage): Item = {
    val item = store.read(key).get
    val aliasTo = item.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get
    store.read(aliasTo).get
  }
}
