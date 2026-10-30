/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo

import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.strategies.DotnetFile
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.DotnetDetector

import java.io.File
import java.nio.file.Files

/** A native DLL must never reach the .NET assembly reader.
  *
  * WHAT: the .NET container type is claimed only when the PE image carries a
  * CLI data directory — data directory entry 14 of the optional header. A PE
  * without one is a native library, and the `Dotnet` strategy must not claim
  * it, which also means nothing constructs the state that hands the file to
  * Cilantro.
  *
  * WHY: a large Maven Central run logged one warning per native library —
  * `applicationinsights-core-native-win64.dll`, `MFCM140*.dll` and others —
  * because every `.dll` was offered to the assembly reader and it threw out of
  * an `ImageDebugType` lookup. At 73 million items that is both noise and
  * wasted work, and the discriminator was available for free in the header that
  * the detector already reads.
  *
  * THEORY: entry 14 is what a .NET assembly *is*, so its presence is the whole
  * test. The fixtures are a real managed assembly and the same bytes with entry
  * 14 cleared, which is what a native build looks like from the header's point
  * of view; asserting both directions keeps the test from passing on a broken
  * reader.
  *
  * LLM note: test ids are DN-n.
  */
class DotnetNativeDllSuite extends GoatRodeoFunSuite {

  private def managed(): Array[Byte] =
    Files.readAllBytes(new File("test_data/hackproj.dll").toPath)

  /** A real native DLL: SQLite's official x64 Windows build, public domain, 3.4
    * MB. See `test_data/native-dll/SOURCES.md` for provenance and the hash.
    */
  private def realNative(): Array[Byte] =
    Files.readAllBytes(new File("test_data/native-dll/sqlite3.dll").toPath)

  /** The managed assembly with data-directory entry 14 (the CLI header RVA)
    * cleared. Used only to pin the discriminator itself: this proves the
    * decision rests on entry 14 rather than on the reader happening to fail.
    */
  private def strippedCli(): Array[Byte] = {
    val bytes = managed().clone()
    val eLfanew =
      (bytes(60) & 0xff) | ((bytes(61) & 0xff) << 8) |
        ((bytes(62) & 0xff) << 16) | ((bytes(63) & 0xff) << 24)
    val optionalHeader = eLfanew + 24
    val magic =
      (bytes(optionalHeader) & 0xff) | ((bytes(optionalHeader + 1) & 0xff) << 8)
    // PE32 has 96 bytes of fixed optional-header fields, PE32+ has 112.
    val dataDirectories = optionalHeader + (if (magic == 0x20b) 112 else 96)
    val entry14 = dataDirectories + 14 * 8
    var i = entry14
    while (i < entry14 + 8) { bytes(i) = 0; i += 1 }
    bytes
  }

  private def claimOf(w: ArtifactWrapper): Vector[ToProcess] = {
    val (claimed, _, _, _) = DotnetFile.computeDotnetFiles(
      Map(w.uuid -> w),
      Map(w.path() -> Vector(w))
    )
    claimed
  }

  // DN-1 — the discriminator itself: entry 14 present for the assembly, absent for
  // the native copy, read from the first block.
  test(
    "DN-1 the CLI data directory is what separates an assembly from a native DLL"
  ) {
    val isManaged = DotnetDetector.peInfo(managed())
    val real = DotnetDetector.peInfo(realNative())
    val stripped = DotnetDetector.peInfo(strippedCli())
    assertEquals(isManaged.map(_.cliDirectoryPresent), Some(true))
    assertEquals(
      real.map(_.cliDirectoryPresent),
      Some(false),
      "the sourced sqlite3.dll is a native PE and must have no CLI directory"
    )
    assert(real.nonEmpty, "the sourced sqlite3.dll must parse as a PE")
    assertEquals(stripped.map(_.cliDirectoryPresent), Some(false))
  }

  // DN-2 — the native library is neither typed as a .NET assembly nor claimed by
  // the .NET strategy, so nothing can hand it to Cilantro.
  test(
    "DN-2 a native DLL is not claimed, so the assembly reader is never reached"
  ) {
    val nativeBytes = realNative()
    val w = ByteWrapper(
      nativeBytes,
      "applicationinsights-core-native-win64.dll",
      None
    )
    assert(
      !w.mimeType.contains(DotnetDetector.DOTNET_MIME_STRING),
      s"a native DLL must not be typed as a .NET assembly, got ${w.mimeType}"
    )
    assertEquals(
      claimOf(w),
      Vector.empty[ToProcess],
      "the .NET strategy must not claim a native DLL"
    )
  }

  // DN-3 — the managed twin is claimed, so DN-2 cannot pass on a detector that
  // refuses everything.
  test("DN-3 the managed assembly is still claimed") {
    val w = ByteWrapper(managed(), "hackproj.dll", None)
    assert(
      w.mimeType.contains(DotnetDetector.DOTNET_MIME_STRING),
      s"the managed fixture must be typed as a .NET assembly, got ${w.mimeType}"
    )
    assert(
      claimOf(w).nonEmpty,
      "the .NET strategy must claim the managed assembly"
    )
  }
}
