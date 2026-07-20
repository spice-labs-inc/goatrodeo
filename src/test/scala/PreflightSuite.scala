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

import java.io.File
import java.nio.file.Files

/** Turns missing test prerequisites into one clear, actionable message instead
  * of a pile of cryptic "not a valid zip" / FileNotFound failures scattered
  * across the suite.
  *
  * The sbt build used to `git lfs pull` and download fixtures automatically in
  * a `Tests.Setup` hook. Under Maven the build tool stays out of provisioning;
  * this check tells you exactly what to run instead.
  */
class PreflightSuite extends munit.FunSuite {

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

  test("downloaded test data is present (run bin/fetch-test-data.sh if this fails)") {
    val marker = new File("test_data/download/iso_tests/simple.iso")
    assume(
      new File("test_data/download").isDirectory,
      // Only assert the contents if someone has started provisioning; a totally
      // absent dir means the data-dependent suites simply weren't set up.
      "test_data/download not present — skipping (data-dependent suites will not run)"
    )
    assert(
      marker.exists(),
      s"test_data/download exists but ${marker.getPath} is missing.\n" +
        "Fetch the remote test fixtures with:\n\n    bin/fetch-test-data.sh\n"
    )
  }
}
