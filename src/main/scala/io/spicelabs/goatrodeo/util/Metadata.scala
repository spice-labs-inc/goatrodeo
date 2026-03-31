package io.spicelabs.goatrodeo.util

import io.bullet.borer.Decoder
import io.bullet.borer.Encoder
import io.spicelabs.goatrodeo.omnibor.StringOrPair

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

object GoatMetadata {
  given Encoder[GoatMetadata] = (writer, item) => writer.write(item.values)

  given Decoder[GoatMetadata] = { value =>
    GoatMetadata(summon[Decoder[TreeMap[String, TreeSet[StringOrPair]]]].read(value))
  }
}

case class GoatMetadata(values: TreeMap[String, TreeSet[StringOrPair]] = TreeMap()) {
  export values.{size, isEmpty, contains, keySet, apply}
  infix def ++ (right: GoatMetadata): GoatMetadata = {
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

  def get(key: String): Option[TreeSet[StringOrPair]] = values.get(key)

  def getOrElse(key: String, default: TreeSet[StringOrPair]): TreeSet[StringOrPair] = {
    values.getOrElse(key, default)
  }
}
