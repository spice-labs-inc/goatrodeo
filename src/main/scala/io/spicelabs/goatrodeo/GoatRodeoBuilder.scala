/* Copyright 2025 Spice Labs, Inc. & Contributors

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

import java.nio.file.Path
import java.nio.file.Paths
import scala.annotation.static
import scala.jdk.CollectionConverters.*

class GoatRodeoBuilder {
  private val log = Logger(classOf[GoatRodeoBuilder])

  private var payload: Option[Path] = None
  private var output: Option[Path] = None
  private var threads: Int = 4
  private var ingested: Option[Path] = None
  private var ignore: Option[Path] = None
  private var fileList: Option[Path] = None
  private var excludePattern: Option[String] = None
  private var maxRecords: Int = 50000
  private var blockList: Option[Path] = None
  private var tempDir: Option[Path] = None
  private var tag: Option[String] = None

  def withPayload(p: String): GoatRodeoBuilder = {
    this.payload = Some(Paths.get(p))
    this
  }

  def withOutput(o: String): GoatRodeoBuilder = {
    this.output = Some(Paths.get(o))
    this
  }

  def withThreads(t: Int): GoatRodeoBuilder = {
    this.threads = t
    this
  }

  def withIngested(i: String): GoatRodeoBuilder = {
    this.ingested = Some(Paths.get(i))
    this
  }

  def withIgnore(i: String): GoatRodeoBuilder = {
    this.ignore = Some(Paths.get(i))
    this
  }

  def withFileList(f: String): GoatRodeoBuilder = {
    this.fileList = Some(Paths.get(f))
    this
  }

  def withExcludePattern(p: String): GoatRodeoBuilder = {
    this.excludePattern = Some(p)
    this
  }

  def withMaxRecords(r: Int): GoatRodeoBuilder = {
    this.maxRecords = r
    this
  }

  def withBlockList(b: String): GoatRodeoBuilder = {
    this.blockList = Some(Paths.get(b))
    this
  }

  def withTempDir(d: String): GoatRodeoBuilder = {
    this.tempDir = Some(Paths.get(d))
    this
  }

  def withTag(t: String): GoatRodeoBuilder = {
    this.tag = Option(t)
    this
  }

  def withExtraArgs(args: java.util.Map[String, String]): GoatRodeoBuilder = {
    withExtraArgs(args.asScala.toMap)
  }

  def withExtraArgs(args: Map[String, String]): GoatRodeoBuilder = {
    args.foreach { case (k, v) => withExtraArg(k, v) }
    this
  }

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
      case "tag"            => withTag(value)
      case unknown =>
        log.warn(s"Ignored unknown GoatRodeoBuilder arg: $unknown=$value")
        this
    }
  }

  def run(): Unit = {
    val args = Seq(
      Some("--build").zip(payload.map(_.toString)),
      Some("--out").zip(output.map(_.toString)),
      Some("--threads").map(_ -> threads.toString),
      tag.map(t => "--tag" -> t),
      ingested.map(i => "--ingested" -> i.toString),
      ignore.map(i => "--ignore" -> i.toString),
      fileList.map(f => "--file-list" -> f.toString),
      excludePattern.map(p => "--exclude-pattern" -> p),
      Some("--maxrecords" -> maxRecords.toString),
      blockList.map(b => "--block" -> b.toString),
      tempDir.map(d => "--tempdir" -> d.toString)
    ).flatten.flatMap { case (k, v) => Seq(k, v) }

    Howdy.run(args.toArray)
  }
}

class GoatRodeo

object GoatRodeo {
  @static
  def builder(): GoatRodeoBuilder = new GoatRodeoBuilder()
}
