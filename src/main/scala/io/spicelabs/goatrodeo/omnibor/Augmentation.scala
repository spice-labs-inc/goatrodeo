package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.util.Helpers

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Data that augments an Item
  */
sealed trait Augmentation {
  def augment(item: Item): Item
  def hashValue: String
}

/** Something that augments the `ItemMetaData` `extra` field
  *
  * @param name
  *   the name (e.g., "static-metadata-all")
  * @param data
  *   the information to store in the extra field
  */
final case class TopLevelExtraAugmentation(
    hashValue: String,
    name: String,
    data: StringOrPair
) extends Augmentation {
  def augment(item: Item): Item = {
    item.bodyMimeType match {
      case None | Some(ItemMetaData.mimeType) =>
        item.copy(
          bodyMimeType = Some(ItemMetaData.mimeType),
          body = {
            val orgBody = item.body match {
              case Some(imd: ItemMetaData) => imd
              case _ =>
                ItemMetaData(
                  fileNames = TreeSet(),
                  mimeType = TreeSet(),
                  fileSize = 0,
                  extra = TreeMap()
                )
            }

            Some(
              orgBody.copy(extra =
                Helpers.mergeTreeMaps(
                  orgBody.extra,
                  TreeMap(name -> TreeSet(data))
                )
              )
            )
          }
        )
      case _ => item
    }
  }
}

/** Augmentation for connections
  *
  * @param hashValue
  *   the hash value (sha1, sha256, etc.)
  * @param connection
  *   the connection to add (e.g., a pURL)
  */
final case class ConnectionAugmentation(
    hashValue: String,
    connection: (String, String)
) extends Augmentation {
  def augment(item: Item): Item = {
    item.copy(connections = item.connections + connection)
  }
}

/** Augmentation for metadata
  *
  * @param hashValue
  *   the hash value (sha1, sha256, etc.)
  * @param name
  *   the key in the `ItemMetaData.extra` field
  * @param data
  *   the data for the field
  */
final case class ExtraAugmentation(
    hashValue: String,
    name: String,
    data: StringOrPair
) extends Augmentation {
  def augment(item: Item): Item = {
    item.bodyMimeType match {
      case None | Some(ItemMetaData.mimeType) =>
        item.copy(
          bodyMimeType = Some(ItemMetaData.mimeType),
          body = {
            val orgBody = item.body match {
              case Some(imd: ItemMetaData) => imd
              case _ =>
                ItemMetaData(
                  fileNames = TreeSet(),
                  mimeType = TreeSet(),
                  fileSize = 0,
                  extra = TreeMap()
                )
            }

            Some(
              orgBody.copy(extra =
                Helpers.mergeTreeMaps(
                  orgBody.extra,
                  TreeMap(name -> TreeSet(data))
                )
              )
            )
          }
        )
      case _ => item
    }
  }
}
