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

package goatrodeo.omnibor

import java.io.File
import goatrodeo.util.Helpers
import scala.util.Try
import java.util.concurrent.atomic.AtomicReference
import java.sql.Blob
import java.sql.PreparedStatement
import java.net.URL
import goatrodeo.loader.GitOID

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
  def read(path: String): Option[String]

  /** Write data to the path
    *
    * @param path
    *   the path
    * @param data
    *   the data to write
    */
  def write(path: String, data: String): Unit

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
}

trait StorageReader {
  def read(path: String): Option[String]
}

class WebStorageReader(base: URL) extends StorageReader {
  def read(path: String): Option[String] = {
    val u2 = new URL(base, path)
    Try {
      new String(Helpers.slurpInput(u2.openStream()), "UTF-8")
    }.toOption
  }
}

class FileStorageReader(base: String) extends StorageReader {
  private val baseFile = new File(base)

  def read(path: String): Option[String] = {
    val stuff = GitOID.urlToFileName(path)
    val theFile =
      new File(baseFile, f"${stuff._1}/${stuff._2}/${stuff._3}.json")
    if (theFile.exists()) {

      Try {
        new String(Helpers.slurpInput(theFile), "UTF-8")
      }.toOption
    } else None
  }
}

object StorageReader {
  def from(url: URL): StorageReader = {
    if (url.getProtocol() == "file") {
      new FileStorageReader(url.getPath())
    } else {
      new WebStorageReader(url)
    }
  }
}

/** Can the filenames be listed?
  */
trait ListFileNames {

  /** A list of all the paths in the backing store, sorted
    *
    * @return
    *   the paths, sorted
    */
  def sortedPaths(): Vector[String] = paths().sorted

  /** All the paths in the backing store
    *
    * @return
    *   the paths in the backing store
    */
  def paths(): Vector[String]

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
      inMem: Boolean,
      dbLoc: Option[File],
      fsLoc: Option[File]
  ): Storage = {
    (inMem, dbLoc, fsLoc) match {
      case (false, Some(db), _) => SqlLiteStorage.getStorage(db)
      case (false, _, Some(fs)) => FileSystemStorage.getStorage(fs)
      case (_, _, target)       => MemStorage.getStorage(target)
    }
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
  def getStorage(targetDir: Option[File]): Storage = {

    // use an atomic reference and an immutable map to store stuff to avoid
    // `synchronized` and lock contention
    val db = new AtomicReference(Map[String, String]())

    new Storage with ListFileNames {
      override def paths(): Vector[String] = db.get().keys.toVector

      override def pathsSortedWithMD5(): Vector[(String, String)] = {
        db.get().keys.map(k => (Helpers.md5hash(k), k)).toVector.sorted
      }

      override def target(): Option[File] = targetDir

      override def exists(path: String): Boolean = db.get().contains(path)

      override def read(path: String): Option[String] = db.get().get(path)

      override def write(path: String, data: String): Unit = {
        def doUpdate(in: Map[String, String]): Map[String, String] =
          in + (path -> data)
        var old = db.get()
        var updated = doUpdate(old)
        while (!db.compareAndSet(old, updated)) {
          old = db.get()
          updated = doUpdate(old)
        }
      }

      override def release(): Unit = db.set(Map())
    }
  }
}

/** Store the GitOID Corpus on the filesystem
  */
object FileSystemStorage {
  def getStorage(root: File): Storage = {

    def buildIt(path: String): File = {
      if (path.startsWith("pkg:")) {
        new File(root, f"purl/${path}.json")
      } else {
        val stuff = GitOID.urlToFileName(path)

        new File(root, f"${stuff._1}/${stuff._2}/${stuff._3}.json")
      }

    }

    new Storage {
      override def exists(path: String): Boolean = buildIt(path).exists()

      override def read(path: String): Option[String] = {
        val wholePath = buildIt(path)
        Try { new String(Helpers.slurpInput(wholePath), "UTF-8") }.toOption
      }

      override def write(path: String, data: String): Unit = {
        val wholePath = buildIt(path)
        val parent = wholePath.getAbsoluteFile().getParentFile().mkdirs()
        Helpers.writeOverFile(wholePath, data)
      }

      def release(): Unit = {}
    }
  }
}

/** The GitOID Corpus in a SQLite database. Turns out this is not materially
  * faster than using the filesystem
  */
object SqlLiteStorage {
  import java.sql.Connection;
  import java.sql.DriverManager;
  import java.sql.ResultSet;
  import java.sql.SQLException;
  import java.sql.Statement
  import org.sqlite.{SQLiteJDBCLoader, SQLiteConfig}

  def getStorage(pathToDB: File): Storage = {
    Class.forName("org.sqlite.JDBC");
    val initialize = SQLiteJDBCLoader.initialize();
    val lock = new Object()
    var cnt: Long = 0
    val jdbc = {
      val config = new SQLiteConfig()
      config.setCacheSize(100000)
      val driverManager = DriverManager.getConnection(
        f"jdbc:sqlite:${pathToDB.getAbsolutePath()}",
        config.toProperties()
      )
      val stmt = driverManager.createStatement()
      stmt.execute("""CREATE TABLE IF NOT EXISTS "files" (
	"name" TEXT PRIMARY KEY, 
	"content" BLOB,          
	"modified" INTEGER,      
	"mode" INTEGER           
     );""")

      // stmt.execute("CREATE INDEX IF NOT EXISTS pk_file ON files (name);")
      driverManager.setAutoCommit(false)
      driverManager
    }

    val countPS =
      jdbc.prepareStatement("SELECT COUNT(*) FROM \"files\" WHERE name = ?")

    val readPS =
      jdbc.prepareStatement("SELECT content FROM files WHERE name = ?")

    val writePS = jdbc.prepareStatement(
      "INSERT INTO files(name, content, modified, mode) VALUES(?, ?, ?, ?) ON CONFLICT(name) DO UPDATE SET content = ?, modified = ?;"
    )
    new Storage {

      def exists(path: String): Boolean = lock.synchronized {

        countPS.setString(1, path)

        val rs = countPS.executeQuery()
        try {
          if (rs.next()) {
            rs.getInt(1) > 0
          } else false
        } finally {
          rs.close()

        }
      }

      def read(path: String): Option[String] = lock.synchronized {

        readPS.setString(1, path)
        val rs = readPS.executeQuery()
        try {
          if (rs.next()) {
            val blob = rs.getBytes(1)
            Some(new String(blob, "UTF-8"))
          } else None
        } finally {
          rs.close()

        }
      }

      def write(path: String, data: String): Unit = lock.synchronized {
        writePS.setString(1, path)
        writePS.setBytes(2, data.getBytes("UTF-8"))
        writePS.setLong(3, System.currentTimeMillis())
        writePS.setInt(4, 0x666)
        writePS.setBytes(5, data.getBytes("UTF-8"))
        writePS.setLong(6, System.currentTimeMillis())
        writePS.executeUpdate()
        cnt = cnt + 1
        if (cnt % 5000 == 0) {
          jdbc.commit()
        }
      }

      def release(): Unit = lock.synchronized {
        jdbc.commit()
        jdbc.close()
      }

    }
  }
}
