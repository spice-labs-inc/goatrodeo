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

import com.github.packageurl.PackageURL
import com.typesafe.scalalogging.Logger
import io.bullet.borer.Json
import io.spicelabs.goatrodeo.util.EdgeTypeId
import io.spicelabs.goatrodeo.util.Gitoid
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.PackageUrl

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiFunction
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.collection.parallel.CollectionConverters.VectorIsParallelizable
import scala.jdk.CollectionConverters._

/** An abstract definition of a GitOID Corpus storage backend
  */
trait Storage {

  /** Does the path exist?
    *
    * @param path
    *   the path
    * @return
    *   true if it's known to the storage
    */
  def exists(path: String): Boolean

  /** Read the backend storage, return the bytes of the path if there's
    * something there
    *
    * @param path
    *   the path to the item
    * @return
    *   the bytes if they exist
    */
  def read(path: String): Option[Item]

  /** Write data to the path
    *
    * @param path
    *   the path
    * @param data
    *   the data to write
    * @param context
    *   generate a String containing the context of the call that led to this
    *   write. Used for contention logging
    *
    * @return
    *   the resulting item if merged
    */
  def write(
      path: String,
      opr: Option[Item] => Option[Item],
      context: Item => String
  ): Option[Item]

  /** Release the backing store or close files or commit the database.
    */
  def release(): Unit

  /** Get the count of items in storage, if computable
    *
    * @return
    *   the count
    */
  def size(): Int

  /** Get the keys from storage (if possible)
    *
    * @return
    */
  def keys(): Set[String]

  def contains(identifier: String): Boolean

  /** return only the keys that start with "gitoid:blob:sha256:"
    *
    * @return
    */
  def gitoidKeys(): Set[Gitoid] =
    keys().collect {
      case k if k.startsWith("gitoid:blob:sha256:") => Gitoid(k)
    }

  def destDirectory(): Option[File]

  /** The endpoint for sending package URLs
    *
    * @param purl
    */
  def addPurl(purl: PackageURL): Unit

  /** Get the purls
    *
    * @return
    *   the purls (each a validated canonical pURL string)
    */
  def purls(): TreeSet[PackageUrl]

  def emitRootsToDir(dir: File): Unit = {
    dir.mkdirs()
    val fileName = f"roots_${System.currentTimeMillis()}.json"
    val file = new File(dir, fileName)
    Storage.logger.debug(f"About to dump roots to ${file}")
    val rootItems = for {
      key <- keys().toVector
      item <- read(key) if item.isRoot()
    } yield item.identifierString

    Files.writeString(file.toPath(), Json.encode(rootItems).toUtf8String)
  }

  def emitAllItemsToDir(dir: File): Unit = {
    dir.mkdirs()
    val fileName = f"items_${System.currentTimeMillis()}.json"
    val file = new File(dir, fileName)
    Storage.logger.debug(f"About to emit all items as JSON to ${file}")
    val fos = new FileOutputStream(file)
    val bos = new BufferedOutputStream(fos)
    bos.write("[\n".getBytes("UTF-8"))
    val rootItems = for {
      (key, idx) <- keys().toVector.zipWithIndex
      item <- read(key)
    } {
      if (idx != 0) {
        bos.write(",\n".getBytes("UTF-8"))
      }
      bos.write(Json.encode(item).toByteArray)

    }

    bos.write("]\n".getBytes("UTF-8"))
    bos.flush()
    bos.close()
  }
}

/** Can the filenames be listed?
  */
trait ListFileNames extends Storage {

  /** A list of all the paths in the backing store, sorted
    *
    * @return
    *   the paths, sorted
    */
  def sortedPaths(): Vector[String] = keys().toVector.sorted

  /** All the paths in the backing store and the MD5 hash of the path. Sorted by
    * MD5 hash
    *
    * @return
    *   sorted vector of Tuples (MD5 of the path, the path)
    */
  def pathsSortedWithMD5(): Vector[(String, String)]

  /** Prepare the store's contents for a v3 streamed write.
    *
    * Performs three things:
    *   1. **Alias-class detection.** Runs union-find across `alias:from` /
    *      `alias:to` edges to group identifiers that refer to the same
    *      content into equivalence classes.
    *   2. **Item collapse.** For every class with two or more identifiers
    *      backed by an Item in the store, the alternate-form Items are
    *      merged into a single canonical Item via `Item.merge`, and the
    *      alternate Items are dropped from the output. Singleton classes
    *      and classes with only one Item-backed member are left alone (the
    *      alias relationships still get recorded in the table, but no
    *      Item-level merging happens). Choice of canonical prefers the
    *      `gitoid:blob:sha256:` form, falling back to the lex-smallest
    *      Item-backed identifier.
    *   3. **Alias-edge stripping + alias map.** From every Item that
    *      participates in a multi-member class, all `alias:from` /
    *      `alias:to` edges are stripped — the equivalence information now
    *      lives in `aliasMap` (`canonical → alternates`). On read,
    *      `GRDWalker` reconstructs the `alias:from` edges from this map so
    *      `Item.connections` round-trips byte-for-byte from the caller's
    *      perspective.
    *
    * The returned `items` are then topologically sorted along forward
    * content edges, identical to the v2 path. */
  def prepareForWrite(): (Vector[Item], TreeMap[String, TreeSet[String]]) = {
    val md5Sorted: Vector[String] = pathsSortedWithMD5().map(_._2)
    val rawItems: Vector[Item] = md5Sorted.flatMap(read)
    if (rawItems.isEmpty) return (Vector.empty, TreeMap.empty)

    // --- 1. Union-find over alias edges ---
    val parent = scala.collection.mutable.HashMap.empty[String, String]
    def find(x: String): String = {
      if (!parent.contains(x)) { parent.update(x, x); return x }
      var cur = x
      while (parent(cur) != cur) cur = parent(cur)
      val root = cur
      cur = x
      while (parent(cur) != root) {
        val nxt = parent(cur)
        parent.update(cur, root)
        cur = nxt
      }
      root
    }
    def union(a: String, b: String): Unit = {
      val ra = find(a); val rb = find(b)
      if (ra != rb) parent.update(ra, rb)
    }

    for (item <- rawItems) {
      val id = item.identifierString
      find(id)
      for ((et, target) <- item.connections.iterator) {
        if (et == EdgeType.aliasFrom || et == EdgeType.aliasTo) {
          find(target); union(id, target)
        }
      }
    }

    // --- 2. Build equivalence classes ---
    val classes =
      scala.collection.mutable.HashMap
        .empty[String, scala.collection.mutable.Set[String]]
    for (k <- parent.keys) {
      classes
        .getOrElseUpdate(find(k), scala.collection.mutable.Set.empty) += k
    }

    val itemById: Map[String, Item] =
      rawItems.iterator.map(it => it.identifierString -> it).toMap

    def pickCanonical(members: Set[String], itemBacked: Set[String]): String =
      if (itemBacked.isEmpty) {
        // shouldn't happen for classes encountered via items, but defensive
        members.min
      } else {
        // Prefer item-backed gitoid:blob:sha256:... form; else lex-smallest item-backed.
        val gitoidBacked = itemBacked.filter(_.startsWith("gitoid:blob:sha256:"))
        if (gitoidBacked.nonEmpty) gitoidBacked.min else itemBacked.min
      }

    // --- 3. Collapse + alias-edge stripping ---
    val aliasMapBuilder = TreeMap.newBuilder[String, TreeSet[String]]
    val collapsed = scala.collection.mutable.HashMap.empty[String, Item]
    val droppedIds = scala.collection.mutable.Set.empty[String]
    val canonicalOf = scala.collection.mutable.HashMap.empty[String, String]

    for ((_, membersMut) <- classes) {
      val members = membersMut.toSet
      val itemBacked = members.filter(itemById.contains)
      if (members.size <= 1 || itemBacked.size <= 1) {
        // Singleton class, or only one Item-backed identifier: no collapse,
        // no alias map entry. Keep the existing Item(s) untouched.
        ()
      } else {
        val canon = pickCanonical(members, itemBacked)
        val baseItem = itemById(canon)
        val others = (itemBacked - canon).iterator.map(itemById)
        val merged =
          others.foldLeft(baseItem)((acc, o) => acc.merge(o))
        collapsed.update(canon, merged)
        for (id <- itemBacked if id != canon) droppedIds += id
        for (m <- members) canonicalOf.update(m, canon)
        val aliases = members - canon
        if (aliases.nonEmpty)
          aliasMapBuilder += (canon -> TreeSet.from(aliases))
      }
    }

    val aliasMap: TreeMap[String, TreeSet[String]] = aliasMapBuilder.result()
    val collapsedCanonicals: Set[String] = collapsed.keySet.toSet

    def stripIfCollapsed(it: Item): Item = {
      val id = it.identifierString
      if (!collapsedCanonicals.contains(id)) it
      else {
        val newConns = it.connections.filterNot { case (et, _) =>
          et == EdgeType.aliasFrom || et == EdgeType.aliasTo
        }
        if (newConns.size == it.connections.size) it
        else it.copy(connections = newConns)
      }
    }

    /** Strip the inverse half of every content-edge pair (`contained:down`
      * = `contains`, and `build:up` = `buildsTo`). With items written in
      * forward topological order, each `A contains B` edge is redundant
      * with the `B containedBy A` edge already encoded on B; same for
      * builds. A consumer that needs reverse lookups should use a
      * cluster-level `reverseEdges` helper, which we can synthesise from
      * the forward edges in one pass. */
    def stripInverseContentEdges(it: Item): Item = {
      val newConns = it.connections.filterNot { case (et, _) =>
        et == EdgeType.contains || et == EdgeType.buildsTo
      }
      if (newConns.size == it.connections.size) it
      else it.copy(connections = newConns)
    }

    val workingItems: Vector[Item] = rawItems.flatMap { it =>
      val id = it.identifierString
      if (droppedIds.contains(id)) None
      else if (collapsed.contains(id))
        Some(stripInverseContentEdges(stripIfCollapsed(collapsed(id))))
      else Some(stripInverseContentEdges(it))
    }

    // --- 4. Topological sort along forward content edges ---
    val items = workingItems
    val n = items.size
    val indexOf: scala.collection.mutable.HashMap[String, Int] =
      scala.collection.mutable.HashMap.empty
    indexOf.sizeHint(n)
    for (i <- 0 until n) indexOf.update(items(i).identifierString, i)

    val successors: Array[scala.collection.mutable.ArrayBuffer[Int]] =
      Array.fill(n)(scala.collection.mutable.ArrayBuffer.empty[Int])
    val inDegree: Array[Int] = Array.fill(n)(0)

    for (i <- 0 until n) {
      val item = items(i)
      for ((edgeType, target) <- item.connections.iterator) {
        if (EdgeTypeId.isForwardContentEdge(edgeType)) {
          indexOf.get(target) match {
            case Some(j) if j != i =>
              successors(j) += i
              inDegree(i) += 1
            case _ => ()
          }
        }
      }
    }

    val ready = scala.collection.mutable.TreeSet.empty[Int]
    for (i <- 0 until n) if (inDegree(i) == 0) ready += i

    val out = scala.collection.mutable.ArrayBuffer.empty[Int]
    out.sizeHint(n)
    def drain(): Unit = {
      while (ready.nonEmpty) {
        val pick = ready.head
        ready.remove(pick)
        out += pick
        for (s <- successors(pick)) {
          val nd = inDegree(s) - 1
          inDegree(s) = nd
          if (nd == 0) ready += s
        }
      }
    }
    drain()
    while (out.size < n) {
      var pick = -1
      var i = 0
      while (i < n && pick == -1) {
        if (inDegree(i) > 0) pick = i
        i += 1
      }
      inDegree(pick) = 0
      ready += pick
      drain()
    }

    val resultBuilder = Vector.newBuilder[Item]
    resultBuilder.sizeHint(n)
    for (idx <- out) resultBuilder += items(idx)
    (resultBuilder.result(), aliasMap)
  }

  /** Items in a deterministic order suitable for the v2 streamed write path.
    *
    * For every "forward content edge" (`contained:up` / `build:down` per
    * `EdgeTypeId.isForwardContentEdge`) `A -> B`, item `B` appears strictly
    * before item `A` in the result. Items not reachable via content edges are
    * placed by their `pathsSortedWithMD5` order (the v1 tie-breaker), so the
    * output is reproducible bit-for-bit across runs on the same input.
    *
    * Edges whose target identifier is not itself an Item in the store
    * (synthetic alias targets, external references) are ignored for sort
    * purposes — they'll be encoded as `WireEdge.External` at write time.
    *
    * Cycles (self-loops or genuine bidirectional content edges through
    * pathological data) are broken by MD5 tie-break: when no item has zero
    * outstanding dependencies, the next item is picked by lowest MD5 among
    * those remaining.
    */
  def topologicallySortedForWrite(): Vector[Item] = prepareForWrite()._1

  /** The target output filename for the Storage
    *
    * @return
    */
  def target(): Option[File]

}

/** A helper/companion to Storage
  */
object Storage {
  private val logger = Logger(getClass())

  /** Based on criteria, return the appropriate storage instance
    *
    * @param inMem
    *   store in-memory
    * @param dbLoc
    *   the location of the SQLite database
    * @param fsLoc
    *   the filesystem location for file store and InMemory target
    * @return
    *   an appropriate storage instance
    */
  def getStorage(
      fsLoc: Option[File]
  ): Storage = {
    fsLoc match {
      case target => MemStorage.getStorage(target)
    }
  }
}

class MemStorage(val targetDir: Option[File])
    extends Storage
    with ListFileNames {

  private val logger = Logger(getClass())

  // The item store. `ConcurrentHashMap.compute` gives us atomic
  // read-modify-write per key under a bucket-level lock — replacing the
  // old `AtomicReference[Map[String, Item]]` + global `dbSync` +
  // per-path `locks: HashMap[String, AtomicInteger]` setup. Each write
  // touches a single bucket instead of allocating a fresh immutable
  // Map on every update, and contention is naturally striped across
  // the table's internal segments.
  private val db: ConcurrentHashMap[String, Item] =
    new ConcurrentHashMap[String, Item]()

  private val thePurls: AtomicReference[TreeSet[PackageUrl]] =
    AtomicReference(TreeSet())

  override def contains(identifier: String): Boolean =
    db.containsKey(identifier)

  override def destDirectory(): Option[File] = targetDir

  def keys(): Set[String] = db.keySet().asScala.toSet

  override def pathsSortedWithMD5(): Vector[(String, String)] = {
    keys().toVector.par
      .map(k => (Helpers.md5hashHex(k), k))
      .toArray
      .sorted
      .toVector
  }

  /** The endpoint for sending package URLs
    *
    * @param purl
    */
  def addPurl(purl: PackageURL): Unit = {
    thePurls.synchronized {
      val next = thePurls.get() + PackageUrl(purl)
      thePurls.set(next)
    }
  }

  def purls(): TreeSet[PackageUrl] = thePurls.get()

  override def size(): Int = db.size()

  override def target(): Option[File] = targetDir

  override def exists(path: String): Boolean = db.containsKey(path)

  override def read(path: String): Option[Item] = Option(db.get(path))

  override def write(
      path: String,
      opr: Option[Item] => Option[Item],
      context: Item => String
  ): Option[Item] = {
    // `compute` holds the bucket lock for `path` for the duration of
    // the remapping function; calls for the same path serialise here
    // exactly as the old per-path AtomicInteger lock did, but without
    // the global sync. Calls for different paths run in parallel
    // (striped across the table's internal segments).
    //
    // The `context` callback is kept in the signature for source
    // compatibility — the old contention-logging machinery it fed
    // (waiter counts on `locks: HashMap[String, AtomicInteger]`) no
    // longer exists.
    val _ = context
    val ref = new AtomicReference[Option[Item]](None)
    // CHM permits a null return from the BiFunction to mean "remove
    // the entry" (it explicitly forbids null values otherwise). With
    // `-Yexplicit-nulls` we have to cast the deletion case manually.
    val remap: BiFunction[String, Item, Item] = (_, current) => {
      val updated = opr(Option(current))
      ref.set(updated)
      updated match {
        case Some(item) => item
        case None       => null.asInstanceOf[Item]
      }
    }
    db.compute(path, remap)
    ref.get()
  }

  def release(): Unit = db.clear()

  def getPurls(): Set[PackageUrl] = {
    thePurls.synchronized {
      thePurls.get()
    }
  }

  def getKeys(): Set[String] = keys()

  def containsID(identifier: String): Boolean = contains(identifier)
}

/** Deal with in-memory storage
  */
object MemStorage {

  /** Get an InMem storage instance
    *
    * @param targetDir
    *   the optional target directory for post-processing output
    * @return
    *   the storage directory
    */
  def getStorage(targetDir: Option[File]): Storage & ListFileNames = {

    MemStorage(targetDir)
  }
}
