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

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** Tests that the Maven build emits SemanticDB files just like the sbt build
  * does when `semanticdbEnabled := true`.
  *
  * Requirement trace: build.sbt sets `semanticdbEnabled := true` and
  * `semanticdbVersion := scalafixSemanticdb.revision`. The Maven build must
  * produce equivalent `.semanticdb` artifacts so that scalafix and other
  * SemanticDB consumers can operate on this project.
  *
  * Theory: compiling a non-empty Scala source tree with `-Ysemanticdb` places
  * at least one `.semanticdb` file under `target/classes/META-INF/semanticdb`.
  * This test scans that directory and asserts it is non-empty, guarding against
  * a future regression where the compiler flag is accidentally removed.
  */
class SemanticDBTest extends munit.FunSuite {

  test("SemanticDB files are generated under target/classes") {
    val semanticDbRoot = new File("target/classes/META-INF/semanticdb")
    assert(
      semanticDbRoot.isDirectory,
      s"Expected SemanticDB root ${semanticDbRoot.getAbsolutePath} to exist"
    )

    val files = Files
      .walk(semanticDbRoot.toPath)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .filter(_.toString.endsWith(".semanticdb"))
      .toList

    assert(
      files.nonEmpty,
      s"No .semanticdb files found under ${semanticDbRoot.getAbsolutePath}"
    )
  }
}
