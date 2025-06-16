package goatrodeo

import java.nio.file.{Path, Paths}
import scala.annotation.static

class GoatRodeoBuilder {
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

  def run(): Unit = {
    val args = Seq(
      Some("--build").zip(payload.map(_.toString)),
      Some("--out").zip(output.map(_.toString)),
      Some("--threads").map(_ -> threads.toString),
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
