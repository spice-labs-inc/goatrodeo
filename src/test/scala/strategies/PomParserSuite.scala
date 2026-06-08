/* Phase 2 Tests: PomParser
   PomParser tests §2.1–2.5
 */

package io.spicelabs.goatrodeo.util

import munit.FunSuite

class PomParserSuite extends FunSuite {

  // 2.1 PomParser basics
  test("PomParser - parses simple POM") {
    val pom = """<project>
      <groupId>com.example</groupId>
      <artifactId>test-art</artifactId>
      <version>1.0</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.groupId, Some("com.example"))
    assertEquals(result.get.artifactId, Some("test-art"))
    assertEquals(result.get.version, Some("1.0"))
  }

  test("PomParser - returns None on invalid XML") {
    assert(PomParser.parse("not xml").isEmpty)
  }

  test("PomParser - handles missing fields gracefully") {
    val result = PomParser.parse("<project></project>")
    assert(result.isDefined)
    assertEquals(result.get.groupId, None)
    assertEquals(result.get.version, None)
  }

  // 2.2: Parse <properties>
  test("PomParser - extracts properties section") {
    val pom = """<project>
      <properties>
        <spring.version>5.3.0</spring.version>
      </properties>
      <groupId>com.test</groupId>
      <artifactId>test</artifactId>
      <version>1.0</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.properties("spring.version"), "5.3.0")
  }

  test("PomParser - empty properties when none defined") {
    val result = PomParser.parse("<project><groupId>g</groupId></project>")
    assert(result.isDefined)
    assertEquals(result.get.properties, Map.empty)
  }

  // 2.3: Property interpolation
  test("PomParser - resolves built-in project.version") {
    val pom = """<project>
      <groupId>com.test</groupId>
      <artifactId>test</artifactId>
      <version>1.2.3</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.version, Some("1.2.3"))
  }

  test("PomParser - resolves custom property in version") {
    val pom = """<project>
      <properties>
        <spring.version>5.3.0</spring.version>
      </properties>
      <groupId>com.test</groupId>
      <artifactId>test</artifactId>
      <version>${spring.version}</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.version, Some("5.3.0"))
  }

  test("PomParser - resolves ${pom.groupId}") {
    val pom = """<project>
      <groupId>com.acme</groupId>
      <artifactId>x</artifactId>
      <version>2.0</version>
      <properties><foo>bar</foo></properties>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.groupId, Some("com.acme"))
  }

  test("PomParser - resolves ${project.version} in parsed output") {
    val pom = """<project>
      <groupId>com.test</groupId>
      <artifactId>test</artifactId>
      <version>4.0</version>
      <properties><derived>${project.version}</derived></properties>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.version, Some("4.0"))
    val allProps = Map(
      "project.version" -> "4.0",
      "pom.version" -> "4.0",
      "project.groupId" -> "com.test",
      "pom.groupId" -> "com.test",
      "project.artifactId" -> "test",
      "pom.artifactId" -> "test"
    ) ++ result.get.properties
    assertEquals(PomParser.resolveProperty("derived", allProps), Some("4.0"))
  }

  test("PomParser - unresolved property returns None in parsed output") {
    val pom = """<project>
      <groupId>${unknown.group}</groupId>
      <artifactId>test</artifactId>
      <version>1.0</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(
      result.get.groupId,
      None,
      "Unresolvable property in groupId should produce None"
    )
  }

  // 2.4 Cycle detection and depth limit
  test("PomParser - detects circular references") {
    val props = Map("a" -> "${b}", "b" -> "${a}")
    val result = PomParser.interpolate("${a}", props)
    assertEquals(result, None, "Circular reference should produce None")
  }

  test("PomParser - depth limit 10") {
    val props = (1 to 12)
      .foldRight(Map("v0" -> "")) { (i, acc) =>
        acc + (s"v${i}" -> s"$${v${i - 1}}")
      }
      .toMap
    val result = PomParser.interpolate("${v11}", props)
    assertEquals(result, None, "Depth exceeding 10 should produce None")
  }

  test("PomParser - valid chained properties") {
    val props = Map("a" -> "1.0", "b" -> "${a}")
    val result = PomParser.interpolate("${b}", props)
    assertEquals(result, Some("1.0"), "Non-circular chain should resolve")
  }

  // 2.5 Extended POM metadata
  test("PomParser - extracts name, description, url, organization") {
    val pom = """<project>
      <name>My Project</name>
      <description>A test project</description>
      <url>https://example.com</url>
      <organization><name>Acme Corp</name></organization>
      <groupId>com.test</groupId>
      <artifactId>t</artifactId>
      <version>1</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.name, Some("My Project"))
    assertEquals(result.get.description, Some("A test project"))
    assertEquals(result.get.url, Some("https://example.com"))
    assertEquals(result.get.organization, Some("Acme Corp"))
  }

  test("PomParser - missing fields produce None") {
    val pom =
      "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>"
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.name, None)
    assertEquals(result.get.url, None)
  }

  // --- New tests for licenses, dependencies, dependencyManagement ---

  test("PomParser - extracts licenses") {
    val pom = """<project>
      <licenses>
        <license>
          <name>Apache 2.0</name>
          <url>https://www.apache.org/licenses/LICENSE-2.0</url>
        </license>
      </licenses>
      <groupId>com.test</groupId>
      <artifactId>a</artifactId>
      <version>1.0</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.licenses.length, 1)
    assertEquals(result.get.licenses(0).name, Some("Apache 2.0"))
    assertEquals(
      result.get.licenses(0).url,
      Some("https://www.apache.org/licenses/LICENSE-2.0")
    )
  }

  test("PomParser - parses dependencies") {
    val pom = """<project>
      <dependencies>
        <dependency>
          <groupId>org.example</groupId>
          <artifactId>lib-a</artifactId>
          <version>1.0</version>
          <scope>compile</scope>
        </dependency>
      </dependencies>
      <groupId>com.test</groupId>
      <artifactId>test</artifactId>
      <version>1.0</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.dependencies.length, 1)
    assertEquals(result.get.dependencies(0).groupId, Some("org.example"))
    assertEquals(result.get.dependencies(0).artifactId, Some("lib-a"))
    assertEquals(result.get.dependencies(0).scope, Some("compile"))
  }

  test("PomParser - parses dependencyManagement") {
    val pom = """<project>
      <dependencyManagement>
        <dependencies>
          <dependency>
            <groupId>com.example</groupId>
            <artifactId>managed-dep</artifactId>
            <version>2.0</version>
          </dependency>
        </dependencies>
      </dependencyManagement>
      <groupId>com.test</groupId>
      <artifactId>t</artifactId>
      <version>1.0</version>
    </project>"""
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.dependencyManagement.length, 1)
    assertEquals(
      result.get.dependencyManagement(0).artifactId,
      Some("managed-dep")
    )
    assertEquals(result.get.dependencyManagement(0).version, Some("2.0"))
  }

  test("PomParser - empty dependencies and licenses when absent") {
    val pom =
      "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>"
    val result = PomParser.parse(pom)
    assert(result.isDefined)
    assertEquals(result.get.dependencies.length, 0)
    assertEquals(result.get.licenses.length, 0)
    assertEquals(result.get.dependencyManagement.length, 0)
  }
}
