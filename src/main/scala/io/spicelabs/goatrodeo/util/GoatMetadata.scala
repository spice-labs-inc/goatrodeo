package io.spicelabs.goatrodeo.util

import io.bullet.borer.Decoder
import io.bullet.borer.Encoder
import io.spicelabs.goatrodeo.omnibor.StringOrPair

import org.json4s.*
import org.json4s.native.JsonMethods.*

import scala.jdk.OptionConverters.RichOptional
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

object GoatMetadata {
  object Entries {
    given string: Conversion[(String, String), Entries] = pair =>
      Entries(pair(0) -> TreeSet(StringOrPair(pair(1))))

    given jArray: Conversion[(String, JArray), Entries] = pair =>
      Entries(
        pair(0) -> TreeSet(
          StringOrPair("application/json" -> compact(render(pair(1))))
        )
      )

    given pair: Conversion[(String, (String, String)), Entries] = pair =>
      Entries(pair(0) -> TreeSet(StringOrPair(pair(1))))

    given treeSet: Conversion[(String, TreeSet[StringOrPair]), Entries] =
      Entries(_)

    given optionTreeSet
        : Conversion[Option[(String, TreeSet[StringOrPair])], Entries] =
      _.fold(Entries())(treeSet.convert(_))

    given stringOrPair: Conversion[(String, StringOrPair), Entries] = pair =>
      Entries(pair(0) -> TreeSet(pair(1)))

    given option: Conversion[(String, Option[String]), Entries] = pair =>
      Entries(pair(0) -> pair(1).fold(TreeSet[StringOrPair]()) { s =>
        TreeSet(StringOrPair(s))
      })

    given optional: Conversion[(String, java.util.Optional[String]), Entries] =
      pair =>
        Entries(pair(0) -> pair(1).toScala.fold(TreeSet[StringOrPair]()) { s =>
          TreeSet(StringOrPair(s))
        })

    given iterable
        : Conversion[Iterable[(String, TreeSet[StringOrPair])], Entries] =
      iterable => Entries(iterable.to(Seq)*)
  }

  case class Entries(values: (String, TreeSet[StringOrPair])*)

  def apply(entries: Entries*): GoatMetadata =
    new GoatMetadata(TreeMap(entries.flatMap(_.values)*))

  given Encoder[GoatMetadata] = (writer, item) => writer.write(item.values)

  given Decoder[GoatMetadata] = { value =>
    GoatMetadata(
      summon[Decoder[TreeMap[String, TreeSet[StringOrPair]]]].read(value)
    )
  }
}

case class GoatMetadata private (
    values: TreeMap[String, TreeSet[StringOrPair]]
) {
  export values.{size, isEmpty, contains, keySet, apply, get, getOrElse}

  infix def +(entries: GoatMetadata.Entries): GoatMetadata = {
    GoatMetadata(values ++ entries.values)
  }

  infix def ++(right: GoatMetadata): GoatMetadata = {
    var ret = values

    for { (key, value) <- right.values } {
      val nv = ret.get(key) match {
        case None     => value
        case Some(mv) => value ++ mv
      }
      ret = ret + (key -> nv)
    }

    GoatMetadata(ret)
  }
}
