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

import goatrodeo.util.GitOIDUtils
import java.util.regex.Pattern
import goatrodeo.util.Helpers
import java.io.ByteArrayInputStream
import goatrodeo.envelopes.ItemEnvelope
import goatrodeo.envelopes.MD5
import goatrodeo.envelopes.Position
import goatrodeo.envelopes.MultifilePosition
import goatrodeo.envelopes.PayloadType
import io.bullet.borer.Cbor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import goatrodeo.omnibor.BuildGraph
import goatrodeo.omnibor.MemStorage
import goatrodeo.omnibor.EdgeType
import goatrodeo.omnibor.ToProcess
import goatrodeo.omnibor.Builder
import goatrodeo.envelopes.ItemEnvelope
import goatrodeo.omnibor.GraphManager
import goatrodeo.util.PackageIdentifier
import goatrodeo.util.PackageProtocol
import goatrodeo.omnibor.FileWrapper
import goatrodeo.omnibor.ArtifactWrapper
import java.io.IOException

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class MySuite extends munit.FunSuite {
  test("example test that succeeds") {
    val obtained = 42
    val expected = 42
    assertEquals(obtained, expected)
  }

  test("gitoid to file") {
    val test = List(
      "gitoid:blob:sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
      "blob:sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
      ":sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
      "880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a"
    )
    test.foreach(v =>
      assertEquals(
        GitOIDUtils.urlToFileName(v),
        (
          "880",
          "485",
          "f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a"
        )
      )
    )
  }

  test("regex") {
    val p = Pattern.compile("a")
    val m = p.matcher("aaaa")
    assert(m.find())
  }

  test("good hex for sha256") {
    val txt = Array[Byte](49, 50, 51, 10)
    val digest = GitOIDUtils.HashType.SHA256.getDigest()
    assertEquals(
      Helpers.toHex(digest.digest(txt)),
      "181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b"
    )

    assertEquals(
      Helpers.toHex(Helpers.computeSHA256(new ByteArrayInputStream(txt))),
      "181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b"
    )
  }

  test("EntryEnvelope Serialization") {

    for { i <- 0 to 1000 } {
      val ee = ItemEnvelope(
        keyMd5 = MD5(Helpers.randomBytes(16)),
        Helpers.randomLong(),
        Helpers.randomLong(),
        Helpers.randomInt(),
        PayloadType.ENTRY
      )

      val bytes = ee.encodeCBOR()

      if (false) {
        import io.bullet.borer.Dom.*
        val tmp = Cbor.decode(bytes).to[Element].value
        throw new Exception(tmp.toString())
      }
      assert(
        bytes.length < 200,
        f"The Entry Envelope should be < 200 bytes long, not ${bytes.length}"
      )

      val ee3 = ItemEnvelope.decodeCBOR(bytes).get

      assertEquals(ee, ee3, "Should be the same before and after serialization")

      if (false) {
        (new FileOutputStream(f"test_data/data_${i}.cbor")).write(bytes)

      }

    }

  }

  // test("EntryEnvelope Serialization from round trips") {

  //   import io.bullet.borer.Dom.*

  //   for { i <- 0 to 100 } {
  //     val theFile = new File(f"test_data/data_b_${i}.cbor")
  //     if (true) {
  //       val env = EntryEnvelope
  //     }
  //     if (theFile.exists()) {
  //       val cbor = new FileInputStream(theFile).readAllBytes()

  //       val tmp = Cbor.decode(cbor).to[Element].value

  //       val newBytes = Cbor.encode(tmp).toByteArray

  //       assertEquals(cbor.toVector, newBytes.toVector)

  //       val ee = EntryEnvelope.decodeCBOR(cbor).get

  //       val ee2: ItemEnvelope =
  //         EntryEnvelope.decodeCBOR(ee.encodeCBOR()).get
  //       assertEquals(ee, ee2, f"Test run ${i}")

  //       if (cbor.toVector != ee2.encodeCBOR().toVector) {
  //         throw new Exception(
  //           f"Not equal iteration ${i}\n${Cbor.decode(cbor).to[Element].value}\n${Cbor.decode(ee2.encodeCBOR()).to[Element].value}"
  //         )
  //       }

  //       assertEquals(
  //         cbor.toVector,
  //         ee2.encodeCBOR().toVector,
  //         "Round trip bytes equal"
  //       )
  //     }
  //   }
  // }

  // test("read old write new") {
  //   if (true) {
  //     for {
  //       compression <- Vector(PayloadCompression.NONE)
  //       testFile = new File("test_data/info_repo_di.txt") if testFile.isFile()
  //     } {
  //       val start = Instant.now()
  //       import io.bullet.borer.Dom.*
  //       import scala.collection.JavaConverters.asScalaIteratorConverter
  //       val lines = new BufferedReader(
  //         new InputStreamReader(
  //           new FileInputStream(testFile)
  //         )
  //       ).lines()
  //         .iterator()
  //         .asScala
  //         .map(s => {
  //           val id = s.indexOf("||,||")
  //           val json = s.substring(id + 5)
  //           upickle.default.read[Entry](json)
  //         })

  //       val dest = new File("frood_dir")
  //       dest.mkdirs()
  //       val res =
  //         GraphManager.writeEntries(dest, lines, compression).get
  //       println(
  //         f"Run with ${compression} took ${Duration.between(start, Instant.now())}"
  //       )

  //       // for { item <- res } {
  //       //   val start = Instant.now()
  //       //   val walker = GRDWalker(
  //       //     new FileInputStream(new File(f"frood_dir/${item}.grd")).getChannel()
  //       //   )
  //       //   walker.open().get
  //       //   for { _ <- walker.items() } {}
  //       //   println(
  //       //     f"Reading ${compression} took ${Duration.between(start, Instant.now())}"
  //       //   )
  //       // }
  //     }
  //   }
  // }

  test("long to hex and back again") {
    assertEquals(Helpers.toHex(0x1), "0000000000000001")

    assertEquals(Helpers.toHex(0x0030005000a00f01L), "0030005000a00f01")

    val txt = Array[Byte](49, 50, 51, 10)

    assertEquals(
      Helpers.toHex(
        Helpers.byteArrayToLong63Bits(
          Helpers.computeSHA256(new ByteArrayInputStream(txt))
        )
      ),
      "181210f8f9c779c2"
    )
  }

  test("File Type Detection") {
    assert(
      BuildGraph
        .streamForArchive(
          FileWrapper(File("test_data/HP1973-Source.zip"), false)
        )
        .isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(
          FileWrapper(File("test_data/log4j-core-2.22.1.jar"), false)
        )
        .isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(FileWrapper(File("test_data/empty.tgz"), false))
        .isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(FileWrapper(File("test_data/toml-rs.tgz"), false))
        .isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(
          FileWrapper(File("test_data/tk8.6_8.6.14-1build1_amd64.deb"), false)
        )
        .isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(
          FileWrapper(File("test_data/tk-8.6.13-r2.apk"), false)
        )
        .isDefined
    )

    assert(
      BuildGraph
        .streamForArchive(FileWrapper(File("test_data/ics_test.tar"), false))
        .isDefined
    )

    assert(
      BuildGraph
        .streamForArchive(FileWrapper(File("test_data/nested.tar"), false))
        .isDefined
    )
  }

  test("Walk a tar file") {
    var cnt = 0
    val (inputStream, _) =
      BuildGraph
        .streamForArchive(FileWrapper(File("test_data/empty.tgz"), false))
        .get
    for {
      e <- inputStream
      (name, file) = e()
    } {
      cnt += 1
      file.delete()
    }

    assert(cnt > 2)
  }

  test("deal with nesting") {
    val nested = FileWrapper(File("test_data/nested.tar"), false)
    assert(nested.isFile() && nested.exists())

    var cnt = 0

    BuildGraph.processFileAndSubfiles(
      nested,
      "nested",
      None,
      false,
      (file, name, parent) => {
        cnt += 1
        val (main, _) = GitOIDUtils.computeAllHashes(file, s => false)
        // println(f"hash for ${name} is ${main} parent ${parent}")
        (main, false)
      }
    )
    assert(cnt > 1200, f"expected more than 1,200, got ${cnt}")
  }

  test("deal with .deb and zst") {
    val nested = FileWrapper(File("test_data/tk8.6_8.6.14-1build1_amd64.deb"), false)
    assert(nested.isFile() && nested.exists())

    var cnt = 0

    BuildGraph.processFileAndSubfiles(
      nested,
      "nested",
      None,
      false,
      (file, name, parent) => {
        cnt += 1
        val (main, _) = GitOIDUtils.computeAllHashes(file, s => false)
        // println(f"hash for ${name} is ${main} parent ${parent}")
        (main, false)
      }
    )
    assert(cnt > 10, f"expected more than 10, got ${cnt}")
  }

  

  test("Build from nested") {
    val store = MemStorage.getStorage(None)
    val nested = File("test_data/nested.tar")
    val got = BuildGraph.buildItemsFor(
      nested,
      nested.getName(),
      store,
      Vector(),
      None,
      Map(),
      false
    )

    assert(got.size > 1200, f"Expection more than 1,200 items, got ${got.size}")
    assert(store.size() > 2200)
    val keys = store.keys()
    assert(!keys.filter(_.startsWith("sha256:")).isEmpty)
    assert(!keys.filter(_.startsWith("md5:")).isEmpty)
    assert(!keys.filter(_.startsWith("sha1:")).isEmpty)
    assert(keys.filter(_.startsWith("floof:")).isEmpty)
    val topAlias = store
      .read(
        "sha256:82ceabe5192a5c3803f8b73536e83cd59e219fb560d8ed9e0c165728b199c0d7"
      )
      .get
    val gitoid = topAlias.connections.head._1
    assert(gitoid.startsWith("gitoid:"))
    val top = store.read(gitoid).get
    store.read("gitoid:blob:sha1:2e79b179ad18431600e9a074735f40cd54dde7f6").get
    for { edge <- top.connections if edge._2 == EdgeType.Contains } {
      val contained = store.read(edge._1).get
    }

    val log4j = store
      .read(
        "gitoid:blob:sha256:e3f8d493cb200fd95c4881e248148836628e0f06ddb3c28cb3f95cf784e2f8e4"
      )
      .get
    assert(
      log4j.connections.filter(_._2 == EdgeType.Contains).size > 1200
    )
  }

  test("Build from Java") {
    val source = File("test_data/jar_test")
    val files = ToProcess.buildQueueAsVec(source)

    assert(
      files.length >= 2,
      f"Expecting at least 2 files, got ${files.length}"
    )

    val store = MemStorage.getStorage(Some(File("/tmp/frood")))
    import scala.collection.JavaConverters.collectionAsScalaIterableConverter
    import scala.collection.JavaConverters.iterableAsScalaIterableConverter

    for { toProcess <- files } {
      BuildGraph.graphForToProcess(toProcess, store)
    }

    val keys = store.keys()
    val items = keys.flatMap(store.read(_))
    assert(items.length > 1100)

    val sourceRef = items.filter(i =>
      i.connections.filter(e => e._2 == EdgeType.BuiltFrom).size > 0
    )
    val fromSource = for {
      i <- items; c <- i.connections if c._2 == EdgeType.BuildsTo
    } yield c
    assert(sourceRef.length > 100)

    assert(fromSource.length == sourceRef.length)

    // the package URL is picked up
    val withPurl =
      items.filter(i => i.connections.filter(_._1.startsWith("pkg:")).size > 0)

    assert(withPurl.length == 4)

    val withPurlSources = withPurl.filter(i =>
      i.connections.filter(_._1.endsWith("?packaging=sources")).size > 0
    )
    assert(withPurlSources.length == 2)
  }

  test("Unreadable JAR") {
    val source = File(File(System.getProperty("user.home")), "/tmp/repo_ea")

    // the test takes a couple of files with questionable TAR and ZIP archives
    // and ensures that they don't cause exceptions
    if (source.isDirectory()) {
      for {
        toTry <- Vector(
          "adif-processor-1.0.65.jar",
          "alpine-executable-war-1.2.2.jar"
        )
      } {
        val bad = File(source, toTry)
        val store = MemStorage.getStorage(None)
        BuildGraph.buildItemsFor(
          bad,
          "bad",
          store,
          Vector(),
          Some(
            PackageIdentifier(
              PackageProtocol.Maven,
              toTry,
              "frood",
              "32",
              None,
              None
            )
          ),
          Map(),
          false
        )
        // No pURL
        // val pkgIndex = store.read("pkg:maven").get
        // assert(
        //   pkgIndex.connections.size > 0,
        //   f"We should have had at least one package, but only found ${pkgIndex.connections.size}"
        // )
      }

    }
  }

  test("Build lots of JARs") {
    val source = File(File(System.getProperty("user.home")), "/tmp/repo_ea")

    if (source.isDirectory()) {

      val resForBigTent = File("res_for_big_tent")

      // delete files if they exist
      if (resForBigTent.exists()) {
        if (resForBigTent.isDirectory()) {
          for { v <- resForBigTent.listFiles() } { v.delete() }
        } else {
          resForBigTent.delete()
        }
      }
      val store = MemStorage.getStorage(Some(resForBigTent))
      import scala.collection.JavaConverters.collectionAsScalaIterableConverter
      import scala.collection.JavaConverters.iterableAsScalaIterableConverter

      Builder.buildDB(source, store, 32)

      // no pURL
      // val pkgIndex = store.read("pkg:maven").get
      // assert(
      //   pkgIndex.connections.size > 4500,
      //   f"We should have had more than 100 packages, but only found ${pkgIndex.connections.size}"
      // )
    }
  }

}
