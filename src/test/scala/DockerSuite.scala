import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.DockerTestFixtures
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.strategies.DockerMarkers
import io.spicelabs.goatrodeo.omnibor.strategies.DockerState
import io.spicelabs.goatrodeo.omnibor.strategies.DockerToProcess
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import org.json4s.*
import org.json4s.native.JsonMethods.*

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class DockerSuite extends munit.FunSuite {

  // Whichever suite touches DockerTestFixtures first pays for parsing the
  // multi-hundred-MB docker tarballs, which exceeds munit's 30-second default
  // on its own — and more so when other test classes are running concurrently.
  override val munitTimeout = scala.concurrent.duration.Duration(30, "minutes")

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

  private def dockerMeta(item: Item, key: String): Option[String] = {
    for {
      body <- item.body
      meta = body.asInstanceOf[ItemMetaData]
      values <- meta.extra.get(s"docker:$key")
      head <- values.headOption
    } yield head.value
  }

  private def testLayersAndManifest(
      identifier: String,
      store1: Storage
  ): Unit = {
    val item2 = store1.read(identifier).get
    assert(item2.body.isDefined, s"Config item $identifier must have a body")
    val extraMetadata = item2.body.get.asInstanceOf[ItemMetaData].extra

    assert(
      extraMetadata.get("docker:ConfigJson").isDefined,
      "docker:ConfigJson should be present"
    )
    assert(
      extraMetadata.get("docker:ManifestJson").isDefined,
      "docker:ManifestJson should be present"
    )
    assert(
      extraMetadata.get("docker_config").isEmpty,
      "docker_config raw JSON key should be absent"
    )
    assert(
      extraMetadata.get("docker_manifest").isEmpty,
      "docker_manifest raw JSON key should be absent"
    )

    val platformOpt = extraMetadata.get("docker:Platform")
    assert(platformOpt.isDefined, "docker:Platform should be present")
    val platform = platformOpt.get.head.value
    assert(
      platform.startsWith("linux/"),
      s"Platform $platform should start with linux/"
    )

    val layerCountOpt = extraMetadata.get("docker:LayerCount")
    assert(layerCountOpt.isDefined, "docker:LayerCount should be present")
    val layerCount = layerCountOpt.get.head.value.toInt
    assert(layerCount > 0, s"LayerCount $layerCount must be > 0")

    val sizeOpt = extraMetadata.get("docker:Size")
    assert(sizeOpt.isDefined, "docker:Size should be present")
    val size = sizeOpt.get.head.value.toLong
    assert(size > 0, s"Size $size must be > 0")

    val historyOpt = extraMetadata.get("docker:History")
    assert(historyOpt.isDefined, "docker:History should be present")
    assert(historyOpt.get.nonEmpty, "docker:History should not be empty")

    val connectedLayers = item2.connections.collect {
      case (EdgeType.contains, v) => v
    }
    assert(
      connectedLayers.nonEmpty,
      "Config item must have at least one layer connected"
    )

    for (layerGitoid <- connectedLayers) {
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

  // ==================== Single-traversal bigtent tests ====================

  test("bigtent image - full validation") {
    assume(
      DockerTestFixtures.checkTestFile(
        "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar"
      ),
      "Docker test data exists"
    )
    val store1 = DockerTestFixtures.bigtentStorage

    // -- pURLs and layers (from "Can build for a simple Docker image") --
    val result = store1.purls().filter(_.startsWith("pkg:docker"))
    assertEquals(result, TreeSet("pkg:docker/bigtent@2025_03_22"))

    val item = store1.read("pkg:docker/bigtent@2025_03_22").get
    val aliasTo = item.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get
    assert(
      aliasTo.startsWith("gitoid:blob:sha256:"),
      s"Expected config gitoid but got $aliasTo"
    )
    testLayersAndManifest(aliasTo, store1)

    // -- structured metadata (from "bigtent image has correct structured metadata") --
    val configItem = store1.read(aliasTo).get
    val extra = configItem.body.get.asInstanceOf[ItemMetaData].extra

    assertEquals(dockerMeta(configItem, "Platform"), Some("linux/amd64"))
    assertEquals(
      dockerMeta(configItem, "Created"),
      Some("2025-03-22T13:10:35.59966703-04:00")
    )
    assert(dockerMeta(configItem, "Author").isEmpty, "Author should be absent")
    assertEquals(dockerMeta(configItem, "WorkingDir"), Some("/"))
    assertEquals(dockerMeta(configItem, "Cmd"), Some("/bigtent"))
    assert(
      dockerMeta(configItem, "Entrypoint").isEmpty,
      "Entrypoint should be absent when empty"
    )
    assert(dockerMeta(configItem, "User").isEmpty, "User should be absent")
    assertEquals(dockerMeta(configItem, "EnvCount"), Some("1"))
    assertEquals(
      dockerMeta(configItem, "Env:PATH"),
      Some("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    )
    assertEquals(dockerMeta(configItem, "LayerCount"), Some("2"))
    assertEquals(dockerMeta(configItem, "Size"), Some("16447488"))

    val history = extra
      .get("docker:History")
      .map(_.map(_.value))
      .getOrElse(TreeSet[String]())
    assert(history.nonEmpty, "History should contain entries")
    assert(
      history.exists(_.contains("ADD alpine-minirootfs")),
      "History should include ADD command"
    )
    assert(
      extra.keys.forall(!_.startsWith("docker:Label:")),
      "No custom labels should be present"
    )
    assert(
      dockerMeta(configItem, "RepoDigest").isEmpty,
      "RepoDigest should be absent"
    )

    // -- completeness audit (from "docker metadata completeness audit") --
    val compactConfig = extra
      .get("docker:ConfigJson")
      .flatMap(_.headOption)
      .map(_.value)
      .getOrElse(fail("docker:ConfigJson must be present"))
    val configJson = parse(compactConfig)

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
      "config.Labels" -> "docker:Label",
      "rootfs.diff_ids" -> "docker:LayerCount",
      "history" -> "docker:History"
    )

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
        case JArray(arr) if arr.nonEmpty => Set(prefix)
        case _                           => Set(prefix)
      }
    }

    val leaves = collectLeaves(configJson)
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
      s"Config JSON leaves without structured representation: ${unaccounted.toList.sorted.mkString(", ")}"
    )
  }

  // ==================== Single-traversal grinder/postgres tests ====================

  test("grinder/postgres complex image - full validation") {
    assume(
      DockerTestFixtures.checkTestFile(
        "test_data/download/docker_tests/grinder_bt_pg_docker.tar"
      ),
      "Docker test data exists"
    )
    val store1 = DockerTestFixtures.grinderStorage

    // -- pURLs and layers (from "Can build for a complex Docker image") --
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

    for (purl <- expectedpurls) {
      val item = store1.read(purl).get
      val aliasTo = item.connections
        .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
        .headOption
        .get
      testLayersAndManifest(aliasTo, store1)
    }

    // -- grinder OCI label metadata (from "grinder image has correct OCI label metadata") --
    val grinderPurl = "pkg:docker/spicelabs%2Fgrinder@0.1.0"
    val grinderItem = store1.read(grinderPurl).get
    val grinderAliasTo = grinderItem.connections
      .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
      .headOption
      .get
    val grinderConfig = store1.read(grinderAliasTo).get
    val grinderExtra = grinderConfig.body.get.asInstanceOf[ItemMetaData].extra

    assertEquals(
      dockerMeta(grinderConfig, "Source"),
      Some("https://github.com/spice-labs-inc/grinder")
    )
    assertEquals(dockerMeta(grinderConfig, "License"), Some("Apache-2.0"))
    assertEquals(dockerMeta(grinderConfig, "Title"), Some("grinder"))
    assertEquals(dockerMeta(grinderConfig, "ImageLabelVersion"), Some("0.1.0"))
    assertEquals(
      dockerMeta(grinderConfig, "Url"),
      Some("https://github.com/spice-labs-inc/grinder")
    )
    assertEquals(
      dockerMeta(grinderConfig, "Description"),
      Some("The Spice Labs open source integration")
    )
    assertEquals(
      dockerMeta(grinderConfig, "Revision"),
      Some("4330087943dfac7cc0f17eba62f97383d74a401b")
    )
    assertEquals(dockerMeta(grinderConfig, "User"), Some("1001:0"))
    assertEquals(dockerMeta(grinderConfig, "WorkingDir"), Some("/opt/docker"))
    assertEquals(
      dockerMeta(grinderConfig, "Entrypoint"),
      Some("/opt/grinder/grind.sh")
    )
    assertEquals(dockerMeta(grinderConfig, "EnvCount"), Some("8"))
    assertEquals(
      dockerMeta(grinderConfig, "Env:PATH"),
      Some(
        "/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
      )
    )
    assertEquals(dockerMeta(grinderConfig, "Env:container"), Some("oci"))
    assertEquals(
      dockerMeta(grinderConfig, "Env:JAVA_HOME"),
      Some("/opt/java/openjdk")
    )
    assertEquals(dockerMeta(grinderConfig, "Env:LANG"), Some("en_US.UTF-8"))
    assertEquals(dockerMeta(grinderConfig, "Env:LANGUAGE"), Some("en_US:en"))
    assertEquals(dockerMeta(grinderConfig, "Env:LC_ALL"), Some("en_US.UTF-8"))
    assertEquals(
      dockerMeta(grinderConfig, "Env:JAVA_VERSION"),
      Some("jdk-21.0.6+7")
    )
    assertEquals(
      dockerMeta(grinderConfig, "Env:CLASSPATH"),
      Some(".;/opt/docker/lib/")
    )
    assertEquals(dockerMeta(grinderConfig, "LayerCount"), Some("12"))
    assertEquals(dockerMeta(grinderConfig, "Size"), Some("408010752"))

    assertEquals(
      grinderExtra.get("docker:Label:com.redhat.component").map(_.head.value),
      Some("ubi9-minimal-container")
    )
    assert(grinderExtra.get("docker_config").isEmpty)
    assert(grinderExtra.get("docker_manifest").isEmpty)

    // -- postgres metadata (from "postgres image has entrypoint and workingdir metadata") --
    Seq(
      ("pkg:docker/postgres@16.6", "14", 439935488L),
      ("pkg:docker/postgres@9.6.12", "14", 237365248L)
    ).foreach { case (purl, expectedLayers, expectedSize) =>
      val pgItem = store1.read(purl).get
      val pgAliasTo = pgItem.connections
        .collect { case (t, v) if EdgeType.isAliasTo(t) => v }
        .headOption
        .get
      val pgConfig = store1.read(pgAliasTo).get

      assertEquals(dockerMeta(pgConfig, "Platform"), Some("linux/amd64"))
      assertEquals(
        dockerMeta(pgConfig, "Entrypoint"),
        Some("docker-entrypoint.sh")
      )
      assertEquals(dockerMeta(pgConfig, "Cmd"), Some("postgres"))
      assertEquals(dockerMeta(pgConfig, "LayerCount"), Some(expectedLayers))
      assertEquals(dockerMeta(pgConfig, "Size"), Some(expectedSize.toString))

      purl match {
        case "pkg:docker/postgres@16.6" =>
          assertEquals(
            dockerMeta(pgConfig, "Env:PATH"),
            Some(
              "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/lib/postgresql/16/bin"
            )
          )
          assertEquals(dockerMeta(pgConfig, "Env:GOSU_VERSION"), Some("1.17"))
          assertEquals(dockerMeta(pgConfig, "Env:PG_MAJOR"), Some("16"))
          assertEquals(
            dockerMeta(pgConfig, "Env:PG_VERSION"),
            Some("16.6-1.pgdg120+1")
          )
        case "pkg:docker/postgres@9.6.12" =>
          assertEquals(
            dockerMeta(pgConfig, "Env:PATH"),
            Some(
              "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/lib/postgresql/9.6/bin"
            )
          )
          assertEquals(dockerMeta(pgConfig, "Env:GOSU_VERSION"), Some("1.11"))
          assertEquals(dockerMeta(pgConfig, "Env:PG_MAJOR"), Some("9.6"))
          assertEquals(
            dockerMeta(pgConfig, "Env:PG_VERSION"),
            Some("9.6.12-1.pgdg90+1")
          )
        case _ =>
      }

      assertEquals(dockerMeta(pgConfig, "Env:LANG"), Some("en_US.utf8"))
      assertEquals(
        dockerMeta(pgConfig, "Env:PGDATA"),
        Some("/var/lib/postgresql/data")
      )
    }
  }

  // ==================== Unit tests (no traversal) ====================

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

    val (purlSet, _) = state.getPurls(artifact, item, DockerMarkers.Manifest)
    val purls = purlSet.canonicalStrings
    assert(purls.isEmpty)
  }

  test("DockerState.getPurls - returns empty for Layer marker") {
    val artifact = ByteWrapper(Array[Byte](), "layer.tar", None)
    val item = createTestItem("test-id")
    val state = DockerState(Map())

    val (purlSet, _) =
      state.getPurls(artifact, item, DockerMarkers.Layer("sha256:abc"))
    val purls = purlSet.canonicalStrings
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

  test("DockerToProcess.itemCnt - calculates total items") {
    val manifest = ByteWrapper(Array[Byte](), "manifest.json", None)
    val tp = DockerToProcess(manifest, List(), Map())
    assertEquals(tp.itemCnt, 1)
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
    assert(toProcess.isEmpty)
  }

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

  // ==================== OCI image layout ====================
  //
  // WHAT: pure OCI layouts (`oci-layout` + `index.json` + `blobs/sha256/…`,
  // as produced by `oras copy --to-oci-layout`) must be claimed by the SAME
  // Docker strategy, with wild-world fidelity: no RepoTags unless the
  // `org.opencontainers.image.ref.name` annotation happens to be present, and
  // hostile descriptors never resolved.
  //
  // WHY: docker-save tars and OCI layouts are two transports of the same
  // image model; the strategy extracts what each format carries. These unit
  // tests pin the claim rules on synthetic fixtures; the wild fixtures are
  // pinned by OciDockerParitySuite.
  //
  // LLM note: O-xx = test id.

  private def hex(c: Char, n: Int): String = c.toString * n

  private def ociArtifacts(
      pairs: (String, String)*
  ): (Map[String, ArtifactWrapper], Map[String, Vector[ArtifactWrapper]]) = {
    val wrappers = pairs.map { case (name, content) =>
      ByteWrapper(content.getBytes("UTF-8"), name, None)
    }.toVector
    val byUuid = wrappers.map(w => w.uuid -> w).toMap
    val byName = wrappers.groupBy(_.path())
    (byUuid, byName)
  }

  private def ociClaim(
      byUuid: Map[String, ArtifactWrapper],
      byName: Map[String, Vector[ArtifactWrapper]]
  ) = DockerToProcess.computeDockerFiles(byUuid, byName)

  private def ociManifestJson(configDigest: String, layerDigest: String) =
    s"""{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"${configDigest}","size":2},"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"${layerDigest}","size":2}]}"""

  private def ociConfigJson(diffId: String) =
    s"""{"architecture":"amd64","os":"linux","config":{"Env":["PATH=/usr/bin"],"Cmd":["/bin/sh"]},"rootfs":{"type":"layers","diff_ids":["${diffId}"]}}"""

  private def ociIndexJson(entries: String) =
    s"""{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[${entries}]}"""

  // O-01 — a single-manifest index.json (ORAS writes a bare manifest when the
  // source is single-platform) is claimed, with empty wild RepoTags.
  test("O-01 single-manifest index.json is claimed") {
    val configDigest = "sha256:" + hex('a', 64)
    val layerDigest = "sha256:" + hex('b', 64)
    val diffId = "sha256:" + hex('c', 64)
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> ociManifestJson(configDigest, layerDigest),
      s"blobs/sha256/${hex('a', 64)}" -> ociConfigJson(diffId),
      s"blobs/sha256/${hex('b', 64)}" -> "layer-bytes"
    )
    val (toProcess, uuidOut, nameOut, name) = ociClaim(byUuid, byName)
    assertEquals(name, "Docker")
    assertEquals(toProcess.length, 1)
    val tp = toProcess.head.asInstanceOf[DockerToProcess]
    assertEquals(tp.config.length, 1)
    assertEquals(tp.config.head.effectiveRepoTags, Vector())
    assertEquals(tp.config.head.layers, List(s"blobs/sha256/${hex('b', 64)}"))
    assert(!nameOut.contains("index.json"))
    assert(!nameOut.contains("oci-layout"))
    assert(!nameOut.contains(s"blobs/sha256/${hex('a', 64)}"))
    assert(!uuidOut.contains(byName("index.json").head.uuid))
  }

  // O-02 — a manifest list selects linux/amd64 deterministically over other
  // platforms and skips attestation entries (no platform).
  test("O-02 manifest list prefers linux/amd64 and skips attestations") {
    val armManifest = "sha256:" + hex('1', 64)
    val amdManifest = "sha256:" + hex('2', 64)
    val armConfig = "sha256:" + hex('3', 64)
    val amdConfig = "sha256:" + hex('4', 64)
    val armLayer = "sha256:" + hex('5', 64)
    val amdLayer = "sha256:" + hex('6', 64)
    val entries =
      s"""{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${armManifest}","size":1,"platform":{"os":"linux","architecture":"arm64"}},
         |{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${amdManifest}","size":1,"platform":{"os":"linux","architecture":"amd64"}},
         |{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${amdManifest}","size":1,"annotations":{"in-toto.io/predicate-type":"attestation"}}""".stripMargin
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> ociIndexJson(entries),
      s"blobs/sha256/${hex('1', 64)}" -> ociManifestJson(armConfig, armLayer),
      s"blobs/sha256/${hex('2', 64)}" -> ociManifestJson(amdConfig, amdLayer),
      s"blobs/sha256/${hex('3', 64)}" -> ociConfigJson(
        "sha256:" + hex('7', 64)
      ),
      s"blobs/sha256/${hex('4', 64)}" -> ociConfigJson(
        "sha256:" + hex('8', 64)
      ),
      s"blobs/sha256/${hex('5', 64)}" -> "arm-layer",
      s"blobs/sha256/${hex('6', 64)}" -> "amd-layer"
    )
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    assertEquals(toProcess.length, 1)
    val tp = toProcess.head.asInstanceOf[DockerToProcess]
    assertEquals(tp.config.length, 1)
    assertEquals(tp.config.head.configPath, s"blobs/sha256/${hex('4', 64)}")
    assertEquals(
      tp.config.head.layers,
      List(s"blobs/sha256/${hex('6', 64)}")
    )
  }

  // O-03 — nested indexes resolve (bounded depth): outer index points at an
  // inner index carrying the image manifest.
  test("O-03 nested indexes are resolved") {
    val innerIndex = "sha256:" + hex('a', 64)
    val manifestD = "sha256:" + hex('b', 64)
    val configD = "sha256:" + hex('c', 64)
    val layerD = "sha256:" + hex('d', 64)
    val inner =
      ociIndexJson(
        s"""{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${manifestD}","size":1,"platform":{"os":"linux","architecture":"amd64"}}"""
      )
    val outer = ociIndexJson(
      s"""{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"${innerIndex}","size":1}"""
    )
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> outer,
      s"blobs/sha256/${hex('a', 64)}" -> inner,
      s"blobs/sha256/${hex('b', 64)}" -> ociManifestJson(configD, layerD),
      s"blobs/sha256/${hex('c', 64)}" -> ociConfigJson(
        "sha256:" + hex('e', 64)
      ),
      s"blobs/sha256/${hex('d', 64)}" -> "layer"
    )
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    assertEquals(toProcess.length, 1)
    val tp = toProcess.head.asInstanceOf[DockerToProcess]
    assertEquals(tp.config.head.configPath, s"blobs/sha256/${hex('c', 64)}")
  }

  // O-04 — the wild ref.name annotation (descriptor level for lists, manifest
  // level for bare manifests) becomes RepoTags; garbage is dropped.
  test("O-04 ref.name annotation becomes RepoTags") {
    val manifestD = "sha256:" + hex('a', 64)
    val configD = "sha256:" + hex('b', 64)
    val layerD = "sha256:" + hex('c', 64)
    val entries =
      s"""{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${manifestD}","size":1,"platform":{"os":"linux","architecture":"amd64"},"annotations":{"org.opencontainers.image.ref.name":"alpine:3.20.6"}}"""
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> ociIndexJson(entries),
      s"blobs/sha256/${hex('a', 64)}" -> ociManifestJson(configD, layerD),
      s"blobs/sha256/${hex('b', 64)}" -> ociConfigJson(
        "sha256:" + hex('d', 64)
      ),
      s"blobs/sha256/${hex('c', 64)}" -> "layer"
    )
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    val tp = toProcess.head.asInstanceOf[DockerToProcess]
    assertEquals(tp.config.head.effectiveRepoTags, Vector("alpine:3.20.6"))

    // manifest-level annotation (bare-manifest index.json)
    val bareManifest =
      s"""{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","annotations":{"org.opencontainers.image.ref.name":"busybox:1.36"},"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"${configD}","size":2},"layers":[{"digest":"${layerD}"}]}"""
    val (byUuid2, byName2) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> bareManifest,
      s"blobs/sha256/${hex('b', 64)}" -> ociConfigJson(
        "sha256:" + hex('d', 64)
      ),
      s"blobs/sha256/${hex('c', 64)}" -> "layer"
    )
    val (tp2, _, _, _) = ociClaim(byUuid2, byName2)
    assertEquals(
      tp2.head.asInstanceOf[DockerToProcess].config.head.effectiveRepoTags,
      Vector("busybox:1.36")
    )
  }

  // O-05 — hostile descriptors are never resolved: traversal paths, bad hex,
  // wrong algorithms, and truncated digests leave the corpus unclaimed.
  test("O-05 hostile digests are never resolved") {
    val layerD = "sha256:" + hex('c', 64)
    val hostileDigests = Vector(
      "../../../etc/passwd",
      "/etc/passwd",
      "sha256:" + hex('Z', 64),
      "sha256:" + hex('a', 63),
      "sha256:short",
      "md5:" + hex('a', 32),
      "sha256:..%2f..%2fetc",
      ""
    )
    hostileDigests.foreach { bad =>
      val index = ociIndexJson(
        s"""{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${bad}","size":1,"platform":{"os":"linux","architecture":"amd64"}}"""
      )
      val (byUuid, byName) = ociArtifacts(
        "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
        "index.json" -> index,
        s"blobs/sha256/${hex('c', 64)}" -> "layer"
      )
      val (toProcess, uuidOut, nameOut, _) = ociClaim(byUuid, byName)
      assert(toProcess.isEmpty, s"digest must be rejected: ${bad}")
      assertEquals(uuidOut, byUuid, s"nothing claimed for: ${bad}")
      assertEquals(nameOut, byName, s"nothing claimed for: ${bad}")
    }

    // a config digest with traversal inside a bare manifest is also rejected
    val bareTraversal =
      s"""{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"../../../etc/passwd","size":2},"layers":[{"digest":"${layerD}"}]}"""
    val (byUuid2, byName2) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> bareTraversal,
      s"blobs/sha256/${hex('c', 64)}" -> "layer"
    )
    val (tp2, _, _, _) = ociClaim(byUuid2, byName2)
    assert(tp2.isEmpty, "traversal config digest must be rejected")
  }

  // O-06 — without the spec-required oci-layout marker, index.json is NOT
  // claimed (a stray index.json is someone else's file).
  test("O-06 index.json without oci-layout is not claimed") {
    val (byUuid, byName) = ociArtifacts(
      "index.json" -> """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}"""
    )
    val (toProcess, uuidOut, nameOut, _) = ociClaim(byUuid, byName)
    assert(toProcess.isEmpty)
    assertEquals(nameOut, byName)
    assertEquals(uuidOut, byUuid)
  }

  // O-07 — a manifest list whose blobs are missing claims nothing and leaves
  // the corpus untouched.
  test("O-07 missing blobs claim nothing") {
    val manifestD = "sha256:" + hex('a', 64)
    val index = ociIndexJson(
      s"""{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${manifestD}","size":1,"platform":{"os":"linux","architecture":"amd64"}}"""
    )
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> index
    )
    val (toProcess, uuidOut, nameOut, _) = ociClaim(byUuid, byName)
    assert(toProcess.isEmpty)
    assertEquals(nameOut, byName)
  }

  // O-08 — precedence: when a docker-save manifest.json is present it wins,
  // and the stray index.json is left alone.
  test("O-08 docker-save manifest.json wins over index.json") {
    val configD = "sha256:" + hex('a', 64)
    val layerD = "sha256:" + hex('b', 64)
    val manifestJson =
      s"""[{"Config":"blobs/sha256/${hex(
          'a',
          64
        )}","RepoTags":["dtest:1"],"Layers":["blobs/sha256/${hex(
          'b',
          64
        )}"]}]"""
    val ociManifest = ociManifestJson(configD, layerD)
    val (byUuid, byName) = ociArtifacts(
      "manifest.json" -> manifestJson,
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> ociManifest,
      s"blobs/sha256/${hex('a', 64)}" -> ociConfigJson(
        "sha256:" + hex('c', 64)
      ),
      s"blobs/sha256/${hex('b', 64)}" -> "layer"
    )
    val (toProcess, _, nameOut, _) = ociClaim(byUuid, byName)
    assertEquals(toProcess.length, 1)
    assertEquals(
      toProcess.head
        .asInstanceOf[DockerToProcess]
        .main
        .contains("manifest.json"),
      true
    )
    assert(
      nameOut.contains("index.json"),
      "the OCI claim must not run when docker-save claimed"
    )
  }

  // O-09 — an empty manifests array is not a claim.
  test("O-09 empty manifests array is not claimed") {
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}"""
    )
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    assert(toProcess.isEmpty)
  }

  // O-10 — a non-JSON index.json is not claimed.
  test("O-10 non-JSON index.json is not claimed") {
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> "not json at all"
    )
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    assert(toProcess.isEmpty)
  }

  // O-11 — garbage ref.name annotations (control characters, over-long) are
  // dropped: no tags, no error, the image is still claimed.
  test("O-11 garbage ref.name annotations are dropped") {
    val manifestD = "sha256:" + hex('a', 64)
    val configD = "sha256:" + hex('b', 64)
    val layerD = "sha256:" + hex('c', 64)
    val entries =
      s"""{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${manifestD}","size":1,"platform":{"os":"linux","architecture":"amd64"},"annotations":{"org.opencontainers.image.ref.name":"bad\\nname:tag"}}"""
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> ociIndexJson(entries),
      s"blobs/sha256/${hex('a', 64)}" -> ociManifestJson(configD, layerD),
      s"blobs/sha256/${hex('b', 64)}" -> ociConfigJson(
        "sha256:" + hex('d', 64)
      ),
      s"blobs/sha256/${hex('c', 64)}" -> "layer"
    )
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    val tp = toProcess.head.asInstanceOf[DockerToProcess]
    assertEquals(tp.config.head.effectiveRepoTags, Vector())
  }

  // O-12 — an index.json larger than the read cap is refused entirely.
  test("O-12 oversized index.json is refused") {
    val big = "{\"x\":" + "\"y\"" * (DockerToProcess.MaxOciJsonBytes / 4) + "}"
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> big
    )
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    assert(toProcess.isEmpty)
  }

  // O-13 — index nesting deeper than the cap is not resolved.
  test("O-13 index nesting beyond the cap is not resolved") {
    val depth = DockerToProcess.MaxOciIndexDepth + 2
    var digests = Vector[String]()
    (0 until depth).foreach { i =>
      digests = digests :+ ("sha256:" + hex((97 + i % 26).toChar, 64))
    }
    val innermost =
      ociManifestJson("sha256:" + hex('f', 64), "sha256:" + hex('e', 64))
    val pairs = scala.collection.mutable.ArrayBuffer[(String, String)]()
    pairs += ("oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""")
    pairs += ("index.json" -> ociIndexJson(
      s"""{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"${digests(
          0
        )}","size":1}"""
    ))
    pairs += (s"blobs/sha256/${digests(0).substring(7)}" -> ociIndexJson(
      s"""{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"${digests(
          1
        )}","size":1}"""
    ))
    (1 until depth - 1).foreach { i =>
      pairs += (s"blobs/sha256/${digests(i).substring(7)}" -> ociIndexJson(
        s"""{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"${digests(
            i + 1
          )}","size":1}"""
      ))
    }
    pairs += (s"blobs/sha256/${digests.last.substring(7)}" -> innermost)
    pairs += (s"blobs/sha256/${hex('f', 64)}" -> ociConfigJson(
      "sha256:" + hex('d', 64)
    ))
    pairs += (s"blobs/sha256/${hex('e', 64)}" -> "layer")
    val (byUuid, byName) = ociArtifacts(pairs.toSeq*)
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    assert(toProcess.isEmpty, "nesting beyond the cap must not resolve")
  }

  // O-14 — a hostile layer digest inside an otherwise valid manifest is
  // skipped without failing the claim.
  test("O-14 hostile layer digest is skipped") {
    val configD = "sha256:" + hex('b', 64)
    val manifest =
      s"""{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"${configD}","size":2},"layers":[{"digest":"../../../etc/passwd"}]}"""
    val (byUuid, byName) = ociArtifacts(
      "oci-layout" -> """{"imageLayoutVersion":"1.0.0"}""",
      "index.json" -> manifest,
      s"blobs/sha256/${hex('b', 64)}" -> ociConfigJson("sha256:" + hex('c', 64))
    )
    val (toProcess, _, _, _) = ociClaim(byUuid, byName)
    assertEquals(toProcess.length, 1)
    assertEquals(
      toProcess.head.asInstanceOf[DockerToProcess].config.head.layers,
      Nil
    )
  }
}
