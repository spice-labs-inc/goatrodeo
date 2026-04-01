package io.spicelabs.goatrodeo.util

import io.bullet.borer.Decoder
import io.bullet.borer.Encoder
import io.spicelabs.goatrodeo.omnibor.StringOrPair

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

object GoatMetadata {

  def apply(values: (String, TreeSet[StringOrPair])*): GoatMetadata =
    GoatMetadata(TreeMap(values*))

  given Encoder[GoatMetadata] = (writer, item) => writer.write(item.values)

  given Decoder[GoatMetadata] = { value =>
    GoatMetadata(
      summon[Decoder[TreeMap[String, TreeSet[StringOrPair]]]].read(value)
    )
  }
}

case class GoatMetadata(
    values: TreeMap[String, TreeSet[StringOrPair]] = TreeMap()
) {
  export values.{size, isEmpty, contains, keySet, apply, get, getOrElse}

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
