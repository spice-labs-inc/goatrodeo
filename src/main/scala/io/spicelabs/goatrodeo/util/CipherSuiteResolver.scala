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

import scala.collection.immutable.Vector

/** Decomposes TLS cipher-suite names into their constituent algorithms.
  *
  * OpenSSL configuration files and service TLS configs name suites as opaque
  * strings (`ECDHE-RSA-AES128-GCM-SHA256`, `TLS_AES_256_GCM_SHA384`). This
  * resolver maps the well-known names (both OpenSSL spellings and TLS 1.3 IANA
  * spellings) to the algorithms they exercise, so downstream CBOM generation
  * can turn a cipher string into cryptographic assets.
  *
  * The mapping is a curated, static table over concrete suite names. Cipher
  * grammar tokens that are not concrete suites (`DEFAULT`, `HIGH`, `!aNULL`,
  * `@SECLEVEL=2`) are preserved as entries but resolve to no algorithms — the
  * resolver never invents algorithms it cannot name.
  */
object CipherSuiteResolver {

  /** One suite entry from a cipher string: its name and the algorithms it
    * resolves to (empty when the token is a grammar keyword or unknown).
    */
  final case class SuiteEntry(name: String, algorithms: Vector[String])

  // Curated OpenSSL + TLS 1.3 cipher-suite names → constituent algorithms.
  private val KnownSuites: Map[String, Vector[String]] = Map(
    "ECDHE-RSA-AES128-GCM-SHA256" -> Vector(
      "ecdh",
      "rsa",
      "aes-128-gcm",
      "sha-256"
    ),
    "ECDHE-RSA-AES256-GCM-SHA384" -> Vector(
      "ecdh",
      "rsa",
      "aes-256-gcm",
      "sha-384"
    ),
    "ECDHE-RSA-CHACHA20-POLY1305" -> Vector(
      "ecdh",
      "rsa",
      "chacha20-poly1305",
      "sha-256"
    ),
    "ECDHE-ECDSA-AES128-GCM-SHA256" -> Vector(
      "ecdh",
      "ecdsa",
      "aes-128-gcm",
      "sha-256"
    ),
    "ECDHE-ECDSA-AES256-GCM-SHA384" -> Vector(
      "ecdh",
      "ecdsa",
      "aes-256-gcm",
      "sha-384"
    ),
    "ECDHE-ECDSA-CHACHA20-POLY1305" -> Vector(
      "ecdh",
      "ecdsa",
      "chacha20-poly1305",
      "sha-256"
    ),
    "DHE-RSA-AES128-GCM-SHA256" -> Vector(
      "dh",
      "rsa",
      "aes-128-gcm",
      "sha-256"
    ),
    "DHE-RSA-AES256-GCM-SHA384" -> Vector(
      "dh",
      "rsa",
      "aes-256-gcm",
      "sha-384"
    ),
    "DHE-RSA-CHACHA20-POLY1305" -> Vector(
      "dh",
      "rsa",
      "chacha20-poly1305",
      "sha-256"
    ),
    "AES128-SHA" -> Vector("aes-128-cbc", "sha-1"),
    "AES128-SHA256" -> Vector("aes-128-cbc", "sha-256"),
    "AES128-GCM-SHA256" -> Vector("aes-128-gcm", "sha-256"),
    "AES256-SHA" -> Vector("aes-256-cbc", "sha-1"),
    "AES256-SHA256" -> Vector("aes-256-cbc", "sha-256"),
    "AES256-GCM-SHA384" -> Vector("aes-256-gcm", "sha-384"),
    "DES-CBC3-SHA" -> Vector("3des", "sha-1"),
    "TLS_AES_128_GCM_SHA256" -> Vector("aes-128-gcm", "sha-256"),
    "TLS_AES_256_GCM_SHA384" -> Vector("aes-256-gcm", "sha-384"),
    "TLS_CHACHA20_POLY1305_SHA256" -> Vector("chacha20-poly1305", "sha-256"),
    "TLS_AES_128_CCM_SHA256" -> Vector("aes-128-ccm", "sha-256"),
    "TLS_AES_128_CCM_8_SHA256" -> Vector("aes-128-ccm-8", "sha-256"),
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" -> Vector(
      "ecdh",
      "rsa",
      "aes-128-gcm",
      "sha-256"
    ),
    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384" -> Vector(
      "ecdh",
      "rsa",
      "aes-256-gcm",
      "sha-384"
    ),
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256" -> Vector(
      "ecdh",
      "ecdsa",
      "aes-128-gcm",
      "sha-256"
    ),
    "TLS_AES_256_CCM_SHA256" -> Vector("aes-256-ccm", "sha-256"),
    "TLS_AES_256_CCM_8_SHA256" -> Vector("aes-256-ccm-8", "sha-256")
  )

  /** The closed vocabulary of algorithm names the resolver can emit. */
  def knownAlgorithms: Set[String] =
    KnownSuites.valuesIterator.flatten.toSet

  /** Resolve a single cipher-suite token to its algorithms, if it is a concrete
    * known suite.
    */
  def resolveToken(token: String): Option[Vector[String]] =
    KnownSuites.get(normalize(token))

  /** Split a cipher string on OpenSSL/CVE separators and resolve each token.
    * Grammar keywords and unknown tokens are kept as name-only entries.
    */
  def resolveCipherString(cipherString: String): Vector[SuiteEntry] = {
    if (cipherString == null || cipherString.trim.isEmpty) Vector.empty
    else {
      cipherString
        .split("[:;,]")
        .toVector
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(t => SuiteEntry(t, resolveToken(t).getOrElse(Vector.empty)))
    }
  }

  // Standalone algorithm names used by service configs (OpenVPN `data-ciphers`,
  // nginx `ssl_ciphers` allow algorithm names like `AES-256-GCM`). Maps the
  // spelled form to the canonical lowercase name.
  private val KnownAlgorithmNames: Map[String, String] = Map(
    "AES-128-GCM" -> "aes-128-gcm",
    "AES-256-GCM" -> "aes-256-gcm",
    "AES-128-CCM" -> "aes-128-ccm",
    "AES-256-CCM" -> "aes-256-ccm",
    "AES-128-CBC" -> "aes-128-cbc",
    "AES-256-CBC" -> "aes-256-cbc",
    "CHACHA20-POLY1305" -> "chacha20-poly1305",
    "CHACHA20" -> "chacha20",
    "3DES" -> "3des",
    "DES" -> "des",
    "CAMELLIA-128-CBC" -> "camellia-128-cbc",
    "CAMELLIA-256-CBC" -> "camellia-256-cbc",
    "ARIA-128-GCM" -> "aria-128-gcm",
    "ARIA-256-GCM" -> "aria-256-gcm",
    "SEED" -> "seed",
    "SHA1" -> "sha-1",
    "SHA224" -> "sha-224",
    "SHA256" -> "sha-256",
    "SHA384" -> "sha-384",
    "SHA512" -> "sha-512"
  )

  /** The closed vocabulary of canonical names the standalone-name table can
    * emit.
    */
  def standaloneAlgorithms: Set[String] = KnownAlgorithmNames.values.toSet

  /** Resolve a standalone algorithm name (not a suite) to its canonical
    * lowercase form, or `None` when unknown.
    */
  def resolveAlgorithmName(name: String): Option[String] =
    KnownAlgorithmNames.get(name.trim.toUpperCase)

  /** Resolve a list of standalone algorithm names (space/comma/colon separated)
    * to their canonical forms, dropping unknown tokens.
    */
  def resolveAlgorithmList(value: String): Vector[String] = {
    if (value == null) Vector.empty
    else
      value
        .split("[\\s,:]+")
        .toVector
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap(tok => resolveAlgorithmName(tok).toVector)
  }

  /** Normalize a token for table lookup: strip exclusion/addition/weakness
    * prefixes and `@SECLEVEL` modifiers, then uppercase (keeping hyphens and
    * underscores), so OpenSSL and IANA spellings meet their table entries.
    */
  private def normalize(token: String): String = {
    var t = token.trim
    while (t.nonEmpty && (t.head == '!' || t.head == '+' || t.head == '?')) {
      t = t.tail
    }
    val at = t.indexOf('@')
    if (at >= 0) t = t.substring(0, at)
    t.toUpperCase
  }
}
