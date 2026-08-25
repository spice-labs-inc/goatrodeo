/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.omnibor.strategies.DockerToProcess
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Parity between docker-save tars and OCI image layouts, against wild fixtures
  * fetched from public registries by pinned digest (see build.sbt `ociPins`).
  *
  * WHAT: the same image, pinned by digest, is ingested twice — once as the
  * docker-save tar (`docker pull @digest` + `docker save`) and once as the OCI
  * layout (`oras copy --to-oci-layout`, exactly what the registry serves). Both
  * are processed by the one Docker strategy; these tests pin that the strategy
  * extracts the maximum information each format carries.
  *
  * WHY: "parity" here does not mean byte-equal outputs. The two transports
  * carry different metadata: docker-save has RepoTags (→ pURLs) and
  * re-compressed layers (different blob digests); the wild OCI layout has the
  * OCI manifest media types and annotations but almost never a
  * `org.opencontainers.image.ref.name` (→ no RepoTags, no pURLs). Parity is:
  * every field the image config carries is extracted identically from both
  * formats, and each format's own extra fields are extracted.
  *
  * THEORY: the config blob digest is identical across the two transports of a
  * pinned image (both store the config blob byte-for-byte), so equality of the
  * extracted config-derived metadata is implied — and asserted. Layer blobs may
  * differ (docker re-compresses), so layer parity is asserted on
  * `rootfs.diff_ids` (config content), not on blob paths.
  *
  * LLM note: P-xx = test id. All integration tests `assume` the fixtures exist;
  * the build fetches them from the registries only when missing, and skips the
  * fetch (and thus these tests) where docker is unavailable.
  */
class OciDockerParitySuite extends munit.FunSuite {

  // Parsing the ~450 MB postgres tarballs (twice, docker + OCI) exceeds the
  // munit default timeout, as in DockerSuite.
  override val munitTimeout = scala.concurrent.duration.Duration(30, "minutes")

  private val alpineLayoutDir = new File("test_data/download/oci_images/alpine")
  private val postgresLayoutDir =
    new File("test_data/download/oci_images/postgres")
  private val alpineDockerTar = new File(
    "test_data/download/docker_tests/alpine_de4fe7064d8f_docker.tar"
  )
  private val postgresDockerTar = new File(
    "test_data/download/docker_tests/postgres_e62fbf9d3e2b_docker.tar"
  )

  /** The pinned config-blob digests (see build.sbt `ociPins`): the parity
    * precondition — both transports of the same pinned image must store this
    * exact config blob.
    */
  private val pinnedConfigDigests = Map(
    "alpine" ->
      "sha256:ff221270b9fb7387b0ad9ff8f69fbbd841af263842e62217392f18c3b5226f38",
    "postgres" ->
      "sha256:6b14e73a48cf2518aeb37e8b758a907473bcc72727297a7353a665afa069ef10"
  )

  private def fixturesPresent: Boolean =
    alpineLayoutDir.isDirectory() && postgresLayoutDir.isDirectory() &&
      alpineDockerTar.exists() && postgresDockerTar.exists()

  // ---------------------------------------------------------------------
  // fixture mechanics
  // ---------------------------------------------------------------------

  /** Tar an OCI layout directory so it flows through the same extraction
    * pipeline as the docker-save tars (entry names = full relative paths:
    * oci-layout, index.json, blobs/sha256/<hex>).
    */
  private def layoutTar(layoutDir: File): File = {
    val tar = File.createTempFile("oci-layout", ".tar")
    val out = new TarArchiveOutputStream(new FileOutputStream(tar))
    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
    try {
      val files = Files
        .walk(layoutDir.toPath())
        .toArray()
        .map(_.asInstanceOf[java.nio.file.Path])
        .filter(p =>
          Files.isRegularFile(p) && !p.getFileName.toString.startsWith(".")
        )
      files.foreach { p =>
        val rel = layoutDir.toPath().relativize(p).toString
        val bytes = Files.readAllBytes(p)
        val entry = new TarArchiveEntry(p.toFile(), rel)
        entry.setSize(bytes.length)
        out.putArchiveEntry(entry)
        out.write(bytes)
        out.closeArchiveEntry()
      }
    } finally {
      out.close()
    }
    tar
  }

  private def dockerStorage(tar: File): Storage =
    ToProcess.buildGraphFromArtifactWrapper(
      FileWrapper(tar, tar.getName(), None)
    )(using Configuration(packageTags = true))

  private def ociStorage(layoutDir: File): Storage = {
    val tar = layoutTar(layoutDir)
    try {
      ToProcess.buildGraphFromArtifactWrapper(
        FileWrapper(tar, tar.getName(), None)
      )(using Configuration(packageTags = true))
    } finally {
      tar.delete()
    }
  }

  private def readJson(p: File): JValue = {
    val s = new String(Files.readAllBytes(p.toPath()), "UTF-8")
    parseOpt(s).getOrElse(fail(s"not JSON: ${p}"))
  }

  private def dockerManifestEntry(tar: File): JValue = {
    val stream =
      new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
        new java.io.FileInputStream(tar)
      )
    try {
      var found: Option[JValue] = None
      var entry = stream.getNextEntry
      while (entry != null && found.isEmpty) {
        if (entry.getName == "manifest.json") {
          val bytes = stream.readAllBytes()
          found = parseOpt(new String(bytes, "UTF-8"))
        }
        entry = stream.getNextEntry
      }
      found
        .getOrElse(fail(s"no manifest.json in ${tar}"))
        .asInstanceOf[JArray]
        .arr
        .head
    } finally {
      stream.close()
    }
  }

  private def ociManifestJson(layoutDir: File): JValue = {
    val index = readJson(new File(layoutDir, "index.json"))
    index \ "manifests" match {
      case JArray(arr) if arr.nonEmpty =>
        val digest = (arr.head \ "digest").asInstanceOf[JString].s
        readJson(new File(layoutDir, s"blobs/sha256/${digest.substring(7)}"))
      case _ => index
    }
  }

  private def configBlobJson(layoutDir: File): JValue = {
    val manifest = ociManifestJson(layoutDir)
    val digest = (manifest \ "config" \ "digest").asInstanceOf[JString].s
    readJson(new File(layoutDir, s"blobs/sha256/${digest.substring(7)}"))
  }

  /** The items of a storage indexed by every mime type they carry (an item is
    * listed under each of its mimes). Items whose body is not ItemMetaData
    * (e.g. tag items) are skipped.
    */
  private def itemsByMime(storage: Storage): Map[String, Vector[Item]] = {
    storage
      .keys()
      .toVector
      .flatMap(k => storage.read(k))
      .flatMap { item =>
        item.body match {
          case Some(meta: ItemMetaData) =>
            meta.mimeType.toVector.map(_ -> item)
          case _ => Vector.empty[(String, Item)]
        }
      }
      .groupBy(_._1)
      .map { case (mime, pairs) => mime -> pairs.map(_._2) }
  }

  private def configItem(storage: Storage): Item = {
    val items =
      itemsByMime(storage).getOrElse(
        "application/vnd.oci.image.config.v1+json",
        Vector()
      )
    assertEquals(
      items.length,
      1,
      s"expected exactly one config item, got ${items.length}; mime keys present: ${itemsByMime(storage).keys.toVector.sorted
          .mkString(", ")}"
    )
    items.head
  }

  private def extra(item: Item): Map[String, Set[String]] = {
    val meta = item.body.get.asInstanceOf[ItemMetaData]
    meta.extra.map { case (k, v) => k -> v.map(_.value) }
  }

  // ---------------------------------------------------------------------
  // P-01 — the parity precondition: both transports carry the pinned
  // config blob. THEORY: if this fails, the fixtures drifted and nothing
  // else in this suite is meaningful.
  // ---------------------------------------------------------------------
  test("P-01 both transports carry the pinned config digest") {
    assume(fixturesPresent, "OCI parity fixtures are not fetched")
    for {
      (name, layoutDir, dockerTar) <- Seq(
        ("alpine", alpineLayoutDir, alpineDockerTar),
        ("postgres", postgresLayoutDir, postgresDockerTar)
      )
    } {
      val pinned = pinnedConfigDigests(name)
      val dockerConfigPath =
        (dockerManifestEntry(dockerTar) \ "Config").asInstanceOf[JString].s
      assertEquals(dockerConfigPath, s"blobs/sha256/${pinned.substring(7)}")
      val ociConfigDigest =
        (ociManifestJson(layoutDir) \ "config" \ "digest")
          .asInstanceOf[JString]
          .s
      assertEquals(ociConfigDigest, pinned)
    }
  }

  // ---------------------------------------------------------------------
  // P-02 — shared-field parity: every config-derived metadata field the
  // strategy extracts is identical across the two transports.
  // ---------------------------------------------------------------------
  test("P-02 config-derived metadata is identical across transports") {
    assume(fixturesPresent, "OCI parity fixtures are not fetched")
    for {
      (name, layoutDir, dockerTar) <- Seq(
        ("alpine", alpineLayoutDir, alpineDockerTar),
        ("postgres", postgresLayoutDir, postgresDockerTar)
      )
    } {
      val docker = dockerStorage(dockerTar)
      val oci = ociStorage(layoutDir)

      val dockerConfig = configItem(docker)
      val ociConfig = configItem(oci)

      val dockerExtra = extra(dockerConfig)
      val ociExtra = extra(ociConfig)

      // Fields both transports must extract identically (from the same
      // config blob). Transport-specific fields (RepoDigests, ConfigMediaType,
      // SchemaVersion, Size, and the RepoTags-derived CanonicalPurl) are
      // excluded — see P-04 and P-05.
      val sharedKeys = (dockerExtra.keySet ++ ociExtra.keySet).filter { k =>
        !k.startsWith("docker:RepoDigest") &&
        !k.startsWith("docker:ConfigMediaType") &&
        !k.startsWith("docker:SchemaVersion") &&
        !k.startsWith("docker:Size") &&
        !k.startsWith("docker:ManifestJson") &&
        !k.startsWith("docker:ConfigJson") &&
        !k.contains("CanonicalPurl")
      }
      for (k <- sharedKeys) {
        assertEquals(
          ociExtra.getOrElse(k, Set()),
          dockerExtra.getOrElse(k, Set()),
          s"${name}: metadata ${k} must match across transports"
        )
      }

      // spot-check the fields that define image behaviour
      assert(
        ociExtra.keySet.exists(_.startsWith("docker:Env:")),
        s"${name}: env"
      )
      assert(
        ociExtra.getOrElse("docker:LayerCount", Set()) ==
          dockerExtra.getOrElse("docker:LayerCount", Set()),
        s"${name}: layer count"
      )
      assert(
        ociExtra.contains("docker:Platform"),
        s"${name}: platform present"
      )
    }
  }

  // ---------------------------------------------------------------------
  // P-03 — layer graph parity: same layer item count and the same number
  // of contains edges from the config item, despite differing blob digests.
  // ---------------------------------------------------------------------
  test("P-03 layer graph parity holds despite differing blob digests") {
    assume(fixturesPresent, "OCI parity fixtures are not fetched")
    for {
      (name, layoutDir, dockerTar) <- Seq(
        ("alpine", alpineLayoutDir, alpineDockerTar),
        ("postgres", postgresLayoutDir, postgresDockerTar)
      )
    } {
      val docker = dockerStorage(dockerTar)
      val oci = ociStorage(layoutDir)

      def layerStats(storage: Storage): (Int, Int) = {
        val byMime = itemsByMime(storage)
        val layers =
          byMime.getOrElse("application/vnd.oci.image.layer.v1.tar", Vector())
        val config = configItem(storage)
        val contains = config.connections.count(_._1 == EdgeType.contains)
        (layers.length, contains)
      }
      val (dockerLayers, dockerContains) = layerStats(docker)
      val (ociLayers, ociContains) = layerStats(oci)
      assertEquals(ociLayers, dockerLayers, s"${name}: layer item count")
      assertEquals(ociContains, dockerContains, s"${name}: contains edges")
    }
  }

  // ---------------------------------------------------------------------
  // P-04 — pURLs: the docker-save side additionally emits the RepoTags pURL;
  // the wild OCI layout (no ref.name annotation) emits none — and the
  // non-docker pURLs (from the layer contents, e.g. carved certs) are
  // identical across transports.
  // ---------------------------------------------------------------------
  test(
    "P-04 docker-save adds the RepoTags pURL; wild OCI emits no docker pURL"
  ) {
    assume(fixturesPresent, "OCI parity fixtures are not fetched")
    val docker = dockerStorage(alpineDockerTar)
    assert(
      docker.purls().exists(_.contains("pkg:docker/alpine@3.20.6")),
      s"docker side must emit the RepoTags pURL: ${docker.purls()}"
    )
    val oci = ociStorage(alpineLayoutDir)
    assert(
      !oci.purls().exists(_.startsWith("pkg:docker/")),
      s"wild OCI layout has no ref.name annotation, so no docker pURL: ${oci.purls()}"
    )
    // the layer-content pURLs (ca-certificates etc.) must agree across
    // transports: both ingest the same layer content
    val dockerNonDocker =
      docker.purls().filterNot(_.startsWith("pkg:docker/"))
    assertEquals(
      oci.purls().filterNot(_.startsWith("pkg:docker/")),
      dockerNonDocker,
      "layer-content pURLs must agree across transports"
    )
  }

  // ---------------------------------------------------------------------
  // P-05 — capability: the OCI layout always carries the OCI manifest
  // media types, which the strategy extracts. The docker-save side MAY
  // preserve an OCI manifest blob (alpine's does — a buildkit artifact of
  // the image build survives `docker save`), so where both sides carry a
  // field its value must agree; absence on the docker side is fine.
  // ---------------------------------------------------------------------
  test("P-05 OCI manifest fields are extracted from the OCI layout") {
    assume(fixturesPresent, "OCI parity fixtures are not fetched")
    val docker = dockerStorage(alpineDockerTar)
    val oci = ociStorage(alpineLayoutDir)

    val ociExtra = extra(configItem(oci))
    assertEquals(
      ociExtra.getOrElse("docker:ConfigMediaType", Set()),
      Set("application/vnd.oci.image.config.v1+json")
    )
    assertEquals(ociExtra.getOrElse("docker:SchemaVersion", Set()), Set("2"))
    val dockerExtra = extra(configItem(docker))
    for (k <- Set("docker:ConfigMediaType", "docker:SchemaVersion")) {
      dockerExtra.get(k).foreach { v =>
        assertEquals(
          v,
          ociExtra.getOrElse(k, Set()),
          s"where the docker-save side carries ${k}, it must agree"
        )
      }
    }
  }

  // ---------------------------------------------------------------------
  // P-06 — diff_ids parity, read straight from the fixtures' config blobs:
  // identical because the config blob is byte-identical.
  // ---------------------------------------------------------------------
  test("P-06 rootfs diff_ids are identical across transports") {
    assume(fixturesPresent, "OCI parity fixtures are not fetched")
    for {
      (name, layoutDir, dockerTar) <- Seq(
        ("alpine", alpineLayoutDir, alpineDockerTar),
        ("postgres", postgresLayoutDir, postgresDockerTar)
      )
    } {
      val ociDiffIds = configBlobJson(layoutDir) \ "rootfs" \ "diff_ids"
      val pinned = pinnedConfigDigests(name)
      val dockerConfigReal = {
        val stream =
          new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            new java.io.FileInputStream(dockerTar)
          )
        try {
          var found: Option[JValue] = None
          var entry = stream.getNextEntry
          while (entry != null && found.isEmpty) {
            if (entry.getName == s"blobs/sha256/${pinned.substring(7)}") {
              found = parseOpt(new String(stream.readAllBytes(), "UTF-8"))
            }
            entry = stream.getNextEntry
          }
          found.getOrElse(fail(s"config blob missing from ${dockerTar}"))
        } finally {
          stream.close()
        }
      }
      val dockerDiffIds = dockerConfigReal \ "rootfs" \ "diff_ids"
      assertEquals(ociDiffIds, dockerDiffIds, s"${name}: diff_ids")
    }
  }

  // ---------------------------------------------------------------------
  // P-07 — the fetched fixture content is data, never executed: the only
  // docker run is the pinned ORAS fetch, and pull/tag/save only ever
  // reference digest-pinned refs.
  // ---------------------------------------------------------------------
  test("P-07 build.sbt never executes fetched image content") {
    val build = new String(
      Files.readAllBytes(new File("build.sbt").toPath()),
      "UTF-8"
    )
    // the ORAS container image is pinned by digest and is the only image the
    // build ever runs
    assert(
      build.contains(
        "ghcr.io/oras-project/oras:v1.3.0@sha256:6ce045ce069a89934d6666b8b49f9c4c0145201bd6de6dbe2aee267814c55468"
      ),
      "the ORAS fetch image must be pinned by digest"
    )
    val lines = build.split("\n").toVector.filterNot(_.trim.startsWith("//"))
    // pull/tag/save must reference the digest-derived variables, and the
    // pinned digests themselves must appear as literals in the pin table
    val pullSaveTag = lines.filter(l =>
      l.contains("docker pull") || l.contains("docker save") ||
        l.contains("docker tag")
    )
    assert(pullSaveTag.nonEmpty, "expected docker pull/save/tag lines")
    assert(
      pullSaveTag.forall(l =>
        l.contains("${ref}") || l.contains("${image}") ||
          l.contains("${tag}")
      ),
      s"pull/tag/save must reference the pin-derived variables: ${pullSaveTag}"
    )
    assert(
      build.contains(
        "sha256:de4fe7064d8f98419ea6b49190df1abbf43450c1702eeb864fe9ced453c1cc5f"
      ) &&
        build.contains(
          "sha256:e62fbf9d3e2b49816a32c400ed2dba83e3b361e6833e624024309c35d334b412"
        ),
      "the pinned image digests must be literals in the pin table"
    )
    assert(
      build.contains("val ref = s\"${image}@${indexDigest}\""),
      "pull/tag/save must reference the digest-pinned ref"
    )
    val forbidden = Vector(
      "docker exec",
      "docker load",
      "docker import",
      "docker build",
      "docker cp",
      "docker create",
      "docker start"
    )
    for (cmd <- forbidden) {
      assert(!build.contains(cmd), s"forbidden docker subcommand: ${cmd}")
    }
  }

  // ---------------------------------------------------------------------
  // P-08 — directory ingestion (the wild usage: `--build <oci layout dir>`
  // with relative paths) is claimed by the Docker strategy.
  // ---------------------------------------------------------------------
  test("P-08 an OCI layout directory is claimed via relative paths") {
    assume(fixturesPresent, "OCI parity fixtures are not fetched")
    val dir = alpineLayoutDir
    val wrappers = Helpers
      .findFiles(dir)
      .map { f =>
        val rel = dir.toPath().relativize(f.toPath()).toString
        FileWrapper(f, rel, None)
      }
    val toProcess =
      ToProcess.strategiesForArtifacts(wrappers, _ => (), false)
    val dockerTps = toProcess.collect { case tp: DockerToProcess => tp }
    assertEquals(dockerTps.length, 1, "the OCI layout must be claimed once")
    assertEquals(dockerTps.head.config.head.effectiveRepoTags, Vector())

    val storage =
      ToProcess.buildGraphForToProcess(toProcess)(using Configuration())
    assert(
      itemsByMime(storage).contains(
        "application/vnd.oci.image.config.v1+json"
      ),
      "the graph must contain the config item"
    )
  }
}
