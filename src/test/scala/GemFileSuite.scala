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

import com.typesafe.scalalogging.Logger
import goatrodeo.omnibor.{BuildGraph, EdgeType, MemStorage}
import goatrodeo.util.{FileWalker, FileWrapper, GitOIDUtils}

import java.io.{BufferedWriter, File, FileWriter}

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class GemFileSuite extends munit.FunSuite {
  val logger = Logger("GemFileSuite")

  test("Simple file format parsing to ArtifactWrapper") {
    val name = "test_data/gem_tests/java-properties-0.3.0.gem"
    assert(
      FileWalker
        .streamForArchive(FileWrapper(File(name), name, false))
        .isDefined
    )
  }
  test("Walk a Gem file") {
    var cnt = 0
    val name = "test_data/gem_tests/java-properties-0.3.0.gem"
    val (inputStream, _) =
      FileWalker
        .streamForArchive(FileWrapper(File(name), name, false))
        .get
    for {
      e <- inputStream
      (name, file) = e()
    } {
      logger.trace(s"name: $name file: $file")
      cnt += 1
      file.delete()
    }

    assert(cnt == 3)

  }

  test("deal with nesting archives inside a Gem") {
    val name = "test_data/gem_tests/java-properties-0.3.0.gem"
    val nested = FileWrapper(File(name), name, false)
    assert(nested.isFile() && nested.exists())

    var cnt = 0

    FileWalker.processFileAndSubfiles(
      nested,
      "nested",
      None,
      Vector[String](),
      false,
      (file, name, parent, x) => {
        logger.trace(s" name: $name parent: $parent x: $x")
        cnt += 1
        val (main, _) = GitOIDUtils.computeAllHashes(file, s => false)
        logger.trace(f"hash for ${name} is ${main} parent ${parent}")
        (main, false, None, x)
      }
    )
    assert(cnt == 30, f"expected 30, got ${cnt}")
  }

  test("Build a graph") {
    val store = MemStorage.getStorage(None)
    val nested = File("test_data/gem_tests/java-properties-0.3.0.gem")
    val built = BuildGraph.buildItemsFor(
      nested,
      nested.getName(),
      store,
      Vector(),
      None,
      Map(), {
        val file = File.createTempFile("goat_rodeo_purls", "_out")
        file.delete()
        file.mkdirs()
        BufferedWriter(FileWriter(File(file, "purls.txt")))
      },
      false
    )

//    assert(built.nameToGitOID.size > 1200, f"Expection more than 1,200 items, got ${built.nameToGitOID.size}")
//    assert(store.size() > 2200)
//    val keys = store.keys()
//    assert(!keys.filter(_.startsWith("sha256:")).isEmpty)
//    assert(!keys.filter(_.startsWith("md5:")).isEmpty)
//    assert(!keys.filter(_.startsWith("sha1:")).isEmpty)
//    assert(keys.filter(_.startsWith("floof:")).isEmpty)
//    val topAlias = store
//      .read(
//        "sha256:82ceabe5192a5c3803f8b73536e83cd59e219fb560d8ed9e0c165728b199c0d7"
//      )
//      .get
//    val gitoid = topAlias.connections.head._2
//    assert(gitoid.startsWith("gitoid:"))
//    val top = store.read(gitoid).get
//    store.read("gitoid:blob:sha1:2e79b179ad18431600e9a074735f40cd54dde7f6").get
//    for { edge <- top.connections if edge._1 == EdgeType.Contains } {
//      val contained = store.read(edge._2).get
//    }
//
//    val log4j = store
//      .read(
//        "gitoid:blob:sha256:e3f8d493cb200fd95c4881e248148836628e0f06ddb3c28cb3f95cf784e2f8e4"
//      )
//      .get
//    assert(
//      log4j.connections.filter(_._1 == EdgeType.Contains).size > 1200
//    )
  }
}
