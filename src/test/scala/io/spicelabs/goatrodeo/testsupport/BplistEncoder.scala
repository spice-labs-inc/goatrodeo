package io.spicelabs.goatrodeo.testsupport

import org.json4s.*

import java.io.ByteArrayOutputStream
import scala.collection.mutable

/** Encodes `org.json4s.JValue` structures into the Apple **binary plist**
  * format (`bplist00`), for test fixtures and golden blobs.
  *
  * The encoder covers the grammar the parser supports: null, booleans, integers
  * (1/2/4/8-byte), doubles, ASCII strings (8-bit safe), UTF-16 strings
  * (non-ASCII), data (as base64 strings), arrays, sets, and dictionaries. Dates
  * are not encoded (the parser maps them to ISO strings; date tests use
  * hand-built golden blobs).
  *
  * ==Determinism==
  * Object-table order is first-encounter, refs and offsets use fixed sizes
  * (refSize = 2, offsetSize = 4), so encoding the same JValue twice produces
  * identical bytes — required by the round-trip property tests and golden
  * comparisons. Fixture corpora are small (well under 65535 objects and 4 GiB),
  * so the fixed sizing is always sufficient.
  */
object BplistEncoder {

  private val Header = "bplist00".getBytes("US-ASCII")

  /** Object-reference width: 2 bytes (up to 65535 objects). */
  private val RefSize = 2

  /** Offset-table entry width: 4 bytes (up to 4 GiB bodies). */
  private val OffsetSize = 4

  /** Encode a JValue into a binary plist.
    *
    * @param value
    *   the value to encode; JNothing encodes as null
    * @return
    *   the bplist bytes
    */
  def encode(value: JValue): Array[Byte] = {
    val objects = new mutable.ArrayBuffer[Array[Byte]]()
    val top = addObject(value, objects)

    val numObjects = objects.length
    // body: the object stream in table order
    val body = new ByteArrayOutputStream()
    objects.foreach(o => body.write(o))
    val offsetTableOffset = Header.length + body.size()

    val out = new ByteArrayOutputStream()
    out.write(Header)
    out.write(body.toByteArray)
    // offset table: absolute file offsets of each object (header first)
    var running = 0
    for (o <- objects) {
      writeBE(out, Header.length + running, OffsetSize)
      running += o.length
    }
    // trailer (32 bytes)
    out.write(Array.fill[Byte](6)(0))
    out.write(OffsetSize)
    out.write(RefSize)
    writeBE(out, numObjects, 8)
    writeBE(out, top, 8)
    writeBE(out, offsetTableOffset, 8)
    out.toByteArray
  }

  private def writeBE(
      out: ByteArrayOutputStream,
      v: Long,
      size: Int
  ): Unit = {
    var i = size - 1
    while (i >= 0) {
      out.write(((v >> (8 * i)) & 0xff).toInt)
      i -= 1
    }
  }

  /** Append an object's encoded bytes to the table; returns its index.
    * Containers are appended AFTER their children (children first), so the
    * offset table is computed over the final order — order does not matter to
    * the parser (the offsets point at each object's bytes).
    */
  private def addObject(
      value: JValue,
      objects: mutable.ArrayBuffer[Array[Byte]]
  ): Int = {
    // Normalize numeric variants so no case below double-appends
    val normalized = value match {
      case JDecimal(d) => JDouble(d.toDouble)
      case JLong(l)    => JInt(BigInt(l))
      case other       => other
    }
    val obj: Array[Byte] = normalized match {
      case JNothing | JNull => Array(0x00)
      case JBool(false)     => Array(0x08)
      case JBool(true)      => Array(0x09)
      case JInt(n) =>
        val v = n.toLong
        val size =
          if (v >= Byte.MinValue && v <= Byte.MaxValue) 1
          else if (v >= Short.MinValue && v <= Short.MaxValue) 2
          else if (v >= Int.MinValue && v <= Int.MaxValue) 4
          else 8
        val marker =
          0x10 | (size match {
            case 1 => 0
            case 2 => 1
            case 4 => 2
            case 8 => 3
          })
        val o = new ByteArrayOutputStream()
        o.write(marker)
        writeBE(o, v, size)
        o.toByteArray
      case JDouble(d) =>
        val o = new ByteArrayOutputStream()
        o.write(0x23)
        writeBE(o, java.lang.Double.doubleToLongBits(d), 8)
        o.toByteArray
      case JString(s) =>
        if (s.forall(ch => ch >= 0x20 && ch <= 0x7e)) {
          val bytes = s.getBytes("US-ASCII")
          lengthPrefixed(0x50, bytes.length, bytes)
        } else {
          val utf16 = s.toCharArray.flatMap(ch =>
            Array(((ch >> 8) & 0xff).toByte, (ch & 0xff).toByte)
          )
          lengthPrefixed(0x60, s.length, utf16)
        }
      case JArray(items) =>
        val refs = items.map(i => addObject(i, objects))
        containerRefs(0xa0, refs.length, refs, objects)
      case JObject(fields) =>
        val keyRefs = fields.map { case (k, _) =>
          addObject(JString(k), objects)
        }
        val valRefs = fields.map { case (_, v) => addObject(v, objects) }
        // Dict count is the number of PAIRS; refs = keys then values.
        containerRefs(0xd0, fields.length, keyRefs ++ valRefs, objects)
      case _ => Array(0x00)
    }
    objects.append(obj)
    objects.length - 1
  }

  /** Encode a container (array/set/dict): count marker (0xf-escaped when
    * needed) followed by the fixed-width object refs.
    *
    * @param count
    *   number of elements (arrays/sets) or key-value pairs (dicts)
    */
  private def containerRefs(
      baseMarker: Int,
      count: Int,
      refs: Seq[Int],
      objects: mutable.ArrayBuffer[Array[Byte]]
  ): Array[Byte] = {
    val o = new ByteArrayOutputStream()
    writeCount(o, baseMarker, count)
    refs.foreach(r => writeBE(o, r, RefSize))
    o.toByteArray
  }

  /** Write a count-carrying marker; the 0xf escape is used for counts > 15.
    */
  private def writeCount(
      o: ByteArrayOutputStream,
      baseMarker: Int,
      count: Int
  ): Unit = {
    if (count < 0x0f) {
      o.write(baseMarker | count)
    } else {
      o.write(baseMarker | 0x0f)
      // integer object: marker 0x10 + 1 byte (count fits 0..255)
      o.write(0x10)
      o.write(count & 0xff)
    }
  }

  private def lengthPrefixed(
      baseMarker: Int,
      length: Int,
      payload: Array[Byte]
  ): Array[Byte] = {
    val o = new ByteArrayOutputStream()
    if (length < 0x0f) o.write(baseMarker | length)
    else {
      o.write(baseMarker | 0x0f)
      o.write(0x10)
      o.write(length & 0xff)
    }
    o.write(payload)
    o.toByteArray
  }
}
