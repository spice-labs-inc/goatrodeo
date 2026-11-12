package io.spicelabs.goatrodeo.envelopes
import io.bullet.borer.Cbor
import io.bullet.borer.Codec
import io.bullet.borer.Decoder
import io.bullet.borer.Dom.*
import io.bullet.borer.Encoder
import io.bullet.borer.Writer
import io.bullet.borer.derivation.key
import io.spicelabs.goatrodeo.omnibor.GraphManager

import java.io.IOException
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Failure
import scala.util.Success
import scala.util.Try

trait EncodeCBOR {
  def encodeCBORElement(): Element
  def encodeCBOR(): Array[Byte] = Cbor.encode(encodeCBORElement()).toByteArray
}

trait DecodeCBOR[T] {
  def decodeCBORElement(in: Element): Try[T]
  def decodeCBOR(in: Array[Byte]): Try[T] = {
    Try(decodeCBORElement(Cbor.decode(in).to[Element].value)).flatten
  }
}

/** Contains an MD5 hash
  *
  * @param hash
  *   the hash contained... should be 16 bytes
  */
final case class MD5(hash: Array[Byte]) extends EncodeCBOR {

  override def encodeCBORElement(): Element = {
    MapElem.Sized(
      StringElem("h") -> ArrayElem.Sized(
        hash.map(v => IntElem(v.toInt & 0xff))*
      )
    )
  }

  if (hash.length != 16) {
    throw new Exception(
      f"Failed to create an MD5 hash holder... requires 16 byte hash, but got ${hash.length}"
    )
  }

  /** Override equals to support comparing two arrays
    *
    * @param x
    * @return
    */
  override def equals(x: Any): Boolean = {
    x match {
      case MD5(data) => data.toVector == hash.toVector
      case _         => false
    }
  }

}

object MD5 extends DecodeCBOR[MD5] {

  override def decodeCBORElement(in: Element): Try[MD5] =
    in match {
      case m: MapElem =>
        val ret = for {
          e <- m.apply("h")
          arr <- e match {
            case ae: ArrayElem
                if ae.elems.length == 16 && ae.elems.forall(x =>
                  x match {
                    case IntElem(value) if value >= -127 && value <= 255 => true
                    case _ => false
                  }
                ) =>
              Some(ae)
            case _ => None
          }
        } yield {
          MD5(
            arr.elems
              .map(x => (x.asInstanceOf[IntElem].value & 0xff).toByte)
              .toArray
          )
        }
        ret match {
          case Some(md5) => Success(md5)
          case None =>
            Failure(new IOException(f"Failed to decode ${in} as MD5"))
        }
      case _ =>
        Failure(new IOException(f"Failed to decode ${in} as MD5"))
    }

}

type Position = Long

type MultifilePosition = (Long, Long)
import io.bullet.borer.derivation.MapBasedCodecs.derived

case class DataFileEnvelope(
    version: Int,
    magic: Int,
    previous: Long,
    @key("depends_on") dependsOn: TreeSet[Long],
    @key("built_from_merge") builtFromMerge: Boolean,
    info: TreeMap[String, String]
) derives Codec {
  def encode(): Array[Byte] = Cbor.encode(this).toByteArray
}

object DataFileEnvelope {
  val DataFileEnvelopeVersion = 2
  def build(
      version: Int = DataFileEnvelopeVersion,
      magic: Int = GraphManager.Consts.DataFileMagicNumber,
      previous: Long,
      dependsOn: TreeSet[Long] = TreeSet(),
      builtFromMerge: Boolean,
      info: TreeMap[String, String] = TreeMap()
  ): DataFileEnvelope = DataFileEnvelope(
    version,
    magic,
    previous,
    dependsOn,
    builtFromMerge,
    info
  )

  def decode(bytes: Array[Byte]): DataFileEnvelope = {

    Cbor.decode(bytes).to[DataFileEnvelope].value
  }
}

case class IndexFileEnvelope(
    version: Int,
    magic: Int,
    size: Int,
    @key("data_files") dataFiles: TreeSet[Long],
    encoding: String,
    info: TreeMap[String, String]
) derives Codec {
  def encode(): Array[Byte] = Cbor.encode(this).toByteArray
}

object IndexFileEnvelope {
  val IndexFileEnvelopeVersion = 1
  def build(
      version: Int = IndexFileEnvelopeVersion,
      magic: Int = GraphManager.Consts.IndexFileMagicNumber,
      size: Int,
      dataFiles: TreeSet[Long],
      encoding: String = Blake3IndexKey.encoding,
      info: TreeMap[String, String] = TreeMap()
  ): IndexFileEnvelope = IndexFileEnvelope(
    version = version,
    magic = magic,
    size = size,
    dataFiles = dataFiles,
    encoding = encoding,
    info = info
  )

  def decode(bytes: Array[Byte]): IndexFileEnvelope =
    Cbor.decode(bytes).to[IndexFileEnvelope].value
}

case class ClusterFileEnvelope(
    version: Int,
    magic: Int,
    @key("data_files") dataFiles: Vector[Long],
    @key("index_files") indexFiles: Vector[Long],
    info: TreeMap[String, String],
    // `encoding` is the index key algorithm declaration. BigTent writes it last in
    // the CBOR map and always present for version 4, so it is a plain string here:
    // an `Option` would add a shape the format does not have. See
    // `test_data/bigtent_v4/cluster_0/*.grc`.
    encoding: String
) derives Codec {
  def encode(): Array[Byte] = Cbor.encode(this).toByteArray

}

object ClusterFileEnvelope {
  val ClusterFileEnvelopeVersion = 4

  def build(
      version: Int = ClusterFileEnvelopeVersion,
      magic: Int = GraphManager.Consts.ClusterFileMagicNumber,
      dataFiles: Vector[Long],
      indexFiles: Vector[Long],
      info: TreeMap[String, String] = TreeMap(),
      encoding: String = Blake3IndexKey.encoding
  ): ClusterFileEnvelope = ClusterFileEnvelope(
    version = version,
    magic = magic,
    dataFiles = dataFiles,
    indexFiles = indexFiles,
    info = info,
    encoding = encoding
  )

  /** Refuse an envelope this reader cannot honour.
    *
    * Version 4 only: a version 3 cluster is MD5-keyed and carries pair-shaped items,
    * which this reader would misread rather than reject. And the declared `encoding`
    * is the contract — readers use the algorithm the files name, so an unknown
    * declaration fails the load.
    */
  def validateForRead(env: ClusterFileEnvelope, fileName: String): Unit = {
    if (env.version != ClusterFileEnvelopeVersion) {
      throw new Exception(
        f"Cluster ${fileName} is version ${env.version}; this reader requires version ${ClusterFileEnvelopeVersion}"
      )
    }
    if (env.encoding != Blake3IndexKey.encoding) {
      throw new Exception(
        f"Cluster ${fileName} declares index key encoding '${env.encoding}'; this reader understands only '${Blake3IndexKey.encoding}'"
      )
    }
  }

}
