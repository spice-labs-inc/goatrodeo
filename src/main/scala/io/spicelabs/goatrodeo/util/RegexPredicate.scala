/* Copyright 2024 David Pollak, Spice Labs, Inc. & Contributors

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

import scala.util.matching.Regex

/** A predicate that matches strings by exact equality or regex pattern matching.
  *
  * This is used by [[IncludeExclude]] to determine whether items match
  * include or exclude criteria.
  *
  * @param exact
  *   a set of strings that match exactly
  * @param regexes
  *   a vector of regex patterns for pattern matching
  */
final case class RegexPredicate(exact: Set[String], regexes: Vector[Regex]) {

  /** Test whether a string matches this predicate.
    *
    * A string matches if it is in the exact set OR if it matches any of the
    * regex patterns.
    *
    * @param str
    *   the string to test
    * @return
    *   true if the string matches any exact value or regex pattern
    */
  def matches(str: String) =
    exact.contains(str) || regexes.exists((regex) => regex.matches(str))
}
