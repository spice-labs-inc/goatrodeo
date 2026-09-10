/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.envelopes

import io.bullet.borer.Dom.ArrayElem
import io.bullet.borer.Dom.IntElem
import io.bullet.borer.Dom.MapElem
import io.bullet.borer.Dom.StringElem
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

/** MD5 CBOR decoding failure containment.
  *
  * '''WHAT:''' `MD5.decodeCBORElement` must return a Failure value when the
  * element is not a valid 16-byte MD5 map, never throw. A hostile or corrupt
  * envelope element must not be able to throw out of the decode path.
  *
  * '''WHY:''' per the project rule, expected failures are values.
  */
class MD5DecodeSuite extends GoatRodeoFunSuite {

  test("MD5.decodeCBORElement - invalid element returns Failure, never throws") {
    val invalid = MapElem.Sized(
      StringElem("h") -> ArrayElem.Sized(IntElem(1))
    )
    val result = MD5.decodeCBORElement(invalid)
    assert(
      result.isFailure,
      "an element with a non-16-byte hash must be a Failure, not a throw"
    )
  }

  test("MD5.decodeCBORElement - non-map element returns Failure") {
    val result = MD5.decodeCBORElement(StringElem("not a map"))
    assert(result.isFailure)
  }

  test("MD5.decodeCBORElement - valid 16-byte hash decodes") {
    val elems = (1 to 16).map(i => IntElem(i))
    val valid = MapElem.Sized(
      StringElem("h") -> ArrayElem.Sized(elems*)
    )
    val result = MD5.decodeCBORElement(valid)
    assert(result.isSuccess)
    assertEquals(result.toOption.get.hash.length, 16)
  }
}
