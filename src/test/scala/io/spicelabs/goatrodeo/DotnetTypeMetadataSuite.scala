package io.spicelabs.goatrodeo
import io.spicelabs.cilantro.AssemblyWalker
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.ByteArrayOutputStream
import java.io.File
import scala.collection.immutable.TreeSet

/** Cilantro 0.3.1 canonical-type JSON surfaced as Item metadata (GRW-6).
  *
  * WHAT: Cilantro's walk yields a `cilantro/type` class entry whose payload is
  * the canonical per-class JSON (`"cilantro-type"` v1). This suite pins that
  * Goat Rodeo surfaces that canonical JSON as metadata on the assembly Item, in
  * the same shape Maven surfaces class-derived structure metadata (module-info
  * etc.) on the JAR Item: accumulated during child processing, stored whole,
  * under a dedicated key.
  *
  * The key is `cilantro:TypeJson` via MetadataKeyConstants.adHoc("cilantro")
  * ("TypeJson"). The value(s) are the canonical JSON strings (one per class in
  * the assembly), stored whole (no budget, no truncation) as
  * TreeSet[StringOrPair] under that key in ItemMetaData.extra.
  *
  * WHY: the canonical JSON is a deterministic fingerprint of each .NET type.
  * Making it readable on the assembly's Item means a consumer can query "what
  * types does this assembly define" and compare type identity across assemblies
  * — which is not possible from the raw blob children alone.
  *
  * LLM note: the assembly Item is the one whose connections include the nuget
  * pURL / is the DotnetFile top-level item. We locate it by finding the Item
  * whose metadata has the canonical JSON key. Fixtures are the real assemblies
  * in test_data/ (Smoke.dll, hackproj.dll).
  */
class DotnetTypeMetadataSuite extends GoatRodeoFunSuite {

  given Configuration = Configuration(
    tempDir = None,
    threads = 4,
    maxRecords = 10000,
    blockList = None,
    fsFilePaths = true
  )

  private val typeJsonKey = MetadataKeyConstants.adHoc("cilantro")("TypeJson")

  private def buildAndFindAssembly(
      path: String
  ): Option[Item] = {
    val wrapper = FileWrapper(new File(path), path, None)
    val store = ToProcess.buildGraphFromArtifactWrapper(wrapper)
    // Find the Item that carries the cilantro:TypeJson metadata (the
    // assembly item after accumulation).
    store
      .keys()
      .iterator
      .flatMap(k => store.read(k))
      .find(item =>
        item.bodyAsItemMetaData.exists(_.extra.contains(typeJsonKey))
      )
  }

  test("type-1 assembly Item metadata carries cilantro:TypeJson") {
    val itemOpt = buildAndFindAssembly("test_data/Smoke.dll")
    assert(
      itemOpt.isDefined,
      "Smoke.dll's assembly Item must carry cilantro:TypeJson metadata"
    )
    val jsonValues = itemOpt.get.bodyAsItemMetaData
      .flatMap(_.extra.get(typeJsonKey))
      .getOrElse(TreeSet.empty[StringOrPair])
    assert(
      jsonValues.nonEmpty,
      "the assembly must have at least one canonical type JSON"
    )
  }

  test(
    "type-2 each canonical JSON is the whole class payload (no truncation)"
  ) {
    val itemOpt = buildAndFindAssembly("test_data/Smoke.dll")
    val jsonValues = itemOpt.get.bodyAsItemMetaData
      .flatMap(_.extra.get(typeJsonKey))
      .getOrElse(TreeSet.empty[StringOrPair])
    // The canonical JSON is a deterministic, versioned object. Spot-check
    // the format marker: it should be a JSON object with a format marker
    // ("cilantro-type" v1) as produced by Cilantro's CanonicalJson.
    jsonValues.foreach { sj =>
      val s = sj.value
      assert(s.startsWith("{"), s"canonical type JSON must be an object: $s")
      assert(
        s.contains("cilantro-type") || s.contains("\"format\""),
        s"canonical type JSON must be the cilantro-type format: ${s.take(200)}"
      )
    }
  }

  test("type-3 every class child's payload matches a stored canonical JSON") {
    val path = "test_data/Smoke.dll"
    val wrapper = FileWrapper(new File(path), path, None)
    val store = ToProcess.buildGraphFromArtifactWrapper(wrapper)
    val assembly = store
      .keys()
      .iterator
      .flatMap(k => store.read(k))
      .find(item =>
        item.bodyAsItemMetaData.exists(_.extra.contains(typeJsonKey))
      )
      .get
    val stored = assembly.bodyAsItemMetaData.get
      .extra(typeJsonKey)
      .map(_.value)

    // The stored canonical JSONs must be exactly the payloads of the
    // assembly's cilantro/type class children. Re-walk the assembly and
    // collect those payloads (the artifact bytes), then compare sets.
    val classPayloads = AssemblyWalker
      .withinAssemblyStream(new File(path)) { entries =>
        entries
          .filter(_.mimeHint.contains("cilantro/type"))
          .map { e =>
            e.processStream { stream =>
              val bos = new ByteArrayOutputStream()
              Helpers.copy(stream, bos)
              new String(bos.toByteArray(), "UTF-8")
            }
          }
      }
      .get
      .toSet

    // The stored value is the \n-joined, sorted canonical JSONs — the
    // whole set of the assembly's class payloads, stored whole.
    val storedValues = assembly.bodyAsItemMetaData.get
      .extra(typeJsonKey)
      .map(_.value)
    assertEquals(storedValues.size, 1)
    val combined = storedValues.head
    val sortedPayloads = classPayloads.toList.sorted
    assertEquals(
      combined,
      sortedPayloads.mkString("\n"),
      "stored value must be the \\n-joined, sorted class payloads, stored whole"
    )
    assert(
      classPayloads.size >= 2,
      s"Smoke.dll should have at least 2 classes; got ${classPayloads.size}"
    )
  }

  test("type-4 hackproj.dll also carries canonical type metadata") {
    val itemOpt = buildAndFindAssembly("test_data/hackproj.dll")
    assert(
      itemOpt.isDefined,
      "hackproj.dll's assembly Item must carry cilantro:TypeJson metadata"
    )
  }
}
