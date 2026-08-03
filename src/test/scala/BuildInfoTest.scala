/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

/** Tests that the Maven build generated the same `hellogoat.BuildInfo`
  * object that the sbt build produces via sbt-buildinfo.
  *
  * Requirement trace: build.sbt defines `buildInfoKeys` as
  * `name, version, scalaVersion, sbtVersion, commit`; the Maven build must
  * expose the same fields so runtime code (version banners, ADG output) works
  * identically under either build tool.
  *
  * Theory: if the generated `BuildInfo` object is source-compatible and the
  * commit SHA matches the current git HEAD, the Maven resource-filtering
  * template produced a faithful replacement for sbt-buildinfo.
  */
class BuildInfoTest extends munit.FunSuite {

  test("BuildInfo.name matches the sbt project name") {
    assertEquals(hellogoat.BuildInfo.name, "goatrodeo")
  }

  test("BuildInfo.version is present and non-empty") {
    assert(
      hellogoat.BuildInfo.version.nonEmpty,
      "BuildInfo.version should not be empty"
    )
  }

  test("BuildInfo.scalaVersion matches the Scala 3 version") {
    assertEquals(hellogoat.BuildInfo.scalaVersion, "3.8.3")
  }

  test("BuildInfo.sbtVersion is present and non-empty") {
    assert(
      hellogoat.BuildInfo.sbtVersion.nonEmpty,
      "BuildInfo.sbtVersion should not be empty"
    )
  }

  test("BuildInfo.commit matches the current git HEAD") {
    import scala.sys.process.*
    val expected = Process("git rev-parse HEAD").lazyLines.head
    assertEquals(hellogoat.BuildInfo.commit, expected)
  }
}
