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
import io.spicelabs.goatrodeo.util.Configuration
import io.spicelabs.goatrodeo.util.VectorOfStrings

import java.nio.file.Paths
import java.time.Instant
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

  private var config = Configuration()

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
    require(t >= 1, s"threads must be >= 1, got $t")
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

  /** Set whether to use static metadata gathering.
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

  /** Enable per-package tagging.
    *
    * @return
    *   this builder
    */
  def withPackageTags(): GoatRodeoBuilder = {
    config = config.copy(packageTags = true)
    this
  }

  /** Use short package names (e.g., artifactId) instead of full qualified
    * names.
    *
    * @return
    *   this builder
    */
  def withPackageTagsShortName(): GoatRodeoBuilder = {
    config = config.copy(packageTagsShortName = true)
    this
  }

  /** Set the version for the top-level tag (requires tag to be set).
    *
    * @param v
    *   the version string
    * @return
    *   this builder
    */
  def withTagVersion(v: String): GoatRodeoBuilder = {
    config = config.copy(tagVersion = Some(v))
    this
  }

  /** Set the date for the top-level tag (requires tag to be set).
    *
    * @param d
    *   the date string (parsed flexibly, e.g., "2024-01-15", "today", "now")
    * @return
    *   Right(this builder) if the date was parsed successfully,
    *   Left(errorMessage) otherwise
    */
  def withTagDate(d: String): Either[String, GoatRodeoBuilder] = {
    io.spicelabs.goatrodeo.util.DateParser.parse(d) match {
      case Right(date) =>
        config = config.copy(tagDate = Some(date))
        Right(this)
      case Left(error) =>
        Left(error)
    }
  }

  /** Refuse to analyze internal files modified after `expiry`. Any archive
    * entry whose modification time is after this instant is dropped from the
    * ADG, along with everything that transitively contains it or is built from
    * it (they must be at least as new), so no dangling references remain.
    * Entries with no/unknown modification time are always kept.
    *
    * @param expiry
    *   the cutoff instant
    * @return
    *   this builder
    */
  def withExpiry(expiry: Instant): GoatRodeoBuilder = {
    config = config.copy(expiry = Some(expiry))
    this
  }

  /** String form of [[withExpiry]] parsing a flexible date (e.g. "2026-01-01",
    * "today").
    *
    * @param d
    *   the date string
    * @return
    *   Right(this builder) if parsed successfully, Left(errorMessage) otherwise
    */
  def withExpiry(d: String): Either[String, GoatRodeoBuilder] = {
    io.spicelabs.goatrodeo.util.DateParser.parse(d) match {
      case Right(date) =>
        config = config.copy(expiry = Some(date.toInstant()))
        Right(this)
      case Left(error) =>
        Left(error)
    }
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
    * mimeFilterFile, emitJsonDir, emitCbomDir, cbomVersion
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
      case "payload"                 => withPayload(value)
      case "output"                  => withOutput(value)
      case "threads"                 => withThreads(value.toInt)
      case "maxRecords"              => withMaxRecords(value.toInt)
      case "ingested"                => withIngested(value)
      case "ignore"                  => withIgnore(value)
      case "fileList"                => withFileList(value)
      case "excludePattern"          => withExcludePattern(value)
      case "blockList"               => withBlockList(value)
      case "tempDir"                 => withTempDir(value)
      case "tag-json"                => withTagJson(value)
      case "tag"                     => withTag(value)
      case "package-tags"            => withPackageTags()
      case "package-tags-short-name" => withPackageTagsShortName()
      case "mimeFilter"              => withMimeFilter(value)
      case "mimeFilterFile"          => withMimeFilterFile(value)
      case "emitJsonDir" =>
        config = config.copy(emitJsonDir = Some(Paths.get(value).toFile()))
        this
      case "emitCbomDir" =>
        config = config.copy(cbomDir = Some(Paths.get(value).toFile()))
        this
      case "cbomVersion" =>
        config = config.copy(cbomVersion = value)
        this
      case unknown =>
        log.warn(s"Ignored unknown GoatRodeoBuilder arg: $unknown=$value")
        this
    }
  }

  /** Attach a progress listener that is notified at phase boundaries (Scanning,
    * Writing, Done) and periodically during the Processing phase. See
    * [[ProgressListener]] for cadence and threading semantics.
    *
    * Passing `null` clears any previously attached listener.
    *
    * @param listener
    *   the listener to attach, or `null` to clear
    * @return
    *   this builder
    */
  def withProgressListener(listener: ProgressListener): GoatRodeoBuilder = {
    config = config.copy(progressListener = Option(listener))
    this
  }

  /** Set the directory to emit CycloneDX CBOM files to.
    *
    * One CBOM JSON file is written per top-level input file. CBOM emission is
    * disabled when this is not set.
    *
    * @param d
    *   the path to the CBOM output directory
    * @return
    *   this builder
    */
  def withCbomDir(d: String): GoatRodeoBuilder = {
    config = config.copy(cbomDir = Some(Paths.get(d).toFile()))
    this
  }

  /** Set the CycloneDX CBOM specification version to emit.
    *
    * @param v
    *   the version string, "1.6" or "1.7"
    * @return
    *   this builder
    */
  def withCbomVersion(v: String): GoatRodeoBuilder = {
    config = config.copy(cbomVersion = v)
    this
  }

  /** Execute the Goat Rodeo build with the current configuration.
    */
  def run(): Unit = {

    Howdy.run(using config)
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
