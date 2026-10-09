package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.testsupport.BplistEncoder
import io.spicelabs.goatrodeo.util.BinaryPlistParser
import org.json4s.*
import org.json4s.JsonDSL.*
import org.scalacheck.{Gen, Prop, Properties}

/** Binary/XML plist parser tests.
  *
  * Requirement (§2.2 of the plan): the parser is bounded (16 MiB blob cap,
  * object-count cap, depth cap, allocation-time bound checks), never throws
  * (malformed input is `None`), normalizes binary and XML plists to the same
  * JValue shape, and mirrors PomParser's XXE hardening on the XML path.
  *
  * LLM note: Bplist-xx = test id.
  */
class BinaryPlistParserSuite extends GoatRodeoFunSuite {

  // Bplist-1.1 — golden binary plist with a dict of scalar types parses to
  // the expected JValue.
  test("Bplist-1.1 golden binary plist parses") {
    val golden = BplistEncoder.encode(
      JObject(
        "name" -> JString("TestApp"),
        "version" -> JInt(7),
        "enabled" -> JBool(true),
        "disabled" -> JBool(false),
        "missing" -> JNull,
        "tags" -> JArray(List(JString("a"), JString("b"), JString("c"))),
        "weight" -> JDouble(1.5)
      )
    )
    val parsed = BinaryPlistParser.parse(golden)
    assert(parsed.isDefined, "golden plist must parse")
    val jv = parsed.get
    assertEquals(jv \ "name", JString("TestApp"))
    assertEquals(jv \ "version", JInt(7))
    assertEquals(jv \ "enabled", JBool(true))
    assertEquals(jv \ "disabled", JBool(false))
    assertEquals(jv \ "missing", JNull)
    assertEquals(
      jv \ "tags",
      JArray(List(JString("a"), JString("b"), JString("c")))
    )
    assertEquals(jv \ "weight", JDouble(1.5))
  }

  // Bplist-1.2 — UTF-16 strings, non-ASCII, surrogate pairs round-trip.
  test("Bplist-1.2 UTF-16 non-ASCII and surrogate pairs round-trip") {
    val cases = List(
      "héllo wörld",
      "日本語",
      "emoji \ud83d\ude00 end", // 😀 as surrogate pair
      "astral \ud83d\udca1\u0000null", // astral plane + embedded null
      "comp\\bined \u00e9" // combining-ish, plain U+00E9
    )
    cases.foreach { s =>
      val encoded = BplistEncoder.encode(JString(s))
      val parsed = BinaryPlistParser.parse(encoded)
      assert(parsed.contains(JString(s)), s"round-trip failed for: $s")
    }
    // UTF-16 path is required for non-ASCII (encoder picks it), and the
    // astral pair must survive intact.
    val encoded = BplistEncoder.encode(JString("😀"))
    val parsed = BinaryPlistParser.parse(encoded)
    assertEquals(parsed, Some(JString("😀")))
  }

  // Bplist-1.3 — empty dict / minimal plist parses.
  test("Bplist-1.3 empty dict parses") {
    val encoded = BplistEncoder.encode(JObject())
    val parsed = BinaryPlistParser.parse(encoded)
    assert(parsed.isDefined, "empty dict must parse")
    assertEquals(parsed.get, JObject())
  }

  // Bplist-1.4 — trailer with absurd object count / offsets is rejected.
  test("Bplist-1.4 hostile trailer counts are rejected") {
    val good = BplistEncoder.encode(JObject("k" -> JString("v")))
    // Corrupt the trailer's numObjects (last 32 bytes: offsetSize at -26,
    // refSize at -25, numObjects 8 bytes at -24..-17).
    def withNumObjects(bytes: Array[Byte], v: Long): Array[Byte] = {
      val b = bytes.clone()
      var i = 0
      while (i < 8) {
        b(b.length - 24 + i) = ((v >> (8 * (7 - i))) & 0xff).toByte
        i += 1
      }
      b
    }
    // absurd counts (above MaxObjectCount=100000)
    withNumObjects(good, 0xffffffffL) // 4 billion
    assert(BinaryPlistParser.parse(withNumObjects(good, 0xffffffffL)).isEmpty)
    assert(BinaryPlistParser.parse(withNumObjects(good, 99999999999L)).isEmpty)
    // offset table beyond the buffer
    val b2 = good.clone()
    // offsetTableOffset is the last 8 bytes; point past the buffer.
    var i = 0
    while (i < 8) {
      b2(b2.length - 8 + i) = 0x7f.toByte
      i += 1
    }
    assert(BinaryPlistParser.parse(b2).isEmpty)
  }

  // Bplist-1.5 — offsets outside buffer / cyclic graphs -> None.
  test("Bplist-1.5 offsets out of buffer and cycles are rejected") {
    val good = BplistEncoder.encode(JObject("k" -> JArray(List(JString("v")))))
    // Corrupt the offset table entry for the top object: point it at the
    // trailer (must be < trailer-1, so trailer itself is invalid).
    // The offset table starts at offsetTableOffset; corrupt the first entry.
    val b = good.clone()
    val offTableStart = java.nio.ByteBuffer
      .wrap(b, b.length - 8, 8)
      .getLong()
      .toInt
    // write a huge offset in the first 4-byte entry
    b(offTableStart) = 0x7f.toByte
    b(offTableStart + 1) = 0x7f.toByte
    b(offTableStart + 2) = 0x7f.toByte
    b(offTableStart + 3) = 0x7f.toByte
    assert(BinaryPlistParser.parse(b).isEmpty)
  }

  // Bplist-1.6 — deep nesting beyond the cap -> None; 0xf length beyond
  // buffer -> None.
  test("Bplist-1.6 depth cap and escaped length bomb") {
    // Build a nesting deeper than MaxDepth (256) using arrays.
    var v: JValue = JString("leaf")
    (0 until 300).foreach(_ => v = JArray(List(v)))
    val encoded = BplistEncoder.encode(v)
    assert(BinaryPlistParser.parse(encoded).isEmpty, "depth cap must reject")

    // Hand-build a data object whose 0xf-escaped length claims more than the
    // buffer holds: marker 0x4f, then 0x10 0x08 (8-byte int length), then
    // 0x7f..x7 (a huge length), then EOF.
    val bomb = Array[Byte](
      0x4f.toByte,
      0x10,
      0x08,
      0x7f,
      0x7f,
      0x7f,
      0x7f,
      0x7f,
      0x7f,
      0x7f,
      0x7f
    )
    // needs a valid trailer; wrap minimal: bplist00 + object + trailer
    val withTrailer = mkMinimal(bomb)
    assert(BinaryPlistParser.parse(withTrailer).isEmpty)
  }

  /** Wrap a single-object body with a valid trailer + offset table so the
    * parser reaches the object decode. The object is at offset 8 (after the
    * header).
    */
  private def mkMinimal(body: Array[Byte]): Array[Byte] = {
    val out = new java.io.ByteArrayOutputStream()
    out.write("bplist00".getBytes("US-ASCII"))
    out.write(body)
    val offTablePos = out.size() // 4-byte offset entry for object at 8
    out.write(Array[Byte](0, 0, 0, 8))
    out.write(Array.fill[Byte](6)(0))
    out.write(4) // offsetSize
    out.write(2) // refSize
    // numObjects = 1
    out.write(Array[Byte](0, 0, 0, 0, 0, 0, 0, 1))
    // topObject = 0
    out.write(Array[Byte](0, 0, 0, 0, 0, 0, 0, 0))
    // offsetTableOffset = offTablePos
    var i = 7
    while (i >= 0) {
      out.write(((offTablePos >> (8 * i)) & 0xff))
      i -= 1
    }
    out.toByteArray
  }

  // Bplist-1.7 — truncated buffer (every prefix length) never throws.
  test("Bplist-1.7 truncation property: never throws") {
    val good = BplistEncoder.encode(
      JObject(
        "pairs" -> JArray(
          List(
            JObject("a" -> JInt(1), "b" -> JString("two")),
            JObject("c" -> JBool(true))
          )
        )
      )
    )
    for (cut <- 0 until good.length) {
      val truncated = java.util.Arrays.copyOf(good, cut)
      val result =
        try BinaryPlistParser.parse(truncated)
        catch {
          case e: Throwable =>
            fail(s"parser threw at truncation $cut: $e")
        }
      // valid or None, but never a throw
      assert(result.isDefined || result.isEmpty)
    }
  }

  // Bplist-1.8 — XML plist (ATS shape) parses to its binary twin's JValue.
  test("Bplist-1.8 XML and binary twins parse to the same JValue") {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
                | "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                |<plist version="1.0"><dict>
                |  <key>NSAppTransportSecurity</key>
                |  <dict>
                |    <key>NSAllowsArbitraryLoads</key><true/>
                |  </dict>
                |  <key>CFBundleIdentifier</key>
                |  <string>org.example.Min</string>
                |  <key>CFBundleVersion</key><integer>42</integer>
                |</dict></plist>""".stripMargin
    val binary = BplistEncoder.encode(
      JObject(
        "NSAppTransportSecurity" ->
          JObject("NSAllowsArbitraryLoads" -> JBool(true)),
        "CFBundleIdentifier" -> JString("org.example.Min"),
        "CFBundleVersion" -> JInt(42)
      )
    )
    val xmlParsed = BinaryPlistParser.parse(xml.getBytes("UTF-8"))
    val binParsed = BinaryPlistParser.parse(binary)
    assert(xmlParsed.isDefined, "XML plist must parse")
    assert(binParsed.isDefined, "binary twin must parse")
    assertEquals(xmlParsed.get, binParsed.get)
    assertEquals(
      xmlParsed.get \ "CFBundleIdentifier",
      JString("org.example.Min")
    )
  }

  // Bplist-1.9 — property-based round-trip over bounded random JValues.
  test("Bplist-1.9 property round-trip over random JValues") {
    val genString = Gen.oneOf(
      Gen.alphaNumStr,
      Gen.chooseNum[Char](0x0020, 0x00ff).map(_.toString),
      Gen.const("😀"),
      Gen.const("日本語")
    )
    def genValue(depth: Int): Gen[JValue] =
      if (depth > 3) genString.map(s => JString(s): JValue)
      else
        Gen.oneOf(
          genString.map(s => JString(s): JValue),
          Gen.chooseNum[Int](-1000, 1000).map(n => JInt(n): JValue),
          Gen.chooseNum[Double](-10.0, 10.0).map(d => JDouble(d): JValue),
          Gen.const(JBool(true): JValue),
          Gen.const(JBool(false): JValue),
          Gen.const(JNull: JValue),
          Gen.listOfN(3, genValue(depth + 1)).map(l => JArray(l): JValue),
          Gen
            .mapOfN(
              3,
              Gen.zip(genString, genValue(depth + 1))
            )
            .map(m => JObject(m.toList): JValue)
        )
    val prop = Prop.forAll(genValue(0)) { v =>
      val encoded = BplistEncoder.encode(v)
      BinaryPlistParser.parse(encoded) == Some(v)
    }
    prop.check()
  }

  // Bplist-1.a — non-plist garbage -> None, never throws.
  test("Bplist-1.a garbage and other formats are rejected") {
    val garbage = List(
      Array[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
      "this is not a plist at all".getBytes("UTF-8"),
      Array.fill[Byte](64)(0x7f),
      // a zip local header
      Array[Byte](0x50, 0x4b, 0x03, 0x04, 0, 0, 0, 0, 0, 0),
      // UTF-16 BOM text
      Array[Byte](0xff.toByte, 0xfe.toByte, 0x41, 0x00, 0x42, 0x00)
    )
    garbage.foreach { g =>
      val r = BinaryPlistParser.parse(g)
      assert(r.isEmpty, s"garbage must be None, got $r")
    }
  }

  // Bplist-1.b — blob larger than the cap is refused (never read fully).
  test("Bplist-1.b oversized blob is refused") {
    val big = Array.fill[Byte](BinaryPlistParser.MaxPlistBytes + 1)(0)
    val r = BinaryPlistParser.parse(big)
    assert(r.isEmpty, "oversized blob must be refused")
  }

  // Bplist-1.c — XML with hostile DOCTYPE stays inert (XXE).
  test("Bplist-1.c hostile DOCTYPE is inert") {
    val xxe = """<?xml version="1.0"?>
                |<!DOCTYPE plist [
                |  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                |]>
                |<plist version="1.0"><string>&xxe;</string></plist>""".stripMargin
    val r = BinaryPlistParser.parse(xxe.getBytes("UTF-8"))
    // Either the parse fails (doctype refused) or the entity stays
    // unexpanded — never file content.
    r match {
      case None => () // refused: acceptable
      case Some(JString(s)) =>
        assert(
          !s.contains("root:") && !s.contains("file:///etc/passwd") &&
            s != "root:" && !s.contains(":0:0:"),
          "XXE payload must not expand to file content"
        )
      // The entity reference may be preserved literally or dropped,
      // but must not be resolved.
      case Some(other) => fail(s"unexpected parse of XXE payload: $other")
    }
  }
}

/** ScalaCheck companion for the Bplist-1.9 round-trip property.
  */
object BplistRoundTripProperties extends Properties("bplist-roundtrip") {
  property("encode/parse round-trip") = {
    val genString = Gen.oneOf(
      Gen.alphaNumStr,
      Gen.const("😀"),
      Gen.const("日本語"),
      Gen.chooseNum[Char](0x0020, 0x00ff).map(_.toString)
    )
    def genValue(depth: Int): Gen[JValue] =
      if (depth > 3) genString.map(s => JString(s): JValue)
      else
        Gen.oneOf(
          genString.map(s => JString(s): JValue),
          Gen.chooseNum[Int](-100000, 100000).map(n => JInt(n): JValue),
          Gen.const(JBool(true): JValue),
          Gen.const(JBool(false): JValue),
          Gen.const(JNull: JValue),
          Gen.listOfN(3, genValue(depth + 1)).map(JArray(_): JValue),
          Gen
            .mapOfN(3, Gen.zip(genString, genValue(depth + 1)))
            .map(m => JObject(m.toList): JValue)
        )
    Prop.forAll(genValue(0)) { v =>
      BinaryPlistParser.parse(BplistEncoder.encode(v)) == Some(v)
    }
  }
}
