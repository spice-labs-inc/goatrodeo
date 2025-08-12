package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import scala.util.Try

/** The bridge to Syft https://github.com/anchore/syft
  */
object Syft {
  private lazy val syftMimeTypes = Set(
    "application/zip",
    "application/java-archive",
    "application/vnd.android.package-archive",
    "application/x-rpm",
    "application/x-archive",
    "application/x-iso9660-image",
    "application/x-gtar",
    "application/x-debian-package",
    "application/gzip",
    "application/zstd"
  )

  /** Is the artifact a container that Syft knowns how to process?
    *
    * @param artifact
    * @return
    */
  def isContainer(artifact: ArtifactWrapper): Boolean = {
    syftMimeTypes.contains(artifact.mimeType)
  }

  def runSyftFor(artfiact: ArtifactWrapper, tempDir: Path): SyftResult = {
    val targetFile = artfiact.forceFile(tempDir)
    val pb = ProcessBuilder(
      List(
        "syft",
        "scan",
        f"file:${targetFile.getName()}",
        "--output",
        "syft-json"
      )*
    ).directory(targetFile.getCanonicalFile().getParentFile())
    val ret = SyftResult(pb, targetFile.getCanonicalPath())
    ret.go()

    ret

  }
}

object SyftResult {
  private val logger = Logger(this.getClass())
}

class SyftResult(private val process: ProcessBuilder, dir: String) {
  val startedAt = System.currentTimeMillis()
  @volatile private var running = false
  @volatile private var answer: Option[(String, org.json4s.JValue)] = None
  @volatile private var theProcess: Option[Process] = None
  @volatile private var processExitCode: Option[Int] = None

  /** Kick off the process
    */
  def go(): Unit = {
    Thread(
      () => {
        SyftResult.logger.info("Started Go thread")
        doBackground()
      },
      f"Syft ${dir}"
    ).start()
    this.synchronized {
      while (!running) {
        this.wait(10)
      }
    }
  }

  def exitCode(): Option[Int] = processExitCode
  def isRunning(): Boolean = running
  def theAnswer(): Option[(String, JValue)] = answer
  def runUntil(when: Long): Option[(String, JValue)] = {
    this.synchronized {
      if (running) {
        this.wait(when - System.currentTimeMillis())
      }
      theAnswer()
    }
  }

  private def doBackground(): Unit = {
    try {
      val myProcess = process.start()
      running = true
      this.synchronized {
        this.notifyAll()
      }
      try {

        theProcess = Some(myProcess)
        val reader = myProcess.getInputStream()
        val bos = ByteArrayOutputStream()
        val bytes: Array[Byte] = new Array(4096)
        var bytesRead = reader.read(bytes)
        while (bytesRead >= 0) {
          if (bytesRead > 0) {
            bos.write(bytes, 0, bytesRead)
          }
          bytesRead = reader.read(bytes)
        }

        // wait a while for the process to finish even after stdout has closed
        while (myProcess.isAlive()) {
          Thread.sleep(100)
        }

        if (myProcess.exitValue() == 0) {
          answer = Try {
            val theBytes = bos.toByteArray()
            val str = String(theBytes, "UTF-8")
            val jsonBase = parse(str)

            val json = jsonBase match {
              case JObject(obj) =>
                // remove some Syft noise
                JObject(obj.filter { f =>
                  f._1 != "source" && f._1 != "descriptor"
                })
              case v => v
            }
            (pretty(render(json)), json)
          }.toOption
        }
      } catch {
        case e: Throwable => SyftResult.logger.error("Failed to read", e)

      } finally {
        processExitCode = Some(myProcess.exitValue())
        running = false

        theProcess = None
        this.synchronized {
          this.notifyAll()
        }
      }

    } catch {
      case e: Throwable => SyftResult.logger.error("Failed to read", e)

    }
  }

}
