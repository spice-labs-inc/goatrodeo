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

import java.io.File
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

/** Tests that the standard (non-fat) library jar is packaged the same way as
  * the sbt `Compile / packageBin` task packages it.
  *
  * Requirement trace: build.sbt filters `logback.xml` out of the library jar
  * with `Compile / packageBin / mappings ~= { _.filter(_._2 != "logback.xml")
  * }`. The Maven build must produce a jar with the same contents so that
  * consumers of the published library do not inherit an unwanted logging
  * configuration.
  *
  * Theory: after the `package` phase the standard jar exists at
  * `target/goatrodeo_3-${version}.jar`. Opening it and scanning entries lets us
  * assert the absence of `logback.xml`. This test runs in the Failsafe
  * integration-test phase because it requires the packaged artifact.
  */
class JarPackagingTest extends munit.FunSuite {

  test("standard library jar excludes logback.xml") {
    val jarFile = new File(
      s"target/goatrodeo_3-${hellogoat.BuildInfo.version}.jar"
    )
    assert(
      jarFile.exists(),
      s"Standard jar not found at ${jarFile.getAbsolutePath}"
    )

    val jar = new JarFile(jarFile)
    try {
      val entryNames = jar.entries().asScala.map(_.getName).toSet
      assert(
        !entryNames.contains("logback.xml"),
        "logback.xml must not be bundled in the published library jar"
      )
    } finally {
      jar.close()
    }
  }

  test("sources jar is produced") {
    val jarFile = new File(
      s"target/goatrodeo_3-${hellogoat.BuildInfo.version}-sources.jar"
    )
    assert(
      jarFile.exists(),
      s"Sources jar not found at ${jarFile.getAbsolutePath}"
    )
  }

  test("javadoc jar stub is produced") {
    val jarFile = new File(
      s"target/goatrodeo_3-${hellogoat.BuildInfo.version}-javadoc.jar"
    )
    assert(
      jarFile.exists(),
      s"Javadoc jar not found at ${jarFile.getAbsolutePath}"
    )

    val jar = new JarFile(jarFile)
    try {
      val entryNames = jar.entries().asScala.map(_.getName).toSet
      assert(
        entryNames.contains("README.txt"),
        "Javadoc stub jar should contain README.txt"
      )
    } finally {
      jar.close()
    }
  }
}
