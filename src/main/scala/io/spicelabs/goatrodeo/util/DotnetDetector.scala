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

import io.spicelabs.cilantro.DotnetAssemblyProbe
import org.apache.tika.mime.MediaType

object DotnetDetector {
  lazy val DOTNET_MIME: MediaType = {
    MediaType.parse("application/x-msdownload; format=pe32-dotnet")
  }

  /** The dotnet MIME as a string; most call sites want the string, not the
    * MediaType wrapper, and converting each time is wasted work.
    */
  lazy val DOTNET_MIME_STRING: String = DOTNET_MIME.toString()

  /** Applicability rule: .NET assemblies are PE32 binaries; text/XML/class
    * files can never be one.
    */
  private[goatrodeo] def mimeRule(mimes: Set[String]): Boolean =
    ArtifactWrapper.noneOf(
      "text/",
      "application/xml",
      "application/java-vm"
    )(mimes)

  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    if (currentMimes.contains(DOTNET_MIME_STRING)) currentMimes
    else {
      val isDotnet =
        artifact.withStream(s => DotnetAssemblyProbe.isDotnetAssembly(s))
      if (isDotnet) currentMimes + DOTNET_MIME_STRING else currentMimes
    }
  }
}
