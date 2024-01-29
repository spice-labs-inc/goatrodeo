/* Copyright 2024 David Pollak & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package goatrodeo.util

import os.truncate
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
  * A bunch of helpers/utilities
  */
object Helpers {
  def findFiles(
      root: File,
      ok: File => Boolean,
      channelSize: Int = 250
  ): BoundedChannel[File] = {
    val ret = new BoundedChannel[File](channelSize)

    def slurp(root: File, top: Boolean): Unit = {
      try {
        if (root.isDirectory()) {
          val fileList = root.listFiles()
          if (fileList != null) {
            for { kid <- fileList } {
              slurp(kid, false)
            }
          }
        } else {
          if (ok(root)) {
          ret.push(root)
        }
        }
      } finally {
        if (top) {
          ret.close()
        }
      }
    }

    val thread =
      new Thread(() => slurp(root, true), f"File finder ${root}").start()
    ret
  }

  def writeOverFile(what: File, data: String): Unit = {
    writeOverFile(what, data.getBytes("UTF-8"))
  }

  def md5hash(in: String): String = {
    val md = MessageDigest.getInstance("MD5")
    val digested = md.digest(in.getBytes("UTF-8"))
    toHex(digested)
  }

  /** Given a byte array, create a lowercase hexadecimal string representing the
    * array
    *
    * @param bytes
    *   the array of bytes
    * @return
    *   the hexadecimal representation of the bytes
    */
  def toHex(bytes: Array[Byte]): String = {
    val sb = new StringBuilder()
    for { b <- bytes } {
      sb.append(String.format("%02x", b))
    }
    sb.toString()
  }

  def writeOverFile(what: File, data: Array[Byte]): Unit = {
    val fos = new FileOutputStream(what, false)
    fos.write(data)
    fos.close()
  }

  def slurpInput(what: InputStream): Array[Byte] = {
    val ret = new ByteArrayOutputStream()
    val buffer = new Array[Byte](4096)

    while (true) {
      val len = what.read(buffer)
      if (len < 0) {
        return ret.toByteArray()
      }
      if (len > 0) {
        ret.write(buffer, 0, len)
      }
    }

    ret.toByteArray()

  }

  def slurpInput(what: File): Array[Byte] = {

    val fis = new FileInputStream(what)
    slurpInput(fis)

  }

  def getData(
      gitoid: String,
      base: URL,
      splitter: String => Vector[String]
  ): (Int, Array[Byte]) = {
    ??? // Support SQLLite DB as well
    val split = splitter(gitoid)
    val actual = split.foldLeft(base)((url, v) => {
      val uri = url.toURI()
      uri
        .resolve(f"${uri.getPath()}/${URLEncoder.encode(v, "UTF-8")}")
        .toURL()
    }
    )

    try {
      val input = actual.openConnection() match {
        case http: HttpURLConnection =>
          http.setRequestMethod("GET")
          http.connect()
          val resp = http.getResponseCode()
          if (resp != 200) return (resp, "N/A".getBytes("UTF-8"))
          http.getInputStream()
        case other =>
          other.connect()
          other.getInputStream()
      }
      (200, Helpers.slurpInput(input))
    } catch {
      case e: Exception => (404, e.toString().getBytes("UTF-8"))
    }

  }
}

class BoundedChannel[T](size: Int) extends Seq[Option[T]] {
  private var active: Boolean = true
  private var data: Vector[T] = Vector()

  def close(): Unit = this.synchronized {
    active = false
    this.notifyAll()
  }

  // only call if data is not empty
  private def popNext(): T = this.synchronized {
    val ret = data(0)
    data = data.drop(1)
    notifyAll()
    ret
  }

  def push(v: T): Unit = this.synchronized {
    while (data.length >= size && active) {
      this.wait(100)
    }

    data = data.appended(v)
    notifyAll()
  }

  override def iterator: Iterator[Option[T]] = {
    new Iterator[Option[T]] {

      override def hasNext: Boolean = BoundedChannel.this.synchronized {
        if (!data.isEmpty) return true
        while (data.isEmpty) {
          BoundedChannel.this.wait(100)
          if (!BoundedChannel.this.active) return !data.isEmpty
        }

        true
      }

      override def next(): Option[T] = BoundedChannel.this.synchronized {
        if (BoundedChannel.this.active && !data.isEmpty) return Some(popNext())
        while (data.isEmpty && active) {
          BoundedChannel.this.wait(100)
        }

        if (!active) {
          if (!data.isEmpty) Some(popNext()) else None
        } else Some(popNext())
      }
    }
  }

  override def apply(i: Int): Option[T] = this.synchronized {
    if (data.length > i) Some(data(i)) else None
  }

  override def length: Int = this.synchronized {
    data.length
  }

}


  // def computePath(s: String): String = {
  //   val items = s.split("\\|").toVector
  //   val basePath = items(0).replace(".", "/")
  //   val basePath2 = f"https://maven-central-eu.storage-download.googleapis.com/maven2/${basePath}/${items(1)}/${items(2)}/${items(1)}-${items(2)}${
  //     items.drop(3).toList match {
  //       case "sources" :: "jar" :: _ => "-sources.jar"
  //       case _ => ".jar"
  //     }

  //   }"

  //   basePath2
  // }

  // def playLucene(): Unit = {
  //   import org.apache.lucene.analysis.standard.StandardAnalyzer;
  //   import org.apache.lucene.document.Document;
  //   import org.apache.lucene.document.Field;
  //   import org.apache.lucene.document.StringField;
  //   import org.apache.lucene.document.TextField;
  //   import org.apache.lucene.index.IndexWriter;
  //   import org.apache.lucene.index.IndexWriterConfig;
  //   import java.nio.file.Paths;
  //   import org.apache.lucene.store.FSDirectory;
  //   import org.apache.lucene.index.DirectoryReader;
  //   import org.apache.lucene.search.IndexSearcher;
  //   import scala.collection.JavaConverters.*

  //   val dir = FSDirectory.open(new File("/home/dpp/tmp/central-lucene-index/"))
  //   val reader = DirectoryReader.open(dir);
  //   val bw = new BufferedWriter(new FileWriter("/home/dpp/tmp/jars.txt"))
  //   var cnt = 0

  //   val searcher = new IndexSearcher(reader);
  //   for { i <- 1 to reader.numDocs()} {
  //     if (i % 10000 == 0) {
  //       println(i)
  //     }
  //     val d = reader.document(i)
  //     val f = d.getField("u")
  //     val f2 = d.getField("i")
  //     if (f != null && f2 != null) {
  //       val s = f.stringValue()
  //       if (
  //         /*s.indexOf("net.liftweb") >= 0 && */ f2.stringValue().indexOf("jar|") == 0
  //       ) {
  //         val path = computePath(s)

  //         bw.write(f"${path}\n")
  //         cnt = cnt + 1
  //       }
  //     }
  //     // println(d.getFields().asScala.toList)
  //     // println(d)

  //   }
  //   throw new Exception()
  // }