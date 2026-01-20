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

/** A filter that determines whether items should be included or excluded based
  * on exact string matches and regex patterns.
  *
  * The filtering logic follows these rules:
  *   - If a candidate is NOT in the exclude set, it is included
  *   - If a candidate IS in the exclude set, check if it's explicitly included
  *   - This allows for "exclude all except" patterns
  *
  * Predicate strings use the following syntax:
  *   - `+pattern` - include exact match
  *   - `*regex` - include regex match
  *   - `-pattern` - exclude exact match
  *   - `/regex` - exclude regex match
  *   - `#...` - comment (ignored)
  *
  * @param include
  *   the predicates for items to include
  * @param exclude
  *   the predicates for items to exclude
  */
class IncludeExclude(include: RegexPredicate, exclude: RegexPredicate) {
  def this(
      incExact: Set[String],
      incRegex: Vector[Regex],
      excExact: Set[String],
      excRegex: Vector[Regex]
  ) = {
    this(RegexPredicate(incExact, incRegex), RegexPredicate(excExact, excRegex))
  }

  /** Create an empty IncludeExclude that includes everything. */
  def this() = {
    this(RegexPredicate(Set(), Vector()), RegexPredicate(Set(), Vector()))
  }

  /** Determine whether a candidate string should be included.
    *
    * @param candidate
    *   the string to check
    * @return
    *   true if the candidate should be included, false if it should be excluded
    */
  def shouldInclude(candidate: String) = {
    // if the candidate is not in the exclude, it should be included.
    // if the candidate *is* is the exclude, then check to see if it's included
    !exclude.matches(candidate) || include.matches(candidate)
  }

  /** Add a single predicate to this IncludeExclude, returning a new instance.
    *
    * @param predicate
    *   the predicate string (e.g., "+include", "-exclude", "*regex", "/regex")
    * @return
    *   a new IncludeExclude with the predicate added
    */
  def :+(predicate: String): IncludeExclude = {
    val (inE, inR, exE, exR) = IncludeExclude.aggregatePredicate(
      predicate,
      (include.exact, include.regexes, exclude.exact, exclude.regexes)
    )
    IncludeExclude(RegexPredicate(inE, inR), RegexPredicate(exE, exR))
  }

  /** Add multiple predicates to this IncludeExclude, returning a new instance.
    *
    * @param predicates
    *   the predicate strings to add
    * @return
    *   a new IncludeExclude with all predicates added
    */
  def ++(predicates: Iterable[String]): IncludeExclude = {
    var inex = this
    for (predicate <- predicates) {
      inex = inex :+ predicate
    }
    inex
  }
}

/** Companion object for IncludeExclude with utility methods. */
object IncludeExclude {

  /** Parse a predicate string and add it to the appropriate include/exclude collection.
    *
    * Predicate syntax:
    *   - `+pattern` - add to include exact matches
    *   - `*regex` - add to include regex patterns
    *   - `-pattern` - add to exclude exact matches
    *   - `/regex` - add to exclude regex patterns
    *   - `#...` - comment (returns unchanged)
    *   - Empty predicates return unchanged
    *
    * @param predicate
    *   the predicate string to parse
    * @param inc_exc
    *   the current state of (include exact, include regex, exclude exact, exclude regex)
    * @return
    *   the updated tuple with the predicate applied
    * @throws IllegalArgumentException
    *   if the predicate starts with an unrecognized command character
    */
  def aggregatePredicate(
      predicate: String,
      inc_exc: (
          incExact: Set[String],
          incRegex: Vector[Regex],
          excExact: Set[String],
          excRegex: Vector[Regex]
      )
  ): (Set[String], Vector[Regex], Set[String], Vector[Regex]) = {
    if (predicate.isEmpty()) {
      return inc_exc
    }
    val command = predicate.charAt(0)
    val pattern = predicate.substring(1)
    if (pattern.isEmpty()) {
      return inc_exc
    }
    command match {
      case '#' => inc_exc
      case '+' =>
        (
          inc_exc.incExact + pattern,
          inc_exc.incRegex,
          inc_exc.excExact,
          inc_exc.excRegex
        )
      case '*' =>
        (
          inc_exc.incExact,
          inc_exc.incRegex :+ pattern.r,
          inc_exc.excExact,
          inc_exc.excRegex
        )
      case '-' =>
        (
          inc_exc.incExact,
          inc_exc.incRegex,
          inc_exc.excExact + pattern,
          inc_exc.excRegex
        )
      case '/' =>
        (
          inc_exc.incExact,
          inc_exc.incRegex,
          inc_exc.excExact,
          inc_exc.excRegex :+ pattern.r
        )
      case _ =>
        throw IllegalArgumentException(
          s"illegal command '${command}' expecting one of #, +, *, -, /"
        )
    }
  }
}
