import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import java.nio.file.Files

/** Turns missing test prerequisites into one clear, actionable message instead
  * of a pile of cryptic "not a valid zip" / FileNotFound failures scattered
  * across the suite.
  *
  * The sbt build provisions the fixtures automatically in a `Tests.Setup` hook
  * (download + git lfs pull). This check turns missing prerequisites into one
  * clear, actionable message instead of a pile of cryptic "not a valid zip" /
  * FileNotFound failures scattered across the suite.
  */
class PreflightSuite extends GoatRodeoFunSuite {

  // A few files that git tracks via LFS. If LFS wasn't pulled, each is a small
  // text pointer beginning with the line below rather than real binary content.
  private val lfsSamples = List(
    "test_data/hidden1.jar",
    "test_data/log4j-core-2.22.1.jar",
    "test_data/nested.tar"
  )

  private val lfsPointerMagic = "version https://git-lfs.github.com/spec/v1"

  private def isLfsPointer(f: File): Boolean =
    f.isFile && f.length() < 1024 && {
      val head = new String(Files.readAllBytes(f.toPath))
      head.startsWith(lfsPointerMagic)
    }

  test("git LFS files are materialised (run `git lfs pull` if this fails)") {
    val present = lfsSamples.map(new File(_)).filter(_.exists())
    assert(
      present.nonEmpty,
      "Expected LFS-tracked test fixtures under test_data/ are missing entirely.\n" +
        "Fetch them with:\n\n    git lfs pull\n"
    )
    val pointers = present.filter(isLfsPointer)
    assert(
      pointers.isEmpty,
      s"These test fixtures are unresolved git-LFS pointer files, not real content:\n" +
        pointers.map(p => s"  - ${p.getPath}").mkString("\n") +
        "\n\nResolve them with:\n\n    git lfs pull\n"
    )
  }

  test(
    "downloaded test data is present (sbt provisions it automatically)"
  ) {
    val marker = new File("test_data/download/iso_tests/simple.iso")
    assert(
      new File("test_data/download").isDirectory,
      // The sbt Tests.Setup hook downloads the fixtures before the suite
      // runs; a totally absent dir means the hook did not run.
      "test_data/download not present — the sbt test-data provisioning hook did not run"
    )
    assert(
      marker.exists(),
      s"test_data/download exists but ${marker.getPath} is missing.\n" +
        "The sbt Tests.Setup hook downloads the remote test fixtures automatically before tests run."
    )
  }
}
