/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0 */

package io.spicelabs.goatrodeo.omnibor.strategies
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Maven identity derived from a JAR's manifest when the JAR has no
  * `META-INF/maven/**/pom.properties`.
  *
  * ==What this suite tests==
  *
  * Two defects in `MavenState.resolveGroupIdArtifactIdVersionFromManifest`,
  * both of which produce a wrong Maven purl for a JAR that carries no
  * `pom.properties`. Spring Boot's own JARs ship without one, so a typical
  * Spring Boot application's dependency set is where the damage concentrates,
  * but neither defect is Spring-specific:
  *
  *   - **The groupId was the parent of a dotted name.** A module name or OSGi
  *     symbolic name is often the group itself (`org.hamcrest`, `io.perfmark`,
  *     `org.objenesis`), or is not Maven-shaped at all (`spring.boot`). Taking
  *     the parent truncated the group: `org.hamcrest` gave `org`, `io.perfmark`
  *     gave `io`.
  *   - **The version preferred `Bundle-Version` over
  *     `Implementation-Version`.** An OSGi bundle version carries qualifiers
  *     the Maven version does not, so the purl advertised a version that no
  *     Maven repository holds: `3.2.0` for a `3.2` JAR, `1.0.27.RELEASE` for
  *     `1.0.27`.
  *
  * ==Why the groupId rule is three rules and not one==
  *
  * Both conventions exist in the wild — a dotted name may be
  * `<group>.<artifact>` or it may be the group — and the manifest alone cannot
  * distinguish them with certainty. The rule order is what makes the change a
  * net improvement rather than a swap of one error for another:
  *
  *   1. the name ends with `.<artifactId>` and at least two segments remain →
  *      those segments are the group (`com.example.foo` + `foo` →
  *      `com.example`); 2. otherwise, the last segment equals the artifactId,
  *      the artifactId without hyphens, or the artifactId's first
  *      hyphen-separated token → the whole name is the group (`org.hamcrest` +
  *      `hamcrest` → `org.hamcrest`); 3. otherwise → the parent, as before.
  *
  * Rule 1 must precede rule 2. Rule 2 alone would break the
  * `<group>.<artifact>` shape by claiming `com.example.foo` is the group of
  * `com.example.foo` + `foo`. Test `A-5` pins that.
  *
  * ==What this suite does NOT test==
  *
  * Spring's own group. `spring.boot` and `spring.core` do not contain
  * `org.springframework.boot` or `org.springframework` anywhere in the JAR, so
  * no rule that reads only the artifact can recover them. That is a known gap,
  * pinned as a characterisation test by `C-1` so that closing it is a
  * deliberate act rather than an accident.
  *
  * ==LLM summary==
  *
  * | Test      | Asserts                                                                                                                                   |
  * |:----------|:------------------------------------------------------------------------------------------------------------------------------------------|
  * | A-1 … A-3 | rule 2: the whole dotted name is the group when its last segment matches the artifactId (exactly, hyphen-stripped, or as the first token) |
  * | A-4       | rule 1: a name ending in `.<artifactId>` with ≥2 segments left yields those segments                                                      |
  * | A-5       | rule 1 keeps the `<group>.<artifact>` shape working (non-regression)                                                                      |
  * | A-6       | rule 3: an unmatched name keeps the parent, as before                                                                                     |
  * | A-7       | `Implementation-Vendor-Id` still outranks every name-derived group                                                                        |
  * | A-8       | an undotted name falls through to the artifactId last resort                                                                              |
  * | A-9       | with no artifactId in the filename, the parent is used                                                                                    |
  * | B-1 … B-4 | version priority `Implementation-Version` > `Bundle-Version` > `Specification-Version`                                                    |
  * | AB-1      | the six non-Spring rows of issue #337, through the resolver                                                                               |
  * | C-1       | characterisation: a Spring module name yields the module-implied group, which is the accepted best effort                                 |
  */
class ManifestPurlDerivationSuite extends GoatRodeoFunSuite {

  private def state = MavenState()

  /** Resolve identity the way the pipeline does: the wrapper's filename
    * supplies the fallback artifactId, the manifest supplies everything else.
    *
    * @param filename
    *   the artifact's file name, e.g. `hamcrest-2.2.jar`
    * @param manifest
    *   the manifest headers, lower-cased keys
    * @return
    *   (groupId, artifactId, version)
    */
  private def resolve(
      filename: String,
      manifest: TreeMap[String, TreeSet[StringOrPair]]
  ): (Option[String], Option[String], Option[String]) =
    state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, filename, None),
      None,
      manifest,
      Map.empty,
      None
    )

  private def manifestOf(
      pairs: (String, String)*
  ): TreeMap[String, TreeSet[StringOrPair]] =
    TreeMap.from(pairs.map { case (k, v) =>
      k -> TreeSet(StringOrPair(v))
    })

  // ==================== Rule 2: the dotted name is the group ====================

  // A-1 — rule 2, exact match. THEORY: `org.hamcrest` is the group, not the
  // parent of it. The artifactId comes from the file name, so the rule can tell
  // "the name ends with the artifact" from "the name is <group>.<artifact>".
  test("A-1 a two-segment module name equal to the artifactId is the group") {
    val (g, a, v) = resolve(
      "hamcrest-2.2.jar",
      manifestOf(
        "automatic-module-name" -> "org.hamcrest",
        "bundle-symbolicname" -> "org.hamcrest",
        "implementation-version" -> "2.2"
      )
    )
    assertEquals(g, Some("org.hamcrest"), "the dotted name is the group")
    assertEquals(a, Some("hamcrest"))
    assertEquals(v, Some("2.2"))
  }

  // A-2 — rule 2, the artifactId without its hyphens. THEORY: OSGi symbolic
  // names drop the hyphens a Maven artifactId keeps, so `reactive-streams`
  // appears as `reactivestreams`. Comparing only the raw artifactId would miss
  // this and fall through to the parent.
  test("A-2 the last segment may be the artifactId without hyphens") {
    val (g, _, _) = resolve(
      "reactive-streams-1.0.4.jar",
      manifestOf(
        "automatic-module-name" -> "org.reactivestreams",
        "bundle-symbolicname" -> "reactive-streams"
      )
    )
    assertEquals(g, Some("org.reactivestreams"))
  }

  // A-3 — rule 2, the artifactId's first token. THEORY: a module name is often
  // the group plus the artifact's first token (`io.perfmark` for
  // `perfmark-api`), so requiring the whole artifactId to match would leave the
  // group as `io`.
  test("A-3 the last segment may be the artifactId's first token") {
    val (g, a, v) = resolve(
      "perfmark-api-0.25.0.jar",
      manifestOf(
        "automatic-module-name" -> "io.perfmark",
        "implementation-title" -> "PerfMark",
        "implementation-version" -> "0.25.0"
      )
    )
    assertEquals(g, Some("io.perfmark"))
    assertEquals(a, Some("perfmark-api"))
    assertEquals(v, Some("0.25.0"))
  }

  // ==================== Rule 1: name minus the artifactId ====================

  // A-4 — rule 1. THEORY: when a symbolic name ends in `.<artifactId>` and
  // leaves at least two segments, those segments are the group. This is the
  // shape most Maven Bundle Plugin artifacts use, and it is the case the parent
  // rule already handled correctly, so the rule exists to keep it correct.
  test("A-4 a name ending in the artifactId yields the segments before it") {
    val (g, a, v) = resolve(
      "reactor-netty-core-1.0.27.jar",
      manifestOf(
        "bundle-symbolicname" -> "io.projectreactor.netty.reactor-netty-core",
        "automatic-module-name" -> "reactor.netty.core",
        "implementation-title" -> "reactor-netty-core",
        "implementation-version" -> "1.0.27",
        "bundle-version" -> "1.0.27.RELEASE"
      )
    )
    assertEquals(g, Some("io.projectreactor.netty"))
    assertEquals(a, Some("reactor-netty-core"))
    assertEquals(v, Some("1.0.27"))
  }

  // A-5 — rule order, the non-regression that makes rule 2 safe. THEORY: rule 2
  // alone would claim `com.example.foo` is the group of `com.example.foo` plus
  // artifactId `foo`, because its last segment matches the artifactId. Rule 1
  // has to run first, or the fix trades one wrong answer for another.
  test("A-5 the group.artifact shape keeps the parent as the group") {
    val (g, a, _) = resolve(
      "foo-1.0.jar",
      manifestOf("automatic-module-name" -> "com.example.foo")
    )
    assertEquals(
      g,
      Some("com.example"),
      "rule 1 must run before rule 2, or this becomes com.example.foo"
    )
    assertEquals(a, Some("foo"))
  }

  // ==================== Rule 3 and the guards ====================

  // A-6 — rule 3, unchanged behaviour. THEORY: a name that matches neither rule
  // keeps the parent, so this change cannot silently alter artifacts whose
  // module name is neither the group nor `<group>.<artifact>`.
  test("A-6 an unmatched name keeps the parent as the group") {
    val (g, a, _) = resolve(
      "test.jar",
      manifestOf("bundle-symbolicname" -> "com.example.mybundle")
    )
    assertEquals(g, Some("com.example"))
    assertEquals(a, Some("com.example.mybundle"))
  }

  // A-7 — priority guard. THEORY: Implementation-Vendor-Id is Maven-native and
  // authoritative, so no name-derived rule may outrank it.
  test("A-7 Implementation-Vendor-Id still outranks a name-derived group") {
    val (g, _, _) = resolve(
      "hamcrest-2.2.jar",
      manifestOf(
        "implementation-vendor-id" -> "com.vendor",
        "automatic-module-name" -> "org.hamcrest"
      )
    )
    assertEquals(g, Some("com.vendor"))
  }

  // A-8 — an undotted name contributes no group of its own. THEORY: a single
  // undotted segment says nothing about a group, so no name-derived rule fires.
  // The groupId in the result is therefore NOT from the manifest: it is
  // `resolveGroupIdArtifactIdVersion`'s documented last resort, which uses the
  // artifactId as the group so that a purl exists at all
  // (`Maven.scala:378-384`). Pinning it here records that distinction, and
  // records the second shape of the Spring gap: `apiguardian-api-1.1.0.jar`
  // carries only an undotted `Implementation-Title`, so it becomes
  // `pkg:maven/apiguardian-api/apiguardian-api@1.1.0`.
  test("A-8 an undotted name falls through to the artifactId fallback") {
    val (g, a, _) = resolve(
      "spring-boot-2.7.14.jar",
      manifestOf("automatic-module-name" -> "springboot")
    )
    assertEquals(
      g,
      Some("spring-boot"),
      "not a name-derived group: the artifactId fallback at Maven.scala:384"
    )
    assertEquals(a, Some("spring-boot"))
  }

  // A-9 — no artifactId, no rules 1 and 2. THEORY: with a filename that carries
  // no version the filename parser yields no artifactId, so the rules that need
  // one must not fire; the parent is the only available answer.
  test("A-9 with no artifactId from the filename the parent is used") {
    val (g, _, _) = resolve(
      "hamcrest.jar",
      manifestOf("automatic-module-name" -> "org.hamcrest")
    )
    assertEquals(g, Some("org"))
  }

  // ==================== Version priority ====================

  // B-1 — Implementation-Version wins. THEORY: a Bundle-Version is an OSGi
  // version, which permits qualifiers and padding that a Maven version does not
  // carry; the Implementation-Version is the Maven-shaped one.
  test("B-1 Implementation-Version outranks Bundle-Version") {
    val (_, _, v) = resolve(
      "objenesis-3.2.jar",
      manifestOf(
        "implementation-version" -> "3.2",
        "bundle-version" -> "3.2.0",
        "specification-version" -> "3.2"
      )
    )
    assertEquals(v, Some("3.2"), "3.2.0 is an OSGi padding of the same release")
  }

  // B-2 — Bundle-Version is still the fallback. THEORY: many OSGi-only bundles
  // have no Implementation-Version, so the fallback must remain.
  test("B-2 Bundle-Version is used when Implementation-Version is absent") {
    val (_, _, v) = resolve(
      "osgi-only-1.4.0.jar",
      manifestOf("bundle-version" -> "1.4.0")
    )
    assertEquals(v, Some("1.4.0"))
  }

  // B-3 — Specification-Version is last. THEORY: it describes the specification
  // the artifact implements, not the artifact's own release, so it is the
  // weakest of the three and must not outrank either.
  test("B-3 Specification-Version is the last resort") {
    val (_, _, v) = resolve(
      "spec-only-2.0.jar",
      manifestOf("specification-version" -> "2.0")
    )
    assertEquals(v, Some("2.0"))
  }

  // B-4 — the issue's qualifier case. THEORY: `1.0.27.RELEASE` is a Spring
  // OSGi qualifier; the Maven release is `1.0.27`, and a purl carrying the
  // qualifier matches no artifact in a repository.
  test("B-4 an OSGi release qualifier does not reach the purl") {
    val (_, _, v) = resolve(
      "reactor-netty-core-1.0.27.jar",
      manifestOf(
        "implementation-version" -> "1.0.27",
        "bundle-version" -> "1.0.27.RELEASE"
      )
    )
    assertEquals(v, Some("1.0.27"))
  }

  // ==================== Issue #337's rows ====================

  // AB-1 — every non-Spring row of the issue's table, resolved from the headers
  // those JARs actually carry (read from the JARs inside a Spring Boot fat JAR on
  // 2026-10-14). THEORY: the individual rules above are necessary but not
  // sufficient; what matters is that the resolver, with its real header sets and
  // priority chain, produces the real coordinates. Each row is a JAR whose group
  // or version the reference table in metadata_multiple_purls.json records
  // differently from what Goat Rodeo produced before this change.
  test("AB-1 the issue's non-Spring rows resolve to their real coordinates") {
    // (file name, manifest headers, expected group, artifactId, version)
    val rows = Vector(
      (
        "hamcrest-2.2.jar",
        manifestOf(
          "implementation-title" -> "hamcrest",
          "automatic-module-name" -> "org.hamcrest",
          "bundle-symbolicname" -> "org.hamcrest",
          "implementation-version" -> "2.2",
          "bundle-version" -> "2.2"
        ),
        "org.hamcrest",
        "hamcrest",
        "2.2"
      ),
      (
        "opentest4j-1.2.0.jar",
        manifestOf(
          "specification-version" -> "1.2.0",
          "implementation-title" -> "opentest4j",
          "bundle-symbolicname" -> "org.opentest4j",
          "bundle-version" -> "1.2.0",
          "implementation-version" -> "1.2.0"
        ),
        "org.opentest4j",
        "opentest4j",
        "1.2.0"
      ),
      (
        "perfmark-api-0.25.0.jar",
        manifestOf(
          "automatic-module-name" -> "io.perfmark",
          "implementation-version" -> "0.25.0",
          "implementation-title" -> "PerfMark"
        ),
        "io.perfmark",
        "perfmark-api",
        "0.25.0"
      ),
      (
        "objenesis-3.2.jar",
        manifestOf(
          "implementation-title" -> "Objenesis",
          "automatic-module-name" -> "org.objenesis",
          "bundle-symbolicname" -> "org.objenesis",
          "implementation-version" -> "3.2",
          "bundle-version" -> "3.2.0",
          "specification-version" -> "3.2"
        ),
        "org.objenesis",
        "objenesis",
        "3.2"
      ),
      (
        "reactive-streams-1.0.4.jar",
        manifestOf(
          "automatic-module-name" -> "org.reactivestreams",
          "bundle-symbolicname" -> "reactive-streams",
          "bundle-version" -> "1.0.4"
        ),
        "org.reactivestreams",
        "reactive-streams",
        "1.0.4"
      ),
      (
        "reactor-netty-core-1.0.27.jar",
        manifestOf(
          "automatic-module-name" -> "reactor.netty.core",
          "bundle-symbolicname" -> "io.projectreactor.netty.reactor-netty-core",
          "implementation-title" -> "reactor-netty-core",
          "implementation-version" -> "1.0.27",
          "bundle-version" -> "1.0.27.RELEASE"
        ),
        "io.projectreactor.netty",
        "reactor-netty-core",
        "1.0.27"
      ),
      (
        "reactor-netty-http-1.0.27.jar",
        manifestOf(
          "automatic-module-name" -> "reactor.netty.http",
          "bundle-symbolicname" -> "io.projectreactor.netty.reactor-netty-http",
          "implementation-title" -> "reactor-netty-http",
          "implementation-version" -> "1.0.27",
          "bundle-version" -> "1.0.27.RELEASE"
        ),
        "io.projectreactor.netty",
        "reactor-netty-http",
        "1.0.27"
      )
    )

    rows.foreach { case (filename, manifest, group, artifact, version) =>
      val (g, a, v) = resolve(filename, manifest)
      assertEquals(g, Some(group), s"$filename: groupId")
      assertEquals(a, Some(artifact), s"$filename: artifactId")
      assertEquals(v, Some(version), s"$filename: version")
    }
  }

  // ==================== The known gap ====================

  // C-1 — the case this change does NOT fix, and the decision that it need not
  // be fixed. THEORY: `spring.boot` and `spring.core` name neither the group nor
  // a `<group>.<artifact>` pair, and nothing in the JAR states
  // `org.springframework.boot` or `org.springframework`. No rule reading only the
  // artifact can recover them.
  //
  // The project position is that an emitted pURL is a best effort: a purl whose
  // groupId is inferred from the manifest is worth more than no purl, because it
  // still resolves for the many artifacts whose module name does carry the group,
  // and a wrong groupId is visible in the output rather than hidden. So this test
  // pins today's answer as the accepted outcome, not as a defect awaiting repair:
  // changing it is a decision about where external knowledge would come from, and
  // it must be taken deliberately.
  test(
    "C-1 a Spring module name yields the module-implied group (best effort)"
  ) {
    val (gBoot, aBoot, vBoot) = resolve(
      "spring-boot-2.7.14.jar",
      manifestOf(
        "implementation-title" -> "Spring Boot",
        "automatic-module-name" -> "spring.boot",
        "implementation-version" -> "2.7.14"
      )
    )
    assertEquals(
      gBoot,
      Some("spring"),
      "best effort: org.springframework.boot is not derivable from this JAR"
    )
    assertEquals(aBoot, Some("spring-boot"))
    assertEquals(vBoot, Some("2.7.14"))

    val (gCore, _, _) = resolve(
      "spring-core-5.3.25.jar",
      manifestOf(
        "implementation-title" -> "spring-core",
        "automatic-module-name" -> "spring.core",
        "implementation-version" -> "5.3.25"
      )
    )
    assertEquals(
      gCore,
      Some("spring"),
      "best effort: org.springframework is not derivable from this JAR"
    )

    val (gSecurity, _, _) = resolve(
      "spring-security-crypto-5.7.6.jar",
      manifestOf(
        "implementation-title" -> "spring-security-crypto",
        "implementation-version" -> "5.7.6",
        "automatic-module-name" -> "spring.security.crypto"
      )
    )
    assertEquals(
      gSecurity,
      Some("spring.security"),
      "best effort: org.springframework.security is not derivable from this JAR"
    )
  }
}
