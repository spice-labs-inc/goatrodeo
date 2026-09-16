import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import java.util.jar.JarFile
import scala.io.Source
import scala.jdk.CollectionConverters.*

/** fat-jar hygiene .
  *
  * WHAT: verifies the fat jar survives the new pinned dependencies:
  * sqlite-jdbc's per-platform natives are present, exactly one
  * `META-INF/services/java.sql.Driver` service entry exists, and no JAR
  * signature files are bundled.
  *
  * WHY: sqlite-jdbc (baharat's opt-in dep) bundles native `.so`/`.dll` files
  * and a JDBC driver service entry; the fat jar must keep exactly one driver
  * (no duplicates from multiple jars) and no signature files (which would break
  * loading). The `--help` execution is covered by the existing
  * FatJarExecutionTest.
  */
class FatJarContentsTest extends GoatRodeoFunSuite {

  private def fatJar(): JarFile = {
    val f = new File(
      s"target/scala-3.8.3/${hellogoat.BuildInfo.name}-${hellogoat.BuildInfo.version}-fat.jar"
    )
    assert(f.exists(), s"Fat JAR not found at ${f.getAbsolutePath}")
    new JarFile(f)
  }

  test("fat JAR carries sqlite-jdbc natives") {
    val jar = fatJar()
    try {
      val entries = jar.entries().asScala.map(_.getName).toList
      val natives = entries.filter(n =>
        n.startsWith("org/sqlite/native/") && (n.endsWith(".so") || n.endsWith(
          ".dll"
        ))
      )
      assert(natives.nonEmpty, "sqlite natives must be bundled")
      assert(
        natives.exists(_.contains("Linux")),
        s"at least the Linux native must be bundled, got: ${natives.take(3)}"
      )
    } finally jar.close()
  }

  test("exactly one java.sql.Driver service entry") {
    val jar = fatJar()
    try {
      val drivers = jar
        .entries()
        .asScala
        .map(_.getName)
        .filter(n =>
          n.startsWith("META-INF/services/") && n.endsWith("java.sql.Driver")
        )
        .toList
      assertEquals(drivers, List("META-INF/services/java.sql.Driver"))
      val in =
        jar.getInputStream(jar.getJarEntry("META-INF/services/java.sql.Driver"))
      val lines = Source
        .fromInputStream(in)
        .getLines()
        .map(_.trim)
        .filter(_.nonEmpty)
        .toList
      in.close()
      assertEquals(lines, List("org.sqlite.JDBC"))
    } finally jar.close()
  }

  test("fat JAR must not contain signature files") {
    val jar = fatJar()
    try {
      val entries = jar.entries().asScala.toList
      val signatureFiles = entries.filter { e =>
        val name = e.getName
        name.startsWith("META-INF/") && (
          name.endsWith(".SF") || name.endsWith(".DSA") ||
            name.endsWith(".RSA") || name.endsWith(".EC") || name.startsWith(
              "SIG-"
            )
        )
      }
      assert(
        signatureFiles.isEmpty,
        s"Found signature files in fat JAR: ${signatureFiles.map(_.getName).mkString(", ")}"
      )
    } finally jar.close()
  }
}
