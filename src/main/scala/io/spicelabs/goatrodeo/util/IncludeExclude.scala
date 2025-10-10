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

class IncludeExclude(include: RegexPredicate, exclude: RegexPredicate) {
  def this(
      incExact: Set[String],
      incRegex: Vector[Regex],
      excExact: Set[String],
      excRegex: Vector[Regex]
  ) = {
    this(RegexPredicate(incExact, incRegex), RegexPredicate(excExact, excRegex))
  }

  def this() = {
    this(RegexPredicate(Set(), Vector()), RegexPredicate(Set(), Vector()))
  }

  def shouldInclude(candidate: String) = {
    // if the candidate is not in the exclude, it should be included.
    // if the candidate *is* is the exclude, then check to see if it's included
    !exclude.matches(candidate) || include.matches(candidate)
  }

  def :+(predicate: String): IncludeExclude = {
    val (inE, inR, exE, exR) = IncludeExclude.aggregatePredicate(
      predicate,
      (include.exact, include.regexes, exclude.exact, exclude.regexes)
    )
    IncludeExclude(RegexPredicate(inE, inR), RegexPredicate(exE, exR))
  }

  def ++(predicates: Iterable[String]): IncludeExclude = {
    var inex = this
    for (predicate <- predicates) {
      inex = inex :+ predicate
    }
    inex
  }
}

object IncludeExclude {
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
