package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File

/** Shared Docker test fixtures. Builds the graph once per Docker image file
  * (lazy vals), so that multiple test suites can assert against the same
  * Storage without redundant traversals of 1+ GB tar files.
  */
object DockerTestFixtures {

  def checkTestFile(path: String): Boolean = new File(path).exists()

  private val bigtentPath =
    "test_data/download/docker_tests/bigtent_2025_03_22_docker.tar"
  private val grinderPath =
    "test_data/download/docker_tests/grinder_bt_pg_docker.tar"

  lazy val bigtentStorage: Storage = {
    val source = FileWrapper(File(bigtentPath), bigtentPath, None)
    ToProcess.buildGraphFromArtifactWrapper(
      source,
      args = Config(packageTags = true)
    )
  }

  lazy val grinderStorage: Storage = {
    val source = FileWrapper(File(grinderPath), grinderPath, None)
    ToProcess.buildGraphFromArtifactWrapper(
      source,
      args = Config(packageTags = true)
    )
  }
}
