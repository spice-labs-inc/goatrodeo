/* Copyright 2025-2026 Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.IncludeExclude
import io.spicelabs.goatrodeo.util.StaticMetadata
import org.json4s.*

import java.io.File
import io.spicelabs.goatrodeo.omnibor.ToProcess
import scala.collection.immutable.TreeSet
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.omnibor.StringOrPair
class MetadataSuite extends munit.FunSuite {
  test("Metadata collections works") {
    if (StaticMetadata.hasSyft) {
      val file = File("test_data/jar_test/slf4j-simple-1.6.1.jar")
      val fileWrapper =
        FileWrapper(file, "slf4j-simple-1.6.1.jar", None, f => ())
      val runner =
        StaticMetadata.runStaticMetadataGather(
          fileWrapper,
          file.toPath(),
          IncludeExclude()
        )
      val (str, json) = runner.get.runForMillis(100000).get

      assert(str.length() > 400, s"Metadata answer too small ${str}")
    } else {
      assert(true)
    }
  }

  test("Metadata collections works on nested stuff") {
    if (StaticMetadata.hasSyft) {
      val file = File("test_data/nested.tar")
      val fileWrapper =
        FileWrapper(file, "slf4j-simple-1.6.1.jar", None, f => ())
      val runner =
        StaticMetadata.runStaticMetadataGather(
          fileWrapper,
          file.toPath(),
          IncludeExclude()
        )
      val (str, json) = runner.get.runForMillis(100000).get

      // val artifacts =

      val purls = for {
        case JArray(artifacts) <- json \ "artifacts"
        artifact <- artifacts
        case JString(purl) <- artifact \ "purl"
      } yield purl

      assert(
        purls == List(
          "pkg:maven/org.apache.logging.log4j/log4j-core@2.22.1",
          "pkg:deb/tk8.6@8.6.14-1build1?arch=amd64"
        ),
        f"expected to get proper purls, got ${purls}"
      )
    } else {
      assert(true)
    }
  }

  test("deb metadata") {
      val file = File("test_data/debwithmetadata.deb")
      val nested = FileWrapper(file, "debwithmetadata.deb", None, f => ())
      //val nested = FileWrapper(file, name, None)
      val store1 =
        ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

      val result = store1.purls()

      assertEquals(
        result,
        TreeSet("pkg:deb/not-a-real-package@1.2.3")
      )

      val mainitemOpt = store1.read("gitoid:blob:sha256:9ae1be82894af5681fcd9947792bc9e8e5dcea5f81b848dc5a0068d50b93ac51")
      assert(mainitemOpt.isDefined)
      val item = mainitemOpt.get
      val metadataOpt = item.bodyAsItemMetaData
      assert(metadataOpt.isDefined)
      val metadata = metadataOpt.get
      val extra = metadata.extra
      val fail = TreeSet(StringOrPair("fail"))
      
      val arch = extra.getOrElse("Baharat:Arch", fail)
      val size = extra.getOrElse("Baharat:Installed_size", fail)
      val maint = extra.getOrElse("Baharat:Maintainer", fail)
      val provides = extra.getOrElse("Baharat:Provides", fail)
      val sum = extra.getOrElse("Baharat:Summary", fail)
      val deps = extra.getOrElse("Dependencies", fail)
      val desc = extra.getOrElse("Description", fail)
      val name = extra.getOrElse("Name", fail)
      val version = extra.getOrElse("Version", fail)
  
      assertContents(arch, "any")
      assertNotEquals(size, fail)
      assertContents(maint, "Spice Labs<info@spice-labs.io>")
      assertNotEquals(provides, fail)
      assertNotEquals(sum, fail)
      assertNotEquals(deps, fail)
      assertContents(desc, "This is a basic description of this package")
      assertContents(name, "not-a-real-package")
      assertContents(version, "1.2.3")
  }

  def assertContents(ts: TreeSet[StringOrPair], expected: String): Unit = {
    val arr = ts.toArray
    assertEquals(arr.length, 1)
    val sop = arr(0)
    assertEquals(sop.value, expected)
  }
}
