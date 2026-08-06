import io.spicelabs.goatrodeo.omnibor.Builder
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.ConfigurationParser

import java.time.Instant
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class ExpiryPruneSuite extends munit.FunSuite {

  private val cutoff = Instant.parse("2026-01-01T00:00:00Z")
  private val old = Instant.parse("2025-06-01T00:00:00Z")
  private val recent = Instant.parse("2026-06-01T00:00:00Z")

  private def item(
      id: String,
      mtime: Option[Instant],
      edges: (String, String)*
  ): Item = {
    val extra: TreeMap[String, TreeSet[StringOrPair]] = mtime match {
      case Some(t) =>
        TreeMap(
          Item.FileModifiedKey -> TreeSet[StringOrPair](
            StringOf(t.toEpochMilli().toString)
          )
        )
      case None => TreeMap.empty
    }
    Item(
      id,
      TreeSet[(String, String)](edges*),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(),
          mimeType = TreeSet("application/octet-stream"),
          fileSize = 0L,
          extra = extra
        )
      )
    )
  }

  test(
    "removes too-new files, their containers/build-targets, and prunes dangling edges"
  ) {
    val items = Vector(
      item("old", Some(old), EdgeType.containedBy -> "C"),
      item(
        "new",
        Some(recent),
        EdgeType.containedBy -> "C",
        EdgeType.buildsTo -> "A"
      ),
      item("C", None, EdgeType.contains -> "old", EdgeType.contains -> "new"),
      item("A", None, EdgeType.builtFrom -> "new"),
      item("U", Some(old))
    )
    val pruned = Builder.pruneExpired(items, cutoff)

    assertEquals(
      pruned.map(_.identifier).toSet,
      Set("old", "U"),
      "the too-new file, its container C, and the artifact A built from it are all removed"
    )
    val oldItem = pruned.find(_.identifier == "old").get
    assert(
      oldItem.connections.isEmpty,
      "the surviving 'old' file's now-dangling containedBy->C edge is pruned"
    )
  }

  test(
    "keeps everything when nothing is past the cutoff; unknown mtimes are kept"
  ) {
    val items = Vector(
      item("old", Some(old), EdgeType.containedBy -> "C"),
      item("C", None, EdgeType.contains -> "old")
    )
    val pruned = Builder.pruneExpired(items, cutoff)
    assertEquals(pruned.map(_.identifier).toSet, Set("old", "C"))
    assertEquals(
      pruned.find(_.identifier == "old").get.connections.size,
      1,
      "no edges pruned when nothing is removed"
    )
  }

  test(
    "earliest recorded mtime wins: a blob seen before and after the cutoff is kept"
  ) {
    val extra: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap(
        Item.FileModifiedKey -> TreeSet[StringOrPair](
          StringOf(old.toEpochMilli().toString),
          StringOf(recent.toEpochMilli().toString)
        )
      )
    val shared = Item(
      "shared",
      TreeSet.empty[(String, String)],
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(TreeSet(), TreeSet("application/octet-stream"), 0L, extra)
      )
    )
    val pruned = Builder.pruneExpired(Vector(shared), cutoff)
    assertEquals(pruned.map(_.identifier).toSet, Set("shared"))
  }

  // Asserts against `ConfigurationParser.parseExpiry` rather than setting the
  // `goatrodeo.expiry` system property. The parsing rules are what this test is
  // actually about.
  test("parseExpiry parses epoch millis and ISO, and is empty when blank") {
    assertEquals(ConfigurationParser.parseExpiry(""), None)
    assertEquals(ConfigurationParser.parseExpiry("   "), None)

    val millis = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    assertEquals(
      ConfigurationParser.parseExpiry(millis.toString).map(_.toEpochMilli()),
      Some(millis)
    )

    assertEquals(
      ConfigurationParser.parseExpiry("2026-01-01T00:00:00Z").map(_.toString),
      Some("2026-01-01T00:00:00Z")
    )
  }

  test("--expiry is the only route to a cutoff on the command line") {
    val parsed = ConfigurationParser.parse(Array("--expiry", "2026-01-01"))
    assertEquals(
      parsed.flatMap(_.expiry).map(_.toString),
      Some("2026-01-01T00:00:00Z")
    )
  }

  /** WHY: the cutoff used to have a second, ambient source — the
    * `goatrodeo.expiry` system property — which `Howdy.run` consulted but the
    * `GoatRodeoBuilder` library path did not, so the same value behaved
    * differently depending on how Goat Rodeo was invoked. It now arrives only
    * via `--expiry` or `withExpiry`. This guards against reintroducing it.
    *
    * Mutating the property here is safe precisely because nothing reads it any
    * more: with no reader there is no cross-test coupling, which is what made
    * the old `expiryFromProperty` test constrain how this suite could be
    * scheduled.
    */
  test("no system property can set the expiry") {
    val prop = "goatrodeo.expiry"
    val saved = Option(System.getProperty(prop))
    try {
      System.setProperty(prop, "2026-01-01T00:00:00Z")
      assertEquals(ConfigurationParser.parse(Array()).flatMap(_.expiry), None)
      assertEquals(Configuration().expiry, None)
    } finally
      saved match {
        case Some(v) => System.setProperty(prop, v)
        case None    => System.clearProperty(prop)
      }
  }
}
