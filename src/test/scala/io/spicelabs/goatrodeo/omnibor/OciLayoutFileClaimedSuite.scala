package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.omnibor.strategies.DockerToProcess
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Regression: every input file of an OCI image layout must appear in the
  * graph. The `oci-layout` marker file is one of the inputs.
  *
  * Pins `Docker.ociLayoutClaim`: `claimedNames` includes `oci-layout` so it is
  * removed from `byName`, but its UUID is not added to `claimedUuids` and
  * `DockerToProcess` never emits it. The terminal generic strategy iterates
  * `byName`, so the file is processed by nobody and has no Item.
  */
class OciLayoutFileClaimedSuite extends GoatRodeoFunSuite {

  private def sha256(bytes: Array[Byte]): String =
    "sha256:" + Helpers.toHex(
      MessageDigest.getInstance("SHA-256").digest(bytes)
    )

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def tinyLayer(): (Array[Byte], Array[Byte]) = {
    val tarBytes = new ByteArrayOutputStream()
    val tar = new TarArchiveOutputStream(tarBytes)
    val content = utf8("hello from a layer\n")
    val entry = new TarArchiveEntry("etc/hello.txt")
    entry.setSize(content.length)
    tar.putArchiveEntry(entry)
    tar.write(content)
    tar.closeArchiveEntry()
    tar.close()
    val gz = new ByteArrayOutputStream()
    val gzOut = new GZIPOutputStream(gz)
    gzOut.write(tarBytes.toByteArray)
    gzOut.close()
    (tarBytes.toByteArray, gz.toByteArray)
  }

  /** Build a minimal, digest-consistent OCI image layout on disk. */
  private def syntheticLayout(): File = {
    val dir = Files.createTempDirectory("oci-layout").toFile
    dir.deleteOnExit()
    val blobs = new File(dir, "blobs/sha256")
    blobs.mkdirs()
    def putBlob(bytes: Array[Byte]): String = {
      val digest = sha256(bytes)
      Files.write(new File(blobs, digest.stripPrefix("sha256:")).toPath, bytes)
      digest
    }
    val (layerTar, layerGz) = tinyLayer()
    val layerDigest = putBlob(layerGz)
    val config = utf8(
      s"""{"architecture":"amd64","os":"linux","config":{},
         |"rootfs":{"type":"layers","diff_ids":["${sha256(
          layerTar
        )}"]}}""".stripMargin
    )
    val configDigest = putBlob(config)
    val manifest = utf8(
      s"""{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
         |"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$configDigest","size":${config.length}},
         |"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"$layerDigest","size":${layerGz.length}}]}""".stripMargin
    )
    val manifestDigest = putBlob(manifest)
    Files.write(
      new File(dir, "index.json").toPath,
      utf8(
        s"""{"schemaVersion":2,"manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json",
           |"digest":"$manifestDigest","size":${manifest.length},
           |"platform":{"architecture":"amd64","os":"linux"}}]}""".stripMargin
      )
    )
    Files.write(
      new File(dir, "oci-layout").toPath,
      utf8("""{"imageLayoutVersion":"1.0.0"}""")
    )
    dir
  }

  test("the oci-layout marker file of a claimed OCI layout has an Item") {
    val dir = syntheticLayout()
    val wrappers = Helpers.findFiles(dir).map { f =>
      FileWrapper(f, dir.toPath.relativize(f.toPath).toString, None)
    }
    val toProcess = ToProcess.strategiesForArtifacts(wrappers, _ => (), false)
    val dockerTps = toProcess.collect { case tp: DockerToProcess => tp }
    assertEquals(
      dockerTps.length,
      1,
      "control: the synthetic layout must be claimed by the Docker strategy"
    )

    val storage =
      ToProcess.buildGraphForToProcess(toProcess)(using Configuration())
    val fileNames = storage
      .keys()
      .toVector
      .flatMap(storage.read)
      .flatMap(_.bodyAsItemMetaData)
      .flatMap(_.fileNames)
    assert(
      fileNames.exists(_.contains("index.json")),
      "control: index.json must be in the graph"
    )
    assert(
      fileNames.exists(_.contains("oci-layout")),
      s"oci-layout must be in the graph; file names present: ${fileNames.sorted.mkString(", ")}"
    )
  }
}
