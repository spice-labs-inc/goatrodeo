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

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.strategies.GenericFile
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class ISOFileSuite extends munit.FunSuite {
  val logger = Logger(getClass())

  test("Simple file format parsing to ArtifactWrapper") {
    // todo - rerun this against 'simple.iso'; it mounts on macos fine and checks out as a proper iso file
    // but this test is giving me a "Negative Seek Offset" error…

    val name = "test_data/download/iso_tests/iso_of_archives.iso"
    val result = FileWalker
      .withinArchiveStream(FileWrapper(File(name), name, None)) { _ =>
        42
      }
    assertEquals(
      result,
      Some(42)
    )
  }
  test("Walk an ISO file") {
    val name = "test_data/download/iso_tests/iso_of_archives.iso"
    val count =
      FileWalker
        .withinArchiveStream(FileWrapper(File(name), name, None)) { items =>
          items.length
        }

    assertEquals(count, Some(9))

  }

  test("deal with nesting archives inside an ISO") {
    val name = "test_data/download/iso_tests/iso_of_archives.iso"
    val nested =
      FileWrapper(File(name), name, None)

    val tp = GenericFile(nested)
    val store = MemStorage(None)
    tp.process(
      None,
      store,
      ParentScope.forAndWith("Testing ISO", None, Map()),
      args = Config(),
      tag = None
    )
    val cnt = store.keys().size
    assert(cnt > 1200, f"expected more than 1,200, got ${cnt}")
  }

}
