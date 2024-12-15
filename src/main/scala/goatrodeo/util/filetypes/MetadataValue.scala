package io.spicelabs.goatrodeo.util

/**
 * Trait representing the base of an ADT for Metadata values, e.g. from Ruby Gem metadata or Debian .deb control
 *
 * Note that all the actual "values" at the bottom are always String key, String value…
 */
sealed trait MetadataValue {
  type T
  def value: T
}

final case class MetadataString(value: String) extends MetadataValue {
  type T = String
}

final case class MetadataList(value: List[MetadataValue]) extends MetadataValue {
  type T = List[MetadataValue]
}

object MetadataList {
  def toMetadataList(objList: List[Object]): MetadataList = {
    val listed: List[MetadataValue] = objList map { v => v match {
      case s: String => MetadataString(s)
      case m: Map[String, Object] => MetadataMap.toMetadataMap(m)
      case l: List[Object] => MetadataList.toMetadataList(l)
      case other => throw new IllegalArgumentException(s"Unsupported value type '${other.getClass}'") // temporary
    }}
    MetadataList(listed)
  }
}

final case class MetadataMap(value: Map[String, MetadataValue]) extends MetadataValue {
  type T = Map[String, MetadataValue]
}

object MetadataMap {
  def toMetadataMap(objMap: Map[String, Object]): MetadataMap = {
    val mapped: Map[String, MetadataValue] = objMap map { (k, v) => v match {
      case s: String => k -> MetadataString(s)
      case m: Map[String, Object] => k -> MetadataMap.toMetadataMap(m)
      case l: List[Object] => k -> MetadataList.toMetadataList(l)
      case other => throw new IllegalArgumentException(s"Unsupported value type '${other.getClass}'") // temporary
    }}
    MetadataMap(mapped)
  }
}
