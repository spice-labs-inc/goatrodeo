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
import io.spicelabs.goatrodeo.util.Identifier

import java.security.MessageDigest

/** Test helpers for constructing valid identifier values from arbitrary
  * labels.
  *
  * If `label` is already a canonical identifier (`gitoid:`, `pkg:`, `md5:`,
  * etc.), it is parsed directly. Otherwise we synthesise a blob+SHA-256
  * gitoid by hashing the label so the result is always a valid `Identifier`
  * while preserving distinctness between different labels.
  */
object GitoidFixtures {

  /** Deterministic `Identifier` derived from any label.
    *
    * Used as `Item.identifier` and as a String-comparable key (via
    * `identifier()` materialisation) in test assertions. */
  def gitoidFor(label: String): Identifier = {
    if (
      label.startsWith("gitoid:") || label.startsWith("pkg:") ||
      label.startsWith("md5:") || label.startsWith("sha1:") ||
      label.startsWith("sha256:") || label.startsWith("sha512:")
    ) {
      Identifier(label)
    } else {
      val md = MessageDigest.getInstance("SHA-256")
      val hash = md.digest(label.getBytes("UTF-8"))
      Identifier.fromGitoid(
        Gitoid(Gitoid.ObjectType.Blob, Gitoid.HashAlgorithm.Sha256, hash)
      )
    }
  }

  /** Canonical String form of the synthesised identifier — used where a
    * test asserts on a String identifier value. */
  def gitoidForString(label: String): String = gitoidFor(label).apply()

  /** Convenience: returns a `Gitoid` for use as a value in
    * `Map[String, Gitoid]` etc. The label must be a gitoid URL or hashable
    * to one. */
  def gitoidForAsGitoid(label: String): Gitoid =
    gitoidFor(label).asGitoid.getOrElse(
      throw new IllegalArgumentException(s"Label '$label' is not a Gitoid")
    )
}
