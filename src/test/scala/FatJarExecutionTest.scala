import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import scala.sys.process.*

class FatJarExecutionTest extends GoatRodeoFunSuite {

  test("fat JAR must execute without SecurityException") {
    val fatJarFile =
      new File(
        s"target/scala-3.8.3/${hellogoat.BuildInfo.name}-${hellogoat.BuildInfo.version}-fat.jar"
      )
    assert(
      fatJarFile.exists(),
      s"Fat JAR not found at ${fatJarFile.getAbsolutePath}"
    )

    val result =
      Process(Seq("java", "-jar", fatJarFile.getAbsolutePath, "--help")).!
    assertEquals(
      result,
      0,
      s"Fat JAR failed to execute with exit code: $result"
    )
  }
}
