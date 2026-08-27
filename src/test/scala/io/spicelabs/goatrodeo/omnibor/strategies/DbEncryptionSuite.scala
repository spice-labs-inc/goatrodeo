/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.CryptoContentDetector
import munit.FunSuite

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Tests for the database-encryption inventory folded into
  * [[ServiceCryptoStrategy]]: MySQL keyring/InnoDB, MariaDB
  * file-key-management, MongoDB encryption-at-rest, Oracle TDE wallet,
  * Cassandra TDE, and SQLCipher PRAGMAs.
  *
  * Hard rule under test: key file contents and key VALUES are never read or
  * emitted — only presence, paths, declared algorithms, and backends.
  */
class DbEncryptionSuite extends FunSuite {

  private def metadata(
      name: String,
      text: String
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val wrapper = ByteWrapper(text.getBytes("UTF-8"), name, None)
    new ServiceCryptoState(wrapper).invokeBuildMetadata(wrapper)
  }

  private def values(
      meta: TreeMap[String, TreeSet[StringOrPair]]
  ): Map[String, Set[String]] =
    meta.map { case (k, vs) =>
      k -> vs.map {
        case StringOf(s) => s
        case other       => other.value
      }
    }

  // DE-01 — the new service configs are claimed by path.
  test("DE-01 mongod.conf, sqlnet.ora, cassandra.yaml are detected services") {
    assertEquals(
      ServiceCryptoStrategy.detectService("etc/mongod.conf"),
      Some("mongodb")
    )
    assertEquals(
      ServiceCryptoStrategy.detectService("oracle/sqlnet.ora"),
      Some("oracle")
    )
    assertEquals(
      ServiceCryptoStrategy.detectService("conf/cassandra.yaml"),
      Some("cassandra")
    )
    assertEquals(ServiceCryptoStrategy.detectService("nginx.conf"), None)
  }

  // DE-02 — MySQL keyring + InnoDB encryption settings.
  test("DE-02 MySQL keyring and InnoDB encryption inventory") {
    val meta = metadata(
      "my.cnf",
      """[mysqld]
        |ssl-cipher=AES128-SHA256
        |keyring_file_data=/var/lib/mysql-keyring/keyring
        |early-plugin-load=keyring_file.so
        |innodb_encrypt_tables=ON
        |innodb_redo_log_encrypt=1
        |""".stripMargin
    )
    val v = values(meta)
    assertEquals(v("DbEncryption:db"), Set("mysql"))
    assert(v("DbEncryption:mechanism").contains("keyring"))
    assertEquals(
      v("DbEncryption:key_file"),
      Set("/var/lib/mysql-keyring/keyring")
    )
    assert(v("DbEncryption:flag:innodb_encrypt_tables").contains("true"))
    assert(v("DbEncryption:flag:innodb_redo_log_encrypt").contains("true"))
    // TLS family still present (overlap, one strategy)
    assertEquals(v("ServiceCrypto:service"), Set("mysql"))
    assert(v.contains("ServiceCrypto:algorithms"))
  }

  // DE-03 — MariaDB file-key-management; algorithm filtered to the registry.
  test("DE-03 MariaDB file-key-management emits registry algorithms only") {
    val meta = metadata(
      "mariadb.cnf",
      """[mysqld]
        |file_key_management_filename=/etc/mysql/encryption/keyfile.enc
        |file_key_management_encryption_algorithm=aes-256-cbc
        |encrypt_binlog=ON
        |aws_key_management_region=us-east-1
        |""".stripMargin
    )
    val v = values(meta)
    assertEquals(v("DbEncryption:db"), Set("mariadb"))
    assert(v("DbEncryption:mechanism").contains("file-key-management"))
    assertEquals(v("DbEncryption:algorithms"), Set("aes-256-cbc"))
    assertEquals(v("DbEncryption:backend"), Set("aws"))
    assert(v("DbEncryption:flag:encrypt_binlog").contains("true"))
  }

  // DE-04 — a non-registry declared algorithm is dropped, not invented.
  test("DE-04 non-registry DB algorithm names are dropped") {
    val meta = metadata(
      "mariadb.cnf",
      """[mysqld]
        |file_key_management_encryption_algorithm=rot13-fancy
        |""".stripMargin
    )
    val v = values(meta)
    assert(
      !v.contains("DbEncryption:algorithms"),
      "bogus algorithm must be dropped"
    )
    assert(v("DbEncryption:db").contains("mariadb"))
  }

  // DE-05 — MongoDB encryption-at-rest (keyfile + kmip).
  test("DE-05 MongoDB encryption-at-rest inventory") {
    val meta = metadata(
      "mongod.conf",
      """security:
        |  enableEncryption: true
        |  encryptionKeyFile: /etc/mongodb/keyfile
        |encryption:
        |  keyFile: /etc/mongodb/keyfile
        |  kmip:
        |    serverName: kmip.example
        |""".stripMargin
    )
    val v = values(meta)
    assertEquals(v("DbEncryption:db"), Set("mongodb"))
    assert(v("DbEncryption:mechanism").contains("keyfile"))
    assert(v("DbEncryption:mechanism").contains("kmip"))
    assert(v("DbEncryption:key_file").contains("/etc/mongodb/keyfile"))
    assert(v("DbEncryption:flag:enableEncryption").contains("true"))
  }

  // DE-06 — Oracle TDE wallet location.
  test("DE-06 Oracle TDE wallet configuration inventory") {
    val meta = metadata(
      "sqlnet.ora",
      """ENCRYPTION_WALLET_LOCATION=
        |(SOURCE=(METHOD=FILE)(METHOD_DATA=(DIRECTORY=/opt/oracle/wallet)))
        |""".stripMargin
    )
    val v = values(meta)
    assertEquals(v("DbEncryption:db"), Set("oracle"))
    assert(v("DbEncryption:mechanism").contains("wallet"))
    assert(v("DbEncryption:key_file").contains("/opt/oracle/wallet"))
  }

  // DE-07 — Cassandra TDE.
  test("DE-07 Cassandra TDE inventory") {
    val meta = metadata(
      "cassandra.yaml",
      """transparent_data_encryption_options:
        |  enabled: true
        |  key_provider: KmipKeyProvider
        |""".stripMargin
    )
    val v = values(meta)
    assertEquals(v("DbEncryption:db"), Set("cassandra"))
    assert(v("DbEncryption:mechanism").contains("tde"))
    assertEquals(v("DbEncryption:backend"), Set("KmipKeyProvider"))
  }

  // DE-08 — SQLCipher: detected by content; hostile: the key value never
  // appears in metadata. Uses a real FileWrapper so the MIME augmentation runs
  // and stamps the db-encryption MIME (the production claiming path).
  test("DE-08 SQLCipher PRAGMA is presence-only, never the key value") {
    assert(
      ServiceCryptoStrategy.detectsSqlcipher("PRAGMA key = 's3cr3t-passphrase'")
    )
    assert(
      ServiceCryptoStrategy.detectsSqlcipher("""PRAGMA key = "x'8s5a9f3"""")
    )
    assert(ServiceCryptoStrategy.detectsSqlcipher("sqlcipher_export('enc.db')"))
    assert(!ServiceCryptoStrategy.detectsSqlcipher("PRAGMA foreign_keys=ON"))

    val dir = java.nio.file.Files.createTempDirectory("dbenc").toFile()
    try {
      val file = new java.io.File(dir, "db.go")
      java.nio.file.Files.writeString(
        file.toPath(),
        """db, _ := sql.Open("sqlite3", "app.db")
          |db.Exec("PRAGMA key = 's3cr3t-passphrase'")
          |""".stripMargin
      )
      val wrapper = io.spicelabs.goatrodeo.util.FileWrapper(
        file,
        file.getName(),
        None
      )
      val v = values(
        new ServiceCryptoState(wrapper).invokeBuildMetadata(wrapper)
      )
      assertEquals(v("DbEncryption:db"), Set("sqlite"))
      assert(v("DbEncryption:mechanism").contains("sqlcipher"))
      val all = v.values.flatten.toSet
      assert(
        !all.exists(_.contains("s3cr3t")),
        "sqlcipher key value must never be emitted"
      )
    } finally {
      java.nio.file.Files
        .walk(dir.toPath())
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => java.nio.file.Files.deleteIfExists(p))
      ()
    }
  }

  // DE-09 — the DbEncryption MIME detector is wired and gated on PRAGMA.
  test("DE-09 db-encryption MIME constant exists and detects SQLCipher") {
    assertEquals(
      CryptoContentDetector.DbEncryptionMime,
      "application/x-goatrodeo-db-encryption"
    )
    assert(ServiceCryptoStrategy.detectsSqlcipher("PRAGMA rekey = 'newkey'"))
    assert(!ServiceCryptoStrategy.detectsSqlcipher("PRAGMA journal_mode=WAL"))
  }
}
