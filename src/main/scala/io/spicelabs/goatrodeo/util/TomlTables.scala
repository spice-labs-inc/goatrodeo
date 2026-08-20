package io.spicelabs.goatrodeo.util

import org.tomlj.TomlArray
import org.tomlj.TomlPosition
import org.tomlj.TomlTable

import java.util.{List as JList, Map as JMap, Set as JSet}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Views a plain nested map as a [[TomlTable]].
  *
  * WHY: `spice` hands a plugin its slice of the config file as a `Map[String,
  * Object]` — the TOML data model expressed in `java.*` types, so the plugin
  * API stays dependency-free and imposes no TOML library on plugin authors.
  * Everything on this side is already written against `TomlTable`, so rather
  * than maintain a second reader for maps, adapt the map back. One reader, one
  * schema, whether the table came from a file or across the SPI.
  *
  * `TomlTable` and `TomlArray` are interfaces whose useful accessors
  * (`getString`, `getLong`, `getTable`, `dottedKeySet`, …) are all `default`
  * methods built on a handful of abstract ones, so this is a small amount of
  * mechanical code rather than a reimplementation.
  *
  * The one method with no honest answer is `inputPositionOf`: a map has no
  * source position. It returns a sentinel, which costs only line numbers in
  * error messages for configuration supplied through the SPI — the messages
  * still name the offending key.
  */
object TomlTables {

  /** A table built from a map has no source position. tomlj requires line and
    * column to be >= 1, so this is the smallest legal value rather than a
    * negative sentinel; it only affects line numbers in error messages for
    * configuration supplied through the SPI, which still name the key.
    */
  private val noPosition: TomlPosition = TomlPosition.positionAt(1, 1)

  /** Convert a table to plain `java.*` values, for handing across the plugin
    * SPI.
    *
    * WHY this exists rather than `TomlTable.toMap()`: that method is
    * **shallow**. A nested table comes back as an `org.tomlj.MutableTomlTable`,
    * so passing its result across the SPI would leak tomlj types through a
    * boundary that promises only `java.*` types — and would do so silently for
    * a flat config, failing only once someone nests a table. This converts all
    * the way down.
    */
  def toPlainMap(table: TomlTable): JMap[String, Object] =
    table
      .toMap()
      .asScala
      .map { case (k, v) => k -> plain(v) }
      .toMap
      .asJava

  private def plain(value: Any): Object = value match {
    case t: TomlTable => toPlainMap(t)
    case a: TomlArray => a.toList().asScala.toVector.map(plain).asJava
    case other        => other.asInstanceOf[Object]
  }

  /** Adapt a nested Java map — what the plugin SPI hands over — to a
    * [[TomlTable]].
    */
  def fromJavaMap(map: JMap[String, Object]): TomlTable =
    MapTable(map.asScala.toMap.map { case (k, v) => k -> (v: Any) })

  /** Adapt a nested Scala map to a [[TomlTable]]. */
  def fromMap(map: Map[String, Any]): TomlTable = MapTable(map)

  /** Values arrive as plain maps and lists; wrap them so nested tables and
    * arrays behave like the real thing.
    */
  private def wrap(value: Any): Any = value match {
    case t: TomlTable => t
    case a: TomlArray => a
    case m: JMap[?, ?] =>
      MapTable(m.asScala.toMap.map { case (k, v) => k.toString -> (v: Any) })
    case m: Map[?, ?] =>
      MapTable(m.map { case (k, v) => k.toString -> (v: Any) })
    case l: JList[?] => ListArray(l.asScala.toVector.map(v => v: Any))
    case l: Seq[?]   => ListArray(l.toVector.map(v => v: Any))
    case other       => other
  }

  /** Text that names a number or a boolean, read as one *when one is asked
    * for*.
    *
    * The environment has no types: every value in it is a string, and nothing
    * can say "this one is a count" at the point it is read out of the
    * environment. The caller can, though — it is asking for a `Long` — so the
    * coercion happens per accessor rather than on the way in. Doing it on the
    * way in turned `tag = "42"` into a number and made it unreadable as text.
    *
    * Safe here in a way it would not be for real TOML: this adapter never sees
    * a TOML file, only maps handed over by a host program or a resolver, where
    * a string saying `16` came from somewhere that could not have written 16.
    */
  private def asLong(value: Any): java.lang.Long | Null = value match {
    case l: java.lang.Long => l
    case s: String =>
      Try(java.lang.Long.valueOf(s.trim)).toOption.getOrElse(null)
    case _ => null
  }

  private def asDouble(value: Any): java.lang.Double | Null = value match {
    case d: java.lang.Double => d
    case l: java.lang.Long   => java.lang.Double.valueOf(l.doubleValue())
    case s: String =>
      Try(java.lang.Double.valueOf(s.trim)).toOption.getOrElse(null)
    case _ => null
  }

  private def asBoolean(value: Any): java.lang.Boolean | Null = value match {
    case b: java.lang.Boolean => b
    case s: String =>
      s.trim.toLowerCase match {
        case "true"  => java.lang.Boolean.TRUE
        case "false" => java.lang.Boolean.FALSE
        case _       => null
      }
    case _ => null
  }

  private case class MapTable(entries: Map[String, Any]) extends TomlTable {

    private lazy val wrapped: Map[String, Any] =
      entries.map { case (k, v) => k -> wrap(v) }

    override def size(): Int = wrapped.size

    override def isEmpty(): Boolean = wrapped.isEmpty

    override def keySet(): JSet[String] = wrapped.keySet.asJava

    override def entrySet(): JSet[JMap.Entry[String, Object]] =
      wrapped
        .map { case (k, v) =>
          JMap.entry(k, v.asInstanceOf[Object])
        }
        .toSet
        .asJava

    override def get(path: JList[String] | Null): Object | Null =
      Option(path).map(_.asScala.toList).getOrElse(Nil) match {
        case Nil        => this
        case key :: Nil => wrapped.get(key).map(_.asInstanceOf[Object]).orNull
        case key :: rest =>
          wrapped.get(key) match {
            case Some(table: TomlTable) => table.get(rest.asJava)
            case _                      => null
          }
      }

    override def getLong(dottedKey: String | Null): java.lang.Long | Null =
      asLong(get(JList.of(dottedKey)))

    override def getLong(path: JList[String] | Null): java.lang.Long | Null =
      asLong(get(path))

    override def getDouble(dottedKey: String | Null): java.lang.Double | Null =
      asDouble(get(JList.of(dottedKey)))

    override def getDouble(path: JList[String] | Null): java.lang.Double |
      Null =
      asDouble(get(path))

    override def getBoolean(dottedKey: String | Null): java.lang.Boolean |
      Null =
      asBoolean(get(JList.of(dottedKey)))

    override def getBoolean(path: JList[String] | Null): java.lang.Boolean |
      Null =
      asBoolean(get(path))

    override def inputPositionOf(path: JList[String] | Null): TomlPosition =
      noPosition

    override def keyPathSet(includeTables: Boolean): JSet[JList[String]] =
      paths(includeTables).map(_.asJava).asJava

    override def entryPathSet(
        includeTables: Boolean
    ): JSet[JMap.Entry[JList[String], Object]] =
      paths(includeTables).map { path =>
        JMap.entry(path.asJava, get(path.asJava).asInstanceOf[Object])
      }.asJava

    override def toMap(): JMap[String, Object] =
      entries.map { case (k, v) => k -> v.asInstanceOf[Object] }.asJava

    /** Dotted key paths, matching tomlj: leaves always, and the tables
      * themselves only when asked for.
      */
    private def paths(includeTables: Boolean): Set[List[String]] =
      wrapped.flatMap {
        case (key, table: TomlTable) => {
          val below = table
            .keyPathSet(includeTables)
            .asScala
            .toSet
            .map((path: JList[String]) => key :: path.asScala.toList)
          if (includeTables) below + List(key) else below
        }
        case (key, _) => Set(List(key))
      }.toSet
  }

  private case class ListArray(values: Vector[Any]) extends TomlArray {

    private lazy val wrapped: Vector[Any] = values.map(wrap)

    override def size(): Int = wrapped.size

    override def isEmpty(): Boolean = wrapped.isEmpty

    override def get(index: Int): Object = wrapped(index).asInstanceOf[Object]

    override def inputPositionOf(index: Int): TomlPosition = noPosition

    /** The wrapped values, like every other accessor here.
      *
      * Returning the raw ones was a bug you could only hit with an array of
      * tables — `[[repositories]]`, say — where a caller doing what tomlj's own
      * contract allows, `toList().get(0).asInstanceOf[TomlTable]`, got a plain
      * Map and a ClassCastException. Every other accessor wrapped; this one did
      * not, and nothing had an array of tables to notice with.
      */
    override def toList(): JList[Object] =
      wrapped.map(_.asInstanceOf[Object]).asJava

    /** TOML arrays are homogeneous, so "contains X" is "every element is an X".
      * An empty array satisfies none of them, matching tomlj.
      */
    private def all(f: Any => Boolean): Boolean =
      wrapped.nonEmpty && wrapped.forall(f)

    override def containsStrings(): Boolean = all(_.isInstanceOf[String])

    override def containsLongs(): Boolean = all(_.isInstanceOf[java.lang.Long])

    override def containsDoubles(): Boolean =
      all(_.isInstanceOf[java.lang.Double])

    override def containsBooleans(): Boolean =
      all(_.isInstanceOf[java.lang.Boolean])

    override def containsOffsetDateTimes(): Boolean =
      all(_.isInstanceOf[java.time.OffsetDateTime])

    override def containsLocalDateTimes(): Boolean =
      all(_.isInstanceOf[java.time.LocalDateTime])

    override def containsLocalDates(): Boolean =
      all(_.isInstanceOf[java.time.LocalDate])

    override def containsLocalTimes(): Boolean =
      all(_.isInstanceOf[java.time.LocalTime])

    override def containsArrays(): Boolean = all(_.isInstanceOf[TomlArray])

    override def containsTables(): Boolean = all(_.isInstanceOf[TomlTable])
  }
}
