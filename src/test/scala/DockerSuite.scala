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
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.FileWrapper
import org.json4s.*
import org.json4s.native.JsonMethods.*

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
      Some(
        ItemMetaData(
          fileNames = TreeSet(id),
          mimeType = TreeSet("application/octet-stream"),
          fileSize = 100,
          extra = TreeMap()
        )
      )
    )
  }

  // Helper: retrieve a docker: prefixed metadata value from an item
  private def dockerMeta(
      item: Item,
      key: String
  ): Option[String] = {
    for {
      body <- item.body
      meta = body.asInstanceOf[ItemMetaData]
      values <- meta.extra.get(s"docker:$key")
      head <- values.headOption
    } yield head.value
  }

  test("Can build for a simple Docker image") {
    val name = "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar"

    val nested = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    val result = store1.purls().filter(_.startsWith("pkg:docker"))

    assertEquals(
      result,
      TreeSet("pkg:docker/bigtent@2025_03_22")
    )

    val item = store1.read("pkg:docker/bigtent@2025_03_22").get
    val aliasTo = item.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get

    // Verify the alias target is the config item (starts with gitoid:blob:sha256)
    assert(
      aliasTo.startsWith("gitoid:blob:sha256:"),
      s"Expected config gitoid but got $aliasTo"
    )

    testLayersAndManifest(aliasTo, store1)
  }

  test("ItemMetaData.merge - combines file names from both items") {
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

  test("Can build for a complex Docker image") {
    val name = "test_data/download/docker_tests/grinder_bt_pg_docker.tar"

    val nested = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    val result = store1.purls().filter(_.startsWith("pkg:docker"))
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
    assert(
      item2.body.isDefined,
      s"Config item $identifier must have a body"
    )
    val extraMetadata = item2.body.get.asInstanceOf[ItemMetaData].extra

    // Raw JSON keys should be present for audit/completeness
    assert(
      extraMetadata.get("docker:ConfigJson").isDefined,
      "docker:ConfigJson should be present"
    )
    assert(
      extraMetadata.get("docker:ManifestJson").isDefined,
      "docker:ManifestJson should be present"
    )

    // Old raw JSON keys must be absent
    assert(
      extraMetadata.get("docker_config").isEmpty,
      "docker_config raw JSON key should be absent"
    )
    assert(
      extraMetadata.get("docker_manifest").isEmpty,
      "docker_manifest raw JSON key should be absent"
    )

    // Platform should be present on every image
    val platformOpt = extraMetadata.get("docker:Platform")
    assert(platformOpt.isDefined, "docker:Platform should be present")
    val platform = platformOpt.get.head.value
    assert(
      platform.startsWith("linux/"),
      s"Platform $platform should start with linux/"
    )

    // LayerCount should be present and positive
    val layerCountOpt = extraMetadata.get("docker:LayerCount")
    assert(layerCountOpt.isDefined, "docker:LayerCount should be present")
    val layerCount = layerCountOpt.get.head.value.toInt
    assert(layerCount > 0, s"LayerCount $layerCount must be > 0")

    // Size should be present and positive
    val sizeOpt = extraMetadata.get("docker:Size")
    assert(sizeOpt.isDefined, "docker:Size should be present")
    val size = sizeOpt.get.head.value.toLong
    assert(size > 0, s"Size $size must be > 0")

    // History should have at least one entry
    val historyOpt = extraMetadata.get("docker:History")
    assert(historyOpt.isDefined, "docker:History should be present")
    assert(historyOpt.get.nonEmpty, "docker:History should not be empty")

    // Config item should have layer connections
    val connectedLayers = item2.connections.collect {
      case (EdgeType.contains, v) => v
    }
    assert(
      connectedLayers.nonEmpty,
      "Config item must have at least one layer connected"
    )

    for {
      layerGitoid <- connectedLayers
    } {
      val layerItem = store1.read(layerGitoid).get
      assert(
        layerItem.connections.size > 3,
        f"Layer ${layerGitoid} must have more than 3 files, found ${layerItem.connections.size}"
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

  // ==================== Structured Metadata Tests ====================

  test("bigtent image has correct structured metadata") {
    val name = "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar"
    val nested = FileWrapper(File(name), name, None)
    val store = ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    val purl = "pkg:docker/bigtent@2025_03_22"
    val item = store.read(purl).get
    val aliasTo = item.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get

    val configItem = store.read(aliasTo).get
    val extra = configItem.body.get.asInstanceOf[ItemMetaData].extra

    // Platform
    assertEquals(dockerMeta(configItem, "Platform"), Some("linux/amd64"))

    // Created
    assertEquals(
      dockerMeta(configItem, "Created"),
      Some("2025-03-22T13:10:35.59966703-04:00")
    )

    // Author not present
    assert(dockerMeta(configItem, "Author").isEmpty, "Author should be absent")

    // WorkingDir
    assertEquals(dockerMeta(configItem, "WorkingDir"), Some("/"))

    // Cmd
    assertEquals(dockerMeta(configItem, "Cmd"), Some("/bigtent"))

    // Entrypoint not present (empty array)
    assert(
      dockerMeta(configItem, "Entrypoint").isEmpty,
      "Entrypoint should be absent when empty"
    )

    // User not present
    assert(dockerMeta(configItem, "User").isEmpty, "User should be absent")

    // EnvCount
    assertEquals(dockerMeta(configItem, "EnvCount"), Some("1"))

    // Env vars extracted
    assertEquals(
      dockerMeta(configItem, "Env:PATH"),
      Some("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    )

    // LayerCount
    assertEquals(dockerMeta(configItem, "LayerCount"), Some("2"))

    // Size
    assertEquals(dockerMeta(configItem, "Size"), Some("16447488"))

    // History
    val history = extra
      .get("docker:History")
      .map(_.map(_.value))
      .getOrElse(TreeSet[String]())
    assert(history.nonEmpty, "History should contain entries")
    assert(
      history.exists(_.contains("ADD alpine-minirootfs")),
      "History should include ADD command"
    )

    // No labels on this image
    assert(
      extra.keys.forall(!_.startsWith("docker:Label:")),
      "No custom labels should be present"
    )

    // RepoDigest not present
    assert(
      dockerMeta(configItem, "RepoDigest").isEmpty,
      "RepoDigest should be absent"
    )
  }

  test("grinder image has correct OCI label metadata") {
    val name = "test_data/download/docker_tests/grinder_bt_pg_docker.tar"
    val nested = FileWrapper(File(name), name, None)
    val store = ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    val purl = "pkg:docker/spicelabs%2Fgrinder@0.1.0"
    val item = store.read(purl).get
    val aliasTo = item.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get

    val configItem = store.read(aliasTo).get
    val extra = configItem.body.get.asInstanceOf[ItemMetaData].extra

    // Normalized OCI labels
    assertEquals(
      dockerMeta(configItem, "Source"),
      Some("https://github.com/spice-labs-inc/grinder")
    )

    assertEquals(
      dockerMeta(configItem, "License"),
      Some("Apache-2.0")
    )

    assertEquals(
      dockerMeta(configItem, "Title"),
      Some("grinder")
    )

    assertEquals(
      dockerMeta(configItem, "ImageLabelVersion"),
      Some("0.1.0")
    )

    assertEquals(
      dockerMeta(configItem, "Url"),
      Some("https://github.com/spice-labs-inc/grinder")
    )

    assertEquals(
      dockerMeta(configItem, "Description"),
      Some("The Spice Labs open source integration")
    )

    assertEquals(
      dockerMeta(configItem, "Revision"),
      Some("4330087943dfac7cc0f17eba62f97383d74a401b")
    )

    // User
    assertEquals(dockerMeta(configItem, "User"), Some("1001:0"))

    // WorkingDir
    assertEquals(dockerMeta(configItem, "WorkingDir"), Some("/opt/docker"))

    // Entrypoint
    assertEquals(
      dockerMeta(configItem, "Entrypoint"),
      Some("/opt/grinder/grind.sh")
    )

    // EnvCount
    assertEquals(dockerMeta(configItem, "EnvCount"), Some("8"))

    // Env vars extracted
    assertEquals(
      dockerMeta(configItem, "Env:PATH"),
      Some(
        "/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
      )
    )
    assertEquals(dockerMeta(configItem, "Env:container"), Some("oci"))
    assertEquals(
      dockerMeta(configItem, "Env:JAVA_HOME"),
      Some("/opt/java/openjdk")
    )
    assertEquals(
      dockerMeta(configItem, "Env:LANG"),
      Some("en_US.UTF-8")
    )
    assertEquals(
      dockerMeta(configItem, "Env:LANGUAGE"),
      Some("en_US:en")
    )
    assertEquals(
      dockerMeta(configItem, "Env:LC_ALL"),
      Some("en_US.UTF-8")
    )
    assertEquals(
      dockerMeta(configItem, "Env:JAVA_VERSION"),
      Some("jdk-21.0.6+7")
    )
    assertEquals(
      dockerMeta(configItem, "Env:CLASSPATH"),
      Some(".;/opt/docker/lib/")
    )

    // LayerCount
    assertEquals(dockerMeta(configItem, "LayerCount"), Some("12"))

    // Size
    assertEquals(dockerMeta(configItem, "Size"), Some("408010752"))

    // Preserved custom label
    assertEquals(
      extra.get("docker:Label:com.redhat.component").map(_.head.value),
      Some("ubi9-minimal-container")
    )

    // Raw JSON keys absent
    assert(extra.get("docker_config").isEmpty)
    assert(extra.get("docker_manifest").isEmpty)
  }

  test("postgres image has entrypoint and workingdir metadata") {
    val name = "test_data/download/docker_tests/grinder_bt_pg_docker.tar"
    val nested = FileWrapper(File(name), name, None)
    val store = ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    // Test both postgres variants
    Seq(
      ("pkg:docker/postgres@16.6", "14", 439935488L),
      ("pkg:docker/postgres@9.6.12", "14", 237365248L)
    ).foreach { case (purl, expectedLayers, expectedSize) =>
      val item = store.read(purl).get
      val aliasTo = item.connections
        .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
        .headOption
        .get

      val configItem = store.read(aliasTo).get

      assertEquals(
        dockerMeta(configItem, "Platform"),
        Some("linux/amd64")
      )

      assertEquals(
        dockerMeta(configItem, "Entrypoint"),
        Some("docker-entrypoint.sh")
      )

      assertEquals(
        dockerMeta(configItem, "Cmd"),
        Some("postgres")
      )

      assertEquals(
        dockerMeta(configItem, "LayerCount"),
        Some(expectedLayers)
      )

      assertEquals(
        dockerMeta(configItem, "Size"),
        Some(expectedSize.toString)
      )

      // Env vars
      purl match {
        case "pkg:docker/postgres@16.6" =>
          assertEquals(
            dockerMeta(configItem, "Env:PATH"),
            Some(
              "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/lib/postgresql/16/bin"
            )
          )
          assertEquals(
            dockerMeta(configItem, "Env:GOSU_VERSION"),
            Some("1.17")
          )
          assertEquals(
            dockerMeta(configItem, "Env:PG_MAJOR"),
            Some("16")
          )
          assertEquals(
            dockerMeta(configItem, "Env:PG_VERSION"),
            Some("16.6-1.pgdg120+1")
          )
        case "pkg:docker/postgres@9.6.12" =>
          assertEquals(
            dockerMeta(configItem, "Env:PATH"),
            Some(
              "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/lib/postgresql/9.6/bin"
            )
          )
          assertEquals(
            dockerMeta(configItem, "Env:GOSU_VERSION"),
            Some("1.11")
          )
          assertEquals(
            dockerMeta(configItem, "Env:PG_MAJOR"),
            Some("9.6")
          )
          assertEquals(
            dockerMeta(configItem, "Env:PG_VERSION"),
            Some("9.6.12-1.pgdg90+1")
          )
        case _ =>
      }

      // Common env vars across postgres versions
      assertEquals(
        dockerMeta(configItem, "Env:LANG"),
        Some("en_US.utf8")
      )
      assertEquals(
        dockerMeta(configItem, "Env:PGDATA"),
        Some("/var/lib/postgresql/data")
      )
    }
  }

  // ==================== Completeness Audit Test ====================

  test("docker metadata completeness audit - all config leaves accounted for") {
    val name = "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar"
    val nested = FileWrapper(File(name), name, None)
    val store = ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    val purl = "pkg:docker/bigtent@2025_03_22"
    val item = store.read(purl).get
    val aliasTo = item.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get

    val configItem = store.read(aliasTo).get
    val extra = configItem.body.get.asInstanceOf[ItemMetaData].extra

    // Parse the raw config JSON back out of metadata
    val compactConfig = extra
      .get("docker:ConfigJson")
      .flatMap(_.headOption)
      .map(_.value)
      .getOrElse(fail("docker:ConfigJson must be present"))
    val configJson = parse(compactConfig)

    // Map from JSON field paths to the structured metadata keys that represent them
    val pathToKey = Map(
      "architecture" -> "docker:Platform",
      "os" -> "docker:Platform",
      "variant" -> "docker:Platform",
      "created" -> "docker:Created",
      "author" -> "docker:Author",
      "config.User" -> "docker:User",
      "config.Env" -> "docker:EnvCount",
      "config.Cmd" -> "docker:Cmd",
      "config.WorkingDir" -> "docker:WorkingDir",
      "config.Entrypoint" -> "docker:Entrypoint",
      "config.Labels" -> "docker:Label", // prefix match
      "rootfs.diff_ids" -> "docker:LayerCount",
      "history" -> "docker:History"
    )

    // Fields known to have no supply-chain value and intentionally unmodeled
    val ignoredPaths = Set(
      "config.Hostname",
      "config.Domainname",
      "config.AttachStdin",
      "config.AttachStdout",
      "config.AttachStderr",
      "config.Tty",
      "config.OpenStdin",
      "config.StdinOnce",
      "config.Image",
      "config.Volumes",
      "config.OnBuild",
      "config.StopSignal",
      "config.ExposedPorts",
      "config.ArgsEscaped",
      "rootfs.type"
    )

    // Recursively collect all leaf paths from the config JSON
    def collectLeaves(jv: JValue, prefix: String = ""): Set[String] = {
      jv match {
        case JObject(fields) =>
          fields.flatMap { case (k, v) =>
            val path = if (prefix.isEmpty) k else s"$prefix.$k"
            v match {
              case JObject(_) | JArray(_) => collectLeaves(v, path)
              case _                      => Set(path)
            }
          }.toSet
        case JArray(arr) if arr.nonEmpty =>
          // For arrays, just record the array path itself
          Set(prefix)
        case _ =>
          Set(prefix)
      }
    }

    val leaves = collectLeaves(configJson)

    // For Env we check individual keys are present
    val envKeys = extra.keys.filter(_.startsWith("docker:Env:"))

    val unaccounted = leaves.filter { leaf =>
      val hasStructured = pathToKey.get(leaf) match {
        case Some(key) => extra.contains(key)
        case None if leaf.startsWith("config.Labels.") =>
          val labelName = leaf.substring("config.Labels.".length)
          extra.contains(s"docker:Label:$labelName") ||
          Set(
            "docker:Source",
            "docker:Revision",
            "docker:License",
            "docker:Title",
            "docker:Description",
            "docker:Url",
            "docker:Vendor",
            "docker:ImageLabelVersion",
            "docker:BaseImageRef",
            "docker:BaseImageDigest",
            "docker:LabelCreated",
            "docker:BuildDate"
          ).exists(extra.contains)
        case None if leaf == "config.Env" => envKeys.nonEmpty
        case None                         => false
      }
      !hasStructured && !ignoredPaths.contains(leaf)
    }

    assert(
      unaccounted.isEmpty,
      s"Config JSON leaves without structured representation or ignored status: ${unaccounted.toList.sorted
          .mkString(", ")}"
    )
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

    val (purls, _) =
      state.getPurls(artifact, item, DockerMarkers.Layer("sha256:abc"))
    assert(purls.isEmpty)
  }

  test("DockerState.getMetadata - returns empty for Manifest marker") {
    val artifact = ByteWrapper(Array[Byte](), "manifest.json", None)
    val item = createTestItem("test-id")
    val state = DockerState(Map())

    val (metadata, _) =
      state.getMetadata(artifact, item, DockerMarkers.Manifest)
    assert(metadata.isEmpty)
  }

  test("DockerState.getMetadata - returns empty for Layer marker") {
    val artifact = ByteWrapper(Array[Byte](), "layer.tar", None)
    val item = createTestItem("test-id")
    val state = DockerState(Map())

    val (metadata, _) =
      state.getMetadata(artifact, item, DockerMarkers.Layer("sha256:abc"))
    assert(metadata.isEmpty)
  }

  test("DockerState.postChildProcessing - returns same state") {
    val storage = MemStorage(None)
    val state = DockerState(Map())

    val newState =
      state.postChildProcessing(None, storage, DockerMarkers.Manifest)
    assertEquals(newState, state)
  }

  // ==================== DockerToProcess Tests ====================

  test("DockerToProcess.itemCnt - calculates total items") {
    val manifest = ByteWrapper(Array[Byte](), "manifest.json", None)
    val tp = DockerToProcess(manifest, List(), Map())

    assertEquals(tp.itemCnt, 1) // just manifest, no config, no layers
  }

  test("DockerToProcess.mimeType - returns manifest mime type") {
    val manifest =
      ByteWrapper("""[{}]""".getBytes("UTF-8"), "manifest.json", None)
    val tp = DockerToProcess(manifest, List(), Map())

    assert(tp.mimeType.contains("application/json"))
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

    val (toProcess, _, _, name) =
      DockerToProcess.computeDockerFiles(byUUID, byName)

    assertEquals(name, "Docker")
    assert(toProcess.isEmpty)
  }

  test("computeDockerFiles - handles invalid JSON manifest") {
    val manifest =
      ByteWrapper("not json".getBytes("UTF-8"), "manifest.json", None)
    val byUUID = Map(manifest.uuid -> manifest)
    val byName = Map("manifest.json" -> Vector(manifest))

    val (toProcess, _, _, _) =
      DockerToProcess.computeDockerFiles(byUUID, byName)

    assert(toProcess.isEmpty)
  }

  test("computeDockerFiles - handles empty JSON array manifest") {
    val manifest = ByteWrapper("[]".getBytes("UTF-8"), "manifest.json", None)
    val byUUID = Map(manifest.uuid -> manifest)
    val byName = Map("manifest.json" -> Vector(manifest))

    val (toProcess, _, _, _) =
      DockerToProcess.computeDockerFiles(byUUID, byName)

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
      case _                         => fail("Expected Layer marker")
    }
  }
}
