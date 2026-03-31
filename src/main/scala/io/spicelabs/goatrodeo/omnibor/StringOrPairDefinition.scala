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

import io.bullet.borer.Decoder
import io.bullet.borer.Encoder

object StringOrPairDefinition {
  opaque type StringOrPair = String | (String, String)

  object StringOrPair {
    def apply(s: String): StringOrPair = s
    def apply(s1: String, s2: String): StringOrPair = (s1, s2)
    def apply(s: (String, String)): StringOrPair = (s._1, s._2)

    given fromString: Conversion[String, StringOrPair] = identity(_)
    given fromPair: Conversion[(String, String), StringOrPair] = identity(_)

    given canEqual: CanEqual[StringOrPair, String] = CanEqual.derived
    given canEqual2: CanEqual[String, StringOrPair] = CanEqual.derived
    given canEqual3: CanEqual[String, (String, String)] = CanEqual.derived
    given canEqual4: CanEqual[(String, String), String] = CanEqual.derived

    given ordering: Ordering[StringOrPair] = { (left, right) =>
      left match {
        case left: String => right match {
          case right: String => Ordering[String].compare(left, right)
          case (key, _)  => -1
        }
        case (leftKey, leftValue) => right match {
          case right: String => 1
          case (rightKey, rightValue) =>
            val comparison = Ordering[String].compare(leftKey, rightKey)
            if (comparison != 0) comparison else Ordering[String].compare(leftValue, rightValue)
        }
      }
    }

    given Encoder[StringOrPair] = { (writer, item) =>
      item match {
        case s: String => writer.writeString(s)
        case (a, b) =>
          writer
            .writeArrayOpen(2)
            .writeString(a)
            .writeString(b)
            .writeArrayClose()
      }

    }

    given Decoder[StringOrPair] = { reader =>
      if (reader.hasArrayStart || reader.hasArrayHeader(2)) {
        val unbounded = reader.readArrayOpen(2)
        val item = (reader.readString(), reader.readString())
        reader.readArrayClose(unbounded, item)
      } else if (reader.hasString) {
        reader.readString()
      } else {
        reader.unexpectedDataItem(
          f"Looking for 'String' or Array of String got ${reader.dataItem()}"
        )
      }

    }
  }

  extension (stringOrPair: StringOrPair) {
    def isPair: Boolean = stringOrPair match {
      case (_, _) => true
      case _      => false
    }

    def value: String = stringOrPair match {
      case s: String => s
      case (_, v)    => v
    }

    def mimeType: Option[String] = stringOrPair match {
      case (mt, _) => Some(mt)
      case _       => None
    }
  }
}

export StringOrPairDefinition.StringOrPair
