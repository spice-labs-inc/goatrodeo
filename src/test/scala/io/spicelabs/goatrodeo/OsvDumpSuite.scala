package io.spicelabs.goatrodeo

import munit.FunSuite

import java.io.File
import scala.util.Try
import org.json4s.*
import org.json4s.native.JsonMethods.*

/** Phase 1 — OSV raw dump validation (spec §2, T2.x).
  *
  * WHAT: validates the sbt `osvDumpJson` output (`target/osv-dump.json`):
  * it is the resolved dependency set (every configuration), contains the
  * pinned modules, contains the build-tool-only configurations raw (the
  * filtering is the script's job), and is deterministically sorted.
  *
  * WHY: the dependency list must be derived from the resolved set — the
  * build tool is the single source of truth; nothing hand-maintained.
  * The dump being raw (including tool configs) pins that the *script* is
  * the single place the product-config filter lives.
  *
  * LLM note: reads only the task's output file; the task is hooked into
  * `sbt test` via dependsOn so the dump is always fresh.
  */
class OsvDumpSuite extends FunSuite {

  private def dump(): List[Map[String, String]] = {
    val f = new File("target/osv-dump.json")
    assert(f.exists(), "target/osv-dump.json missing — run `sbt osvDumpJson`")
    parse(scala.io.Source.fromFile(f).mkString) match {
      case JArray(items) =>
        items.map {
          case JObject(fs) =>
            fs.collect { case (k, JString(v)) => k -> v }.toMap
          case other => fail(s"bad dump row: $other")
        }
      case other => fail(s"dump not an array: $other")
    }
  }

  test("T2.1 dumpIsValidAndNonEmpty") {
    val rows = dump()
    assert(rows.nonEmpty)
    rows.foreach { r =>
      assert(r.contains("configuration"), s"row missing configuration: $r")
      assert(r.contains("organization"))
      assert(r.contains("name"))
      assert(r.contains("revision"))
    }
  }

  test("T2.2 dumpContainsPinnedModules") {
    val rows = dump()
    val pins = List(
      ("io.spicelabs", "baharat", "0.2.1"),
      ("io.spicelabs", "annatto", "0.3.0"),
      ("io.spicelabs", "cilantro_3", "0.2.1"),
      ("io.spicelabs", "saffron", "0.5.0"),
      ("io.spicelabs", "coordinates", "1.2.1"),
      ("org.eclipse.jgit", "org.eclipse.jgit", "7.3.0.202506031305-r"),
      ("org.xerial", "sqlite-jdbc", "3.53.4.0"),
      ("io.airlift", "aircompressor", "2.0.3"),
      ("at.yawk.lz4", "lz4-java", "1.11.2")
    )
    pins.foreach { case (o, n, v) =>
      assert(
        rows.exists(r => r("organization") == o && r("name") == n && r("revision") == v),
        s"missing pin $o:$n:$v in dump"
      )
    }
  }

  test("T2.3 dumpIsRawIncludingToolConfigs") {
    val rows = dump()
    // The dump is RAW: it must include the build-tool-only configurations,
    // because the product-config filtering lives in the gate script, not
    // the task. If this ever fails, the task is filtering too early.
    val toolConfigs = rows.map(_("configuration")).filter(c =>
      c == "scala-tool" || c == "scala-doc-tool" || c == "scala-repl-tool"
    )
    assert(toolConfigs.nonEmpty, "dump must include tool-only configurations (raw)")
  }

  test("T2.4 dumpIsDeterministicallySorted") {
    val rows = dump()
    val sorted = rows.sortBy(r => (r("configuration"), r("organization"), r("name"), r("revision")))
    assertEquals(rows, sorted, "dump rows must be sorted deterministically")
  }
}