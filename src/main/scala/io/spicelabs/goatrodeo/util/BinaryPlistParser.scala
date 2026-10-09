package io.spicelabs.goatrodeo.util

import org.json4s.*
import org.json4s.JsonDSL.*
import org.w3c.dom.{Element, Node, NodeList}

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import scala.collection.mutable
import scala.util.Try

/** Parses Apple property-list files into `org.json4s.JValue`, supporting both
  * the **binary** plist format (`bplist00`) and **XML** plists.
  *
  * This is the parsing foundation for iOS `.ipa` support: an iOS app bundle's
  * `Info.plist` (and the payload inside `embedded.mobileprovision`) is
  * frequently a binary plist, and the existing MobileTls ATS capture reads raw
  * bytes and regexes them — it cannot read binary plists. Both formats
  * normalize to the same JValue shape so consumers (MobileTls, the Ipa
  * strategy) write one code path.
  *
  * ==Bounded and never throws==
  *
  * The parser is hostile-input safe in the style of [[PomParser]]:
  *
  *   - a blob read cap (16 MiB, mirroring `DockerToProcess.MaxOciJsonBytes`) is
  *     enforced by the caller or the facade before parsing;
  *   - the trailer's object count, offset-table claims, per-object length
  *     fields (including the `0xf`-escaped forms) and the depth are all checked
  *     **before allocation**, so attacker-controlled counts cannot drive memory
  *     amplification;
  *   - refs outside the object table and offsets outside the buffer are
  *     rejected; cyclic object graphs are rejected via a resolved-set;
  *   - any malformed input yields `None` — a failed parse is a value, never an
  *     exception out of the strategy.
  *
  * ==XML path==
  *
  * The XML path reuses the hardened `DocumentBuilderFactory` configuration from
  * [[PomParser]] (doctype disallowed, external entities off, no external DTD,
  * no XInclude), with a DOCTYPE-stripping retry so benign plist DOCTYPEs parse.
  * A hostile DOCTYPE with entity expansion stays inert (verified by
  * `BinaryPlistParserSuite.Bplist-1.c`).
  */
object BinaryPlistParser {

  /** Hard read cap for a property-list blob (16 MiB, mirroring the OCI metadata
    * cap). The facade refuses to parse larger inputs.
    */
  val MaxPlistBytes: Int = 16 * 1024 * 1024

  /** Maximum number of objects accepted in a binary plist object table.
    */
  val MaxObjectCount: Int = 100_000

  /** Maximum container nesting depth.
    */
  val MaxDepth: Int = 256

  private val bplistHeader = "bplist00"

  /** A hardened DocumentBuilderFactory for the XML path (identical settings to
    * PomParser's secureDbf: doctype off, external entities off, no external
    * DTD, no XInclude).
    */
  private def secureDbf: DocumentBuilderFactory = {
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(false)
    f.setValidating(false)
    f.setXIncludeAware(false)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    f.setFeature(
      "http://xml.org/sax/features/external-general-entities",
      false
    )
    f.setFeature(
      "http://apache.org/xml/features/nonvalidating/load-external-dtd",
      false
    )
    f
  }

  /** Parse a plist from bytes: binary (`bplist00` magic) or XML.
    *
    * @param bytes
    *   the plist bytes (bounded: callers should enforce [[MaxPlistBytes]]; the
    *   facade also refuses larger inputs)
    * @return
    *   the parsed JValue, or None when the input is not a plist or is malformed
    *   — never an exception
    */
  def parse(bytes: Array[Byte]): Option[JValue] = {
    if (bytes == null || bytes.length == 0) return None
    if (bytes.length > MaxPlistBytes) return None
    if (
      bytes.length >= 8 && startsWith(bytes, bplistHeader.getBytes("US-ASCII"))
    )
      parseBinary(bytes)
    else parseXml(bytes)
  }

  private def startsWith(bytes: Array[Byte], prefix: Array[Byte]): Boolean = {
    if (bytes.length < prefix.length) false
    else {
      var i = 0
      while (i < prefix.length && bytes(i) == prefix(i)) i += 1
      i == prefix.length
    }
  }

  // ------------------------------------------------------------------
  // Binary plist (bplist00)
  // ------------------------------------------------------------------

  /** Parse a binary plist.
    *
    * Layout: 8-byte header ("bplist00"), the object stream, then the 32-byte
    * trailer at the *end* of the buffer:
    *
    * {{{
    *   bytes[length-32 .. length-27]  unused (6 bytes, zero)
    *   bytes[length-26]               offsetSize   (1 byte)
    *   bytes[length-25]               objectRefSize(1 byte)
    *   bytes[length-24 .. length-17]  numObjects   (8 bytes BE)
    *   bytes[length-16 .. length-9]   topObject    (8 bytes BE)
    *   bytes[length-8  .. length-1]   offsetTableOffset (8 bytes BE)
    * }}}
    *
    * The offset table at `offsetTableOffset` holds `numObjects` entries of
    * `offsetSize` bytes, each an absolute offset to an object's marker byte.
    */
  private def parseBinary(bytes: Array[Byte]): Option[JValue] = {
    val n = bytes.length
    if (n < 8 + 32) return None
    val trailer = n - 32
    val offsetSize = bytes(trailer + 6) & 0xff
    val objectRefSize = bytes(trailer + 7) & 0xff
    val numObjects = readBE(bytes, trailer + 8, 8)
    val topObject = readBE(bytes, trailer + 16, 8)
    val offsetTableOffset = readBE(bytes, trailer + 24, 8)

    // Bound checks BEFORE allocation (plan §2.2: allocation-time caps).
    if (numObjects < 0 || numObjects > MaxObjectCount) return None
    if (topObject < 0 || topObject >= numObjects) return None
    if (offsetSize < 1 || offsetSize > 8) return None
    if (objectRefSize < 1 || objectRefSize > 8) return None
    val tableBytes = numObjects.toLong * offsetSize.toLong
    if (offsetTableOffset < 0 || offsetTableOffset + tableBytes > n) return None

    // Read the offset table.
    val offsets = new Array[Long](numObjects.toInt)
    var i = 0
    while (i < numObjects) {
      val off = readBE(
        bytes,
        (offsetTableOffset + (i.toLong * offsetSize.toLong)).toInt,
        offsetSize
      )
      if (off < 0 || off >= trailer - 1) return None
      offsets(i) = off
      i += 1
    }

    val resolved = new mutable.HashSet[Long]()
    resolveObject(bytes, offsets, objectRefSize, topObject.toInt, resolved, 0)
      .map(_._1)
  }

  /** Read a big-endian unsigned integer of `size` bytes as a Long. Returns -1
    * when out of range or size > 8.
    */
  private def readBE(bytes: Array[Byte], at: Int, size: Int): Long = {
    if (size < 1 || size > 8) return -1
    if (at < 0 || at + size > bytes.length) return -1
    var v = 0L
    var i = 0
    while (i < size) {
      v = (v << 8) | (bytes(at + i) & 0xffL)
      i += 1
    }
    v
  }

  /** Resolve one object from the object table by index, with cycle protection:
    * an object currently being resolved (a cycle) is rejected.
    *
    * @return
    *   (JValue, objectCount consumed in this subtree) — the count is unused by
    *   the parser but kept for future budget accounting.
    */
  private def resolveObject(
      bytes: Array[Byte],
      offsets: Array[Long],
      refSize: Int,
      index: Int,
      resolved: mutable.HashSet[Long],
      depth: Int
  ): Option[(JValue, Int)] = {
    if (depth > MaxDepth) return None
    if (index < 0 || index >= offsets.length) return None
    val objOffset = offsets(index)
    if (resolved.contains(index.toLong)) return None // cycle
    resolved += index.toLong

    try {
      val marker = bytes(objOffset.toInt) & 0xff
      val kind = marker >> 4
      val info = marker & 0x0f
      kind match {
        case 0x0 =>
          info match {
            case 0x0 => Some(JNull -> 1) // null
            case 0x8 => Some(JBool(false) -> 1)
            case 0x9 => Some(JBool(true) -> 1)
            case _   => None // fill is not a real object
          }
        case 0x1 => // integer: 2^info bytes (info 0..3 => 1..8 bytes), signed
          if (info > 3) None
          else {
            val size = 1 << info
            readBE(bytes, objOffset.toInt + 1, size) match {
              case v if v < 0 => None
              case unsigned   =>
                // bplist ints are two's complement: if the sign bit is set,
                // the value is negative.
                val signBit = 1L << (size * 8 - 1)
                val signed =
                  if ((unsigned & signBit) != 0) unsigned - (1L << (size * 8))
                  else unsigned
                Some(JInt(BigInt(signed)) -> 1)
            }
          }
        case 0x2 => // real: 2^info bytes (4 or 8)
          info match {
            case 2 =>
              val bits = readBE(bytes, objOffset.toInt + 1, 4)
              if (bits < 0) None
              else
                Some(
                  JDouble(
                    java.lang.Float.intBitsToFloat(bits.toInt).toDouble
                  ) -> 1
                )
            case 3 =>
              val bits = readBE(bytes, objOffset.toInt + 1, 8)
              if (bits < 0) None
              else Some(JDouble(java.lang.Double.longBitsToDouble(bits)) -> 1)
            case _ => None
          }
        case 0x3
            if info == 3 => // date: 8-byte BE double, seconds since 2001-01-01
          val bits = readBE(bytes, objOffset.toInt + 1, 8)
          if (bits < 0) None
          else {
            val secs = java.lang.Double.longBitsToDouble(bits)
            val iso =
              java.time.Instant
                .ofEpochMilli(((secs + 978307200.0) * 1000.0).toLong)
                .toString
            Some(JString(iso) -> 1)
          }
        case 0x4 => // data: info = length, or 0xf + next int for long data
          val (len, nextPos) = lengthAndPos(bytes, objOffset, info)
          if (len < 0 || nextPos + len > bytes.length) None
          else {
            val data = java.util.Base64.getEncoder.encodeToString(
              java.util.Arrays.copyOfRange(bytes, nextPos, nextPos + len)
            )
            Some(JString(data) -> 1)
          }
        case 0x5 => // ASCII string
          val (len, nextPos) = lengthAndPos(bytes, objOffset, info)
          if (len < 0 || nextPos + len > bytes.length) None
          else
            Some(
              JString(new String(bytes, nextPos, len, "US-ASCII")) -> 1
            )
        case 0x6 => // UTF-16BE string
          val (len, nextPos) = lengthAndPos(bytes, objOffset, info)
          if (len < 0 || nextPos + len * 2 > bytes.length) None
          else {
            val chars = new Array[Char](len)
            var i = 0
            while (i < len) {
              val hi = bytes(nextPos + i * 2) & 0xff
              val lo = bytes(nextPos + i * 2 + 1) & 0xff
              chars(i) = ((hi << 8) | lo).toChar
              i += 1
            }
            // Re-encode through String so surrogate pairs (astral-plane
            // characters) are preserved exactly.
            Some(JString(new String(chars)) -> 1)
          }
        case 0x8 => // UID: info+1 bytes
          val len = info + 1
          if (len < 1 || len > 8) None
          else
            readBE(bytes, objOffset.toInt + 1, len) match {
              case v if v < 0 => None
              case v          => Some(JInt(BigInt(v)) -> 1)
            }
        case 0xa | 0xc => // array or set
          val (count, refsPos) = lengthAndPos(bytes, objOffset, info)
          if (count < 0 || count > MaxObjectCount) None
          else if (refsPos + count.toLong * refSize > bytes.length) None
          else {
            var values = Vector.empty[JValue]
            var i = 0
            while (i < count) {
              val ref = readBE(bytes, refsPos + i * refSize, refSize)
              resolveObject(
                bytes,
                offsets,
                refSize,
                ref.toInt,
                resolved,
                depth + 1
              ) match {
                case Some((jv, _)) => values = values :+ jv
                case None          => return None
              }
              i += 1
            }
            Some(JArray(values.toList) -> 1)
          }
        case 0xd => // dict: info = count; then count key refs, then count value refs
          val (count, refsPos) = lengthAndPos(bytes, objOffset, info)
          if (count < 0 || count > MaxObjectCount) None
          else if (refsPos + (count.toLong * 2 * refSize) > bytes.length) None
          else {
            val fields = new mutable.LinkedHashMap[String, JValue]()
            var i = 0
            while (i < count) {
              val keyRef = readBE(bytes, refsPos + i * refSize, refSize)
              val keyObj = resolveObject(
                bytes,
                offsets,
                refSize,
                keyRef.toInt,
                resolved,
                depth + 1
              )
              keyObj match {
                case Some((JString(key), _)) =>
                  val valRef =
                    readBE(bytes, refsPos + (count + i) * refSize, refSize)
                  resolveObject(
                    bytes,
                    offsets,
                    refSize,
                    valRef.toInt,
                    resolved,
                    depth + 1
                  ) match {
                    case Some((jv, _)) => fields(key) = jv
                    case None          => return None
                  }
                case _ => return None // keys must be strings
              }
              i += 1
            }
            Some(JObject(fields.toList) -> 1)
          }
        case _ => None // unknown object type: fail the plist (per design)
      }
    } finally {
      resolved -= index.toLong
    }
  }

  /** Compute the length and the position after the length field for
    * data/string/array/set/dict markers. When `info < 0xf`, the length is
    * exactly `info`; when `info == 0xf`, the length is the following integer
    * object (marker 0x1n + n bytes, big-endian).
    *
    * @return
    *   (length, position after the length encoding), or (-1, pos) when
    *   malformed
    */
  private def lengthAndPos(
      bytes: Array[Byte],
      objOffset: Long,
      info: Int
  ): (Int, Int) = {
    if (info < 0xf) return (info, objOffset.toInt + 1)
    // 0xf: the next marker must be an integer (kind 0x1)
    val pos = objOffset.toInt + 1
    if (pos >= bytes.length) return (-1, pos)
    val marker = bytes(pos) & 0xff
    if ((marker >> 4) != 0x1) return (-1, pos)
    val intInfo = marker & 0x0f
    if (intInfo > 3) return (-1, pos)
    val len = readBE(bytes, pos + 1, 1 << intInfo)
    if (len < 0 || len > Int.MaxValue) (-1, pos)
    else (len.toInt, pos + 1 + (1 << intInfo))
  }

  // ------------------------------------------------------------------
  // XML plist path
  // ------------------------------------------------------------------

  /** Parse an XML plist with the hardened parser; a benign DOCTYPE (Apple's
    * plist DTD) is stripped and the parse retried, mirroring PomParser.
    */
  private def parseXml(bytes: Array[Byte]): Option[JValue] = {
    val xml = new String(bytes, "UTF-8")
    parseXmlOnce(xml).orElse {
      if (xml.toUpperCase.contains("<!DOCTYPE"))
        parseXmlOnce(stripDoctype(xml))
      else None
    }
  }

  private def parseXmlOnce(xml: String): Option[JValue] = {
    Try {
      val dbf = secureDbf
      val builder = dbf.newDocumentBuilder()
      val doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")))
      val root = doc.getDocumentElement
      if (root.getTagName != "plist") None
      else children(root).flatMap(xmlValue).headOption
    }.toOption.flatten
  }

  /** Convert a plist XML element subtree to JValue.
    */
  private def xmlValue(elem: Element): Option[JValue] = {
    elem.getTagName match {
      case "string" => Some(JString(elem.getTextContent))
      case "integer" =>
        Try(JInt(BigInt(elem.getTextContent.trim))).toOption
      case "real"  => Try(JDouble(elem.getTextContent.trim.toDouble)).toOption
      case "true"  => Some(JBool(true))
      case "false" => Some(JBool(false))
      case "date"  => Some(JString(elem.getTextContent.trim))
      case "data"  => Some(JString(elem.getTextContent.trim))
      case "array" =>
        Some(JArray(children(elem).flatMap(xmlValue)))
      case "dict" =>
        val kids = children(elem)
        val fields = new mutable.LinkedHashMap[String, JValue]()
        var i = 0
        var ok = true
        while (i + 1 < kids.length && ok) {
          val key = kids(i)
          val value = kids(i + 1)
          if (key.getTagName == "key") {
            xmlValue(value) match {
              case Some(jv) => fields(key.getTextContent) = jv
              case None     => ok = false
            }
          } else ok = false
          i += 2
        }
        if (ok) Some(JObject(fields.toList)) else None
      case _ => None
    }
  }

  private def children(parent: Element): List[Element] = {
    val nl: NodeList = parent.getChildNodes
    (0 until nl.getLength).toList.flatMap { i =>
      nl.item(i) match {
        case e: Element => Some(e)
        case _          => None
      }
    }
  }

  /** Strip the DOCTYPE declaration (mirrors PomParser's strip routine): finds
    * "<!DOCTYPE" and removes through the matching ">" handling internal subsets
    * and quoted strings.
    */
  private def stripDoctype(xml: String): String = {
    val start = xml.toUpperCase.indexOf("<!DOCTYPE")
    if (start < 0) return xml
    var pos = start + "<!DOCTYPE".length
    var bracketDepth = 0
    var inQuote: Option[Char] = None
    while (pos < xml.length) {
      val ch = xml.charAt(pos)
      inQuote match {
        case Some(q) =>
          if (ch == q) inQuote = None
        case None =>
          if (ch == '[') bracketDepth += 1
          else if (ch == ']' && bracketDepth > 0) bracketDepth -= 1
          else if (ch == '"' || ch == '\'') inQuote = Some(ch)
          else if (ch == '>' && bracketDepth == 0) {
            val end = pos + 1
            return xml.substring(0, start) + xml.substring(end)
          }
      }
      pos += 1
    }
    xml
  }
}
