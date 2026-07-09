/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0 */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.ByteWrapper
import munit.FunSuite

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Tests that field-level merge produces pURLs that exist in Maven Central.
  *
  * '''What this tests:''' For JARs where pom.properties is incomplete or
  * absent, the field-level merge algorithm must produce a pURL that can be
  * found in Maven Central as a standalone artifact. This is the "Best"
  * criterion: "Best = can be found in Maven Central."
  *
  * '''Why this matters:''' The entire purpose of field-level merge is to
  * produce pURLs that are more likely to match real Maven artifacts. If the
  * merged pURL doesn't exist in Maven Central, it's not "Best" — it's a
  * fabricated identity.
  *
  * '''How "Maven Central existence" is verified:''' At test time, we hardcode
  * the expected pURLs that we have verified exist in Maven Central (via manual
  * lookup at https://repo1.maven.org/maven2/). No runtime Maven Central queries
  * are performed.
  *
  * '''Test data source:''' The manifest values used in these tests are
  * extracted from real JARs in the test_data/ directory (e.g.,
  * commons-codec-1.2.jar inside wps-demo-1.3.0.war). This satisfies HS-4: we
  * use the actual test corpus, not synthetic data.
  *
  * '''LLM Summary:''' This suite verifies the "Best" criterion by checking that
  * field-level merge produces pURLs matching known Maven Central artifacts.
  * Each test calls resolveGroupIdArtifactIdVersion with realistic
  * manifest/filename combinations and asserts the resulting
  * groupId/artifactId/version matches a real Maven Central coordinate.
  */
class BestPurlSuite extends FunSuite {

  /** Tests that field-level merge produces a Maven Central pURL for
    * commons-codec-1.2.jar (from wps-demo-1.3.0.war).
    *
    * '''What it tests:''' A JAR with no pom.properties, manifest with
    * Extension-Name="org.apache.commons.codec.*", Implementation-Title=
    * "org.apache.commons.codec.*", Implementation-Version="1.2", and filename
    * "commons-codec-1.2.jar". Field-level merge should produce
    * groupId="org.apache.commons" (from manifest), artifactId="commons-codec"
    * (from filename), version="1.2" (from manifest).
    *
    * '''Why:''' With source-level priority (current code), the manifest wins
    * wholesale, producing artifactId="org.apache.commons.codec.*" — a package
    * path with wildcard, NOT a Maven artifactId. The resulting pURL
    * `pkg:maven/org.apache.commons/org.apache.commons.codec.*@1.2` does NOT
    * exist in Maven Central. Field-level merge produces
    * `pkg:maven/org.apache.commons/commons-codec@1.2` which DOES exist.
    *
    * '''Maven Central verification:'''
    * https://repo1.maven.org/maven2/org/apache/commons/commons-codec/1.2/ —
    * confirmed exists.
    *
    * '''Requirement:''' Plan Test 10 — field-level merge produces pURL in Maven
    * Central.
    *
    * '''LLM context:''' This is a RED test. The current code produces
    * artifactId="org.apache.commons.codec.*" (from manifest), which does not
    * match the Maven Central artifactId "commons-codec". Field-level merge
    * produces the correct artifactId from the filename.
    */
  test("commons-codec-1.2: field-level merge produces Maven Central pURL") {
    val state = MavenState()
    // Manifest extracted from commons-codec-1.2.jar inside wps-demo-1.3.0.war
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "extension-name" -> TreeSet(StringOrPair("org.apache.commons.codec.*")),
      "implementation-title" -> TreeSet(
        StringOrPair("org.apache.commons.codec.*")
      ),
      "implementation-version" -> TreeSet(StringOrPair("1.2")),
      "implementation-vendor-id" -> TreeSet(StringOrPair("")),
      "package" -> TreeSet(StringOrPair("org.apache.commons.*"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "commons-codec-1.2.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    // groupId from manifest (Extension-Name parent path)
    assertEquals(
      g,
      Some("org.apache.commons"),
      "groupId from manifest Extension-Name parent path"
    )
    // artifactId from filename (field-level merge: filename > manifest for artifactId)
    assertEquals(
      a,
      Some("commons-codec"),
      "artifactId from filename (not manifest Implementation-Title)"
    )
    // version from manifest (Implementation-Version)
    assertEquals(v, Some("1.2"), "version from manifest Implementation-Version")

    // Verify the resulting pURL exists in Maven Central
    val expectedPurl = "pkg:maven/org.apache.commons/commons-codec@1.2"
    val actualPurl = s"pkg:maven/${g.get}/${a.get}@${v.get}"
    assertEquals(
      actualPurl,
      expectedPurl,
      "Merged pURL must match Maven Central coordinate org.apache.commons:commons-codec:1.2"
    )
  }

  /** Tests that field-level merge produces a Maven Central pURL for
    * commons-lang-2.4.jar.
    *
    * '''What it tests:''' A JAR with no pom.properties, manifest with
    * Bundle-SymbolicName="org.apache.commons.lang", Bundle-Version="2.4", and
    * filename "commons-lang-2.4.jar". Field-level merge should produce
    * groupId="org.apache.commons" (from manifest BSN parent path),
    * artifactId="commons-lang" (from filename), version="2.4" (from manifest).
    *
    * '''Maven Central verification:'''
    * https://repo1.maven.org/maven2/org/apache/commons/commons-lang/2.4/ —
    * confirmed exists.
    *
    * '''Requirement:''' Plan Test 10 (second case) — field-level merge produces
    * Maven Central pURL for Bundle-SymbolicName case.
    */
  test("commons-lang-2.4: field-level merge produces Maven Central pURL") {
    val state = MavenState()
    val manifest = TreeMap[String, TreeSet[StringOrPair]](
      "bundle-symbolicname" -> TreeSet(StringOrPair("org.apache.commons.lang")),
      "bundle-version" -> TreeSet(StringOrPair("2.4"))
    )
    val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
      ByteWrapper(Array.emptyByteArray, "commons-lang-2.4.jar", None),
      None,
      manifest,
      Map.empty,
      None
    )
    assertEquals(
      g,
      Some("org.apache.commons"),
      "groupId from Bundle-SymbolicName parent path"
    )
    assertEquals(
      a,
      Some("commons-lang"),
      "artifactId from filename (not Bundle-SymbolicName)"
    )
    assertEquals(v, Some("2.4"), "version from Bundle-Version")

    val expectedPurl = "pkg:maven/org.apache.commons/commons-lang@2.4"
    val actualPurl = s"pkg:maven/${g.get}/${a.get}@${v.get}"
    assertEquals(
      actualPurl,
      expectedPurl,
      "Merged pURL must match Maven Central coordinate org.apache.commons:commons-lang:2.4"
    )
  }
}
