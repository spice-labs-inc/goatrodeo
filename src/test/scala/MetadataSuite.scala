/* Copyright 2025 Spice Labs, Inc. & Contributors

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
}
