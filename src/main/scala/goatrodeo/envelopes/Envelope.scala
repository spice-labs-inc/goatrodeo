package goatrodeo.envelopes

import scala.util.Try
import java.io.DataOutputStream
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import io.bullet.borer.Cbor
import io.bullet.borer.Dom.*
import io.bullet.borer.derivation.key
import scala.util.Success
import scala.util.Failure
import io.bullet.borer.Codec
import goatrodeo.omnibor.GraphManager
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.GZIPInputStream
import java.io.InputStream
import goatrodeo.util.Helpers

trait EncodeCBOR {
  def encodeCBORElement(): Element
  def encodeCBOR(): Array[Byte] = Cbor.encode(encodeCBORElement()).toByteArray
}

trait DecodeCBOR[T] {
  def decodeCBORElement(in: Element): Try[T]
  def decodeCBOR(in: Array[Byte]): Try[T] = {
    for {
      elem <- Cbor.decode(in).to[Element].valueTry
      ret <- decodeCBORElement(elem)
    } yield ret
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
          case Some(ret) => Success(ret)
          case _ => Failure(new Exception(f"Failed to decode ${in} as MD5"))
        }
      case _ => Failure(new Exception(f"Failed to decode ${in} as MD5"))
    }

}

type Position = Long

enum PayloadType extends EncodeCBOR {
  case ENTRY

  override def encodeCBORElement(): Element = StringElem(this.toString())

}

object PayloadType extends DecodeCBOR[PayloadType] {

  override def decodeCBORElement(in: Element): Try[PayloadType] = Try {
    PayloadType.valueOf(in.asInstanceOf[StringElem].value)
  }

}

// enum PayloadCompression extends EncodeCBOR {
//   case NONE, DEFLATE, GZIP

//   private def compressWith(
//       out: OutputStream => OutputStream,
//       bytes: Array[Byte]
//   ): Array[Byte] = {
//     val bos = new ByteArrayOutputStream()
//     val compressor = out(bos)
//     compressor.write(bytes)
//     compressor.flush()
//     compressor.close()
//     bos.flush()
//     bos.toByteArray()
//   }

//   private def unCompressWith(
//       out: InputStream => InputStream,
//       bytes: Array[Byte]
//   ): Array[Byte] = {
//     val bos = new ByteArrayInputStream(bytes)
//     val uncompressor = out(bos)
//     Helpers.slurpInput(uncompressor)
//   }

//   def compress(bytes: Array[Byte]): Array[Byte] = {
//     this match {
//       case NONE => bytes
//       case DEFLATE =>
//         compressWith(os => new DeflaterOutputStream(os), bytes)
//       case GZIP => compressWith(os => new GZIPOutputStream(os), bytes)
//     }
//   }

//   def deCompress(bytes: Array[Byte]): Array[Byte] = {
//     this match {
//       case NONE => bytes
//       case DEFLATE =>
//         unCompressWith(os => new InflaterInputStream(os), bytes)
//       case GZIP => unCompressWith(os => new GZIPInputStream(os), bytes)
//     }
//   }

//   override def encodeCBORElement(): Element = StringElem(this.toString())
// }

// object PayloadCompression extends DecodeCBOR[PayloadCompression] {

//   override def decodeCBORElement(in: Element): Try[PayloadCompression] = Try {
//     PayloadCompression.valueOf(in.asInstanceOf[StringElem].value)
//   }

// }

type MultifilePosition = (Long, Long)

// object ItemEnvelope extends DecodeCBOR[ItemEnvelope] {
//   private def longFrom(e: Element): Try[Long] = Try {
//     e match {
//       case LongElem(value) => value
//       case OverLongElem(negative, value) =>
//         if (negative && value > 0L) -1L * value else value
//       case IntElem(value) => value
//       case x => throw new Exception(f"Couldn't turn ${e} into a long")
//     }
//   }

//   private def longPairFrom(e: Element): Try[(Long, Long)] = Try {
//     e match {
//       case ae: ArrayElem if ae.elems.length == 2 =>
//         for {
//           a <- longFrom(ae.elems(0))
//           b <- longFrom(ae.elems(1))
//         } yield (a, b)
//       case x => throw new Exception(f"Couldn't turn ${e} into a long pair")
//     }

//   }.flatten

//   private def intFrom(e: Element): Try[Int] = Try {
//     e match {
//       case IntElem(value) => value
//       case x => throw new Exception(f"Couldn't turn ${e} into a int")
//     }
//   }

//   private def boolFrom(e: Element): Try[Boolean] = Try {
//     e match {
//       case BooleanElem(value) => value
//       case x => throw new Exception(f"Couldn't turn ${e} into a bool")
//     }
//   }
//   private def elemFor[T](
//       map: MapElem,
//       key: String,
//       converter: Element => Try[T]
//   ): Try[T] = Try {
//     val elem = map(key) match {
//       case Some(e) => e
//       case _       => throw new Exception(f"Couldn't find key ${key} in ${map}")
//     }
//     converter(elem)
//   }.flatten
//   override def decodeCBORElement(in: Element): Try[ItemEnvelope] =
//     for {
//       env <- Try { in.asInstanceOf[MapElem] }
//       md5 <- elemFor(env, "h", MD5.decodeCBORElement(_))
//       position <- elemFor(env, "p", longFrom(_))
//       backpointer <- elemFor(env, "bp", longFrom(_))
//       dataLen <- elemFor(env, "l", intFrom(_))
//       dataType <- elemFor(env, "pt", PayloadType.decodeCBORElement(_))
//     } yield ItemEnvelope(
//       md5,
//       position = position,
//       backpointer = backpointer,
//       dataLen = dataLen,
//       dataType = dataType
//     )
// }

// case class ItemEnvelope(
//     keyMd5: MD5,
//     position: Position,
//     backpointer: Long,
//     dataLen: Int,
//     dataType: PayloadType
// ) extends EncodeCBOR {

//   override def encodeCBORElement(): Element = MapElem.Sized(
//     "h" -> keyMd5.encodeCBORElement(),
//     "p" -> LongElem(position),
//     "bp" -> (if (backpointer < 0) OverLongElem(false, backpointer)
//              else LongElem(backpointer)),
//     "l" -> IntElem(dataLen),
//     "pt" -> dataType.encodeCBORElement()
//   )

// }

case class DataFileEnvelope(
    version: Int,
    magic: Int,
    previous: Long,
    @key("depends_on") dependsOn: Vector[Long],
    @key("built_from_merge") builtFromMerge: Boolean,
    info: Map[String, String]
) {
  def encode(): Array[Byte] = Cbor.encode(this).toByteArray
}

object DataFileEnvelope {
  def build(
      version: Int = 1,
      magic: Int = GraphManager.Consts.DataFileMagicNumber,
      previous: Long,
      dependsOn: Vector[Long] = Vector(),
      builtFromMerge: Boolean,
      info: Map[String, String] = Map()
  ) = DataFileEnvelope(
    version,
    magic,
    previous,
    dependsOn,
    builtFromMerge,
    info
  )

  given Codec[DataFileEnvelope] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveCodec[DataFileEnvelope]
  }

  def decode(bytes: Array[Byte]): Try[DataFileEnvelope] = {

    Cbor.decode(bytes).to[DataFileEnvelope].valueTry
  }
}

case class IndexFileEnvelope(
    version: Int,
    magic: Int,
    size: Int,
    @key("data_files") dataFiles: Vector[Long],
    encoding: String,
    info: Map[String, String]
) {
  def encode(): Array[Byte] = Cbor.encode(this).toByteArray
}

object IndexFileEnvelope {

  def build(
      version: Int = 1,
      magic: Int = GraphManager.Consts.IndexFileMagicNumber,
      size: Int,
      dataFiles: Vector[Long],
      encoding: String = "MD5/Long/Long",
      info: Map[String, String] = Map()
  ) = IndexFileEnvelope(
    version = version,
    magic = magic,
    size = size,
    dataFiles = dataFiles,
    encoding = encoding,
    info = info
  )

  given Codec[IndexFileEnvelope] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveCodec[IndexFileEnvelope]
  }

  def decode(bytes: Array[Byte]): Try[IndexFileEnvelope] =
    Cbor.decode(bytes).to[IndexFileEnvelope].valueTry
}

case class ClusterFileEnvelope(
    version: Int,
    magic: Int,
    @key("data_files") dataFiles: Vector[Long],
    @key("index_files") indexFiles: Vector[Long],
    info: Map[String, String]
) {
  def encode(): Array[Byte] = Cbor.encode(this).toByteArray

}

object ClusterFileEnvelope {
  def build(
      version: Int = 1,
      magic: Int = GraphManager.Consts.ClusterFileMagicNumber,
      dataFiles: Vector[Long],
      indexFiles: Vector[Long],
      info: Map[String, String] = Map()
  ) = ClusterFileEnvelope(
    version = version,
    magic = magic,
    dataFiles = dataFiles,
    indexFiles = indexFiles,
    info = info
  )

  given Codec[ClusterFileEnvelope] = {
    import io.bullet.borer.derivation.MapBasedCodecs.*
    deriveCodec[ClusterFileEnvelope]
  }
}
