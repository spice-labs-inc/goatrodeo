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


trait Storage {
  def exists(path: String): Boolean
  def read(path: String): Option[Array[Byte]]
  def write(path: String, data: Array[Byte]): Unit
  def write(path: String, data: String): Unit = {
    write(path, data.getBytes("UTF-8"))
  }
  def release(): Unit
}

trait ListFileNames {
  def sortedPaths(): Vector[String]
  def paths(): Vector[String]
  def pathsSortedWithMD5(): Vector[(String, String)]
  def target(): Option[File]
}

object Storage {
  def getStorage(inMem: Boolean, dbLoc: Option[File], fsLoc: Option[File]): Storage = {
    (inMem, dbLoc, fsLoc) match {
      case (false, Some(db), _) => SqlLiteStorage.getStorage(db)
      case (false, _, Some(fs)) => FileSystemStorage.getStorage(fs)
      case (_, _, target)             => MemStorage.getStorage(target)
    }
  }
}

object MemStorage {
  def getStorage(targetDir: Option[File]): Storage = {
    val db = new AtomicReference(Map[String, Array[Byte]]())
    new Storage with ListFileNames {


      override def paths(): Vector[String] = db.get().keys.toVector

      override def pathsSortedWithMD5(): Vector[(String,String)] = {
        db.get().keys.map(k => (Helpers.md5hash(k), k)).toVector.sorted
      }

      override def target(): Option[File] = targetDir

      override def sortedPaths(): Vector[String] = db.get().keys.toVector.sorted

      override def exists(path: String): Boolean = db.get().contains(path)

      override def read(path: String): Option[Array[Byte]] = db.get().get(path)

      override def write(path: String, data: Array[Byte]): Unit = {
        def doUpdate(in: Map[String, Array[Byte]]): Map[String, Array[Byte]] =
          in + (path -> data)
        var old = db.get()
        var updated = doUpdate(old)
        while (!db.compareAndSet(old, updated)) {
          old = db.get()
          updated = doUpdate(old)
        }
      }

      def release(): Unit = db.set(Map())
    }
  }
}

object FileSystemStorage {
  def getStorage(root: File): Storage = {

    def buildIt(path: String): File = {
      new File(root, path)
    }

    new Storage {

      override def exists(path: String): Boolean = buildIt(path).exists()

      override def read(path: String): Option[Array[Byte]] = {
        val wholePath = buildIt(path)
        Try { Helpers.slurpInput(wholePath) }.toOption
      }

      override def write(path: String, data: Array[Byte]): Unit = {
        val wholePath = buildIt(path)
        val parent = wholePath.getAbsoluteFile().getParentFile().mkdirs()
        Helpers.writeOverFile(wholePath, data)
      }

      def release(): Unit = {}
    }
  }
}

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

      def read(path: String): Option[Array[Byte]] = lock.synchronized {

        readPS.setString(1, path)
        val rs = readPS.executeQuery()
        try {
          if (rs.next()) {
            val blob = rs.getBytes(1)
            Some(blob)
          } else None
        } finally {
          rs.close()

        }
      }

      def write(path: String, data: Array[Byte]): Unit = lock.synchronized {
        writePS.setString(1, path)
        writePS.setBytes(2, data)
        writePS.setLong(3, System.currentTimeMillis())
        writePS.setInt(4, 0x666)
        writePS.setBytes(5, data)
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
