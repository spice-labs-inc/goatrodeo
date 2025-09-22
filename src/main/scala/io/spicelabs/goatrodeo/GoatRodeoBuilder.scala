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
import io.bullet.borer.Dom
import io.bullet.borer.Json
import io.spicelabs.goatrodeo.util.Config

import java.nio.file.Paths
import java.util.regex.Pattern
import scala.annotation.static
import scala.jdk.CollectionConverters.*
import scala.util.Try

class GoatRodeoBuilder {
  private val log = Logger(classOf[GoatRodeoBuilder])

  private var config = Config()

  def withPayload(p: String): GoatRodeoBuilder = {
    this.config =
      this.config.copy(build = this.config.build :+ Paths.get(p).toFile())
    this
  }

  def withOutput(o: String): GoatRodeoBuilder = {
    this.config = this.config.copy(out = Some(Paths.get(o).toFile()))
    this
  }

  def withThreads(t: Int): GoatRodeoBuilder = {
    this.config = this.config.copy(threads = t)
    this
  }

  def withIngested(i: String): GoatRodeoBuilder = {
    this.config = this.config.copy(ingested = Some(Paths.get(i).toFile()))
    this
  }

  def withIgnore(i: String): GoatRodeoBuilder = {
    this.config =
      this.config.copy(ignore = config.ignore :+ Paths.get(i).toFile())
    this
  }

  def withFileList(f: String): GoatRodeoBuilder = {
    config = config.copy(fileList = config.fileList :+ Paths.get(f).toFile())
    this
  }

  def withExcludePattern(p: String): GoatRodeoBuilder = {
    config =
      config.copy(exclude = config.exclude :+ (p, Try(Pattern.compile(p))))
    this
  }

  def withMaxRecords(r: Int): GoatRodeoBuilder = {
    config = config.copy(maxRecords = r)
    this
  }

  def withBlockList(b: String): GoatRodeoBuilder = {
    config = config.copy(blockList = Some(Paths.get(b).toFile()))
    this
  }

  def withTempDir(d: String): GoatRodeoBuilder = {
    config = config.copy(tempDir = Some(Paths.get(d).toFile()))
    this
  }

  def withTag(t: String): GoatRodeoBuilder = {
    config = config.copy(tag = Some(t))
    this
  }

  def withStaticMetadata(b: Boolean): GoatRodeoBuilder = {
    config = config.copy(useStaticMetadata = b)
    this
  }

  def withTagJson(t: String): GoatRodeoBuilder = {
    config = config.copy(tagJson =
      Some(Json.decode(t.getBytes("UTF-8")).to[Dom.Element].value)
    )
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
      case "tag-json"       => withTagJson(value)
      case "tag"            => withTag(value)
      case unknown =>
        log.warn(s"Ignored unknown GoatRodeoBuilder arg: $unknown=$value")
        this
    }
  }

  def run(): Unit = {

    Howdy.run(config)
  }
}

class GoatRodeo

object GoatRodeo {
  @static
  def builder(): GoatRodeoBuilder = new GoatRodeoBuilder()
}
