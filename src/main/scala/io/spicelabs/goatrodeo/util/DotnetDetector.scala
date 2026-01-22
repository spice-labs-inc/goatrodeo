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
import io.spicelabs.goatrodeo.util.FileInputStreamEx.toFileInputStream
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
import java.io.BufferedInputStream

class DotnetDetector extends Detector {

  override def detect(input: InputStream, metadata: Metadata): MediaType = {
    import DotnetDetector.isPE32
    // bail quick if not your very basic PE32
    if (!isPE32(input))
      MediaType.OCTET_STREAM
    else
      toFileInputStream(input, metadata) match {
        case Some(fs) =>
          Try {
            val assembly = AssemblyDefinition.readAssembly(fs)
            if (assembly != null && assembly.mainModule != null) {
              DotnetDetector.DOTNET_MIME
            } else {
              MediaType.OCTET_STREAM
            }
          } match {
            case Failure(exception) =>
              FileInputStreamEx.closeIfNeeded(fs)
              MediaType.OCTET_STREAM
            case Success(value) =>
              FileInputStreamEx.closeIfNeeded(fs)
              value
          }
        case None => MediaType.OCTET_STREAM
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
        true
    }
  }
}

class FileInputStreamEx(private val f: File) extends FileInputStream(f) {
  // def this(path: String) = {
  //   this(File(path))
  // }
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

  private def saferGetFileInputStream(
      tis: TikaInputStream
  ): Option[FileInputStream] = {
    FileWalker.withinTempDir { tempPath =>
      // Tika documentation sez: thou shalt mark/reset
      // when you do a mark with a "reasonable" value (4K or so), the reset fails with an invalid mark
      // because we're reading the whole file.
      // When you mark with the stream length, that also fails.
      // This works. Steve sez: don't touch this.
      tis.mark(Integer.MAX_VALUE)
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
  }

  def closeIfNeeded(fis: FileInputStream) = {
    fis match {
      case fex: FileInputStreamEx => fex.close()
      case _                      => ()
    }
  }
}
