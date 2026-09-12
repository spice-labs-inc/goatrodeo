/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

class SemanticDBTest extends GoatRodeoFunSuite {

  test("SemanticDB files are generated") {
    val semanticDbRoot = Some(
      new File("target/scala-3.8.3/meta/META-INF/semanticdb")
    ).filter(_.isDirectory)
    assert(
      semanticDbRoot.isDefined,
      "Expected a SemanticDB root under target/scala-3.8.3/meta/META-INF/semanticdb"
    )

    val files = Files
      .walk(semanticDbRoot.get.toPath)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .filter(_.toString.endsWith(".semanticdb"))
      .toList

    assert(
      files.nonEmpty,
      s"No .semanticdb files found under ${semanticDbRoot.get.getAbsolutePath}"
    )
  }
}
