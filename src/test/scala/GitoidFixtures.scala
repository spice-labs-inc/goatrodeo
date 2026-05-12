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

package io.spicelabs.goatrodeo.test

import io.spicelabs.goatrodeo.util.Gitoid

import java.security.MessageDigest

/** Test helpers for constructing valid Gitoid values from arbitrary labels.
  *
  * The strict Gitoid parser only accepts well-formed `gitoid:<type>:<algo>:<hex>`
  * URLs with the correct hash length. Many existing tests use short or
  * non-hex placeholder strings as identifiers; this helper hashes the label
  * deterministically so the test fixture is always a valid Gitoid while
  * preserving distinctness between different labels.
  */
object GitoidFixtures {

  /** Produce a deterministic valid blob/sha256 Gitoid URL string from any
    * label.
    *
    * If `label` already parses as a Gitoid URL, returns it verbatim; otherwise
    * synthesises a Gitoid by hashing the UTF-8 bytes of the label and returns
    * its URL form. */
  def gitoidFor(label: String): String = {
    if (label.startsWith("gitoid:")) {
      val _ = Gitoid(label) // validate
      label
    } else {
      val md = MessageDigest.getInstance("SHA-256")
      val hash = md.digest(label.getBytes("UTF-8"))
      Gitoid(Gitoid.ObjectType.Blob, Gitoid.HashAlgorithm.Sha256, hash).apply()
    }
  }

  /** Convenience: Gitoid form for use as a value in `Map[String, Gitoid]` etc. */
  def gitoidForAsGitoid(label: String): Gitoid = Gitoid(gitoidFor(label))
}
