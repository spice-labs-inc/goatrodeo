/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.util

import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeSet
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Security-relevant data extracted from an OpenSSL configuration file.
  *
  * All fields are immutable. The parser is conservative: if a file does not
  * contain security-relevant OpenSSL directives, most fields will be empty or
  * None.
  *
  * @param sections
  *   section names that contain security-relevant keys or participate in
  *   `ssl_conf` indirection
  * @param cipherString
  *   value of the `CipherString` directive
  * @param cipherSuites
  *   value of the `Ciphersuites` directive
  * @param minProtocol
  *   value of the `MinProtocol` directive
  * @param maxProtocol
  *   value of the `MaxProtocol` directive
  * @param options
  *   values of `Options` directives (may contain commas; split into discrete
  *   values)
  * @param includeReferences
  *   raw paths from `.include` directives
  * @param sslConfReferences
  *   section names referenced by `ssl_conf` directives
  */
case class OpenSSLConfigData(
    sections: TreeSet[String] = TreeSet.empty,
    cipherString: Option[String] = None,
    cipherSuites: Option[String] = None,
    minProtocol: Option[String] = None,
    maxProtocol: Option[String] = None,
    options: TreeSet[String] = TreeSet.empty,
    includeReferences: Vector[String] = Vector.empty,
    sslConfReferences: Vector[String] = Vector.empty
) {

  /** True if any security-relevant field is populated. */
  def hasSecurityData: Boolean =
    cipherString.isDefined || cipherSuites.isDefined ||
      minProtocol.isDefined || maxProtocol.isDefined || options.nonEmpty
}

/** Tolerant parser for OpenSSL configuration files.
  *
  * OpenSSL configs are INI-style files. The parser reads at most 1 MB, ignores
  * invalid UTF-8, and never throws from the public entry points. It extracts
  * security-relevant directives and follows `ssl_conf` indirection within the
  * same file up to a bounded depth.
  */
object OpenSSLConfigParser {

  /** Maximum bytes read from a single config file. */
  val MaxReadBytes: Int = 1024 * 1024

  /** Maximum depth for intra-file section indirection (`ssl_conf` chains). */
  val MaxSectionDepth: Int = 8

  /** Security-relevant keys (case-insensitive). */
  private val SecurityKeys: Set[String] = Set(
    "cipherstring",
    "ciphersuites",
    "minprotocol",
    "maxprotocol",
    "options"
  )

  /** Keys whose value is itself a section name to resolve. These appear inside
    * `ssl_conf` target sections (e.g., `system_default = system_default_sect`).
    */
  private val SectionPointerKeys: Set[String] = Set(
    "ssl_conf",
    "system_default",
    "server",
    "client",
    "default",
    "default_server",
    "default_client",
    "tls_system_default"
  )

  /** Parse an artifact into `OpenSSLConfigData`.
    *
    * @param artifact
    *   the artifact to parse
    * @return
    *   `Success(OpenSSLConfigData)` on valid text input, `Success(data)` even
    *   for files without security data, or `Failure` for binary/invalid input
    */
  def parse(artifact: ArtifactWrapper): Try[OpenSSLConfigData] = {
    if (looksBinary(artifact)) {
      Failure(new RuntimeException("binary data is not an OpenSSL config"))
    } else {
      readText(artifact).flatMap(parseString)
    }
  }

  /** Parse a raw string (convenience for tests).
    */
  def parseString(content: String): Try[OpenSSLConfigData] = Try {
    val rawSections = parseSections(content)
    if (rawSections.isEmpty) {
      OpenSSLConfigData()
    } else {
      val resolved = resolveSections(rawSections)
      extractData(rawSections, resolved)
    }
  }

  /** Parse an INI-style document into a map from section name to (lowercase key
    * -> raw value).
    */
  private def parseSections(
      content: String
  ): Map[String, Map[String, String]] = {
    val lines = content.linesIterator
    var currentSection = ""
    var sections = Map.empty[String, Map[String, String]]

    lines.foreach { rawLine =>
      val line = rawLine.trim
      if (line.nonEmpty && !line.startsWith("#") && !line.startsWith(";")) {
        val sectionMatch =
          OpenSSLConfigDetector.SectionHeaderPattern.findFirstMatchIn(line)
        sectionMatch match {
          case Some(m) =>
            currentSection = Option(m.group(0)) match {
              case Some(text) =>
                text.trim.stripPrefix("[").stripSuffix("]").trim
              case None =>
                ""
            }
            sections = sections.updated(
              currentSection,
              sections.getOrElse(currentSection, Map.empty)
            )
          case None =>
            if (currentSection.nonEmpty) {
              if (line.startsWith(".include")) {
                val rest = line.substring(".include".length).trim
                // OpenSSL supports `.include filename` and `.include !filename`
                val path =
                  if (rest.startsWith("!")) rest.substring(1).trim else rest
                if (path.nonEmpty) {
                  sections = sections.updated(
                    currentSection,
                    sections
                      .getOrElse(currentSection, Map.empty)
                      .updated(
                        ".include",
                        sections
                          .getOrElse(currentSection, Map.empty)
                          .get(".include")
                          .map(_ + "\n" + path)
                          .getOrElse(path)
                      )
                  )
                }
              } else {
                val eqIdx = line.indexOf('=')
                if (eqIdx >= 0) {
                  val key = line.substring(0, eqIdx).trim.toLowerCase
                  val value = line.substring(eqIdx + 1).trim
                  sections = sections.updated(
                    currentSection,
                    sections
                      .getOrElse(currentSection, Map.empty)
                      .updated(key, value)
                  )
                }
              }
            }
        }
      }
    }
    sections
  }

  /** Resolve section indirection.
    *
    * Starting from every section that contains `ssl_conf`, walk the chain of
    * section references. The result is a map from the starting section name to
    * the set of all section names that contribute effective configuration.
    *
    * Cycle detection is done via a recursion-depth limit and a visited set.
    */
  private def resolveSections(
      sections: Map[String, Map[String, String]]
  ): Map[String, Set[String]] = {
    val sectionNames = sections.keySet

    def resolveOne(
        name: String,
        visited: Set[String],
        depth: Int
    ): Set[String] = {
      if (depth > MaxSectionDepth || visited.contains(name)) {
        Set.empty
      } else {
        val current = sections.get(name).getOrElse(Map.empty)
        val direct = Set(name)
        val pointed = current.view.flatMap { case (key, value) =>
          val isPointer = key == "ssl_conf" || SectionPointerKeys.contains(key)
          if (isPointer && sectionNames.contains(value)) {
            Some(value)
          } else {
            None
          }
        }.toSet
        val transitive = pointed.flatMap { target =>
          resolveOne(target, visited + name, depth + 1)
        }
        direct ++ pointed ++ transitive
      }
    }

    sections.keys.map { name =>
      name -> resolveOne(name, Set.empty, 0)
    }.toMap
  }

  /** Extract security-relevant data from raw sections using the resolved
    * section map.
    *
    * We collect values from every section that is security-relevant itself or
    * is reachable via `ssl_conf` indirection from any starting section.
    */
  private def extractData(
      sections: Map[String, Map[String, String]],
      resolved: Map[String, Set[String]]
  ): OpenSSLConfigData = {
    val securitySections = sections.keys.filter { name =>
      val keys = sections(name).keySet
      keys.exists(SecurityKeys.contains) ||
      keys.contains("ssl_conf") ||
      keys.contains(".include")
    }.toSet

    val reachableSections = resolved.view.flatMap { case (start, targets) =>
      if (
        securitySections
          .contains(start) || targets.exists(securitySections.contains)
      ) {
        Some(start) ++ targets
      } else {
        Set.empty[String]
      }
    }.toSet

    val activeSections = securitySections ++ reachableSections

    var cipherString: Option[String] = None
    var cipherSuites: Option[String] = None
    var minProtocol: Option[String] = None
    var maxProtocol: Option[String] = None
    var options = TreeSet.empty[String]
    var includeRefs = Vector.empty[String]
    var sslConfRefs = Vector.empty[String]

    activeSections.foreach { sectionName =>
      val kv = sections.getOrElse(sectionName, Map.empty)
      kv.foreach { case (key, value) =>
        key match {
          case "cipherstring" =>
            cipherString = Some(value)
          case "ciphersuites" =>
            cipherSuites = Some(value)
          case "minprotocol" =>
            minProtocol = Some(value)
          case "maxprotocol" =>
            maxProtocol = Some(value)
          case "options" =>
            options = options ++ value.split(',').map(_.trim).filter(_.nonEmpty)
          case ".include" =>
            includeRefs =
              includeRefs ++ value.split('\n').map(_.trim).filter(_.nonEmpty)
          case "ssl_conf" =>
            sslConfRefs = sslConfRefs :+ value
          case _ =>
            // Other keys are ignored unless they are section pointers whose
            // targets contain security data (already merged via resolveSections).
            ()
        }
      }
    }

    OpenSSLConfigData(
      sections = TreeSet.from(activeSections),
      cipherString = cipherString,
      cipherSuites = cipherSuites,
      minProtocol = minProtocol,
      maxProtocol = maxProtocol,
      options = options,
      includeReferences = includeRefs.distinct,
      sslConfReferences = sslConfRefs.distinct
    )
  }

  /** Read up to `MaxReadBytes` from the artifact as UTF-8 text. */
  private def readText(artifact: ArtifactWrapper): Try[String] = Try {
    artifact.withStream { stream =>
      val bytes = stream.readNBytes(MaxReadBytes)
      new String(bytes, StandardCharsets.UTF_8)
    }
  }

  /** Binary heuristic mirroring `OpenSSLConfigDetector.looksBinary`. */
  private def looksBinary(artifact: ArtifactWrapper): Boolean = {
    if (artifact.size() == 0) {
      false
    } else {
      readText(artifact) match {
        case Failure(_) => true
        case Success(text) =>
          if (text.isEmpty) {
            false
          } else {
            val controlCount = text.count { c =>
              c < 0x20 && !c.isWhitespace
            }
            controlCount.toDouble / text.length > 0.1
          }
      }
    }
  }
}
