/* Copyright 2025-2026 Spice Labs, Inc. & Contributors

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
import io.bullet.borer.Json
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.Config.VectorOfStrings
import io.spicelabs.goatrodeo.util.IncludeExclude

import java.nio.file.Paths
import java.util.regex.Pattern
import scala.annotation.static
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A fluent builder for programmatically configuring and running Goat Rodeo.
  *
  * This builder provides a Java-friendly API for configuring Goat Rodeo without
  * using the command-line interface. All configuration methods return `this` to
  * enable method chaining.
  *
  * Example usage:
  * {{{
  * new GoatRodeoBuilder()
  *   .withPayload("/path/to/artifacts")
  *   .withOutput("/path/to/output")
  *   .withThreads(8)
  *   .run()
  * }}}
  */
class GoatRodeoBuilder {
  private val log = Logger(classOf[GoatRodeoBuilder])

  private var config = Config()

  /** Add a directory of artifacts to process.
    *
    * @param p
    *   the path to the directory containing artifacts
    * @return
    *   this builder
    */
  def withPayload(p: String): GoatRodeoBuilder = {
    this.config =
      this.config.copy(build = this.config.build :+ Paths.get(p).toFile())
    this
  }

  /** Set whether to include filesystem file paths in Items.
    *
    * @param fp
    *   true to include paths, false for just filenames
    * @return
    *   this builder
    */
  def withFsFilePaths(fp: Boolean): GoatRodeoBuilder = {
    this.config = this.config.copy(fsFilePaths = fp)
    this
  }

  /** Set the output directory for the ADG files.
    *
    * @param o
    *   the path to the output directory
    * @return
    *   this builder
    */
  def withOutput(o: String): GoatRodeoBuilder = {
    this.config = this.config.copy(out = Some(Paths.get(o).toFile()))
    this
  }

  /** Set the number of parallel processing threads.
    *
    * @param t
    *   the number of threads
    * @return
    *   this builder
    */
  def withThreads(t: Int): GoatRodeoBuilder = {
    this.config = this.config.copy(threads = t)
    this
  }

  /** Set the file to append successfully ingested file paths to.
    *
    * @param i
    *   the path to the ingested files output file
    * @return
    *   this builder
    */
  def withIngested(i: String): GoatRodeoBuilder = {
    this.config = this.config.copy(ingested = Some(Paths.get(i).toFile()))
    this
  }

  /** Add a file containing paths to ignore during processing.
    *
    * @param i
    *   the path to the ignore file (one path per line)
    * @return
    *   this builder
    */
  def withIgnore(i: String): GoatRodeoBuilder = {
    this.config =
      this.config.copy(ignore = config.ignore :+ Paths.get(i).toFile())
    this
  }

  /** Add a file containing a list of files to process.
    *
    * @param f
    *   the path to the file list (one path per line)
    * @return
    *   this builder
    */
  def withFileList(f: String): GoatRodeoBuilder = {
    config = config.copy(fileList = config.fileList :+ Paths.get(f).toFile())
    this
  }

  /** Add a regex pattern to exclude files from processing.
    *
    * @param p
    *   the regex pattern
    * @return
    *   this builder
    */
  def withExcludePattern(p: String): GoatRodeoBuilder = {
    config =
      config.copy(exclude = config.exclude :+ (p, Try(Pattern.compile(p))))
    this
  }

  /** Set the maximum number of records to process at once.
    *
    * @param r
    *   the maximum record count
    * @return
    *   this builder
    */
  def withMaxRecords(r: Int): GoatRodeoBuilder = {
    config = config.copy(maxRecords = r)
    this
  }

  /** Set a file containing GitOIDs to skip.
    *
    * @param b
    *   the path to the block list file
    * @return
    *   this builder
    */
  def withBlockList(b: String): GoatRodeoBuilder = {
    config = config.copy(blockList = Some(Paths.get(b).toFile()))
    this
  }

  /** Set the directory for temporary files (ideally a RAM disk).
    *
    * @param d
    *   the path to the temp directory
    * @return
    *   this builder
    */
  def withTempDir(d: String): GoatRodeoBuilder = {
    config = config.copy(tempDir = Some(Paths.get(d).toFile()))
    this
  }

  /** Set a tag name for the build.
    *
    * @param t
    *   the tag name
    * @return
    *   this builder
    */
  def withTag(t: String): GoatRodeoBuilder = {
    config = config.copy(tag = Some(t))
    this
  }

  /** Set whether to use Syft for static metadata gathering.
    *
    * @param b
    *   true to enable, false to disable
    * @return
    *   this builder
    */
  def withStaticMetadata(b: Boolean): GoatRodeoBuilder = {
    config = config.copy(useStaticMetadata = b)
    this
  }

  /** Set additional JSON to include in the tag.
    *
    * @param t
    *   the JSON string
    * @return
    *   this builder
    */
  def withTagJson(t: String): GoatRodeoBuilder = {
    config = config.copy(tagJson =
      Some(Json.decode(t.getBytes("UTF-8")).to[Dom.Element].value)
    )
    this
  }

  /** Add a MIME type filter predicate.
    *
    * @param filter
    *   the filter predicate string (e.g., "+type", "-type", "*regex")
    * @return
    *   this builder
    */
  def withMimeFilter(filter: String): GoatRodeoBuilder = {
    config = config.copy(mimeFilter = config.mimeFilter :+ filter)
    this
  }

  /** Add a file containing MIME type filter predicates.
    *
    * @param f
    *   the path to the filter file (one predicate per line)
    * @return
    *   this builder
    */
  def withMimeFilterFile(f: String): GoatRodeoBuilder = {
    config = config.copy(mimeFilter = config.mimeFilter ++ VectorOfStrings(f))
    this
  }

  /** Add extra arguments from a Java Map.
    *
    * @param args
    *   the map of argument names to values
    * @return
    *   this builder
    */
  def withExtraArgs(args: java.util.Map[String, String]): GoatRodeoBuilder = {
    withExtraArgs(args.asScala.toMap)
  }

  /** Add extra arguments from a Scala Map.
    *
    * @param args
    *   the map of argument names to values
    * @return
    *   this builder
    */
  def withExtraArgs(args: Map[String, String]): GoatRodeoBuilder = {
    args.foreach { case (k, v) => withExtraArg(k, v) }
    this
  }

  /** Add a single extra argument by key and value.
    *
    * Supported keys: payload, output, threads, maxRecords, ingested, ignore,
    * fileList, excludePattern, blockList, tempDir, tag-json, tag, mimeFilter,
    * mimeFilterFile, emitJsonDir
    *
    * @param key
    *   the argument name
    * @param value
    *   the argument value
    * @return
    *   this builder
    */
  def withExtraArg(key: String, value: String): GoatRodeoBuilder = {
    key match {
      case "payload"        => withPayload(value)
      case "output"         => withOutput(value)
      case "threads"        => withThreads(value.toInt)
      case "maxRecords"     => withMaxRecords(value.toInt)
      case "ingested"       => withIngested(value)
      case "ignore"         => withIgnore(value)
      case "fileList"       => withFileList(value)
      case "excludePattern" => withExcludePattern(value)
      case "blockList"      => withBlockList(value)
      case "tempDir"        => withTempDir(value)
      case "tag-json"       => withTagJson(value)
      case "tag"            => withTag(value)
      case "mimeFilter"     => withMimeFilter(value)
      case "mimeFilterFile" => withMimeFilterFile(value)
      case "emitJsonDir" =>
        config = config.copy(emitJsonDir = Some(Paths.get(value).toFile()))
        this
      case unknown =>
        log.warn(s"Ignored unknown GoatRodeoBuilder arg: $unknown=$value")
        this
    }
  }

  /** Execute the Goat Rodeo build with the current configuration.
    */
  def run(): Unit = {

    Howdy.run(config)
  }
}

/** Marker class for the GoatRodeo API entry point. */
class GoatRodeo

/** Factory for creating GoatRodeoBuilder instances.
  *
  * This is the main entry point for the programmatic API.
  */
object GoatRodeo {

  /** Create a new GoatRodeoBuilder instance.
    *
    * @return
    *   a new builder for configuring and running Goat Rodeo
    */
  @static
  def builder(): GoatRodeoBuilder = new GoatRodeoBuilder()
}
