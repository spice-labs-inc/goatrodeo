import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.strategies.GenericFile
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class ISOFileSuite extends GoatRodeoFunSuite {

  /** The default configuration for these tests; individual calls override it
    * with an explicit `(using ...)` where they need different settings.
    */
  private given Configuration = Configuration()

  val logger = Logger(getClass())

  test("withinArchiveStream - opens ISO file") {
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
  test("withinArchiveStream - counts entries in ISO file") {
    val name = "test_data/download/iso_tests/iso_of_archives.iso"
    val count =
      FileWalker
        .withinArchiveStream(FileWrapper(File(name), name, None)) { items =>
          items.length
        }

    assertEquals(count, Some(9))

  }

  test("GenericFile.process - handles nested archives inside an ISO") {
    val name = "test_data/download/iso_tests/iso_of_archives.iso"
    val nested =
      FileWrapper(File(name), name, None)

    val tp = GenericFile(nested)
    val store = MemStorage(None)
    tp.process(
      None,
      store,
      ParentScope.forAndWith("Testing ISO", None, Map()),
      tag = None
    )
    val cnt = store.keys().size
    assert(cnt > 1200, f"expected more than 1,200, got ${cnt}")
  }

}
