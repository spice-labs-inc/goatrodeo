/* Copyright 2024 David Pollak & Contributors

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

import goatrodeo.loader.{Loader, GitOID, Analyzer}
import scala.collection.JavaConverters._
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import upickle.default.*
import scopt.OParser
import goatrodeo.util.Helpers
import org.apache.lucene.index.IndexReader
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import goatrodeo.omnibor.{
  Storage,
  FileSystemStorage,
  ListFileNames,
  Builder,
  Merger
}
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream
import java.net.URL

/** The `main` class
  */
object Howdy {

  /** Command Line Configuration
    *
    * @param analyze
    *   -- analyze a file against the GC (GitOID Corpus)
    * @param out
    *   -- the place to output either the analysis or the results of the build
    * @param dbOut
    *   -- the name of the SQLite DB if that's the choice for outputs
    * @param build
    *   -- build a GitOID Corpus based on JAR files found in the directory
    * @param threads
    *   -- the number of threads for the build -- default 4... typically 4x the
    *   number of physical CPUs
    * @param inMem
    *   -- true/false... should the GitOID Corpus be built in memory (very fast,
    *   uses lots of RAM) and be dumped into sharded files?
    */
  case class Config(
      analyze: Option[File] = None,
      out: Option[File] = None,
      dbOut: Option[File] = None,
      build: Option[File] = None,
      threads: Int = 4,
      inMem: Boolean = false,
      fetchURL: URL = new URL("https://goatrodeo.org/omnibor"),
      mergees: Option[Vector[File]] = None
  )

  lazy val builder = OParser.builder[Config]
  lazy val parser1 = {
    import builder._
    OParser.sequence(
      programName("goatrodeo"),
      head("goatrodeo", "0.2"),
      opt[File]('a', "analyze")
        .action((x, c) =>
          c.copy(analyze = Some(x).filter(f => f.exists() && f.isFile()))
        )
        .text("Analyze a JAR or WAR file"),
      opt[Unit]('m', "mem")
        .text("Compute value using in-memory data store")
        .action((x, c) => c.copy(inMem = true)),
      opt[Seq[File]]("merge")
        .text("Merge omnibor files created with 'build' command")
        .action((x, c) => c.copy(mergees = Some(x.toVector))),
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
      opt[File]('d', "db")
        .text("Output SQLite database for DB-based gitoid storage")
        .action((db, c) => c.copy(dbOut = Some(db))),
      opt[Int]('t', "threads")
        .text(
          "How many threads to run (default 4). Should be 2x-3x number of cores"
        )
        .action((t, c) => c.copy(threads = t))
    )
  }

  /** Bail out... gracefully if we're running in SBT
    *
    * @return
    */
  private def bailFail(): Nothing = {
    if (Thread.currentThread().getStackTrace().length < 6) System.exit(1)
    throw new Exception()
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
      case Some(Config(Some(_), _, _, Some(_), _, _, _, _)) =>
        println("Cannot do both analysis and building...")
        println(OParser.usage(parser1))
        bailFail()

      case Some(Config(_, _, _, Some(_), _, _, _, Some(_))) =>
        println("Cannot do both merge and building...")
        println(OParser.usage(parser1))
        bailFail()

      case Some(Config(Some(_), _, _, _, _, _, _, Some(_))) =>
        println("Cannot do both analysis and merging...")
        println(OParser.usage(parser1))
        bailFail()

      case Some(Config(None, _, _, None, _, _, _, None)) =>
        println("You must either build, merge, or analyze...");
        println(OParser.usage(parser1))
        bailFail()

      case Some(Config(Some(analyzeFile), _, _, _, _, _, fetch, _)) =>
        Analyzer.analyze(analyzeFile, fetch)

      case Some(Config(_, out, _, _, _, _, _, Some(toMerge)))
          if toMerge.length > 1 =>
        Merger.merge(toMerge, out)

      case Some(Config(_, _, _, _, _, _, _, Some(_))) =>
        println("You must supply at least 2 files to merge...");
        println(OParser.usage(parser1))
        bailFail()

      case Some(Config(_, out, dbOut, Some(buildFrom), threads, inMem, _, _))
          if out.isDefined || dbOut.isDefined =>
        Builder.buildDB(
          buildFrom,
          Storage.getStorage(inMem, dbOut, out),
          threads
        )

      case Some(Config(_, out, dbOut, Some(buildFrom), threads, inMem, _, _)) =>
        println(
          "Either `out` or `db` must be defined... where does the build result go?"
        )
        println(OParser.usage(parser1))
        bailFail()

      case None => bailFail()
    }
  }
}
