/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0 */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Checks the Spring groupId lookup in `KnownMavenGroups`.
  *
  * The snapshot in `spring_maven_groups.tsv` comes from Maven Central via
  * `scripts/refresh_spring_artifacts.py`; rerun that script to pick up new
  * Spring artifacts. These tests check that every snapshot entry resolves
  * through the full manifest path, and that each fallback prefix in
  * `projectPrefixes` agrees with the snapshot, so a typo or a relocated project
  * shows up here rather than as a wrong pURL.
  */
class KnownMavenGroupsSuite extends GoatRodeoFunSuite {

  private val snapshot = KnownMavenGroups.springSnapshot

  test("snapshot loads") {
    assert(snapshot.size > 1000, s"only ${snapshot.size} Spring artifacts")
    assertEquals(snapshot.get("spring-boot"), Some("org.springframework.boot"))
    assertEquals(snapshot.get("spring-beans"), Some("org.springframework"))
  }

  test(
    "every snapshot artifact resolves to its groupId from a Spring manifest"
  ) {
    val wrong = snapshot.toVector.sorted.flatMap { case (artifactId, groupId) =>
      val manifest = TreeMap[String, TreeSet[StringOrPair]](
        "automatic-module-name" -> TreeSet(
          StringOrPair(artifactId.replace('-', '.'))
        ),
        "implementation-version" -> TreeSet(StringOrPair("1.0"))
      )
      val (g, _, _) = MavenState().resolveGroupIdArtifactIdVersion(
        ByteWrapper(Array.emptyByteArray, s"$artifactId-1.0.jar", None),
        None,
        manifest,
        Map.empty,
        None
      )
      Option.when(!g.contains(groupId))(
        s"$artifactId: expected $groupId, got $g"
      )
    }
    assert(wrong.isEmpty, wrong.mkString("\n"))
  }

  test("each fallback prefix matches the snapshot for its own project") {
    // Among the snapshot artifacts a prefix `p` covers (and no longer prefix
    // does), no other group may be more common than p's. Strays inside a
    // project, like Spring Cloud Function's `spring-kafka-supplier`, are fine
    // because the snapshot is consulted first. The catch-all `spring` is
    // exempt: what it covers is mostly freely-named Spring Cloud Function
    // modules and retired projects (XD, Social, OSGi), all in the snapshot.
    val wrong = KnownMavenGroups.projectPrefixes.toVector.sorted
      .filterNot(_._1 == "spring")
      .flatMap { case (prefix, groupId) =>
        val longer = KnownMavenGroups.projectPrefixes.keySet
          .filter(other => other != prefix && other.startsWith(prefix + "-"))
        val counts = snapshot
          .filter { case (artifactId, _) =>
            (artifactId == prefix || artifactId.startsWith(prefix + "-")) &&
            !longer
              .exists(l => artifactId == l || artifactId.startsWith(l + "-"))
          }
          .groupMapReduce(_._2)(_ => 1)(_ + _)
        val own = counts.getOrElse(groupId, 0)
        Option.when(own == 0 || counts.values.exists(_ > own))(
          s"$prefix -> $groupId, but the snapshot has $counts"
        )
      }
    assert(wrong.isEmpty, wrong.mkString("\n"))
  }

  test("the catch-all prefix covers Spring Framework's own modules") {
    // Artifacts under a project prefix are left out: some, like the original
    // `spring-ldap`, were published under org.springframework before the
    // project got its own group, and the snapshot already handles them.
    val projects = KnownMavenGroups.projectPrefixes.keySet - "spring"
    val framework = snapshot.collect {
      case (artifactId, "org.springframework")
          if !projects
            .exists(p => artifactId == p || artifactId.startsWith(p + "-")) =>
        artifactId
    }
    val wrong = framework.toVector.sorted.filterNot { artifactId =>
      val tokens = artifactId.split('-')
      (tokens.length to 1 by -1)
        .map(n => tokens.take(n).mkString("-"))
        .collectFirst(Function.unlift(KnownMavenGroups.projectPrefixes.get))
        .contains("org.springframework")
    }
    assert(wrong.isEmpty, wrong.mkString("\n"))
  }

  test("artifacts outside the snapshot fall back to the longest prefix") {
    assertEquals(
      KnownMavenGroups.springGroupId("spring-boot-starter-newthing"),
      Some("org.springframework.boot")
    )
    assertEquals(
      KnownMavenGroups.springGroupId("spring-newmodule"),
      Some("org.springframework")
    )
    assertEquals(KnownMavenGroups.springGroupId("springfox-core"), None)
  }
}
