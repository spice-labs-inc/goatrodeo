/* Copyright 2024 David Pollak, Spice Labs, Inc. & Contributors

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

class DotnetDetector extends Detector {

  override def detect(input: InputStream, metadata: Metadata): MediaType = {
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
          case Success(value)     =>
            FileInputStreamEx.closeIfNeeded(fs)
            value
        }
      case None => MediaType.OCTET_STREAM
    }
  }
}

object DotnetDetector {
  lazy val DOTNET_MIME = {
    MediaType.parse("application/x-msdownload; format=pe32-dotnet")
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
              case tis: TikaInputStream if tis.getFile() match {
                    case null => false
                    case f    => f.isFile()
                  } =>
                Some(FileInputStreamEx(tis.getFile()))
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

  def closeIfNeeded(fis: FileInputStream) = {
    fis match {
      case fex: FileInputStreamEx => fex.close()
      case _ => ()
    }
  }
}
