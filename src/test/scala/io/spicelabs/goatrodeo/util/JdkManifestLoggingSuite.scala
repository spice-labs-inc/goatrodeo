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

package io.spicelabs.goatrodeo.util

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

/** The JDK's duplicate-manifest-attribute warning is silenced, and parsing is
  * not.
  *
  * WHAT: after [[Helpers]] is initialised, the platform logger `java.util.jar`
  * no longer emits WARNING; and a manifest with duplicate attributes still
  * parses, with the last value winning, exactly as `java.util.jar.Manifest`
  * resolves it.
  *
  * WHY: `java.util.jar.Attributes.read` logs one warning per duplicated
  * attribute per manifest, through `PlatformLogger.getLogger("java.util.jar")`.
  * Real jars repeat `Manifest-Version`, `Created-By`, `Automatic-Module-Name`
  * and `Build-Jdk-Spec`, and a single Maven-shaped run produced tens of
  * thousands of those lines — the largest source of log noise in the run, for
  * manifests whose duplicates every consumer tolerates.
  *
  * THEORY: the warning is emitted through JUL, so the observable is whether
  * that logger is willing to log WARNING at all — the same predicate the JDK
  * consults before it formats the message. Silencing must not change what we
  * parse, so the duplicate attribute is asserted to arrive with the later
  * value.
  *
  * LLM note: test ids are JL-n.
  */
class JdkManifestLoggingSuite extends GoatRodeoFunSuite {

  private val duplicated: String =
    "Manifest-Version: 1.0\n" +
      "Created-By: 17.0.1 (Eclipse Adoptium)\n" +
      "Created-By: 21.0.2 (Eclipse Adoptium)\n" +
      "Build-Jdk-Spec: 21\n" +
      "\n"

  // JL-1 — the logger no longer logs WARNING. Touching Helpers is what initialises
  // the silencing, and parsing a manifest is the operation that used to warn.
  test("JL-1 the java.util.jar platform logger no longer logs WARNING") {
    Helpers.treeInfoFromManifest(duplicated)
    val jul = java.util.logging.Logger.getLogger("java.util.jar")
    assert(
      !jul.isLoggable(java.util.logging.Level.WARNING),
      s"the java.util.jar logger must not log WARNING, level is ${jul.getLevel}"
    )
  }

  // JL-2 — silencing the warning did not silence the manifest: the duplicate
  // attribute still parses, and the last value wins, which is the JDK's own rule.
  test(
    "JL-2 a duplicated manifest attribute still parses, last value winning"
  ) {
    val tree = Helpers.treeInfoFromManifest(duplicated)
    val createdBy = tree.collectFirst {
      case (k, v) if k.equalsIgnoreCase("Created-By") => v
    }
    assert(createdBy.isDefined, s"Created-By must be present, got ${tree.keys}")
    val values = createdBy.get.toVector.map(_.toString)
    assert(
      values.exists(_.contains("21.0.2")),
      s"the later Created-By value must win, got $values"
    )
  }
}
