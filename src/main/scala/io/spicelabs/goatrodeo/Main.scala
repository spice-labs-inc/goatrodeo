/* Copyright 2024 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo

import com.typesafe.scalalogging.Logger
import io.bullet.borer.Dom
import io.spicelabs.goatrodeo.omnibor.Builder
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.TagInfo
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.Helpers
import scopt.OParser

import java.io.File
import java.nio.file.Files
import scala.annotation.static
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import io.spicelabs.goatrodeo.components.Arguments
import io.spicelabs.goatrodeo.components.RodeoHost

/** Marker class for the main entry point. */
class Howdy

/** The main entry point for the Goat Rodeo CLI application.
  *
  * Goat Rodeo is a tool for building Artifact Dependency Graphs (ADGs)
  * using the OmniBOR specification. It processes software artifacts
  * (JARs, DEBs, Docker images, .NET assemblies, etc.) to generate
  * content-addressable GitOID identifiers and track relationships
  * between artifacts.
  *
  * Usage:
  * {{{
  * goatrodeo --build /path/to/artifacts --out /path/to/output
  * }}}
  *
  * @see [[https://omnibor.io/ OmniBOR Specification]]
  */
object Howdy {
  private val logger = Logger(getClass())

  /** The entrypoint
    *
    * @param args
    *   an array of command line paramets
    */
  def main(args: Array[String]): Unit = {
    logger.info(
      f"Goat Rodeo version ${hellogoat.BuildInfo.version} (commit: ${hellogoat.BuildInfo.commit})"
    )

    // parse the CLI params
    val parsed = OParser.parse(Config.parser1, args, Config())

    // Based on the CLI parse, make the right choices and do the right thing
    parsed match {
      case Some(params) => run(params)
      case _            => Helpers.bailFail()
    }
  }

  /** Run the Goat Rodeo builder with the given configuration.
    *
    * This is the main processing method that:
    *   1. Validates configuration parameters
    *   2. Sets up file ingestion tracking if requested
    *   3. Processes files to build the ADG
    *   4. Outputs results to the specified directory
    *
    * @param params
    *   the configuration parameters
    */
  @static
  def run(params: Config): Unit = {
    startComponents(params)

    val logger = Logger(getClass())

    val fileListers = params.getFileListBuilders()

    if (!params.nonexistantDirectories.isEmpty) {
      logger.error(
        "One or more directories in a -b or --build option was not found: "
      )
      params.nonexistantDirectories.foreach(f =>
        logger.error(f.getAbsolutePath())
      )
      logger.info(OParser.usage(Config.parser1))
      Helpers.bailFail()
      return
    }

    if (fileListers.isEmpty) {
      logger.error("At least one `-b` or `--file-list` must be provided")
      logger.info(OParser.usage(Config.parser1))
      Helpers.bailFail()
      return
    }

    // if the `ingested` option was selected, build functions to
    // capture what was ingested and output it on successful run
    val (onFileFinish: (File => Unit), onRunFinish: (Boolean => Unit)) =
      params.ingested match {
        case None => ((f: File) => {}, (good: Boolean) => {})
        case Some(destFile) => {
          @volatile
          var success: Vector[File] = Vector()
          val sync = Object()
          (
            (f: File) => {
              sync.synchronized {
                success = success :+ f.getCanonicalFile()
              }; ()
            },
            (good: Boolean) => {
              if (good) {
                logger.info(
                  f"Completed, exporting ${success.length} ingested items to ${destFile}"
                )

                val out = java.io.FileWriter(
                  destFile,
                  java.nio.charset.Charset.forName("UTF-8"),
                  true
                )
                for { f <- success } {
                  out.write(f.getPath())
                  out.write("\n")
                }
                out.flush()
                out.close()
              } else {
                logger.error("Failed to process the input.")
                Helpers.exitWrapper(1) // non-zero exit
              }

              ()
            }
          )
        }
      }

    // get the set of paths to ignore
    val ignorePathSet = (
      for {
        ignore <- params.ignore
        lines <- Try {
          Files.readAllLines(ignore.toPath()).asScala
        }.toOption.toVector
        line <- lines
      } yield line
    ).toSet

    val dest = params.out match {
      case None =>
        logger.error("Must provide an `--out` directory")
        Helpers.bailFail()
        return
      case Some(d) => d
    }

    var badPat = false
    val excludePatterns = params.exclude.flatMap((str, pat) =>
      pat match {
        case Failure(exception) =>
          logger.error(
            f"Exclude pattern ${str} failed to compile to a regular expression ${exception.getMessage()}"
          )
          badPat = true
          None
        case Success(value) => Some(value)
      }
    )

    if (badPat) {
      Helpers.bailFail()
      return
    }

    val preWriteDB: Vector[Storage => Boolean] =
      params.dumpRootDir.toVector.map(dir =>
        (storage: Storage) => { storage.emitRootsToDir(dir); true }
      ) ++
        params.emitJsonDir.toVector.map(dir =>
          (storage: Storage) => { storage.emitAllItemsToDir(dir); true }
        )

    Builder.buildDB(
      dest = dest,
      tempDir = params.tempDir,
      threadCnt = params.threads,
      maxRecords = params.maxRecords,
      tag = (params.tag, params.tagJson) match {
        case (None, None)       => None
        case (Some(tag), v)     => Some(TagInfo(tag, v))
        case (_, Some(tagJson)) => Some(TagInfo("N/A", Some(tagJson)))
      },
      fileListers = fileListers,
      ignorePathSet = ignorePathSet,
      excludeFileRegex = excludePatterns,
      blockList = params.blockList,
      finishedFile = onFileFinish,
      done = onRunFinish,
      args = params,
      preWriteDB = preWriteDB,
      fsFilePaths = params.fsFilePaths
    )

    Helpers.exitZero()
  }

  /** Initialize and start the component system.
    *
    * Starts the RodeoHost component runtime, processes component arguments,
    * and optionally prints component information.
    *
    * @param params
    *   the configuration parameters containing component settings
    */
  @static
  def startComponents(params: Config) = {
    val host = RodeoHost.host
    host.begin()
    host.exportImport()
    if (params.printComponentInfo) {
      host.printComponentInfo()
      Helpers.exitZero()
    }
    if (params.printComponentArgumentInfo) {
      Arguments.printDescriptions()
      Helpers.exitZero()
    }
    Arguments.processComponentArguments(params.componentArgs)
    host.completeLoading()
  }
}
