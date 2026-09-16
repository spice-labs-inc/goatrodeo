package io.spicelabs.goatrodeo

import ch.qos.logback.classic.Level
import io.spicelabs.goatrodeo.omnibor.Edge
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.strategies.DotnetState
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.FileWrapper
import org.slf4j.LoggerFactory

import java.lang.management.ManagementFactory
import java.nio.file.Files
import scala.collection.immutable.TreeSet

/** Regression: when Cilantro fails to read an assembly, the `FileInputStream`
  * opened for it must be closed.
  *
  * Pins `DotnetState.beginProcessing`: the stream is opened inside a
  * for-comprehension and is unreachable in the `Failure` branch, so every
  * unreadable PE (native DLLs inside fat jars are the common case) leaks a file
  * descriptor for the rest of the run.
  */
class DotnetStreamLeakSuite extends GoatRodeoFunSuite {
  private given Configuration = Configuration()

  test("a failed assembly read does not leak a file descriptor") {
    val os = ManagementFactory.getOperatingSystemMXBean
    assume(
      os.isInstanceOf[com.sun.management.UnixOperatingSystemMXBean],
      "open file descriptor count is only observable on a Unix JVM"
    )
    val unix = os.asInstanceOf[com.sun.management.UnixOperatingSystemMXBean]

    val blob = Files.createTempFile("dotnet-garbage", ".dll").toFile()
    blob.deleteOnExit()
    Files.write(blob.toPath, "this is not a PE/COFF assembly".getBytes("UTF-8"))
    val artifact = FileWrapper(blob, blob.getPath, None)
    val item = Item(
      "sha256:" + "cd" * 32,
      TreeSet.empty[Edge],
      Some("application/octet-stream"),
      None
    )

    val control = DotnetState().beginProcessing(artifact, item, SingleMarker())
    assert(
      control.maybePackageTag(SingleMarker()).isEmpty,
      "control: the read must fail"
    )

    // Sample after every read and assert on the peak growth: a leak shows as
    // a ramp long before any collection could close the unreachable streams,
    // while a correct implementation never rises more than a handful.
    val iterations = 300
    val before = unix.getOpenFileDescriptorCount
    var maxGrowth = 0L
    // 300 deliberate failures would otherwise log 300 lines; mute the
    // strategy's logger for the loop (restored afterwards, inherited level
    // when it was never explicitly set).
    val dotnetLogger = LoggerFactory
      .getLogger("io.spicelabs.goatrodeo.omnibor.strategies.DotnetState")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val savedLevel = dotnetLogger.getLevel
    dotnetLogger.setLevel(Level.OFF)
    try {
      for (_ <- 1 to iterations) {
        DotnetState().beginProcessing(artifact, item, SingleMarker())
        maxGrowth =
          math.max(maxGrowth, unix.getOpenFileDescriptorCount - before)
      }
    } finally dotnetLogger.setLevel(savedLevel)
    assert(
      maxGrowth < 50,
      s"open file descriptors grew by $maxGrowth during $iterations failed reads: the stream is not closed on failure"
    )
  }
}
