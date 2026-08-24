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

import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.*
import io.spicelabs.goatrodeo.util.Configuration

import java.io.ByteArrayInputStream
import java.io.File
import java.util.regex.Pattern

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class MySuite extends munit.FunSuite {

  /** The default configuration for these tests; individual calls override it
    * with an explicit `(using ...)` where they need different settings.
    */
  private given Configuration = Configuration()

  test("Gitoid URL converts to file path components") {
    val test = List(
      "gitoid:blob:sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
      "blob:sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
      ":sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
      "880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a"
    )
    test.foreach(v =>
      assertEquals(
        GitOIDUtils.urlToFileName(v),
        (
          "880",
          "485",
          "f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a"
        )
      )
    )
  }

  test("Basic regex pattern matching works") {
    val p = Pattern.compile("a")
    val m = p.matcher("aaaa")
    assert(m.find())
  }

  test("SHA256 hash produces correct hex output") {
    val txt = Array[Byte](49, 50, 51, 10)
    val digest = GitOIDUtils.HashType.SHA256.getDigest()
    assertEquals(
      Helpers.toHex(digest.digest(txt)),
      "181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b"
    )

    assertEquals(
      Helpers.toHex(Helpers.computeSHA256(new ByteArrayInputStream(txt))),
      "181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b"
    )
  }

  test("Long value converts to hex and back") {
    assertEquals(Helpers.toHex(0x1), "0000000000000001")

    assertEquals(Helpers.toHex(0x0030005000a00f01L), "0030005000a00f01")

    val txt = Array[Byte](49, 50, 51, 10)

    assertEquals(
      Helpers.toHex(
        Helpers.byteArrayToLong63Bits(
          Helpers.computeSHA256(new ByteArrayInputStream(txt))
        )
      ),
      "181210f8f9c779c2"
    )
  }

  test("File type detection works for common archive formats") {
    assert({
      val name = "test_data/HP1973-Source.zip"
      FileWalker
        .withinArchiveStream(
          FileWrapper(File(name), name, None)
        ) { _ => 42 }
        .isDefined
    })
    assert({
      val name = "test_data/log4j-core-2.22.1.jar"
      FileWalker
        .withinArchiveStream(
          FileWrapper(File(name), name, None)
        ) { _ => 42 }
        .isDefined
    })

    assert({
      val name = "test_data/ics_test.tar"
      FileWalker
        .withinArchiveStream(FileWrapper(File(name), name, None)) { _ => 42 }
        .isDefined
    })

    assert({
      val name = "test_data/nested.tar"
      FileWalker
        .withinArchiveStream(FileWrapper(File(name), name, None)) { _ => 42 }
        .isDefined
    })

    assert({
      val name = "test_data/tk8.6_8.6.14-1build1_amd64.deb"
      FileWalker
        .withinArchiveStream(
          FileWrapper(File(name), name, None)
        ) { _ => 42 }
        .isDefined
    })
    assert({
      val name = "test_data/tk-8.6.13-r2.apk"
      FileWalker
        .withinArchiveStream(
          FileWrapper(File(name), name, None)
        ) { _ => 42 }
        .isDefined
    })

    assert({
      val name = "test_data/empty.tgz"
      FileWalker
        .withinArchiveStream(FileWrapper(File(name), name, None)) { _ => 42 }
        .isDefined
    })
    assert({
      val name = "test_data/toml-rs.tgz"
      FileWalker
        .withinArchiveStream(FileWrapper(File(name), name, None)) { _ => 42 }
        .isDefined
    })

  }

  test("Walking a tar file yields multiple entries") {
    val name = "test_data/empty.tgz"
    val count =
      FileWalker
        .withinArchiveStream(FileWrapper(File(name), name, None)) { x =>
          x.length
        }
        .get

    assert(count > 2)
  }

  test("Build graph from nested archive") {
    val name = "test_data/nested.tar"
    val nested = FileWrapper(File(name), name, None)

    val store = ToProcess.buildGraphFromArtifactWrapper(
      nested
    )(using Configuration(useStaticMetadata = true))

    val gitoids = store.gitoidKeys()
    val cnt = gitoids.size

    assert(cnt > 1200, f"expected more than 1,200, got ${cnt}")
    if (StaticMetadata.hasSyft) { // only run the Static Metadata tests if Syft is installed
      {
        // 46dccecac556623d8e2ce8648496824a82951d139062a4e61148aff1a25ed18d  log4j-core-2.22.1.jar
        val item = store
          .read(
            store
              .read(
                "sha256:46dccecac556623d8e2ce8648496824a82951d139062a4e61148aff1a25ed18d"
              )
              .get
              .connections
              .head
              ._2
          )
          .get

        val purls = item.connections.toVector.filter(_._2.startsWith("pkg:"))

        assert(purls.length == 1, s"should be a purl ${purls} on log4j")

        assert(
          item.bodyAsItemMetaData.get.extra
            .get("static-metadata-artifact")
            .get
            .size == 1,
          "Should have one augmentation on log4j"
        )
      }

      {
        // a51c97150f29aae8d0b3bbc40e880352fbdb381020364a19fb73fc7612f8ba17 tk

        val item = store
          .read(
            store
              .read(
                "sha256:a51c97150f29aae8d0b3bbc40e880352fbdb381020364a19fb73fc7612f8ba17"
              )
              .get
              .connections
              .head
              ._2
          )
          .get

        assert(
          item.bodyAsItemMetaData.get.extra
            .get("static-metadata-artifact")
            .get
            .size == 1,
          "Should have one augmentation on tk"
        )
      }

    }
  }

  test("Build graph from nested archive without static metadata") {
    /* What this tests:
     *  The Maven strategy produces pURLs for artifacts inside nested.tar
     *  even when Syft/static-metadata is disabled.  This ensures pURL
     *  generation is a core strategy behavior, not dependent on Syft.
     *  Requirement: Phase 1-5 Maven strategy pURL generation.
     *  Theory:  log4j-core-2.22.1.jar inside nested.tar should be claimed by
     *  MavenToProcess and produce a pkg:maven/... pURL.
     */
    val name = "test_data/nested.tar"
    val nested = FileWrapper(File(name), name, None)

    // useStaticMetadata defaults to false
    val store = ToProcess.buildGraphFromArtifactWrapper(nested)

    val gitoids = store.gitoidKeys()
    val cnt = gitoids.size

    assert(cnt > 1200, f"expected more than 1,200, got ${cnt}")

    // log4j-core-2.22.1.jar sha256
    val log4jSha256 =
      "sha256:46dccecac556623d8e2ce8648496824a82951d139062a4e61148aff1a25ed18d"
    val log4jItemOpt = for {
      firstItem <- store.read(log4jSha256)
      // Follow the first "contains" edge to the nested JAR child
      childGitoid <- firstItem.connections
        .find(_._1 == EdgeType.contains)
        .map(_._2)
        .orElse(firstItem.connections.headOption.map(_._2))
      childItem <- store.read(childGitoid)
    } yield childItem

    assert(
      log4jItemOpt.isDefined,
      s"log4j JAR should be reachable from $log4jSha256"
    )

    val log4jPurls =
      log4jItemOpt.get.connections.toVector.filter(_._2.startsWith("pkg:"))
    assert(
      log4jPurls.nonEmpty,
      s"log4j JAR should have at least one pURL connection, got ${log4jPurls}"
    )

    // tk sha256
    val tkSha256 =
      "sha256:a51c97150f29aae8d0b3bbc40e880352fbdb381020364a19fb73fc7612f8ba17"
    val tkItemOpt = for {
      firstItem <- store.read(tkSha256)
      childGitoid <- firstItem.connections
        .find(_._1 == EdgeType.contains)
        .map(_._2)
        .orElse(firstItem.connections.headOption.map(_._2))
      childItem <- store.read(childGitoid)
    } yield childItem

    assert(
      tkItemOpt.isDefined,
      s"tk package should be reachable from $tkSha256"
    )

    val tkPurls =
      tkItemOpt.get.connections.toVector.filter(_._2.startsWith("pkg:"))
    assert(
      tkPurls.nonEmpty,
      s"tk package should have at least one pURL connection, got ${tkPurls}"
    )
  }

  test("Finds files in RPM package") {
    val name = "test_data/tk-8.6.8-1.el8.x86_64.rpm"
    val nested =
      FileWrapper(File(name), name, None)

    val store = ToProcess.buildGraphFromArtifactWrapper(nested)
    val gitoids = store.gitoidKeys()

    assert(
      gitoids.size > 200,
      s"There should be at least 200 files found in the rpm, found ${gitoids.size}"
    )
  }

  test("Build graph from deb package with zst compression") {
    val name = "test_data/tk8.6_8.6.14-1build1_amd64.deb"
    val nested =
      FileWrapper(File(name), name, None)

    val store = ToProcess.buildGraphFromArtifactWrapper(nested)
    val gitoids = store.gitoidKeys()
    val cnt = gitoids.size

    assert(cnt > 10, f"expected more than 10, got ${cnt}")
  }

  test("Calculate MIME type for class file") {
    val classFileName =
      "target/scala-3.8.3/classes/io/spicelabs/goatrodeo/Howdy.class"
    val f = FileWrapper(new File(classFileName), classFileName, None, _ => ())
    assert(
      f.mimeType.contains("application/java-vm"),
      f"Expecting mime type for a class file contain 'application/java-vm' but got ${f.mimeType}"
    )
  }

  test("Build graph from nested archive with block list") {
    val name = "test_data/nested.tar"
    val nested = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(nested)
    val store2 = ToProcess.buildGraphFromArtifactWrapper(
      nested,
      block = Set(
        "gitoid:blob:sha256:e3f8d493cb200fd95c4881e248148836628e0f06ddb3c28cb3f95cf784e2f8e4"
      )
    )

    val store3 =
      ToProcess.buildGraphFromArtifactWrapper(nested)

    assertEquals(
      store1.keys().toSet,
      store3.keys().toSet,
      "Builds are reproducible"
    )
    assertNotEquals(
      store1.keys().toSet,
      store2.keys().toSet,
      "Block list should work"
    )

    val gitoids = store1.gitoidKeys()

    assert(
      gitoids.size > 1200,
      f"Expecting more than 1,200 items, got ${gitoids.size}"
    )
    assert(store1.size() > 2200)
    val keys = store1.keys()
    assert(!keys.filter(_.startsWith("sha256:")).isEmpty)
    assert(!keys.filter(_.startsWith("md5:")).isEmpty)
    assert(!keys.filter(_.startsWith("sha1:")).isEmpty)
    assert(keys.filter(_.startsWith("floof:")).isEmpty)
    val topAlias = store1
      .read(
        "sha256:82ceabe5192a5c3803f8b73536e83cd59e219fb560d8ed9e0c165728b199c0d7"
      )
      .get
    val gitoid = topAlias.connections.head._2
    assert(gitoid.startsWith("gitoid:"))
    val top = store1.read(gitoid).get
    store1.read("gitoid:blob:sha1:2e79b179ad18431600e9a074735f40cd54dde7f6").get
    for { edge <- top.connections if edge._1 == EdgeType.contains } {
      val contained = store1.read(edge._2).get
    }

    val log4j = store1
      .read(
        "gitoid:blob:sha256:e3f8d493cb200fd95c4881e248148836628e0f06ddb3c28cb3f95cf784e2f8e4"
      )
      .get
    assert(
      log4j.connections.filter(_._1 == EdgeType.contains).size > 1200
    )
  }

  test("Build graph from Java source and JAR directory") {
    val source = File("test_data/jar_test")
    val strategy = ToProcess.strategyForDirectory(source, false, None)

    assert(
      strategy.length >= 2,
      f"Expecting at least 2 files, got ${strategy.length}"
    )

    var packages: Vector[String] = Vector()
    val store = ToProcess.buildGraphForToProcess(
      strategy,
      purlOut = purl => {
        packages = packages :+ purl
      }
    )

    val keys = store.keys()
    val items = keys.toVector.flatMap(store.read(_))
    assert(items.length > 1100)
    assert(items.length == keys.size)

    val sourceRef = items.filter(i =>
      i.connections.filter(e => EdgeType.isBuiltFrom(e._1)).size > 0
    )
    val fromSource = for {
      i <- items; c <- i.connections if c._1 == EdgeType.buildsTo
    } yield c
    assert(sourceRef.length > 100, "Expected to find source files")

    assert(fromSource.length == sourceRef.length)

    // the package URL is picked up
    val withPurl =
      items.filter(i => i.connections.filter(_._2.startsWith("pkg:")).size > 0)

    assert(
      withPurl.length == 6,
      f"expected 6, got ${packages.length} and ${packages}"
    )

    val withPurlSources = withPurl.filter(i =>
      i.connections.filter(_._2.endsWith("?packaging=sources")).size > 0
    )
    assert(withPurlSources.length == 2)

    val jars =
      withPurl.filter(i => i.connections.filter(_._2.contains("?")).size == 0)

    assert(jars.length == 2, f"Expecting two JARs, but got ${jars.length}")

    val extra = jars(0).body.get.asInstanceOf[ItemMetaData].extra

    assert(extra.get("manifest").isDefined)
    assert(extra.get("pom").isDefined)
    assert(extra.get("$$Sloth").isEmpty)
  }

  val complexManifest = """Manifest-Version: 1.0
Created-By: Apache Maven Bundle Plugin 5.1.9
Build-Jdk-Spec: 21
Specification-Title: Apache Commons IO
Specification-Version: 2.15
Specification-Vendor: The Apache Software Foundation
Implementation-Title: Apache Commons IO
Implementation-Version: 2.15.1
Implementation-Vendor: The Apache Software Foundation
Automatic-Module-Name: org.apache.commons.io
Bundle-Description: The Apache Commons IO library contains utility class
 es, stream implementations, file filters,file comparators, endian trans
 formation classes, and much more.
Bundle-DocURL: https://commons.apache.org/proper/commons-io/
Bundle-License: https://www.apache.org/licenses/LICENSE-2.0.txt
Bundle-ManifestVersion: 2
Bundle-Name: Apache Commons IO
Bundle-SymbolicName: org.apache.commons.commons-io
Bundle-Vendor: The Apache Software Foundation
Bundle-Version: 2.15.1
Export-Package: org.apache.commons.io;version="1.4.9999",org.apache.comm
 ons.io.comparator;version="1.4.9999",org.apache.commons.io.filefilter;v
 ersion="1.4.9999",org.apache.commons.io.input;version="1.4.9999",org.ap
 ache.commons.io.output;version="1.4.9999",org.apache.commons.io.build;v
 ersion="2.15.1",org.apache.commons.io.channels;version="2.15.1",org.apa
 che.commons.io.charset;version="2.15.1",org.apache.commons.io.file;vers
 ion="2.15.1",org.apache.commons.io.file.attribute;version="2.15.1",org.
 apache.commons.io.file.spi;version="2.15.1",org.apache.commons.io.funct
 ion;version="2.15.1",org.apache.commons.io.input.buffer;version="2.15.1
 ",org.apache.commons.io.monitor;version="2.15.1",org.apache.commons.io.
 serialization;version="2.15.1",org.apache.commons.io;version="2.15.1",o
 rg.apache.commons.io.comparator;version="2.15.1",org.apache.commons.io.
 filefilter;version="2.15.1",org.apache.commons.io.input;version="2.15.1
 ",org.apache.commons.io.output;version="2.15.1"
Import-Package: sun.nio.ch;resolution:=optional,sun.misc;resolution:=opt
 ional
Include-Resource: META-INF/LICENSE.txt=LICENSE.txt,META-INF/NOTICE.txt=N
 OTICE.txt
Require-Capability: osgi.ee;filter:="(&(osgi.ee=JavaSE)(version=1.8))"
Tool: Bnd-6.4.1.202306080939
Multi-Release: true

"""

  test("Parse MANIFEST.MF into tree structure") {
    val tree = Helpers.treeInfoFromManifest(complexManifest)
    assert(!tree.isEmpty, "Should have items in tree")
    assert(!tree("manifest").isEmpty, "Should find manifest")
    assert(
      tree("export-package").size >= 1,
      f"Should be lots of at least on entry in 'export pacakge' got ${tree("export-package")}"
    )
  }
}
