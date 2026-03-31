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
import scala.sys.process._

class FatJarExecutionTest extends munit.FunSuite {

  test("fat JAR must execute without SecurityException") {
    val fatJarFile = new File(s"target/scala-3.8.3/goatrodeo-0.0.1-SNAPSHOT-fat.jar")
    assert(fatJarFile.exists(), s"Fat JAR not found at ${fatJarFile.getAbsolutePath}")

    val result = Process(Seq("java", "-jar", fatJarFile.getAbsolutePath, "--help")).!
    assertEquals(
      result,
      0,
      s"Fat JAR failed to execute with exit code: $result"
    )
  }
}
