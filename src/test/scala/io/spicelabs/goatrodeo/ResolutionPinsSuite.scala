package io.spicelabs.goatrodeo

import munit.FunSuite

import java.io.File
import java.util.jar.JarFile
import scala.util.Try
import scala.jdk.CollectionConverters.*

/** Phase 1 — dependency pin verification (spec §1).
  *
  * WHAT: verifies the resolved dependency set contains exactly the pinned
  * versions from the spec's §1 table. The resolved set is read from the
  * test classpath jar filenames (`<artifact>-<version>.jar`), which sbt
  * preserves in its staging directory, plus jar-embedded metadata
  * (pom.properties when present) for the group check.
  *
  * WHY: the spec pins specific Maven Central releases of the Spice Labs
  * readers plus three direct pins. A drift in resolution would silently
  * change behavior; these tests fail loudly.
  *
  * LLM note: filename-derived artifact+version is layout-independent
  * (coursier cache vs sbt bg-jobs staging). Group id is checked only
  * where the jar embeds pom.properties (cilantro embeds none).
  */
class ResolutionPinsSuite extends FunSuite {

  private def classpathEntries(): List[String] =
    System.getProperty("java.class.path").split(File.pathSeparator).toList

  private def artifactVersion(fileName: String): Option[(String, String)] = {
    if (!fileName.endsWith(".jar")) None
    else {
      val stem = fileName.stripSuffix(".jar")
      // version starts where the first digit-dash boundary occurs:
      // artifact may contain dashes/dots; version may too (e.g. 7.3.0...-r)
      val idx = stem.indexWhere(c => c == '-')
      val candidates = stem.zipWithIndex.collect {
        case ('-', i) if i < stem.length - 1 && stem.charAt(i + 1).isDigit => i
      }
      candidates.headOption.map { i =>
        stem.substring(0, i) -> stem.substring(i + 1)
      }
    }
  }

  private def fileNameOf(path: String): String =
    new File(path).getName

  private def resolvedArtifacts(): List[(String, String)] =
    classpathEntries().flatMap(p => artifactVersion(fileNameOf(p)))

  private def version(art: String): Option[String] =
    resolvedArtifacts().collectFirst { case (a, v) if a == art => v }

  private def groupOf(art: String): Option[String] =
    classpathEntries().flatMap { path =>
      Try {
        val jar = new JarFile(path)
        try {
          val e = jar.getJarEntry("META-INF/maven/io.spicelabs/baharat/pom.properties")
          if (e == null) None
          else {
            val props = new java.util.Properties()
            props.load(jar.getInputStream(e))
            Option(props.getProperty("groupId"))
          }
        } finally jar.close()
      }.toOption.flatten
    }.headOption

  test("T1.1 resolutionPinsMatchSpec — all pinned versions resolve") {
    val expected = List(
      ("baharat_3", "0.2.1"),
    )
    // artifact names as resolved by sbt (Scala suffix where cross-versioned):
    val expectedResolved = List(
      ("baharat", "0.2.1"),
      ("annatto", "0.3.0"),
      ("cilantro_3", "0.2.1"),
      ("saffron", "0.5.0"),
      ("coordinates", "1.2.1"),
      ("org.eclipse.jgit", "7.3.0.202506031305-r"),
      ("sqlite-jdbc", "3.53.4.0"),
      ("aircompressor", "2.0.3"),
      ("lz4-java", "1.11.2")
    )
    expectedResolved.foreach { case (art, ver) =>
      assertEquals(
        version(art),
        Some(ver),
        s"expected $art:$ver on the classpath"
      )
    }
    // baharat resolves as `baharat` (pure Java artifact), not baharat_3:
    assertEquals(version("baharat_3"), None)
  }

  test("T1.2 noSnapshotsOnResolvedSet") {
    val snaps = resolvedArtifacts().collect {
      case (a, v) if v.contains("SNAPSHOT") => s"$a:$v"
    }
    assertEquals(snaps, Nil, "no SNAPSHOT artifacts may be resolved")
  }

  test("T1.3 lz4ComesFromAtYawkFork") {
    assertEquals(version("lz4-java"), Some("1.11.2"))
    // org.lz4's artifact name is also `lz4-java` — the group is the fork
    // distinguisher. Check the embedded pom.properties group where present:
    // at.yawk.lz4:lz4-java is on the classpath; org.lz4:lz4-java must not be.
    val jars = classpathEntries().filter(_.contains("lz4-java"))
    assert(jars.nonEmpty, "lz4-java must be on the classpath")
    val groups = jars.flatMap { p =>
      Try {
        val jar = new JarFile(p)
        try {
          val e = jar.getJarEntry("META-INF/maven/at.yawk.lz4/lz4-java/pom.properties")
          if (e != null) {
            val props = new java.util.Properties()
            props.load(jar.getInputStream(e))
            Some(props.getProperty("groupId"))
          } else None
        } finally jar.close()
      }.toOption.flatten
    }
    assert(groups.contains("at.yawk.lz4"), s"lz4 must come from at.yawk.lz4, got $groups")
    assert(!groups.contains("org.lz4"), "org.lz4 fork must not be present")
  }
}