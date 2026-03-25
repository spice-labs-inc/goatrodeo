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

package io.spicelabs.goatrodeo.util

import io.spicelabs.cilantro.AssemblyDefinition
import org.apache.tika.mime.MediaType

import java.io.FileInputStream
import java.io.InputStream
import scala.util.Using

object DotnetDetector {
  lazy val DOTNET_MIME: MediaType = {
    MediaType.parse("application/x-msdownload; format=pe32-dotnet")
  }

  // the first 2 bytes of a PE file is M (0x4d) and Z (0x5a)
  // This can be extended to jump to the formal PE header, but that
  // involves:
  // Skipping forward 58 bytes
  // reading a little endian 4 byte int (offset to PE header)
  // Skipping to that offset if it's "sane"
  // reading a little endian 4 byte int
  // checking to see if it matches 0x00004550
  private def isPE32(input: InputStream): Boolean = {
    input.mark(1024)
    try {
      val b0 = input.read()
      val b1 = input.read()
      b0 == 0x4d && b1 == 0x5a
    } catch {
      case _: Exception =>
        false
    } finally {
      input.reset()
    }
  }

  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    // it kinda looks like a dot net file, so try to open it
    if (
      currentMimes.contains("application/x-msdownload; format=pe32") || artifact
        .withStream(s => isPE32(s))
    ) {
      artifact.withFile(file => {
        try {
          Using.resource(FileInputStream(file)) { fis =>
            val assembly = AssemblyDefinition.readAssembly(fis)
            if (assembly != null && assembly.mainModule != null) {
              currentMimes + DotnetDetector.DOTNET_MIME.toString()
            } else {
              currentMimes
            }
          }

        } catch {
          case e: Exception => currentMimes
        }
      })
    } else {
      currentMimes
    }
  }
}
