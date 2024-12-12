package goatrodeo.util.filetypes

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

final case class MetadataList(value: List[String]) extends MetadataValue {
  type T = List[String]
}

final case class MetadataMap(value: Map[String, String]) extends MetadataValue {
  type T = Map[String, String]
}
