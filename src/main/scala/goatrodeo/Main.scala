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

package goatrodeo

import com.typesafe.scalalogging.Logger
import goatrodeo.omnibor.Builder
import goatrodeo.util.Helpers
import org.apache.commons.io.filefilter.WildcardFileFilter
import scopt.OParser
import scopt.OParserBuilder

import java.io.File
import java.io.FileFilter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.regex.Pattern
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** The `main` class
  */
object Howdy {
  private val logger = Logger(getClass())

  /** Command Line Configuration
    *
    * @param analyze
    *   -- analyze a file against the GC (GitOID Corpus)
    * @param out
    *   -- the place to output either the analysis or the results of the build
    * @param build
    *   -- build a GitOID Corpus based on JAR files found in the directory
    * @param threads
    *   -- the number of threads for the build -- default 4... typically 4x the
    *   number of physical CPUs
    */
  case class Config(
      out: Option[File] = None,
      build: Vector[File] = Vector(),
      ingested: Option[File] = None,
      ignore: Vector[File] = Vector(),
      fileList: Vector[File] = Vector(),
      exclude: Vector[(String, Try[Pattern])] = Vector(),
      threads: Int = 4,
      blockList: Option[File] = None,
      maxRecords: Int = 50000,
      tempDir: Option[File] = None
  ) {
    def getFileListBuilders(): Vector[() => Seq[File]] = {
      build.map(file => () => Helpers.findFiles(file, f => true)) ++ fileList
        .map(f => {
          val fileNames =
            Files
              .readAllLines(f.toPath())
              .asScala
              .toSeq
              .map(fn => new File(fn))
              .filter(_.exists())
          () => fileNames
        })
    }
  }

  lazy val builder: OParserBuilder[Config] = OParser.builder[Config]
  lazy val parser1: OParser[Unit, Config] = {
    import builder._
    OParser.sequence(
      programName("goatrodeo"),
      head("goatrodeo", hellogoat.BuildInfo.version),
      opt[File]("block")
        .text(
          "The gitoid block list. Do not process these gitoids. Used for common gitoids such as license files"
        )
        .action((x, c) => c.copy(blockList = ExpandFiles(x).headOption)),
      opt[File]('b', "build")
        .text("Build gitoid database from jar files in a directory")
        .action((x, c) =>
          c.copy(build =
            (c.build ++ ExpandFiles(x))
              .filter(f => f.exists() && f.isDirectory())
          )
        ),
      opt[File]("ingested")
        .text(
          "Append all the ingested files to this file on successful completion"
        )
        .action((x, c) => c.copy(ingested = ExpandFiles(x).headOption)),
      opt[File]("ignore")
        .text(
          "A file containing paths to ignore, likely because they have been processed in the past"
        )
        .action((x, c) =>
          c.copy(ignore = (c.ignore ++ ExpandFiles(x)).filter(_.exists()))
        ),
      opt[File]("file-list")
        .text(
          "A file containing a list of files to process. This may be used in conjunction with the `-b` (build) flag and this list may be generated by an external process"
        )
        .action((file: File, config: Config) =>
          config.copy(fileList =
            (config.fileList ++ ExpandFiles(file)).filter(_.exists())
          )
        ),
      opt[String]("exclude-pattern")
        .text(
          "A regular expression pattern that can be used to exclude files, for example `html$` will exclude all files that end in `html`"
        )
        .action((p, config) =>
          config.copy(exclude = (config.exclude :+ (p -> Try {
            Pattern.compile(p)
          })))
        ),
      opt[Int]("maxrecords")
        .text(
          "The maximum number of records to process at once. Default 50,000"
        )
        .action((x, c) => if (x > 100) c.copy(maxRecords = x) else c),
      opt[File]('o', "out")
        .text("output directory for the file-system based gitoid storage")
        .action((x, c) => c.copy(out = Some(x))),
      opt[File]("tempdir")
        .text("Where to temporarily store files... should be a RAM disk")
        .action((x, c) => c.copy(tempDir = Some(x))),
      opt[Int]('t', "threads")
        .text(
          "How many threads to run (default 4). Should be 2x-3x number of cores"
        )
        .action((t, c) => c.copy(threads = t)),
      opt[Unit]('V', "version")
        .text("print version and exit")
        .action((_, c) => {
          logger.info(f"Goat Rodeo version ${hellogoat.BuildInfo}")
          System.exit(0)
          c
        }),
      opt[Unit]('?', "help")
        .text("print help and exit")
        .action((_, c) => {
          logger.info(OParser.usage(parser1))
          System.exit(0)
          c
        })
    )
  }

  object ExpandFiles {
    def apply(in: File): Vector[File] = {

      val fixed = fixTilde(in)
      if (!fixed.exists()) {
        Vector(fixed)
      } else {
        val allFiles = {
          val parent = fixed.getAbsoluteFile().getParentFile()
          val wcf: FileFilter =
            WildcardFileFilter.builder.setWildcards(fixed.getName()).get()
          parent.listFiles(wcf)
        }
        allFiles.toVector
      }
    }
    def unapply(in: Option[Vector[File]]): Option[Vector[File]] =
      in match {
        case None => None
        case Some(vf) =>
          val ret = for {
            fa <- vf
            f = fixTilde(fa)
            i <- {
              val parent = f.getAbsoluteFile().getParentFile()
              val wcf: FileFilter =
                WildcardFileFilter.builder.setWildcards(f.getName()).get()
              parent.listFiles(wcf)
            }
          } yield {
            i
          }
          Some(ret)
      }

    def fixTilde(in: File): File = {
      if (in.getPath().startsWith("~" + File.separator)) {
        val path = System.getProperty("user.home") + in.getPath().substring(1)
        new File(path)
      } else in
    }
  }

  /** The entrypoint
    *
    * @param args
    *   an array of command line paramets
    */
  def main(args: Array[String]): Unit = {
    val logger = Logger(getClass())
    // parse the CLI params
    val parsed = OParser.parse(parser1, args, Config())

    // Based on the CLI parse, make the right choices and do the right thing
    parsed match {
      case Some(params) =>
        val fileListers = params.getFileListBuilders()

        if (fileListers.isEmpty) {
          logger.error("At least one `-b` or `--file-list` must be provided")
          logger.info(OParser.usage(parser1))
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
                  sync.synchronized { success = success :+ f }; ()
                },
                (good: Boolean) => {
                  if (good) {
                    logger.info(
                      f"Completed, exporting ${success.length} ingested items to ${destFile}"
                    )
                    Files.writeString(
                      destFile.toPath(),
                      (success
                        .foldLeft(StringBuilder()) { case (sb, file) =>
                          sb.append(file.getCanonicalPath())
                          sb.append('\n')
                          sb
                        })
                        .toString(),
                      StandardOpenOption.CREATE,
                      StandardOpenOption.APPEND
                    )
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

        Builder.buildDB(
          dest = dest,
          tempDir = params.tempDir,
          threadCnt = params.threads,
          maxRecords = params.maxRecords,
          fileListers = fileListers,
          ignorePathSet = ignorePathSet,
          excludeFileRegex = excludePatterns,
          blockList = params.blockList,
          finishedFile = onFileFinish,
          done = onRunFinish
        )

      case _ => Helpers.bailFail()
    }
  }
}
