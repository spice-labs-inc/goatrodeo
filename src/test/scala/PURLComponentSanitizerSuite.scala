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

package io.spicelabs.goatrodeo.util

class PURLComponentSanitizerSuite extends munit.FunSuite {

  // ==================== Maven groupId ====================

  test("sanitizeMavenGroupId keeps a normal groupId") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId("org.apache.httpcomponents"),
      Some("org.apache.httpcomponents")
    )
  }

  test("sanitizeMavenGroupId trims whitespace") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId(
        "  org.apache.httpcomponents  "
      ),
      Some("org.apache.httpcomponents")
    )
  }

  test("sanitizeMavenGroupId strips leading dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId(".org.apache.httpcomponents"),
      Some("org.apache.httpcomponents")
    )
  }

  test("sanitizeMavenGroupId strips trailing dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId("org.apache.httpcomponents."),
      Some("org.apache.httpcomponents")
    )
  }

  test("sanitizeMavenGroupId strips multiple trailing dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId(
        "org.apache.httpcomponents.."
      ),
      Some("org.apache.httpcomponents")
    )
  }

  test("sanitizeMavenGroupId collapses internal multi-dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId("org..apache.httpcomponents"),
      Some("org.apache.httpcomponents")
    )
  }

  test("sanitizeMavenGroupId normalizes slash separators to dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId("org/apache/httpcomponents"),
      Some("org.apache.httpcomponents")
    )
  }

  test("sanitizeMavenGroupId returns None for whitespace only") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId("   "),
      None
    )
  }

  test(
    "sanitizeMavenGroupId returns None when only illegal characters remain"
  ) {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId("///"),
      None
    )
  }

  test("sanitizeMavenGroupId returns None when stripping leaves nothing") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenGroupId("..."),
      None
    )
  }

  // ==================== Maven artifactId ====================

  test("sanitizeMavenArtifactId keeps a normal artifactId") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenArtifactId("httpmime"),
      Some("httpmime")
    )
  }

  test("sanitizeMavenArtifactId strips leading and trailing dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenArtifactId("..httpmime.."),
      Some("httpmime")
    )
  }

  test("sanitizeMavenArtifactId removes spaces") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenArtifactId("http mime"),
      Some("httpmime")
    )
  }

  test("sanitizeMavenArtifactId returns None when nothing remains") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenArtifactId("!!!"),
      None
    )
  }

  // ==================== Maven version ====================

  test("sanitizeMavenVersion keeps a normal version") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenVersion("4.5.1"),
      Some("4.5.1")
    )
  }

  test("sanitizeMavenVersion preserves plus signs") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenVersion("1.0.0+build.123"),
      Some("1.0.0+build.123")
    )
  }

  test("sanitizeMavenVersion strips trailing dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenVersion("4.5.1.."),
      Some("4.5.1")
    )
  }

  test("sanitizeMavenVersion removes illegal characters") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenVersion("4.5.1-SNAPSHOT!"),
      Some("4.5.1-SNAPSHOT")
    )
  }

  test("sanitizeMavenVersion returns None when nothing legal remains") {
    assertEquals(
      PURLComponentSanitizer.sanitizeMavenVersion("!@#"),
      None
    )
  }

  // ==================== Generic identifier ====================

  test("sanitizeGenericIdentifier keeps normal identifiers") {
    assertEquals(
      PURLComponentSanitizer.sanitizeGenericIdentifier("libc6"),
      Some("libc6")
    )
  }

  test("sanitizeGenericIdentifier strips leading and trailing dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeGenericIdentifier("..libc6.."),
      Some("libc6")
    )
  }

  test("sanitizeGenericIdentifier collapses internal multi-dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeGenericIdentifier("lib..c6"),
      Some("lib.c6")
    )
  }

  test("sanitizeGenericIdentifier removes spaces") {
    assertEquals(
      PURLComponentSanitizer.sanitizeGenericIdentifier("libc 6"),
      Some("libc6")
    )
  }

  test("sanitizeGenericIdentifier returns None when nothing remains") {
    assertEquals(
      PURLComponentSanitizer.sanitizeGenericIdentifier("!!!"),
      None
    )
  }

  // ==================== Generic version ====================

  test("sanitizeGenericVersion keeps normal versions") {
    assertEquals(
      PURLComponentSanitizer.sanitizeGenericVersion("2.31-0ubuntu9"),
      Some("2.31-0ubuntu9")
    )
  }

  test("sanitizeGenericVersion strips trailing dots") {
    assertEquals(
      PURLComponentSanitizer.sanitizeGenericVersion("2.31.."),
      Some("2.31")
    )
  }

  test("sanitizeGenericVersion returns None when nothing legal remains") {
    assertEquals(
      PURLComponentSanitizer.sanitizeGenericVersion("!!!"),
      None
    )
  }
}
