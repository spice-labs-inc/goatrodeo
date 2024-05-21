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
import goatrodeo.envelopes.EntryEnvelope
import goatrodeo.envelopes.MD5
import goatrodeo.envelopes.Position
import goatrodeo.envelopes.MultifilePosition
import goatrodeo.envelopes.PayloadFormat
import goatrodeo.envelopes.PayloadType
import goatrodeo.envelopes.PayloadCompression
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
        MD5(Helpers.randomBytes(16)),
        Position(Helpers.randomLong()),
        Helpers.randomLong(),
        MultifilePosition(Position(Helpers.randomLong()), Helpers.randomLong()),
        (Helpers.randomLong() & 0xffffff) + 1,
        Helpers.randomInt(),
        PayloadFormat.CBOR,
        PayloadType.ENTRY,
        PayloadCompression.NONE,
        false
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

      val ee3 = EntryEnvelope.decodeCBOR(bytes).get

      assertEquals(ee, ee3, "Should be the same before and after serialization")

      if (false) {
        (new FileOutputStream(f"test_data/data_${i}.cbor")).write(bytes)

      }

    }

  }
  test("EntryEnvelope Serialization from round trips") {

    import io.bullet.borer.Dom.*

    for { i <- 0 to 1000 } {
      val theFile = new File(f"test_data/data_${i}.cbor")
      if (theFile.exists()) {
        val cbor = new FileInputStream(theFile).readAllBytes()

        val tmp = Cbor.decode(cbor).to[Element].value

        val newBytes = Cbor.encode(tmp).toByteArray

        assertEquals(cbor.toVector, newBytes.toVector)

        val ee = EntryEnvelope.decodeCBOR(cbor).get

        val ee2: ItemEnvelope =
          EntryEnvelope.decodeCBOR(ee.encodeCBOR()).get
        assertEquals(ee, ee2, f"Test run ${i}")

        if (cbor.toVector != ee2.encodeCBOR().toVector) {
          throw new Exception(
            f"Not equal iteration ${i}\n${Cbor.decode(cbor).to[Element].value}\n${Cbor.decode(ee2.encodeCBOR()).to[Element].value}"
          )
        }

        assertEquals(
          cbor.toVector,
          ee2.encodeCBOR().toVector,
          "Round trip bytes equal"
        )
      }
    }
  }

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

  val toCompress = """public final class goatrodeo.Howdy$ implements java.io.Serializable {
  public static final long OFFSET$_m_1;

  public static final long OFFSET$_m_0;

  public static final goatrodeo.Howdy$Config$ Config;

  private volatile java.lang.Object builder$lzy1;

  private volatile java.lang.Object parser1$lzy1;

  private static final goatrodeo.Howdy$ExpandFiles$ ExpandFiles;

  public static final goatrodeo.Howdy$ MODULE$;

  private goatrodeo.Howdy$();
    Code:
       0: aload_0
       1: invokespecial #57                 // Method java/lang/Object."<init>":()V
       4: return

  private static {};
    Code:
       0: getstatic     #64                 // Field scala/runtime/LazyVals$.MODULE$:Lscala/runtime/LazyVals$;
       3: ldc           #2                  // class goatrodeo/Howdy$
       5: ldc           #65                 // String parser1$lzy1
       7: invokevirtual #71                 // Method java/lang/Class.getDeclaredField:(Ljava/lang/String;)Ljava/lang/reflect/Field;
      10: invokevirtual #75                 // Method scala/runtime/LazyVals$.getOffsetStatic:(Ljava/lang/reflect/Field;)J
      13: putstatic     #77                 // Field OFFSET$_m_1:J
      16: getstatic     #64                 // Field scala/runtime/LazyVals$.MODULE$:Lscala/runtime/LazyVals$;
      19: ldc           #2                  // class goatrodeo/Howdy$
      21: ldc           #78                 // String builder$lzy1
      23: invokevirtual #71                 // Method java/lang/Class.getDeclaredField:(Ljava/lang/String;)Ljava/lang/reflect/Field;
      26: invokevirtual #75                 // Method scala/runtime/LazyVals$.getOffsetStatic:(Ljava/lang/reflect/Field;)J
      29: putstatic     #80                 // Field OFFSET$_m_0:J
      32: new           #2                  // class goatrodeo/Howdy$
      35: dup
      36: invokespecial #81                 // Method "<init>":()V
      39: putstatic     #83                 // Field MODULE$:Lgoatrodeo/Howdy$;
"""

  test("Compression") {
    val bytes = toCompress.getBytes("UTF-8")

    var b2 = PayloadCompression.NONE.compress(bytes)
    assertEquals(bytes.length, b2.length)
    assertEquals(bytes.toVector, b2.toVector)
    b2 = PayloadCompression.NONE.deCompress(b2)

    assertEquals(bytes.length, b2.length)
    assertEquals(bytes.toVector, b2.toVector)

    b2 = PayloadCompression.GZIP.compress(bytes)
    assert(bytes.length > b2.length)
    b2 = PayloadCompression.GZIP.deCompress(b2)

    assertEquals(bytes.length, b2.length)
    assertEquals(bytes.toVector, b2.toVector)

    b2 = PayloadCompression.DEFLATE.compress(bytes)
    assert(bytes.length > b2.length)
    b2 = PayloadCompression.DEFLATE.deCompress(b2)

    assertEquals(bytes.length, b2.length)
    assertEquals(bytes.toVector, b2.toVector)

    assert(
      PayloadCompression.DEFLATE
        .compress(bytes)
        .toVector != PayloadCompression.GZIP.compress(bytes).toVector,
      "The different compression algorithms should produce different results"
    )
  }

  test("File Type Detection") {
    assert(
      BuildGraph
        .streamForArchive(File("test_data/HP1973-Source.zip"))
        .isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(File("test_data/log4j-core-2.22.1.jar"))
        .isDefined
    )
    assert(
      BuildGraph.streamForArchive(File("test_data/empty.tgz")).isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(File("test_data/toml-rs.tgz"))
        .isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(File("test_data/tk8.6_8.6.14-1build1_amd64.deb"))
        .isDefined
    )
    assert(
      BuildGraph
        .streamForArchive(File("test_data/tk-8.6.13-r2.apk"))
        .isDefined
    )

    assert(
      BuildGraph
        .streamForArchive(File("test_data/ics_test.tar"))
        .isDefined
    )

    assert(
      BuildGraph
        .streamForArchive(File("test_data/nested.tar"))
        .isDefined
    )
  }

  test("Walk a tar file") {
    var cnt = 0
    val inputStream =
      BuildGraph.streamForArchive(File("test_data/empty.tgz")).get
    var entry = inputStream.getNextEntry()
    while (entry != null) {
      if (inputStream.canReadEntryData(entry)) {
        cnt += 1
      }
      entry.getSize()
      entry = inputStream.getNextEntry()
    }

    assert(cnt > 2)
  }

  test("deal with nesting") {
    val nested = File("test_data/nested.tar")
    assert(nested.isFile() && nested.exists())

    var cnt = 0

    BuildGraph.processFileAndSubfiles(
      nested,
      "nested",
      None,
      (file, name, parent) => {
        cnt += 1
        val (main, _) = GitOIDUtils.computeAllHashes(file)
        // println(f"hash for ${name} is ${main} parent ${parent}")
        main
      }
    )
    assert(cnt > 1200)
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
      Map()
    )

    assert(got.size > 1200)
    assert(store.size().get > 2200)
    val keys = store.keys().get
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
    val files = ToProcess.buildQueue(source)

    assert(files.size() == 2)

    val store = MemStorage.getStorage(Some(File("/tmp/frood")))
    import scala.collection.JavaConverters.collectionAsScalaIterableConverter
    import scala.collection.JavaConverters.iterableAsScalaIterableConverter

    for { toProcess <- files.asScala } {
      BuildGraph.graphForToProcess(toProcess, store)
    }

    val keys = store.keys().get
    val items = keys.flatMap(store.read(_))
    assert(items.length > 1100)

    val sourceRef = items.filter(i =>
      i.connections.filter(e => e._2 == EdgeType.BuiltFrom).length > 0
    )
    val fromSource = for {
      i <- items; c <- i.connections if c._2 == EdgeType.BuildsTo
    } yield c
    assert(sourceRef.length > 100)

    assert(fromSource.length == sourceRef.length)

    // the package URL is picked up
    val withPurl = items.filter(i =>
      i.altIdentifiers.filter(_.startsWith("pkg:")).length > 0
    )

    assert(withPurl.length == 4)

    val withPurlSources = withPurl.filter(i =>
      i.altIdentifiers.filter(_.endsWith("?packaging=sources")).length > 0
    )
    assert(withPurlSources.length == 2)
  }

  test("Build lots of JARs") {
    val source = File(File(System.getProperty("user.home")),"/tmp/repo_ea")

    val store = MemStorage.getStorage(Some(File("frood_dir/")))
    import scala.collection.JavaConverters.collectionAsScalaIterableConverter
    import scala.collection.JavaConverters.iterableAsScalaIterableConverter

    Builder.buildDB(source, store, 16)

    Builder.writeGoatRodeoFiles(store)

    // FIXME -- find bundle
  }

}
