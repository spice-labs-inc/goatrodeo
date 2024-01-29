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

import goatrodeo.loader.GitOID
import goatrodeo.loader.PackageIdentifier
import goatrodeo.loader.PackageProtocol
import goatrodeo.omnibor.SqlLiteStorage
import java.util.regex.Pattern
import goatrodeo.util.Helpers

// For more information on writing tests, see
// https://scalameta.org/munit/docs/getting-started.html
class MySuite extends munit.FunSuite {
  test("example test that succeeds") {
    val obtained = 42
    val expected = 42
    assertEquals(obtained, expected)
  }

  test("gitoid to file") {
    val test = List("gitoid:blob:sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
    "blob:sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
    ":sha256:880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
    "880485f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a",
    )
    test.foreach(v => assertEquals(GitOID.urlToFileName(v), ("880", "485", "f48092dd308a2ad8a7b6ce060c4b2ec81ecb4ba3f5fd450b79136a852a")))
  }

  test("Get OSV") {
    val pi = PackageIdentifier(PackageProtocol.Maven, "org.apache.logging.log4j", "log4j-core", "2.7")
    val osv = pi.getOSV().get
    val vulns = osv("vulns")
    assert(vulns.arr.length > 0)
  }

    test("Fail Get OSV") {
    val pi = PackageIdentifier(PackageProtocol.Maven, "org.apache.logging.lZOOog4j", "log4j-core", "3232.7")
    
    val osvt = pi.getOSV()

    assert(osvt.isFailure)
  }

  test("regex") {
    val p = Pattern.compile("a")
    val m = p.matcher("aaaa")
    assert(m.find())
  }

  test("good hex for sha256") {
    val txt = Array[Byte](49, 50, 51, 10)
    val digest = GitOID.HashType.SHA256.getDigest()
    assertEquals(Helpers.toHex(digest.digest(txt)), "181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b")
  }

  test("Can store stuff") {
    val theFile = new java.io.File("frood.db")
    if (theFile.exists()) {
      theFile.delete()
    }
    val st = SqlLiteStorage.getStorage(theFile)
    assertEquals(st.exists("wombat"), false)
    val dogboy = "dogboy".getBytes("UTF-8")
    st.write("wombat", dogboy)
    assertEquals(st.exists("wombat"), true)
    val ret: Option[Array[Byte]] = st.read("wombat")
    assertEquals(new String(dogboy), new String(ret.get))

    for {i <- 1 to 100000} {
      val it = f"store ${i}"
      st.write(it, it)
      val gotten = st.read(it)
      assertEquals(it, new String(gotten.get))
    }

    st.release()
  }
}
