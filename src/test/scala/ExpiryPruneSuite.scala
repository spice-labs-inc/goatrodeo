import io.spicelabs.goatrodeo.omnibor.Builder
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair

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

  test("removes too-new files, their containers/build-targets, and prunes dangling edges") {
    val items = Vector(
      item("old", Some(old), EdgeType.containedBy -> "C"),
      item("new", Some(recent), EdgeType.containedBy -> "C", EdgeType.buildsTo -> "A"),
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

  test("keeps everything when nothing is past the cutoff; unknown mtimes are kept") {
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

  test("earliest recorded mtime wins: a blob seen before and after the cutoff is kept") {
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
      Some(ItemMetaData(TreeSet(), TreeSet("application/octet-stream"), 0L, extra))
    )
    val pruned = Builder.pruneExpired(Vector(shared), cutoff)
    assertEquals(pruned.map(_.identifier).toSet, Set("shared"))
  }
}
