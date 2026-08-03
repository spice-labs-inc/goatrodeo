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

import com.typesafe.scalalogging.Logger

import java.nio.charset.StandardCharsets
import java.util.Properties
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Security-relevant data extracted from a Java `java.security` policy file.
  *
  * All fields are immutable. The parser is conservative: if a file does not
  * contain the tracked security properties, most fields will be empty or
  * `None`.
  *
  * @param disabledAlgorithms
  *   value of `jdk.tls.disabledAlgorithms`
  * @param certpathDisabledAlgorithms
  *   value of `jdk.certpath.disabledAlgorithms`
  * @param legacyAlgorithms
  *   value of `jdk.tls.legacyAlgorithms`
  * @param namedGroups
  *   value of `jdk.tls.namedGroups`
  * @param ephemeralDHKeySize
  *   value of `jdk.tls.ephemeralDHKeySize`
  */
case class JavaSecurityData(
    disabledAlgorithms: TreeSet[String] = TreeSet.empty,
    certpathDisabledAlgorithms: TreeSet[String] = TreeSet.empty,
    legacyAlgorithms: TreeSet[String] = TreeSet.empty,
    namedGroups: TreeSet[String] = TreeSet.empty,
    ephemeralDHKeySize: Option[String] = None
) {

  /** True if any security-relevant field is populated. */
  def hasSecurityData: Boolean =
    disabledAlgorithms.nonEmpty || certpathDisabledAlgorithms.nonEmpty ||
      legacyAlgorithms.nonEmpty || namedGroups.nonEmpty ||
      ephemeralDHKeySize.isDefined
}

/** Tolerant parser for Java `java.security` files.
  *
  * The parser reads at most 1 MB, uses `java.util.Properties` to parse the
  * content, and never throws from the public entry points. It extracts the five
  * security-relevant properties tracked by Goat Rodeo.
  */
object JavaSecurityParser {
  private val logger = Logger(getClass())

  /** Maximum bytes read from a single security file. */
  val MaxReadBytes: Int = 1024 * 1024

  private val KeyDisabledAlgorithms = "jdk.tls.disabledAlgorithms"
  private val KeyCertpathDisabledAlgorithms = "jdk.certpath.disabledAlgorithms"
  private val KeyLegacyAlgorithms = "jdk.tls.legacyAlgorithms"
  private val KeyNamedGroups = "jdk.tls.namedGroups"
  private val KeyEphemeralDHKeySize = "jdk.tls.ephemeralDHKeySize"

  /** Parse an artifact into `JavaSecurityData`.
    *
    * @param artifact
    *   the artifact to parse
    * @return
    *   `Success(JavaSecurityData)` on valid properties input, or `Failure` for
    *   unreadable/invalid input
    */
  def parse(artifact: ArtifactWrapper): Try[JavaSecurityData] = {
    readText(artifact).flatMap(parseString)
  }

  /** Parse a raw string (convenience for tests).
    */
  def parseString(content: String): Try[JavaSecurityData] = Try {
    val props = new Properties()
    props.load(new java.io.StringReader(content))
    JavaSecurityData(
      disabledAlgorithms =
        tokenize(Option(props.getProperty(KeyDisabledAlgorithms))),
      certpathDisabledAlgorithms = tokenize(
        Option(props.getProperty(KeyCertpathDisabledAlgorithms))
      ),
      legacyAlgorithms =
        tokenize(Option(props.getProperty(KeyLegacyAlgorithms))),
      namedGroups = tokenize(Option(props.getProperty(KeyNamedGroups))),
      ephemeralDHKeySize = Option(props.getProperty(KeyEphemeralDHKeySize))
        .map(_.trim)
        .filter(_.nonEmpty)
    )
  }

  /** Tokenize a comma-separated property value into an immutable `TreeSet`.
    *
    * Empty tokens and surrounding whitespace are discarded. Internal spaces are
    * preserved (e.g., `RSA keySize < 2048` stays as one token).
    */
  def tokenize(valueOpt: Option[String]): TreeSet[String] = {
    valueOpt.map(_.trim) match {
      case Some("") => TreeSet.empty
      case Some(value) =>
        TreeSet.from(value.split(',').map(_.trim).filter(_.nonEmpty))
      case None => TreeSet.empty
    }
  }

  /** Read up to `MaxReadBytes` from the artifact as an ISO-8859-1 text. */
  private def readText(artifact: ArtifactWrapper): Try[String] = Try {
    if (artifact.size() > MaxReadBytes) {
      logger.warn(
        s"Java security file ${artifact.path()} exceeds ${MaxReadBytes} byte parse budget; only the first ${MaxReadBytes} bytes will be parsed"
      )
    }
    artifact.withStream { stream =>
      val bytes = stream.readNBytes(MaxReadBytes)
      new String(bytes, StandardCharsets.ISO_8859_1)
    }
  }
}
