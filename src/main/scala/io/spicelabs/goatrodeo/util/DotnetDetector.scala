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

package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import io.spicelabs.cilantro.AssemblyDefinition
import org.apache.tika.detect.Detector
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.mime.MediaType

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import java.nio.file.Files
import java.io.FileOutputStream

class DotnetDetector(artifactOpt: Option[ArtifactWrapper], truePathOpt: Option[String]) extends Detector {

  override def detect(input: InputStream, metadata: Metadata): MediaType = {
    import DotnetDetector.isPE32
    // bail quick if not your very basic PE32
    if (!isPE32(input)) {
      MediaType.OCTET_STREAM
    } else {
      DotnetDetector.withFileInputStream(None, artifactOpt, truePathOpt, fs => {
        Try {
          val assembly = AssemblyDefinition.readAssembly(fs)
          if (assembly != null && assembly.mainModule != null) {
            DotnetDetector.DOTNET_MIME
          } else {
            MediaType.OCTET_STREAM
          }
        } match {
          case Failure(exception) => MediaType.OCTET_STREAM
          case Success(value) => value
        }
      }) match {
        case Some(value) => value
        case None => MediaType.OCTET_STREAM
      }
    }
  }
}

object DotnetDetector {
  lazy val DOTNET_MIME: MediaType = {
    MediaType.parse("application/x-msdownload; format=pe32-dotnet")
  }

  // the first 2 bytes of a PE file is M (0x4d) and Z (0x5a)
  // This can be extended to jump to the formal PE header, but that
  // involves:
  // Skipping forward 58 bytes
  // reading a little endian 4 byte int (offset to PE header)
  // Skipping to that offset if it's "sane"
  // reading a little endian 4 byte int
  // checking to see if it matches 0x00004550
  private def isPE32(input: InputStream): Boolean = {
    input.mark(1024)
    Try {
      val b0 = input.read()
      val b1 = input.read()
      b0 == 0x4d && b1 == 0x5a
    } match {
      case Failure(exception) =>
        input.reset()
        false
      case Success(value) =>
        input.reset()
        value
    }
  }


  // This is a work around for the problem of needing to mark/reset on an enormous file.
  // There are 3 case here:
  // 1. We have an ArtifactWrapper. In this case we either have a FileWrapper (easy)
  //    or a ByteWrapper. In the latter case, stream it to a file using the existing tools.
  // 2. We have an actual path. This happens when mimeTypeFor was called outside of an
  //    artifact context but there is an actual path to that. There are a fair number of cases
  //    of that happening, mostly in tests.
  // 3. We have a TikaInputStream only. There is precisely one case where we get that and
  //    it is in the case of looking inside an archive. A TikaInputStream is constructed from
  //    a byte array.  In this case, it is safe to call the old mark/reset code since it is
  //    not likely to be an enormous file - more precisely, if it was we would have already
  //    failed in slurpInputNoClose.
  
  def withFileInputStream[T](tisOpt: Option[TikaInputStream], artifactOpt: Option[ArtifactWrapper],
            truePathOpt: Option[String], f: (FileInputStream) => T): Option[T] = {
      if (artifactOpt.isDefined) {
        val artifact = artifactOpt.get
        artifact match {
          case fileArt: FileWrapper => {
            val fs = FileInputStream(fileArt.wrappedFile)
            val result = f(fs)
            fs.close()
            Some(result)
          }
          case other: ArtifactWrapper => {
            other.tempDir match {
              case None =>  {
                FileWalker.withinTempDir { tempDir =>
                  val fileName = other.filenameWithNoPath
                  val file = Files.createTempFile(tempDir, "goatrodeo", fileName).toFile()
                  val fs = FileInputStream(file)
                  file.delete()
                  val result = f(fs)
                  fs.close()
                  Some(result)
                }
              }
              case Some(tempDir) => {
                other.withStream(is => {
                  val file = Helpers.tempFileFromStream(is, true, tempDir.toPath())
                  val fs = FileInputStream(file)
                  file.delete()
                  val result = f(fs)
                  fs.close()
                  Some(result)
                })
              }
            }
          }
        }
      } else if (truePathOpt.isDefined) {
        val fs = FileInputStream(truePathOpt.get)
        val result = f(fs) 
        fs.close()
        Some(result)
      } else if (tisOpt.isDefined) {
        val tis = tisOpt.get
        FileInputStreamEx.saferGetFileInputStream(tis) match {
          case None => None
          case Some(fs) => {
            val result = f(fs)
            fs.close()
            Some(result)
          }
        }
      } else {
        None
      }
  }
}

class FileInputStreamEx(private val f: File) extends FileInputStream(f) {
}

object FileInputStreamEx {
  val log = Logger(classOf[FileInputStreamEx])
  def toFileInputStream(
      is: InputStream,
      metadata: Metadata
  ): Option[FileInputStream] = {
    is match {
      case fis: FileInputStream => Some(fis)
      case _ =>
        val path = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
        if (path == null) {
          log.error(
            "Metadata data needs the RESOURCE_NAME_KEY set to the path name"
          )
          None
        } else {
          val f = File(path)

          if (!f.exists()) {
            is match {
              case tis: TikaInputStream => {
                saferGetFileInputStream(tis)
              }
              case _ =>
                log.error(
                  "file in metadata doesn't exist; tried to get TikaInputStream - that failed too."
                )
                None
            }
          } else if (!f.isFile()) {
            None
          } else {
            Some(
              FileInputStreamEx(
                f
              )
            )
          }
        }
    }
  }

  def saferGetFileInputStream(
      tis: TikaInputStream
  ): Option[FileInputStream] = {
    FileWalker.withinTempDir { tempPath =>
      // Tika documentation sez: thou shalt mark/reset
      // when you do a mark with a "reasonable" value (4K or so), the reset fails with an invalid mark
      // because we're reading the whole file.
      // When you mark with the stream length, that also fails.
      // This works. Steve sez: don't touch this.
      Try {
        tis.mark(Int.MaxValue)
        val result = Try {
          val tempFile =
            Files.createTempFile(tempPath, "mimework", ".tmp").toFile()
          val tempFileOutputStream = FileOutputStream(tempFile)
          tis.transferTo(tempFileOutputStream)
          tempFileOutputStream.close()
          val tempFileInputStream = FileInputStream(tempFile)
          tempFile.delete()
          Some(tempFileInputStream)
        } match {
          case Failure(exception) => {
            log.error(
              s"Failure creating temp file to detect MIME type: ${exception.getMessage()}"
            )
            None
          }
          case Success(value) => {
            value
          }
        }
        tis.reset()
        result
      }
    } match {
      case Failure(exception) => None
      case Success(value) => value
    }
  }

  def closeIfNeeded(fis: FileInputStream) = {
    fis match {
      case fex: FileInputStreamEx => fex.close()
      case _                      => ()
    }
  }
}
