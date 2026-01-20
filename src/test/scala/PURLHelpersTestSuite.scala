/* Copyright 2024 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

import io.spicelabs.goatrodeo.util.PURLHelpers
import io.spicelabs.goatrodeo.util.PURLHelpers.Ecosystems

class PURLHelpersTestSuite extends munit.FunSuite {

  // ==================== mavenQualifiers Tests ====================

  test("mavenQualifiers - contains pom qualifier") {
    val qualifiers = PURLHelpers.mavenQualifiers

    assert(qualifiers.contains("pom"))
    assertEquals(qualifiers("pom"), ("type", "pom"))
  }

  test("mavenQualifiers - contains sources qualifier") {
    val qualifiers = PURLHelpers.mavenQualifiers

    assert(qualifiers.contains("sources"))
    assertEquals(qualifiers("sources"), ("packaging", "sources"))
  }

  test("mavenQualifiers - contains javadoc qualifier") {
    val qualifiers = PURLHelpers.mavenQualifiers

    assert(qualifiers.contains("javadoc"))
    assertEquals(qualifiers("javadoc"), ("classifier", "javadoc"))
  }

  test("mavenQualifiers - has exactly 3 entries") {
    assertEquals(PURLHelpers.mavenQualifiers.size, 3)
  }

  // ==================== Ecosystems Tests ====================

  test("Ecosystems - contains Maven") {
    val maven = Ecosystems.Maven
    assertEquals(maven.toString, "Maven")
  }

  test("Ecosystems - contains Debian") {
    val debian = Ecosystems.Debian
    assertEquals(debian.toString, "Debian")
  }

  test("ecosystems map - Maven has type maven") {
    val ecosystems = PURLHelpers.ecosystems
    val (typeName, _) = ecosystems(Ecosystems.Maven)

    assertEquals(typeName, "maven")
  }

  test("ecosystems map - Debian has type deb") {
    val ecosystems = PURLHelpers.ecosystems
    val (typeName, _) = ecosystems(Ecosystems.Debian)

    assertEquals(typeName, "deb")
  }

  test("ecosystems map - Maven has qualifiers") {
    val ecosystems = PURLHelpers.ecosystems
    val (_, qualifiers) = ecosystems(Ecosystems.Maven)

    assert(qualifiers.isDefined)
  }

  test("ecosystems map - Debian has no qualifiers") {
    val ecosystems = PURLHelpers.ecosystems
    val (_, qualifiers) = ecosystems(Ecosystems.Debian)

    assertEquals(qualifiers, None)
  }

  // ==================== buildPackageURL - Maven Tests ====================

  test("buildPackageURL - creates basic Maven pURL") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.apache.logging.log4j"),
      artifactId = "log4j-core",
      version = "2.22.1"
    )

    assertEquals(purl.getType(), "maven")
    assertEquals(purl.getNamespace(), "org.apache.logging.log4j")
    assertEquals(purl.getName(), "log4j-core")
    assertEquals(purl.getVersion(), "2.22.1")
  }

  test("buildPackageURL - Maven pURL requires namespace") {
    // Maven pURLs require both namespace and name per spec
    import com.github.packageurl.MalformedPackageURLException
    intercept[MalformedPackageURLException] {
      PURLHelpers.buildPackageURL(
        ecosystem = Ecosystems.Maven,
        artifactId = "simple-artifact",
        version = "1.0.0"
      )
    }
  }

  test("buildPackageURL - Maven pURL with pom qualifier") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.example"),
      artifactId = "test-artifact",
      version = "1.0.0",
      qualifierName = Some("pom")
    )

    assertEquals(purl.getQualifiers().get("type"), "pom")
  }

  test("buildPackageURL - Maven pURL with sources qualifier") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.example"),
      artifactId = "test-artifact",
      version = "1.0.0",
      qualifierName = Some("sources")
    )

    assertEquals(purl.getQualifiers().get("packaging"), "sources")
  }

  test("buildPackageURL - Maven pURL with javadoc qualifier") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.example"),
      artifactId = "test-artifact",
      version = "1.0.0",
      qualifierName = Some("javadoc")
    )

    assertEquals(purl.getQualifiers().get("classifier"), "javadoc")
  }

  test("buildPackageURL - Maven pURL with custom qualifiers") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.example"),
      artifactId = "test-artifact",
      version = "1.0.0",
      qualifiers = Seq(("custom", "value"))
    )

    assertEquals(purl.getQualifiers().get("custom"), "value")
  }

  test("buildPackageURL - Maven pURL with multiple qualifiers") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.example"),
      artifactId = "test-artifact",
      version = "1.0.0",
      qualifierName = Some("pom"),
      qualifiers = Seq(("repository_url", "https://repo.example.com"))
    )

    assertEquals(purl.getQualifiers().get("type"), "pom")
    assertEquals(
      purl.getQualifiers().get("repository_url"),
      "https://repo.example.com"
    )
  }

  // ==================== buildPackageURL - Debian Tests ====================

  test("buildPackageURL - creates basic Debian pURL") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Debian,
      namespace = Some("ubuntu"),
      artifactId = "libc6",
      version = "2.31-0ubuntu9"
    )

    assertEquals(purl.getType(), "deb")
    assertEquals(purl.getNamespace(), "ubuntu")
    assertEquals(purl.getName(), "libc6")
    assertEquals(purl.getVersion(), "2.31-0ubuntu9")
  }

  test("buildPackageURL - Debian pURL without namespace") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Debian,
      artifactId = "openssl",
      version = "1.1.1"
    )

    assertEquals(purl.getType(), "deb")
    assertEquals(purl.getNamespace(), null)
    assertEquals(purl.getName(), "openssl")
  }

  test("buildPackageURL - Debian ignores unknown qualifierName") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Debian,
      namespace = Some("debian"),
      artifactId = "apt",
      version = "2.0.0",
      qualifierName = Some("unknown")
    )

    // Should not throw, qualifierName is ignored for Debian
    assertEquals(purl.getType(), "deb")
    assert(purl.getQualifiers() == null || purl.getQualifiers().isEmpty())
  }

  test("buildPackageURL - Debian pURL with arch qualifier") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Debian,
      namespace = Some("ubuntu"),
      artifactId = "tk8.6",
      version = "8.6.14-1build1",
      qualifiers = Seq(("arch", "amd64"))
    )

    assertEquals(purl.getQualifiers().get("arch"), "amd64")
  }

  // ==================== buildPackageURL - Edge Cases ====================

  test("buildPackageURL - handles empty namespace for Debian") {
    // Debian allows empty namespace
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Debian,
      namespace = None,
      artifactId = "artifact",
      version = "1.0.0"
    )

    assertEquals(purl.getNamespace(), null)
  }

  test("buildPackageURL - handles empty qualifiers") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.example"),
      artifactId = "artifact",
      version = "1.0.0",
      qualifiers = Seq()
    )

    assert(purl.getQualifiers() == null || purl.getQualifiers().isEmpty())
  }

  test("buildPackageURL - handles None qualifierName") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.example"),
      artifactId = "artifact",
      version = "1.0.0",
      qualifierName = None
    )

    assert(purl.getQualifiers() == null || purl.getQualifiers().isEmpty())
  }

  test("buildPackageURL - pURL can be converted to string") {
    val purl = PURLHelpers.buildPackageURL(
      ecosystem = Ecosystems.Maven,
      namespace = Some("org.apache.logging.log4j"),
      artifactId = "log4j-core",
      version = "2.22.1"
    )

    val purlString = purl.canonicalize()
    assert(purlString.startsWith("pkg:maven/"))
    assert(purlString.contains("org.apache.logging.log4j"))
    assert(purlString.contains("log4j-core"))
    assert(purlString.contains("2.22.1"))
  }
}
