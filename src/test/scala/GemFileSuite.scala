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
import goatrodeo.util.ArtifactWrapper
import goatrodeo.util.FileWalker.ArchiveStream

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class GemFileSuite extends munit.FunSuite {
  val logger = Logger("GemFileSuite")

  test("Simple file format parsing to ArtifactWrapper") {
    assert(
      FileWalker
        .streamForArchive(
          FileWrapper.from(
            File("test_data/gem_tests/java-properties-0.3.0.gem")
          )
        )
        .isDefined
    )
  }
  test("Walk a Gem file") {
    var cnt = 0
    val ArchiveStream(inputStream, _) =
      FileWalker
        .streamForArchive(
          FileWrapper.from(
            File("test_data/gem_tests/java-properties-0.3.0.gem")
          )
        )
        .get
    for {
      e <- inputStream
      file = e()
    } {
      logger.trace(s"name: ${file.getPath()} file: $file")
      cnt += 1
      file.cleanUp()
    }

    assert(cnt == 3)

  }

  test("deal with nesting archives inside a Gem") {
    val nested =
      FileWrapper.from(File("test_data/gem_tests/java-properties-0.3.0.gem"))

    var cnt = 0

    FileWalker.processFileAndSubfiles(
      nested,
      None,
      Vector[String](),
      false,
      (file, parent, x) => {
        logger.trace(s" name: ${file.getPath()} parent: $parent x: $x")
        cnt += 1
        val (main, _) = GitOIDUtils.computeAllHashes(file, s => false)
        logger.trace(f"hash for ${file.getPath()} is ${main} parent ${parent}")
        (main, false, None, x)
      }
    )
    assert(cnt == 30, f"expected 30, got ${cnt}")
  }

  test("Build a graph") {
    val store = MemStorage.getStorage(None)
    val nested = FileWrapper.from(File("test_data/gem_tests/java-properties-0.3.0.gem"))
    val built = BuildGraph.buildItemsFor(
      nested,
      store,
      Vector(),
      None,
      Vector(), {
        val file = File.createTempFile("goat_rodeo_purls", "_out")
        file.delete()
        file.mkdirs()
        BufferedWriter(FileWriter(File(file, "purls.txt")))
      },
      false
    )


  }
}
