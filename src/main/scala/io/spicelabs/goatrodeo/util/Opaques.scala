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

import io.bullet.borer.Encoder
import io.bullet.borer.Decoder

object Opaques {
  opaque type Gitoid = String

  object Gitoid {
    def apply(string: String): Gitoid = string

    given Ordering[Gitoid] = Ordering.String
    given Encoder[Gitoid] = Encoder.forString.write(_, _)
    given Decoder[Gitoid] = Decoder.forString.read(_)
  }

  extension (gitoid: Gitoid) {
    def apply(): String = gitoid
    def length: Int = (gitoid: String).length
    def startsWith(prefix: String): Boolean = (gitoid: String).startsWith(prefix)
    def isBlobSha256: Boolean = (gitoid: String).startsWith("gitoid:blob:sha256:")
  }
}

export Opaques.Gitoid
