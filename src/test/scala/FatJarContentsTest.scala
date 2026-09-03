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

/** Phase 1 — fat-jar hygiene (spec §1, T1.4).
  *
  * WHAT: verifies the fat jar survives the new pinned dependencies:
  * sqlite-jdbc's per-platform natives are present, exactly one
  * `META-INF/services/java.sql.Driver` service entry exists, and no JAR
  * signature files are bundled.
  *
  * WHY: sqlite-jdbc (baharat's opt-in dep) bundles native `.so`/`.dll`
  * files and a JDBC driver service entry; the fat jar must keep exactly
  * one driver (no duplicates from multiple jars) and no signature files
  * (which would break loading). The `--help` execution is covered by the
  * existing FatJarExecutionTest.
  */
class FatJarContentsTest extends munit.FunSuite {

  private def fatJar(): JarFile = {
    val f = new File(
      s"target/scala-3.8.3/${hellogoat.BuildInfo.name}-${hellogoat.BuildInfo.version}-fat.jar"
    )
    assert(f.exists(), s"Fat JAR not found at ${f.getAbsolutePath}")
    new JarFile(f)
  }

  test("T1.4a fat JAR carries sqlite-jdbc natives") {
    val jar = fatJar()
    try {
      val entries = jar.entries().asScala.map(_.getName).toList
      val natives = entries.filter(n =>
        n.startsWith("org/sqlite/native/") && (n.endsWith(".so") || n.endsWith(".dll"))
      )
      assert(natives.nonEmpty, "sqlite natives must be bundled")
      assert(
        natives.exists(_.contains("Linux")),
        s"at least the Linux native must be bundled, got: ${natives.take(3)}"
      )
    } finally jar.close()
  }

  test("T1.4b exactly one java.sql.Driver service entry") {
    val jar = fatJar()
    try {
      val drivers = jar.entries().asScala
        .map(_.getName)
        .filter(n => n.startsWith("META-INF/services/") && n.endsWith("java.sql.Driver"))
        .toList
      assertEquals(drivers, List("META-INF/services/java.sql.Driver"))
      val in = jar.getInputStream(jar.getJarEntry("META-INF/services/java.sql.Driver"))
      val lines = scala.io.Source.fromInputStream(in).getLines().map(_.trim).filter(_.nonEmpty).toList
      in.close()
      assertEquals(lines, List("org.sqlite.JDBC"))
    } finally jar.close()
  }

  test("T1.4c fat JAR must not contain signature files") {
    val jar = fatJar()
    try {
      val entries = jar.entries().asScala.toList
      val signatureFiles = entries.filter { e =>
        val name = e.getName
        name.startsWith("META-INF/") && (
          name.endsWith(".SF") || name.endsWith(".DSA") ||
            name.endsWith(".RSA") || name.endsWith(".EC") || name.startsWith("SIG-")
        )
      }
      assert(
        signatureFiles.isEmpty,
        s"Found signature files in fat JAR: ${signatureFiles.map(_.getName).mkString(", ")}"
      )
    } finally jar.close()
  }
}