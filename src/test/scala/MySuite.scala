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

import io.spicelabs.goatrodeo.util.GitOIDUtils
import java.util.regex.Pattern
import io.spicelabs.goatrodeo.util.Helpers
import java.io.ByteArrayInputStream
import io.spicelabs.goatrodeo.envelopes.MD5
import io.spicelabs.goatrodeo.envelopes.Position
import io.spicelabs.goatrodeo.envelopes.MultifilePosition
import io.bullet.borer.Cbor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import io.spicelabs.goatrodeo.omnibor.BuildGraph
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.Builder
import io.spicelabs.goatrodeo.omnibor.GraphManager
import io.spicelabs.goatrodeo.util.PackageIdentifier
import io.spicelabs.goatrodeo.util.PackageProtocol
import java.io.IOException
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class MySuite extends munit.FunSuite {

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
      FileWalker
        .streamForArchive(
          FileWrapper(File("test_data/HP1973-Source.zip"), false)
        )
        .isDefined
    )
    assert(
      FileWalker
        .streamForArchive(
          FileWrapper(File("test_data/log4j-core-2.22.1.jar"), false)
        )
        .isDefined
    )
    assert(
      FileWalker
        .streamForArchive(FileWrapper(File("test_data/empty.tgz"), false))
        .isDefined
    )
    assert(
      FileWalker
        .streamForArchive(FileWrapper(File("test_data/toml-rs.tgz"), false))
        .isDefined
    )
    assert(
      FileWalker
        .streamForArchive(
          FileWrapper(File("test_data/tk8.6_8.6.14-1build1_amd64.deb"), false)
        )
        .isDefined
    )
    assert(
      FileWalker
        .streamForArchive(
          FileWrapper(File("test_data/tk-8.6.13-r2.apk"), false)
        )
        .isDefined
    )

    assert(
      FileWalker
        .streamForArchive(FileWrapper(File("test_data/ics_test.tar"), false))
        .isDefined
    )

    assert(
      FileWalker
        .streamForArchive(FileWrapper(File("test_data/nested.tar"), false))
        .isDefined
    )

  }

  test("Walk a tar file") {
    var cnt = 0
    val (inputStream, _) =
      FileWalker
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

    FileWalker.processFileAndSubfiles(
      nested,
      "nested",
      None,
      Vector[String](),
      false,
      (file, name, parent, x) => {
        cnt += 1
        val (main, _) = GitOIDUtils.computeAllHashes(file, s => false)
        // println(f"hash for ${name} is ${main} parent ${parent}")
        (main, false, None, x)
      }
    )
    assert(cnt > 1200, f"expected more than 1,200, got ${cnt}")
  }

  test("Compute pURL for .deb") {
    val purl = PackageIdentifier.computePurl(
      File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    )
    assert(purl.isDefined, "Should compute a purl")
    assertEquals(purl.get.artifactId, "tk8.6")
    assert(
      purl.get.extra.get("maintainer").get.size > 0,
      "Should have a mainter"
    )
  }

  test("deal with .deb and zst") {
    val nested =
      FileWrapper(File("test_data/tk8.6_8.6.14-1build1_amd64.deb"), false)
    assert(nested.isFile() && nested.exists())

    var cnt = 0

    FileWalker.processFileAndSubfiles(
      nested,
      "nested",
      None,
      Vector[String](),
      false,
      (file, name, parent, x) => {
        cnt += 1
        val (main, _) = GitOIDUtils.computeAllHashes(file, s => false)
        // println(f"hash for ${name} is ${main} parent ${parent}")
        (main, false, None, x)
      }
    )
    assert(cnt > 10, f"expected more than 10, got ${cnt}")
  }

  test("Build from nested") {
    val store = MemStorage.getStorage(None)
    val nested = File("test_data/nested.tar")
    val built = BuildGraph.buildItemsFor(
      nested,
      nested.getName(),
      store,
      Vector(),
      None,
      Map(), {
        val file = File.createTempFile("goat_rodeo_purls", "_out")
        file.delete()
        file.mkdirs()
        BufferedWriter(FileWriter(File(file, "purls.txt")))
      },
      false
    )

    assert(built.nameToGitOID.size > 1200, f"Expection more than 1,200 items, got ${built.nameToGitOID.size}")
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
    val gitoid = topAlias.connections.head._2
    assert(gitoid.startsWith("gitoid:"))
    val top = store.read(gitoid).get
    store.read("gitoid:blob:sha1:2e79b179ad18431600e9a074735f40cd54dde7f6").get
    for { edge <- top.connections if edge._1 == EdgeType.contains } {
      val contained = store.read(edge._2).get
    }

    val log4j = store
      .read(
        "gitoid:blob:sha256:e3f8d493cb200fd95c4881e248148836628e0f06ddb3c28cb3f95cf784e2f8e4"
      )
      .get
    assert(
      log4j.connections.filter(_._1 == EdgeType.contains).size > 1200
    )
  }

  test("Build from Java") {
    val source = File("test_data/jar_test")
    val files = ToProcess.buildQueueAsVec(source)

    assert(
      files.length >= 2,
      f"Expecting at least 2 files, got ${files.length}"
    )

    val bos = ByteArrayOutputStream()
    val purlOut = BufferedWriter(OutputStreamWriter(bos))

    val store = MemStorage.getStorage(Some(File("/tmp/frood")))
    import scala.collection.JavaConverters.collectionAsScalaIterableConverter
    import scala.collection.JavaConverters.iterableAsScalaIterableConverter

    for { toProcess <- files } {
      BuildGraph.graphForToProcess(toProcess, store, purlOut = purlOut)
    }

    val keys = store.keys()
    val items = keys.flatMap(store.read(_))
    assert(items.length > 1100)

    val sourceRef = items.filter(i =>
      i.connections.filter(e => e._1 == EdgeType.builtFrom).size > 0
    )
    val fromSource = for {
      i <- items; c <- i.connections if c._1 == EdgeType.buildsTo
    } yield c
    assert(sourceRef.length > 100)

    assert(fromSource.length == sourceRef.length)

    // the package URL is picked up
    val withPurl =
      items.filter(i => i.connections.filter(_._2.startsWith("pkg:")).size > 0)

    assert(withPurl.length == 4)

    val withPurlSources = withPurl.filter(i =>
      i.connections.filter(_._2.endsWith("?packaging=sources")).size > 0
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
              None,
              Map()
            )
          ),
          Map(), {
            val file = File.createTempFile("goat_rodeo_purls", "_out")
            file.delete()
            file.mkdirs()
            BufferedWriter(FileWriter(File(file, "purls.txt")))
          },
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
