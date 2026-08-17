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

package io.spicelabs.goatrodeo.omnibor

/** The shared canonical algorithm-name registry.
  *
  * Single source of truth for algorithm vocabulary, primitive classification,
  * and `parameterSetIdentifier` extraction, consumed by the CBOM emitter and
  * cross-checked against every discovery strategy's emissions (see
  * `CryptoAlgorithmsSuite.R-T-01`).
  *
  * Classification is substring-based (for composite names like
  * `sha256withrsa`); therefore every added classification name must be
  * distinctive enough that it cannot appear inside a canonical name of a
  * different primitive (pinned by `CryptoAlgorithmsSuite.R-T-06`).
  */
object CryptoAlgorithms {

  /** Hash-family names. Contains both plain names (`sha256`) and hyphenated
    * canonical names (`sha3-256`, `sha512-224`, `blake2b-512`).
    */
  val hashNames: Set[String] = Set(
    "md5",
    "sha1",
    "sha224",
    "sha256",
    "sha384",
    "sha512",
    "sha3-224",
    "sha3-256",
    "sha3-384",
    "sha3-512",
    "sha512-224",
    "sha512-256",
    "blake2b",
    "blake2s",
    "blake2b-256",
    "blake2b-512",
    "blake2s-256",
    "blake3",
    "shake128",
    "shake256",
    "whirlpool",
    "ripemd160",
    "sm3",
    "streebog",
    "sha-3",
    "md4",
    "mdc2",
    "tiger192",
    "haval",
    "double-sha",
    "bcrypt",
    "scrypt",
    "yescrypt",
    "argon2",
    "argon2d",
    "argon2i",
    "argon2id",
    "nt-hash",
    "apr1"
  )

  /** Block-cipher family names. */
  val blockCipherNames: Set[String] =
    Set("aes", "des", "3des", "camellia", "aria", "seed", "blowfish", "twofish")

  /** Stream-cipher family names. */
  val streamCipherNames: Set[String] = Set("chacha", "salsa", "rc4")

  /** Key-derivation family names. */
  val kdfNames: Set[String] = Set("pbkdf", "hkdf", "kdf", "scrypt", "argon2")

  /** Key-agreement family names. */
  val keyAgreeNames: Set[String] =
    Set("dh", "ecdh", "x25519", "x448", "ml-kem", "kyber", "kem")

  /** Signature family names (`with` matches `SHA256withRSA` composites). */
  val signatureNames: Set[String] =
    Set("dsa", "ed25519", "ed448", "falcon", "slh-dsa", "ml-dsa", "with")

  /** Classify an algorithm name into a CycloneDX primitive.
    *
    * Substring matching is intentional: composite names (`sha256withrsa`,
    * `aes-128-gcm`) carry their family inside them. Free-text inputs are the
    * caller's responsibility to gate (see `CbomEmitter`'s JWT path).
    *
    * @param alg
    *   the algorithm name
    * @return
    *   one of `hash`, `mac`, `block-cipher`, `stream-cipher`, `kdf`,
    *   `key-agree`, `signature`, `pke`, or `other`
    */
  def inferPrimitive(alg: String): String = {
    val lower = alg.toLowerCase
    if (hashNames.exists(lower.contains(_))) "hash"
    else if (lower.contains("hmac")) "mac"
    else if (blockCipherNames.exists(lower.contains(_))) "block-cipher"
    else if (streamCipherNames.exists(lower.contains(_))) "stream-cipher"
    else if (kdfNames.exists(lower.contains(_))) "kdf"
    else if (keyAgreeNames.exists(lower.contains(_))) "key-agree"
    else if (signatureNames.exists(lower.contains(_))) "signature"
    else if (lower.contains("rsa")) "pke"
    else "other"
  }

  /** Explicit `parameterSetIdentifier` overrides where the legacy
    * first-digit-run heuristic is wrong. Hyphenated hash names use their
    * trailing (output-size) number, not the leading family/version digit.
    */
  private val ParameterTable: Map[String, String] = Map(
    "sha512-224" -> "224",
    "sha512-256" -> "256",
    "blake2b-256" -> "256",
    "blake2b-512" -> "512",
    "blake2s-256" -> "256",
    "sha3-224" -> "224",
    "sha3-256" -> "256",
    "sha3-384" -> "384",
    "sha3-512" -> "512"
  )

  /** Names whose embedded digits are version/family digits, not parameters. */
  private val NoParameter: Set[String] = Set(
    "argon2",
    "argon2d",
    "argon2i",
    "argon2id",
    "shake128",
    "shake256",
    "blake3",
    "sm3",
    "streebog",
    "md4",
    "mdc2",
    "nt-hash",
    "apr1",
    "double-sha",
    "haval",
    "tiger192",
    "sha-3"
  )

  /** Extract the parameter (key/digest size) from an algorithm name.
    *
    * Rules, in order:
    *   1. names in [[NoParameter]] carry no parameter (their digits are version
    *      or family digits); 2. names in [[ParameterTable]] use the pinned
    *      override; 3. curve names `ed25519`/`ed448`/`x25519`/`x448` carry no
    *      parameter (legacy rule); 4. otherwise the first digit run (legacy
    *      fallback), e.g. `aes-128-cbc` -> `"128"`.
    */
  def parameterFor(name: String): Option[String] = {
    val lower = name.toLowerCase
    if (NoParameter.contains(lower)) None
    else
      ParameterTable
        .get(lower)
        .orElse {
          if (
            lower.contains("ed25519") || lower.contains("ed448") ||
            lower.contains("x25519") || lower.contains("x448")
          ) None
          else """(\d+)""".r.findFirstIn(lower)
        }
  }

  /** The closed vocabulary of canonical names the discovery strategies and
    * resolvers may emit. Producer tables are cross-checked against this set by
    * `CryptoAlgorithmsSuite.R-T-01`; `CipherSuiteResolver` outputs (dashed SHA
    * spellings, cipher modes) and PGP/shadow values are curated here rather
    * than imported to keep the registry a leaf module.
    */
  val canonicalVocabulary: Set[String] =
    hashNames ++ blockCipherNames ++ streamCipherNames ++ kdfNames ++
      keyAgreeNames ++ signatureNames ++ ParameterTable.keySet ++ Set(
        "rsa",
        "ecdh",
        "ecdsa",
        "dh",
        "aes-128-gcm",
        "aes-256-gcm",
        "aes-128-cbc",
        "aes-256-cbc",
        "aes-128-ccm",
        "aes-128-ccm-8",
        "aes-256-ccm",
        "aes-256-ccm-8",
        "aes-128",
        "aes-192",
        "aes-256",
        "chacha20",
        "chacha20-poly1305",
        "camellia-128-cbc",
        "camellia-256-cbc",
        "aria-128-gcm",
        "aria-256-gcm",
        "hmac",
        "hmac-sha-256",
        "aes-gcm",
        "curve25519",
        "tls",
        "ffdhe-1536",
        "ffdhe-2048",
        "ffdhe-3072",
        "ffdhe-4096",
        "ffdhe-6144",
        "secp256r1",
        "secp384r1",
        "secp521r1",
        "sha-1",
        "sha-2",
        "sha-224",
        "sha-256",
        "sha-384",
        "sha-512"
      )
}
