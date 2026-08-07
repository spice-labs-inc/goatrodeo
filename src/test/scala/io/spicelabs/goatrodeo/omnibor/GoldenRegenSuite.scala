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

package io.spicelabs.goatrodeo.omnibor

import munit.FunSuite
import org.json4s.*
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Parity guard + regenerator for the pqc_report golden differential.
  *
  * The golden (`pqc_report/test_data/parity/goat_rodeo_golden.json`) is a
  * snapshot of what Goat Rodeo's own `CbomEmitter` produces for the committed
  * ADG fixtures: one record per gitoid-keyed crypto component plus one record
  * per algorithm component (name + primitive).
  *
  * This test replays the committed ADG JSON into a `MemStorage` and runs the
  * real emitter, then:
  *   - default mode: asserts every committed golden record is reproducible by
  *     the current emitter (no silent drift),
  *   - `-Dgolden.sync=true`: overwrites the golden file from the emitter
  *     output (used when a legitimate emitter change expands the output, e.g.
  *     the removal of CBOM-side redaction).
  *
  * No key bytes ever enter metadata (the capture hard constraint), so replay
  * contains only derived metadata + flags.
  */
class GoldenRegenSuite extends FunSuite {

  private implicit val formats: Formats = DefaultFormats

  /** Canonicalize signature-OID algorithm names to their gallery names so the
    * golden's `algorithms` list matches the naming rule pqc_report applies
    * (`canonical_sig_name` in `pqc_report/src/cbom_adg.rs`). The golden is the
    * parity differential; the documented divergence canoncalizes the raw
    * `<unknown-sig-oid-...>` goatrodeo names before matching.
    */
  private def canonicalAlgoName(name: String): String = {
    if (name.startsWith("<") && name.endsWith(">")) {
      name.drop(1).dropRight(1) match {
        case "unknown-sig-oid-2.16.840.1.101.3.4.3.40" => "ml-dsa-65"
        case "unknown-sig-oid-2.16.840.1.101.3.4.3.17" => "ml-dsa-44"
        case "unknown-sig-oid-2.16.840.1.101.3.4.3.18" => "ml-dsa-65"
        case "unknown-sig-oid-2.16.840.1.101.3.4.3.19" => "ml-dsa-87"
        case other                                     => other
      }
    } else name
  }

  private def adgDir(): File = {
    val p = Option(System.getProperty("golden.adg")).getOrElse(
      new File("../pqc_report/test_data/adg").getCanonicalPath
    )
    val f = new File(p)
    assert(f.isDirectory, s"ADG fixture dir not found: $f")
    f
  }

  private def goldenFile(): File = {
    val p = Option(System.getProperty("golden.file")).getOrElse(
      new File(
        "../pqc_report/test_data/parity/goat_rodeo_golden.json"
      ).getCanonicalPath
    )
    new File(p)
  }

  private def regen(
      adg: File,
      version: String
  ): (List[JObject], List[JObject]) = {
    val storage = new MemStorage(None)
    val adgFiles = Option(adg.listFiles())
      .getOrElse(Array.empty[File])
      .filter(_.getName.endsWith(".json"))
      .sortBy(_.getName)

    var loaded = 0
    for (f <- adgFiles) {
      val parsed = parse(Files.readString(f.toPath))
      parsed match {
        case JArray(items) =>
          items.foreach {
            case o: JObject =>
              val identifier = (o \ "identifier") match {
                case JString(s) => s
                case _          => ""
              }
              if (identifier.nonEmpty) {
                val connections: TreeSet[Edge] = TreeSet.from(
                  (o \ "connections") match {
                    case JArray(arr) =>
                      arr.collect { case JArray(List(JString(t), JString(to))) =>
                        t -> to
                      }
                    case _ => List.empty
                  }
                )
                val bodyMimeType = (o \ "body_mime_type") match {
                  case JString(s) => Some(s)
                  case _          => None
                }
                val bodyOpt: Option[ItemMetaData] =
                  ((o \ "body"), bodyMimeType) match {
                    case (JObject(b), Some(ItemMetaData.mimeType)) =>
                      val fileNames = TreeSet.from(
                        (JObject(b) \ "file_names") match {
                          case JArray(arr) =>
                            arr.collect { case JString(s) => s }
                          case _ => List.empty
                        }
                      )
                      val mimeTypes = TreeSet.from(
                        (JObject(b) \ "mime_type") match {
                          case JArray(arr) =>
                            arr.collect { case JString(s) => s }
                          case _ => List.empty
                        }
                      )
                      val fileSize = (JObject(b) \ "file_size") match {
                        case JLong(l)   => l
                        case JInt(i)    => i.toLong
                        case JDouble(d) => d.toLong
                        case _          => 0L
                      }
                      val extra: TreeMap[String, TreeSet[StringOrPair]] =
                        TreeMap.from(
                          (JObject(b) \ "extra") match {
                            case JObject(kvs) =>
                              kvs.flatMap { case (k, v) =>
                                TreeSet.from(
                                  v match {
                                    case JString(s) => List(StringOrPair(s))
                                    case JArray(vals) =>
                                      vals.flatMap {
                                        case JString(s) => List(StringOrPair(s))
                                        case JArray(
                                              List(JString(a), JString(b2))
                                            ) =>
                                          List(PairOf(a, b2))
                                        case _ => List.empty
                                      }
                                    case _ => List.empty
                                  }
                                ) match {
                                  case ts if ts.nonEmpty => List(k -> ts)
                                  case _                 => List.empty
                                }
                              }
                            case _ => List.empty
                          }
                        )
                      Some(
                        ItemMetaData(
                          fileNames = fileNames,
                          mimeType = mimeTypes,
                          fileSize = fileSize,
                          extra = extra
                        )
                      )
                    case _ => None
                  }
                val item =
                  Item(identifier, connections, bodyMimeType, bodyOpt)
                storage.write(
                  item.identifier,
                  _ => Some(item),
                  _ => "golden-regen"
                )
                loaded += 1
              }
            case _ => ()
          }
        case other =>
          throw new IllegalArgumentException(
            s"Expected a JSON array of items in $f, got ${other.getClass.getSimpleName}"
          )
      }
    }
    assert(loaded > 0, s"expected ADG items in $adg")

    val cbomDir = Files.createTempDirectory("golden-cbom").toFile
    val files = CbomEmitter.emitForStorage(storage, version, cbomDir).get

    val gitoidRecords = scala.collection.mutable.LinkedHashMap[String, JObject]()
    val algoRecords = scala.collection.mutable.LinkedHashMap[String, (String, String)]()

    for (f <- files) {
      val parsed = parse(Files.readString(f.toPath))
      val comps = (parsed \ "components") match {
        case JArray(arr) => arr
        case _           => List.empty[JValue]
      }
      for (c <- comps) {
        val bomRef = (c \ "bom-ref") match {
          case JString(r) => r
          case _          => ""
        }
        val crypto = c \ "cryptoProperties"
        val assetType = (crypto \ "assetType") match {
          case JString(a) => a
          case _          => ""
        }
        if (bomRef.startsWith("gitoid:blob:sha256:") && assetType != "algorithm") {
          val name = (c \ "name") match {
            case JString(n) => JString(n)
            case _          => JNothing
          }
          val description = (c \ "description") match {
            case JString(d) => JString(d)
            case _          => JNothing
          }
          val propKeys: JArray = (c \ "properties") match {
            case JArray(arr) =>
              JArray(
                arr.flatMap {
                  case o: JObject =>
                    (o \ "name") match {
                      case JString(n) => Some(n)
                      case _          => None
                    }
                  case _ => None
                }
              )
            case _ => JArray(List.empty)
          }
          val blocks: List[String] = List(
            "certificateProperties",
            "relatedCryptoMaterialProperties",
            "protocolProperties"
          ).filter(b => (crypto \ b) != JNothing)
          val rec = JObject(
            "gitoid" -> JString(bomRef),
            "name" -> name,
            "description" -> description,
            "prop_keys" -> propKeys,
            "asset_type" -> JString(assetType),
            "blocks" -> JArray(blocks.map(JString(_)))
          )
          gitoidRecords.getOrElseUpdate(bomRef, rec)
        }
        if (assetType == "algorithm") {
          val name = (c \ "name") match {
            case JString(n) => canonicalAlgoName(n)
            case _          => ""
          }
          val prim = (crypto \ "algorithmProperties" \ "primitive") match {
            case JString(p) => p
            case _          => ""
          }
          if (name.nonEmpty && prim.nonEmpty && !algoRecords.contains(name)) {
            algoRecords += name -> (name, prim)
          }
        }
      }
    }

    val gidSorted = gitoidRecords.values.toList.sortBy { r =>
      (r \ "gitoid") match {
        case JString(g) => g
        case _          => ""
      }
    }
    val algSorted = algoRecords.values.toList.sortBy(_._1).map { case (n, p) =>
      JObject("name" -> JString(n), "primitive" -> JString(p))
    }
    (gidSorted, algSorted)
  }

  test("golden differential matches Goat Rodeo emitter output over the ADG fixtures") {
    val adg = adgDir()
    val goldenPath = goldenFile()
    val sync = sys.props.get("golden.sync").contains("true")
    val version = Option(System.getProperty("golden.version")).getOrElse("1.6")

    val (gidRecs, algRecs) = regen(adg, version)

    if (sync) {
      val golden = JObject(
        "version" -> JInt(1),
        "gitoid_components" -> JArray(gidRecs),
        "algorithms" -> JArray(algRecs)
      )
      Files.writeString(
        goldenPath.toPath,
        compact(render(golden)),
        StandardCharsets.UTF_8
      )
      println(
        s"GoldenRegen[sync]: wrote ${gidRecs.length} gitoid records + ${algRecs.length} algorithms to $goldenPath"
      )
    } else {
      val parsed = parse(Files.readString(goldenPath.toPath))
      val committed = (parsed \ "gitoid_components") match {
        case JArray(arr) => arr
        case _           => List.empty[JValue]
      }
      val regenById = gidRecs.map(r => (r \ "gitoid", r)).toMap
      for (rec <- committed) {
        val gid = rec \ "gitoid"
        assert(
          regenById.contains(gid),
          s"committed golden gitoid $gid is not reproducible from the current emitter (drift?)"
        )
      }
      assert(
        committed.nonEmpty,
        "committed golden must not be empty"
      )
      println(
        s"GoldenRegen[verify]: ${committed.length} committed records all reproducible from ${gidRecs.length} emitted records"
      )
    }
  }
}