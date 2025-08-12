package io.spicelabs.goatrodeo.util

import io.bullet.borer.Dom

import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.Try

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
    tag: Option[String] = None,
    exclude: Vector[(String, Try[Pattern])] = Vector(),
    threads: Int = 4,
    tagJson: Option[Dom.Element] = None,
    blockList: Option[File] = None,
    maxRecords: Int = 50000,
    tempDir: Option[File] = None,
    useSyft: Boolean = false
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
