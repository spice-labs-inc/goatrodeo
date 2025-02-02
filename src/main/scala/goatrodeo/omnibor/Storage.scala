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

package goatrodeo.omnibor

import java.io.File
import goatrodeo.util.Helpers
import scala.util.Try
import java.util.concurrent.atomic.AtomicReference
import java.sql.Blob
import java.sql.PreparedStatement
import java.net.URL
import goatrodeo.util.GitOIDUtils
import goatrodeo.util.GitOID
import scala.util.Failure
import scala.util.Success
import scala.annotation.tailrec
import java.net.http.HttpRequest
import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpClient
import java.net.http.HttpResponse.BodyHandlers
import java.io.ByteArrayInputStream
import io.bullet.borer.Json
import java.nio.file.Files
import goatrodeo.envelopes.DataFileEnvelope
import java.io.FileOutputStream
import goatrodeo.envelopes.MD5
import goatrodeo.envelopes.Position
import goatrodeo.envelopes.MultifilePosition
import java.io.FileInputStream
import io.bullet.borer.Cbor
import goatrodeo.envelopes.IndexFileEnvelope
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException

/** An abstract definition of a GitOID Corpus storage backend
  */
trait Storage {

  /** Does the path exist?
    *
    * @param path
    *   the path
    * @return
    *   true if it's known to the storage
    */
  def exists(path: String): Boolean

  /** Read the backend storage, return the bytes of the path if there's
    * something there
    *
    * @param path
    *   the path to the item
    * @return
    *   the bytes if they exist
    */
  def read(path: String): Option[Item]

  /** Write data to the path
    *
    * @param path
    *   the path
    * @param data
    *   the data to write
    */
  def write(path: String, opr: Option[Item] => Item): Item

  /** Write data to the path
    *
    * @param path
    *   the path
    * @param data
    *   the data to write
    */
  // def write(path: String, data: String): Unit = {
  //   write(path, data.getBytes("UTF-8"))
  // }

  /** Release the backing store or close files or commit the database.
    */
  def release(): Unit

  /** Get the count of items in storage, if computable
    *
    * @return
    *   the count
    */
  def size(): Int

  /** Get the keys from storage (if possible)
    *
    * @return
    */
  def keys(): Set[String]

  def contains(identifier: String): Boolean

  /**
    * return only the keys that start with "gitoid:blob:sha256:"
    *
    * @return
    */
  def gitoidKeys(): Set[GitOID] = keys().filter(_.startsWith("gitoid:blob:sha256:"))

  def destDirectory(): Option[File]
}

// trait StorageReader {
//   def read(path: GitOID): Option[String]
// }

// trait BulkStorageReader {
//   def bulkRead(
//       paths: Set[GitOID],
//       known: Map[GitOID, Option[Item]],
//       totalBytes: Long = 0
//   ): Map[GitOID, Option[Item]]
// }



/** Can the filenames be listed?
  */
trait ListFileNames extends Storage {

  /** A list of all the paths in the backing store, sorted
    *
    * @return
    *   the paths, sorted
    */
  def sortedPaths(): Vector[String] = keys().toVector.sorted

  /** All the paths in the backing store and the MD5 hash of the path. Sorted by
    * MD5 hash
    *
    * @return
    *   sorted vector of Tuples (MD5 of the path, the path)
    */
  def pathsSortedWithMD5(): Vector[(String, String)]

  /** The target output filename for the Storage
    *
    * @return
    */
  def target(): Option[File]

}

/** A helper/companion to Storage
  */
object Storage {

  /** Based on criteria, return the appropriate storage instance
    *
    * @param inMem
    *   store in-memory
    * @param dbLoc
    *   the location of the SQLite database
    * @param fsLoc
    *   the filesystem location for file store and InMemory target
    * @return
    *   an appropriate storage instance
    */
  def getStorage(
      fsLoc: Option[File]
  ): Storage = {
    fsLoc match {
      case target => MemStorage.getStorage(target)
    }
  }
}

class MemStorage(val targetDir: Option[File])
    extends Storage
    with ListFileNames {


  override def contains(identifier: String): Boolean = db.get().contains(identifier)

  override def destDirectory(): Option[File] = targetDir

  private val sync = new Object()
  private var db: AtomicReference[Map[String, Item]] = AtomicReference(Map())
  private val locks: java.util.HashMap[String, AtomicInteger] =
    java.util.HashMap()
  def keys(): Set[String] = {

    db.get().keySet

  }

  override def pathsSortedWithMD5(): Vector[(String, String)] = {
    keys().toVector.map(k => (Helpers.md5hashHex(k), k)).sorted
  }

  override def size() = db.get().size

  override def target(): Option[File] = targetDir

  override def exists(path: String): Boolean =
    db.get().contains(path)

  override def read(path: String): Option[Item] = {
    val ret = db.get().get(path)
    ret
  }

  override def write(path: String, opr: Option[Item] => Item): Item = {
    val lock = sync.synchronized {
      val theLock = Option(locks.get(path)) match {
        case Some(lock) => lock
        case None => {
          val lock = AtomicInteger(0)
          locks.put(path, lock)
          lock
        }
      }

      theLock.incrementAndGet()
      theLock
    }
    try {
      lock.synchronized {
        val current = read(path)
        val updated = opr(current)
        sync.synchronized {
          val newVal = db.get() + (path -> updated)
          db.set(newVal)
          updated
        }
      }
    } finally {
      sync.synchronized {
        val cnt = lock.decrementAndGet()
        if (cnt == 0) {
          locks.remove(path)
        }
      }
    }
  }

  override def release(): Unit = sync.synchronized {
    db.set(Map()); locks.clear()
  }
}

/** Deal with in-memory storage
  */
object MemStorage {

  /** Get an InMem storage instance
    *
    * @param targetDir
    *   the optional target directory for post-processing output
    * @return
    *   the storage directory
    */
  def getStorage(targetDir: Option[File]): Storage & ListFileNames = {

    MemStorage(targetDir)
  }
}
