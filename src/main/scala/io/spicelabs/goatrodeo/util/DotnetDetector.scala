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

import io.spicelabs.cilantro.AssemblyDefinition
import org.apache.tika.detect.Detector
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.mime.MediaType

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class DotnetDetector extends Detector {
  override def detect(input: InputStream, metadata: Metadata): MediaType = {
    var fs: FileInputStream = null
    try {
      fs = FileInputStreamEx.toFileInputStream(input, metadata)
      val assembly = AssemblyDefinition.readAssembly(fs)
      assembly != null && assembly.mainModule != null
      return DotnetDetector.DOTNET_MIME
    } catch {
      case _ => return MediaType.OCTET_STREAM
    } finally {
      if (fs.isInstanceOf[FileInputStreamEx])
        fs.close()
    }
  }
}

object DotnetDetector {
  lazy val DOTNET_MIME = {
    MediaType.parse("application/x-msdownload; format=pe32-dotnet")
  }
}

class FileInputStreamEx(private val f: File) extends FileInputStream(f) {
  def this(path: String) = {
    this(File(path))
  }
}

object FileInputStreamEx {
  def toFileInputStream(
      is: InputStream,
      metadata: Metadata
  ): FileInputStream = {
    if (is.isInstanceOf[FileInputStream])
      return is.asInstanceOf[FileInputStream]
    val path = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
    if (path == null)
      throw new IllegalArgumentException(
        "metadata data needs the RESOURCE_NAME_KEY set to the path name"
      )
    val f = File(path)
    if (!f.exists())
      throw new IllegalArgumentException(
        s"RESOURCE_NAME_KEY is set to ${path} but that path doesn't exist"
      )
    FileInputStreamEx(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY))
  }
}
