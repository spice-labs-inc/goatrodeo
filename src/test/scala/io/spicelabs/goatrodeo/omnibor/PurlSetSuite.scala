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

package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.coordinates.Purl
import munit.FunSuite

/** Unit tests for the [[PurlSet]] type.
  *
  * == What these tests test ==
  *
  * [[PurlSet]] is the return type of [[ProcessingState.getPurls]]. It bundles
  * an optional canonical pURL (the primary identity) with a vector of ALL
  * pURLs for an artifact (including the canonical one). These tests verify:
  *
  *  1. '''Construction safety''' — constructing a PurlSet never throws,
  *     even with inconsistent data (canonical not in purls). This enforces
  *     the project rule that code must not throw exceptions for expected,
  *     recoverable conditions.
  *  2. '''Factory method consistency''' — `single` and `build` produce
  *     well-formed PurlSets where the canonical pURL is a member of the
  *     purls vector.
  *  3. '''String conversion safety''' — `canonicalString` and
  *     `canonicalStrings` never throw; malformed pURLs are silently dropped
  *     via `Try`.
  *  4. '''Canonical inclusion''' — `canonicalStrings` always includes the
  *     canonical pURL, even if it was not a member of the `purls` vector
  *     (which can happen with direct construction).
  *  5. '''Deduplication''' — `canonicalStrings` removes duplicate strings
  *     (String has proper equals/hashCode, unlike Purl which uses reference
  *     equality).
  *
  * == Why these tests exist ==
  *
  * The previous design used a `require` check in the case class body that
  * would throw `IllegalArgumentException` if the canonical pURL was not a
  * member of the purls vector. This was rejected because:
  *  - The project policy forbids throwing exceptions for recoverable
  *    conditions.
  *  - A `Purl` is a Java `final class` that does not override `equals`, so
  *    `contains` uses reference equality — the `require` check could fail
  *    even for semantically-equal pURLs.
  *
  * Instead, the invariant is enforced by the factory methods (`single`,
  * `build`) and verified by these tests. `canonicalStrings` defensively
  * includes the canonical pURL regardless.
  *
  * == LLM-friendly summary ==
  *
  * `PurlSet` is a data container: `Option[Purl]` (canonical) + `Vector[Purl]`
  * (all). It never throws. Factory methods ensure consistency. String
  * conversion drops malformed pURLs via `Try`. The canonical pURL is always
  * included in the string output.
  *
  * == Requirements covered ==
  *
  * Plan Part 1: PurlSet type — all 10 tests from the plan's "PurlSet unit
  * tests" section.
  */
class PurlSetSuite extends FunSuite {

  // ---- Helpers ----

  /** Create a valid Maven pURL object for testing. */
  private def mkPurl(ns: String, name: String, ver: String): Purl = {
    Purl.parse(s"pkg:maven/$ns/$name@$ver")
  }

  /** Create a malformed pURL that will throw on `toCanonical()`.
    *
    * Maven pURLs require a namespace. A null namespace causes
    * `toCanonical()` to throw `Purl.PurlException`.
    */
  private def mkBadPurl(name: String): Purl = {
    new Purl(
      "maven",
      null, // maven requires a namespace — this will throw on toCanonical
      name,
      null,
      java.util.Collections.emptyMap[String, String](),
      null
    )
  }

  // ---- Test 1: Empty PurlSet ----
  //
  // Requirement: Plan Part 1, test 1 — "Empty PurlSet"
  // Theory: `PurlSet.empty` is the zero element. It must have no canonical,
  // no purls, and produce empty string output. This verifies the `empty`
  // factory method and the base case for all string conversion methods.

  test("PurlSet.empty - canonical is None, purls is empty, strings are empty") {
    val ps = PurlSet.empty
    assertEquals(ps.canonical, None)
    assert(ps.purls.isEmpty)
    assertEquals(ps.canonicalString, None)
    assert(ps.canonicalStrings.isEmpty)
  }

  // ---- Test 2: Single pURL ----
  //
  // Requirement: Plan Part 1, test 2 — "Single pURL"
  // Theory: `PurlSet.single(p)` should set canonical to `Some(p)` and purls
  // to `Vector(p)`. The canonical pURL must be a member of purls (by
  // reference, since Purl does not override equals). Both `canonicalString`
  // and `canonicalStrings` should produce the correct canonical string.

  test("PurlSet.single - canonical is Some(p), p is in purls, strings match") {
    val p = mkPurl("org.foo", "bar", "1.0")
    val ps = PurlSet.single(p)
    assertEquals(ps.canonical, Some(p))
    assertEquals(ps.purls, Vector(p))
    assertEquals(ps.canonicalString, Some("pkg:maven/org.foo/bar@1.0"))
    assertEquals(ps.canonicalStrings, Vector("pkg:maven/org.foo/bar@1.0"))
  }

  // ---- Test 3: Build with canonical + secondary ----
  //
  // Requirement: Plan Part 1, test 3 — "Canonical + secondary"
  // Theory: `PurlSet.build(Some(canonical), Vector(secondary))` should produce
  // a PurlSet where the canonical pURL is in the purls vector (by reference),
  // the secondary pURL is present, and both string conversion methods work.

  test("PurlSet.build - canonical and secondary are in purls, strings correct") {
    val canonical = mkPurl("org.foo", "bar", "1.0")
    val secondary = mkPurl("org.baz", "qux", "2.0")
    val ps = PurlSet.build(Some(canonical), Vector(secondary))
    assertEquals(ps.canonical, Some(canonical))
    assert(ps.purls.contains(canonical), "canonical must be in purls by reference")
    assert(ps.purls.contains(secondary), "secondary must be in purls by reference")
    assertEquals(ps.canonicalString, Some("pkg:maven/org.foo/bar@1.0"))
    assert(ps.canonicalStrings.contains("pkg:maven/org.foo/bar@1.0"))
    assert(ps.canonicalStrings.contains("pkg:maven/org.baz/qux@2.0"))
  }

  // ---- Test 4: Deduplication via build ----
  //
  // Requirement: Plan Part 1, test 4 — "Deduplication"
  // Theory: `PurlSet.build(Some(p), Vector(p))` should not produce duplicate
  // entries in purls. Since Purl uses reference equality, `distinct` removes
  // the duplicate by identity. The resulting purls vector should have length 1.

  test("PurlSet.build - same Purl object in canonical and secondary deduplicated") {
    val p = mkPurl("org.foo", "bar", "1.0")
    val ps = PurlSet.build(Some(p), Vector(p))
    assertEquals(ps.purls.length, 1, "duplicate Purl object should be removed by distinct")
    assertEquals(ps.canonicalStrings.length, 1, "duplicate string should be removed")
  }

  // ---- Test 5: Direct construction does NOT throw ----
  //
  // Requirement: Plan Part 1, test 5 — "Direct construction does NOT throw"
  // Theory: The project policy is that code must not throw exceptions for
  // expected, recoverable conditions. A PurlSet with a canonical pURL not
  // in the purls vector is a data inconsistency, not an unrecoverable
  // failure. Direct construction must succeed without throwing.
  //
  // This test replaces the old `require` check that threw
  // `IllegalArgumentException`.

  test("PurlSet direct construction - does not throw even with inconsistent data") {
    val p = mkPurl("org.foo", "bar", "1.0")
    val other = mkPurl("org.baz", "qux", "2.0")

    // canonical not in purls — must NOT throw
    val ps1 = PurlSet(Some(p), Vector.empty)
    assertEquals(ps1.canonical, Some(p))
    assert(ps1.purls.isEmpty)

    // canonical not in purls, different pURL in purls — must NOT throw
    val ps2 = PurlSet(Some(p), Vector(other))
    assertEquals(ps2.canonical, Some(p))
    assertEquals(ps2.purls, Vector(other))
  }

  // ---- Test 6: canonicalStrings includes canonical even when not in purls ----
  //
  // Requirement: Plan Part 1, test 6 — "canonicalStrings includes canonical
  // pURL even when not in purls"
  // Theory: When a PurlSet is constructed directly (bypassing factory methods)
  // with a canonical pURL that is not a member of the purls vector,
  // `canonicalStrings` must still include the canonical pURL's string. This
  // is the defensive behavior that replaces the old `require` check.

  test("canonicalStrings - includes canonical even when not in purls vector") {
    val p = mkPurl("org.foo", "bar", "1.0")
    val other = mkPurl("org.baz", "qux", "2.0")
    val ps = PurlSet(Some(p), Vector(other))

    val strings = ps.canonicalStrings
    assert(strings.contains("pkg:maven/org.foo/bar@1.0"), "canonical must be in canonicalStrings")
    assert(strings.contains("pkg:maven/org.baz/qux@2.0"), "other must be in canonicalStrings")
    assertEquals(strings.length, 2)
  }

  // ---- Test 7: canonicalString returns None when canonical is None ----
  //
  // Requirement: Plan Part 1, test 7 — "canonicalString returns None when
  // canonical is None"
  // Theory: `canonicalString` is an `Option[String]` — it returns `None` when
  // there is no canonical pURL. This verifies the Option-based design (no
  // exceptions, no nulls).

  test("canonicalString - returns None when canonical is None") {
    val p = mkPurl("org.foo", "bar", "1.0")
    val ps = PurlSet(None, Vector(p))
    assertEquals(ps.canonicalString, None)
    assertEquals(ps.canonicalStrings, Vector("pkg:maven/org.foo/bar@1.0"))
  }

  // ---- Test 8: canonicalStrings drops malformed pURLs ----
  //
  // Requirement: Plan Part 1, test 8 — "canonicalStrings drops failures"
  // Theory: `toCanonical()` can throw `Purl.PurlException` for malformed
  // pURLs (e.g., a Maven pURL with a null namespace). Each call is wrapped
  // in `Try` so one malformed pURL does not prevent the others from being
  // emitted. This is the same defensive pattern used throughout the
  // strategies.

  test("canonicalStrings - drops malformed pURLs, preserves valid ones") {
    val good = mkPurl("org.foo", "bar", "1.0")
    val bad = mkBadPurl("broken")
    val ps = PurlSet(None, Vector(good, bad))

    val strings = ps.canonicalStrings
    assert(strings.contains("pkg:maven/org.foo/bar@1.0"), "valid pURL must be present")
    assert(strings.length == 1, "malformed pURL must be dropped, only 1 string expected")
  }

  test("canonicalString - returns None when canonical pURL is malformed") {
    val bad = mkBadPurl("broken")
    val good = mkPurl("org.foo", "bar", "1.0")
    val ps = PurlSet(Some(bad), Vector(good))

    assertEquals(ps.canonicalString, None, "malformed canonical must produce None")
    assert(ps.canonicalStrings.contains("pkg:maven/org.foo/bar@1.0"), "valid secondary must still be present")
  }

  // ---- Test 9: Property-based — single round-trip ----
  //
  // Requirement: Plan Part 1, test 9 — "Property-based: single round-trip"
  // Theory: For any valid pURL string `s`, `PurlSet.single(Purl.parse(s))
  // .canonicalStrings` must contain `s`. This verifies that the `single`
  // factory preserves the pURL through string conversion.

  test("PurlSet.single round-trips through canonicalStrings") {
    val testCases = List(
      "pkg:maven/org.foo/bar@1.0",
      "pkg:maven/com.google/guava@32.1.1-jre",
      "pkg:nuget/Newtonsoft.Json@13.0.1",
      "pkg:generic/eclipse/temurin@17.0.8"
    )
    testCases.foreach { s =>
      val p = Purl.parse(s)
      val ps = PurlSet.single(p)
      assert(ps.canonicalStrings.contains(s), s"single round-trip failed for $s")
      assert(ps.canonicalString.contains(s), s"canonicalString failed for $s")
    }
  }

  // ---- Test 10: Property-based — build round-trip ----
  //
  // Requirement: Plan Part 1, test 10 — "Property-based: build round-trip"
  // Theory: For any `Option[Purl]` (canonical) and `Vector[Purl]` (secondary,
  // all valid), `PurlSet.build(canonical, secondary).canonicalStrings` must
  // contain the canonical string (if canonical is present and valid) and all
  // secondary strings. This verifies that `build` preserves all pURLs through
  // string conversion.

  test("PurlSet.build round-trips through canonicalStrings") {
    val canonical = mkPurl("org.canonical", "primary", "1.0")
    val secondary1 = mkPurl("org.secondary", "first", "2.0")
    val secondary2 = mkPurl("org.secondary", "second", "3.0")
    val ps = PurlSet.build(Some(canonical), Vector(secondary1, secondary2))

    val strings = ps.canonicalStrings.toSet
    assert(strings.contains("pkg:maven/org.canonical/primary@1.0"))
    assert(strings.contains("pkg:maven/org.secondary/first@2.0"))
    assert(strings.contains("pkg:maven/org.secondary/second@3.0"))
  }

  // ---- Test 11: canonicalStrings deduplicates at string level ----
  //
  // Requirement: Plan Part 1, implied by test 4 and test 6
  // Theory: Purl does not override equals, so `Vector.distinct` on Purl
  // objects uses reference equality. But `canonicalStrings` converts to
  // String first, then calls `.distinct`. String has proper equals/hashCode,
  // so semantically-equal pURLs (different Purl objects, same canonical
  // string) are deduplicated. This test verifies that two different Purl
  // objects that produce the same canonical string are deduplicated in
  // `canonicalStrings`.

  test("canonicalStrings - deduplicates semantically equal pURLs at string level") {
    // Two different Purl objects with the same canonical string
    val p1 = Purl.parse("pkg:maven/org.foo/bar@1.0")
    val p2 = Purl.parse("pkg:maven/org.foo/bar@1.0")
    assert(p1 ne p2, "p1 and p2 must be different objects")

    val ps = PurlSet(None, Vector(p1, p2))
    assertEquals(ps.canonicalStrings.length, 1, "semantically equal pURLs must be deduplicated at string level")
  }

  // ---- Test 12: build with None canonical and empty secondary ----
  //
  // Requirement: Plan Part 1, implied by factory method coverage
  // Theory: `PurlSet.build(None, Vector.empty)` should be equivalent to
  // `PurlSet.empty`. This verifies the edge case of the build factory.

  test("PurlSet.build(None, Vector.empty) - equivalent to PurlSet.empty") {
    val ps = PurlSet.build(None, Vector.empty)
    assertEquals(ps.canonical, None)
    assert(ps.purls.isEmpty)
    assert(ps.canonicalStrings.isEmpty)
  }
}
