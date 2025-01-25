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

import goatrodeo.loader.{Loader, Analyzer}
import scala.collection.JavaConverters._
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import scopt.OParser
import goatrodeo.util.Helpers
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import goatrodeo.omnibor.{Storage, ListFileNames, Builder}
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream
import java.net.URL
import org.apache.commons.io.filefilter.WildcardFileFilter
import java.io.FileFilter
import java.nio.ByteBuffer
import goatrodeo.util.Helpers.bailFail
import goatrodeo.toplevel.HiddenReaper
import goatrodeo.util.FileWrapper

/** The `main` class
  */
object Howdy {

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
      analyze: Option[File] = None,
      out: Option[File] = None,
      build: Option[File] = None,
      threads: Int = 4,
      fetchURL: URL = new URL("https://goatrodeo.org/omnibor"),
      hiddenNoMore: Boolean = false,
      toAnalyzeDir: File = new File("/jars_to_test")
  )

  lazy val builder = OParser.builder[Config]
  lazy val parser1 = {
    import builder._
    OParser.sequence(
      programName("goatrodeo"),
      head("goatrodeo", "0.4.6"),
      opt[File]('a', "analyze")
        .action((x, c) =>
          c.copy(analyze = Some(x).filter(f => f.exists() && f.isFile()))
        )
        .text("Analyze a JAR or WAR file"),
      opt[URL]('f', "fetch")
        .text("Fetch OmniBOR ids from which URL")
        .action((u, c) => c.copy(fetchURL = u)),
      opt[File]('b', "build")
        .text("Build gitoid database from jar files in a directory")
        .action((x, c) =>
          c.copy(build = Some(x).filter(f => f.exists() && f.isDirectory()))
        ),
      opt[File]('o', "out")
        .text("output directory for the file-system based gitoid storage")
        .action((x, c) => c.copy(out = Some(x))),
      opt[File]("toanalyzedir")
        .text("The directory to scan for Hidden Reaper items")
        .action((x, c) => c.copy(toAnalyzeDir = x)),
      opt[Unit]("unmask")
        .text("Test artifacts against the grim list and unmask Hidden Reapers")
        .action((_, c) => c.copy(hiddenNoMore = true)),
      opt[Int]('t', "threads")
        .text(
          "How many threads to run (default 4). Should be 2x-3x number of cores"
        )
        .action((t, c) => c.copy(threads = t))
    )
  }

  private object ExpandFiles {
    def unapply(in: Option[Vector[File]]): Option[Vector[File]] =
      in match {
        case None => None
        case Some(vf) =>
          val ret = for {
            fa <- vf
            f = fixTilde(fa)
            i <- {
              val parent = f.getAbsoluteFile().getParentFile()
              val wcf: FileFilter = new WildcardFileFilter(f.getName())
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
    // parse the CLI params
    val parsed = OParser.parse(parser1, args, Config())

    // Based on the CLI parse, make the right choices and do the right thing
    parsed match {
      case Some(Config(None, outDir, None, threads, _, true, toAnalyzeDir)) =>
        HiddenReaper.deGrimmify(toAnalyzeDir, outDir.getOrElse(new File("/out")), threads)

      case Some(Config(Some(_), _, Some(_), _, _, false, _)) =>
        println("Cannot do both analysis and building...")
        println(OParser.usage(parser1))
        Helpers.bailFail()

      case Some(Config(None, _, None, _, _, false, _)) =>
        println("You must either build or analyze...");
        println(OParser.usage(parser1))
        Helpers.bailFail()

      case Some(Config(Some(analyzeFile), _, _, _, fetch, false, _)) =>
        Analyzer.analyze(analyzeFile, fetch)

      case Some(Config(_, out, Some(buildFrom), threads, _, false, _))
          if out.isDefined =>
        Builder.buildDB(
          buildFrom,
          Storage.getStorage(out),
          threads
        )

      case Some(
            Config(_, out, Some(buildFrom), threads, _, false, _)
          ) =>
        println(
          "`out`  must be defined... where does the build result go?"
        )
        println(OParser.usage(parser1))
        Helpers.bailFail()

      case _ => Helpers.bailFail()
    }
  }
}
