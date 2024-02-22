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
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicReference

type GitOID = String

/** A bunch of helpers/utilities
  */
object Helpers {

  /** Given a file root and a filter function, return a channel that contains
    * the files found in the folder and subfolders that match the filter.
    *
    * @param root
    *   the root directory to search
    * @param ok
    *   the filter function
    * @param channelSize
    *   the maximum channel size (default 250 entries)
    * @return
    *   a channel that contains the files
    */
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

  /** Write data over a file
    *
    * @param what
    *   the file to write
    * @param data
    *   the data
    */
  def writeOverFile(what: File, data: String): Unit = {
    writeOverFile(what, data.getBytes("UTF-8"))
  }

  /** Write data over a file
    *
    * @param what
    *   the file to write
    * @param data
    *   the data
    */
  def writeOverFile(what: File, data: Array[Byte]): Unit = {
    val fos = new FileOutputStream(what, false)
    fos.write(data)
    fos.close()
  }

  /** Compute the MD5 hash of a String (converted to bytes using UTF-8
    * encoding). Note MD5 is faster and more space efficient than secure hashes.
    * It's used to compute the hash of file paths/names for indexing.
    *
    * @param in
    *   the String to get the hash for
    * @return
    *   the hex of the MD5 hash.
    */
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

  /** Slurp the contents of an InputStream
    *
    * @param what
    *   the InputStream
    * @return
    *   the bytes contained in the InputStream
    */
  def slurpInput(what: InputStream): Array[Byte] = {
    val ret = new ByteArrayOutputStream()
    val buffer = new Array[Byte](4096)

    while (true) {
      val len = what.read(buffer)
      if (len < 0) {
        what.close()
        return ret.toByteArray()
      }
      if (len > 0) {
        ret.write(buffer, 0, len)
      }
    }

    what.close()
    ret.toByteArray()
  }

  /** Bail out... gracefully if we're running in SBT
    *
    * @return
    */
  def bailFail(): Nothing = {
    if (Thread.currentThread().getStackTrace().length < 6) System.exit(1)
    throw new Exception()
  }

  /** Slurp the contents of a File
    *
    * @param what
    *   the File
    * @return
    *   the bytes contained in the File
    */
  def slurpInput(what: File): Array[Byte] = {
    val fis = new FileInputStream(what)
    slurpInput(fis)
  }

  private val allFiles: AtomicReference[Map[String, Set[String]]] =
    new AtomicReference(Map())

  def filesForParent(in: File): Set[String] = {
    val parentFile = in.getAbsoluteFile().getParentFile()
    val parentStr = parentFile.getAbsolutePath()

    allFiles.get().get(parentStr) match {
      case Some(r) => r
      case None =>
        val v = Set(parentFile.listFiles().map(f => f.getName()): _*)
        allFiles.getAndUpdate(last => last + (parentStr -> v))
        v
    }
  }

  def findSrcFile(like: File): Option[File] = {
    val name = like.getName()
    val myNameIsh = name.substring(0, name.length() - 4)
    val possible = filesForParent(like)
    val ns = f"${myNameIsh}-sources.jar"

    possible.contains(ns) match {
      case true =>
        val maybe = new File(like.getAbsoluteFile().getParentFile(), ns)
        if (maybe.exists()) { Some(maybe) }
        else { None }
      case _ =>
        None
    }

  }

  /** Get data on a GitOID from a GitOID Corpus
    *
    * @param gitoid
    *   the gitoid to look up
    * @param base
    *   the base of the corpus (e.g., https or file)
    * @param splitter
    *   the function that splits the filename for lookup
    * @return
    *   a tuple containing an HTTP response code. If it's 200, then the second
    *   item contains the `Array[Byte]` returned from the request
    */
//   def getData(
//       gitoid: GitOID,
//       base: URL,
//       splitter: String => (Vector[String], Option[String])
//   ): (Int, Array[Byte]) = {

//     // FIXME Support SQLLite DB as well
//     val (split, suffix) = splitter(gitoid)
//     val actualPre = split.foldLeft(base)((url, v) => {
//       val uri = url.toURI()
//       uri
//         .resolve(f"${uri.getPath()}/${v}")
//         .toURL()
//     })

//     val actual = suffix match {
//       case None => actualPre
//       case Some(suff) => {
//         val uri = actualPre.toURI()
//         uri.resolve(f"${uri.getPath()}.${suff}").toURL()
//       }
//     }

//     try {
//       val input = actual.openConnection() match {
//         case http: HttpURLConnection =>
//           http.setRequestMethod("GET")
//           http.connect()
//           val resp = http.getResponseCode()
//           if (resp != 200) return (resp, "N/A".getBytes("UTF-8"))
//           http.getInputStream()
//         case other =>
//           other.connect()
//           other.getInputStream()
//       }
//       (200, Helpers.slurpInput(input))
//     } catch {
//       case e: Exception =>
//         {
//           (404, e.toString().getBytes("UTF-8"))
//         }
//     }

//   }
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

// Some utility code to pull data from the Maven Central Lucene index and create a set of URLs to JAR files in Maven Central
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
