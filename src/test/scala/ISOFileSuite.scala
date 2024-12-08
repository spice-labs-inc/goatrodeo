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
import goatrodeo.model.FileWrapper
import io.spicelabs.goatrodeo.util.{FileWalker, GitOIDUtils}

import java.io.File

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class ISOFileSuite extends munit.FunSuite {
  val logger = Logger("ISOFileSuite")

  test("Simple file format parsing to ArtifactWrapper") {
    // todo - rerun this against 'simple.iso'; it mounts on macos fine and checks out as a proper iso file
    // but this test is giving me a "Negative Seek Offset" error…
    assert(
      FileWalker
        .streamForArchive(FileWrapper(File("test_data/iso_tests/iso_of_archives.iso"), false))
        .isDefined
    )
  }
  test("Walk an ISO file") {
    var cnt = 0
    val (inputStream, _) =
      FileWalker
        .streamForArchive(FileWrapper(File("test_data/iso_tests/iso_of_archives.iso"), false))
        .get
    for {
      e <- inputStream
      (name, file) = e()
    } {
      logger.debug(s"name: $name file: $file")
      cnt += 1
      file.delete()
    }

    assert(cnt == 9)

  }

  test("deal with nesting archives inside an ISO") {
    val nested = FileWrapper(File("test_data/iso_tests/iso_of_archives.iso"), false)
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
        // println(f"hash for ${name} is ${main} parent ${parent}")
        (main, false, None, x)
      }
    )
    assert(cnt > 1200, f"expected more than 1,200, got ${cnt}")
  }

}