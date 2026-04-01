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
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

class FatJarContentsTest extends munit.FunSuite {

  test("fat JAR must not contain signature files") {
    val fatJarFile =
      new File(s"target/scala-3.8.3/goatrodeo-0.0.1-SNAPSHOT-fat.jar")
    assert(
      fatJarFile.exists(),
      s"Fat JAR not found at ${fatJarFile.getAbsolutePath}"
    )

    val jar = new JarFile(fatJarFile)
    try {
      val entries = jar.entries().asScala.toList
      val signatureFiles = entries.filter { e =>
        val name = e.getName
        name.startsWith("META-INF/") && (
          name.endsWith(".SF") || name.endsWith(".DSA") ||
            name.endsWith(".RSA") || name.endsWith(".EC") || name.startsWith(
              "SIG-"
            )
        )
      }

      assert(
        signatureFiles.isEmpty,
        s"Found signature files in fat JAR: ${signatureFiles.map(_.getName).mkString(", ")}"
      )
    } finally {
      jar.close()
    }
  }
}
