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

package io.spicelabs.goatrodeo.omnibor.strategies

/** Canonical-mapping tables for the Certificates strategy. Pure data — no
  * logic, no side effects. Maps are `private[strategies]` for use by
  * `Certificates` and `CertificatesState`.
  */
private[strategies] object CertificatesOidMaps {

  // ---------- X.509 algorithm + signature OIDs -----------

  /** Public-key OIDs → (canonical alg, optional params token). */
  private[strategies] val pubkeyOidMap: Map[String, (String, Option[String])] =
    Map(
      "2.16.840.1.101.3.4.3.17" -> ("ml-dsa", Some("44")),
      "2.16.840.1.101.3.4.3.18" -> ("ml-dsa", Some("65")),
      "2.16.840.1.101.3.4.3.19" -> ("ml-dsa", Some("87")),
      "2.16.840.1.101.3.4.3.32" -> ("ml-dsa", Some("44")),
      "2.16.840.1.101.3.4.3.33" -> ("ml-dsa", Some("65")),
      "2.16.840.1.101.3.4.3.34" -> ("ml-dsa", Some("87")),
      // composite signatures (IETF draft-ounsworth-pq-composite-sigs):
      // SPKI uses the same composite OID as the signature; alg=composite,
      // no params (sig-alg distinguishes the specific hybrid).
      "1.3.6.1.5.5.7.6.37" -> ("composite", None),
      "1.3.6.1.5.5.7.6.39" -> ("composite", None),
      "1.3.6.1.5.5.7.6.40" -> ("composite", None),
      "1.3.6.1.5.5.7.6.41" -> ("composite", None),
      "1.3.6.1.5.5.7.6.49" -> ("composite", None),
      "2.16.840.1.101.3.4.3.20" -> ("slh-dsa", Some("128s")),
      "2.16.840.1.101.3.4.3.21" -> ("slh-dsa", Some("128f")),
      "2.16.840.1.101.3.4.3.22" -> ("slh-dsa", Some("192s")),
      "2.16.840.1.101.3.4.3.23" -> ("slh-dsa", Some("192f")),
      "2.16.840.1.101.3.4.3.24" -> ("slh-dsa", Some("256s")),
      "2.16.840.1.101.3.4.3.25" -> ("slh-dsa", Some("256f")),
      "2.16.840.1.101.3.4.3.26" -> ("slh-dsa", Some("shake-128s")),
      "2.16.840.1.101.3.4.3.27" -> ("slh-dsa", Some("shake-128f")),
      "2.16.840.1.101.3.4.3.28" -> ("slh-dsa", Some("shake-192s")),
      "2.16.840.1.101.3.4.3.29" -> ("slh-dsa", Some("shake-192f")),
      "2.16.840.1.101.3.4.3.30" -> ("slh-dsa", Some("shake-256s")),
      "2.16.840.1.101.3.4.3.31" -> ("slh-dsa", Some("shake-256f")),
      "1.3.9999.3.11" -> ("falcon", Some("512")),
      "1.3.9999.3.14" -> ("falcon", Some("1024"))
    )

  private[strategies] val sigAlgOidMap: Map[String, String] = Map(
    "1.2.840.113549.1.1.4" -> "md5-rsa",
    "1.2.840.113549.1.1.2" -> "md2-rsa",
    "1.2.840.113549.1.1.3" -> "md4-rsa",
    "1.2.840.113549.1.1.4" -> "md5-rsa",
    "1.2.840.113549.1.1.5" -> "sha1-rsa",
    "1.2.840.113549.1.1.14" -> "sha224-rsa",
    "1.2.840.113549.1.1.11" -> "sha256-rsa",
    "1.2.840.113549.1.1.12" -> "sha384-rsa",
    "1.2.840.113549.1.1.13" -> "sha512-rsa",
    "1.2.840.113549.1.1.10" -> "rsa-pss",
    "1.2.840.10045.4.1" -> "sha1-ecdsa",
    "1.2.840.10045.4.3.2" -> "sha256-ecdsa",
    "1.2.840.10045.4.3.3" -> "sha384-ecdsa",
    "1.2.840.10045.4.3.4" -> "sha512-ecdsa",
    "1.3.101.112" -> "ed25519",
    "1.3.101.113" -> "ed448",
    "1.2.840.10040.4.3" -> "sha1-dsa",
    "2.16.840.1.101.3.4.3.2" -> "sha256-dsa",
    "2.16.840.1.101.3.4.3.17" -> "ml-dsa-44",
    "2.16.840.1.101.3.4.3.18" -> "ml-dsa-65",
    "2.16.840.1.101.3.4.3.19" -> "ml-dsa-87",
    "2.16.840.1.101.3.4.3.32" -> "ml-dsa-44-prehash-sha512",
    "2.16.840.1.101.3.4.3.33" -> "ml-dsa-65-prehash-sha512",
    "2.16.840.1.101.3.4.3.34" -> "ml-dsa-87-prehash-sha512",
    "2.16.840.1.101.3.4.3.20" -> "slh-dsa-sha2-128s",
    "2.16.840.1.101.3.4.3.21" -> "slh-dsa-sha2-128f",
    "2.16.840.1.101.3.4.3.22" -> "slh-dsa-sha2-192s",
    "2.16.840.1.101.3.4.3.23" -> "slh-dsa-sha2-192f",
    "2.16.840.1.101.3.4.3.24" -> "slh-dsa-sha2-256s",
    "2.16.840.1.101.3.4.3.25" -> "slh-dsa-sha2-256f",
    "2.16.840.1.101.3.4.3.26" -> "slh-dsa-shake-128s",
    "2.16.840.1.101.3.4.3.27" -> "slh-dsa-shake-128f",
    "2.16.840.1.101.3.4.3.28" -> "slh-dsa-shake-192s",
    "2.16.840.1.101.3.4.3.29" -> "slh-dsa-shake-192f",
    "2.16.840.1.101.3.4.3.30" -> "slh-dsa-shake-256s",
    "2.16.840.1.101.3.4.3.31" -> "slh-dsa-shake-256f",
    "1.3.9999.3.11" -> "falcon-512",
    "1.3.9999.3.14" -> "falcon-1024",
    "1.3.6.1.5.5.7.6.37" -> "mldsa44-rsa2048-pss-sha256",
    "1.3.6.1.5.5.7.6.39" -> "mldsa44-ed25519-sha512",
    "1.3.6.1.5.5.7.6.40" -> "mldsa44-ecdsa-p256-sha256",
    "1.3.6.1.5.5.7.6.41" -> "mldsa65-rsa3072-pss-sha512",
    "1.3.6.1.5.5.7.6.49" -> "mldsa87-ecdsa-p384-sha512"
  )

  private[strategies] val ecCurveMap: Map[String, String] = Map(
    "secp256r1" -> "p-256",
    "prime256v1" -> "p-256",
    "secp384r1" -> "p-384",
    "secp521r1" -> "p-521",
    "secp256k1" -> "secp256k1",
    "brainpoolp256r1" -> "brainpoolp256r1",
    "brainpoolp384r1" -> "brainpoolp384r1",
    "brainpoolp512r1" -> "brainpoolp512r1"
  )

  private[strategies] val ekuOidMap: Map[String, String] = Map(
    "1.3.6.1.5.5.7.3.1" -> "server-auth",
    "1.3.6.1.5.5.7.3.2" -> "client-auth",
    "1.3.6.1.5.5.7.3.3" -> "code-signing",
    "1.3.6.1.5.5.7.3.4" -> "email-protection",
    "1.3.6.1.5.5.7.3.8" -> "time-stamping",
    "1.3.6.1.5.5.7.3.9" -> "ocsp-signing"
  )

  // ---------- PKCS#8 / PBES2 + PBES1 OIDs --------------------

  /** PKCS#5/PKCS#12 PBES1 OIDs → canonical cipher name. RFC 8018 §A.3. */
  private[strategies] val pbes1OidToCanonicalCipher: Map[String, String] = Map(
    "1.2.840.113549.1.5.3" -> "des-cbc", // pbeWithMD5AndDES
    "1.2.840.113549.1.5.6" -> "rc2-cbc", // pbeWithMD5AndRC2
    "1.2.840.113549.1.5.10" -> "des-cbc", // pbeWithSHA1AndDES
    "1.2.840.113549.1.5.11" -> "rc2-cbc", // pbeWithSHA1AndRC2
    "1.2.840.113549.1.12.1.1" -> "rc4", // pbeWithSHAAnd128BitRC4
    "1.2.840.113549.1.12.1.2" -> "rc4", // pbeWithSHAAnd40BitRC4
    "1.2.840.113549.1.12.1.3" -> "des-ede3-cbc", // pbeWithSHAAnd3-KeyTripleDES-CBC
    "1.2.840.113549.1.12.1.4" -> "des-ede2-cbc", // pbeWithSHAAnd2-KeyTripleDES-CBC
    "1.2.840.113549.1.12.1.5" -> "rc2-cbc", // pbeWithSHAAnd128BitRC2
    "1.2.840.113549.1.12.1.6" -> "rc2-cbc" // pbeWithSHAAnd40BitRC2
  )

  /** PBES1 OIDs → PRF (hash function used by the KDF inside). */
  private[strategies] val pbes1OidToPrf: Map[String, String] = Map(
    "1.2.840.113549.1.5.3" -> "md5",
    "1.2.840.113549.1.5.6" -> "md5",
    "1.2.840.113549.1.5.10" -> "sha1",
    "1.2.840.113549.1.5.11" -> "sha1",
    "1.2.840.113549.1.12.1.1" -> "sha1",
    "1.2.840.113549.1.12.1.2" -> "sha1",
    "1.2.840.113549.1.12.1.3" -> "sha1",
    "1.2.840.113549.1.12.1.4" -> "sha1",
    "1.2.840.113549.1.12.1.5" -> "sha1",
    "1.2.840.113549.1.12.1.6" -> "sha1"
  )

  /** PRF OIDs (used inside PBKDF2-params) → canonical hash names. */
  private[strategies] val prfOidToCanonicalHash: Map[String, String] = Map(
    "1.2.840.113549.2.7" -> "sha256", // hmacWithSHA256 (default)
    "1.2.840.113549.2.8" -> "sha384", // hmacWithSHA384
    "1.2.840.113549.2.9" -> "sha512", // hmacWithSHA512
    "1.2.840.113549.2.10" -> "sha512-224",
    "1.2.840.113549.2.11" -> "sha512-256",
    "1.2.840.113549.2.7.1" -> "sha1"
  )

  /** Cipher OIDs (used as PBES2 encryption-scheme alg) → canonical names. */
  private[strategies] val cipherOidToName: Map[String, String] = Map(
    "2.16.840.1.101.3.4.1.2" -> "aes-128-cbc",
    "2.16.840.1.101.3.4.1.22" -> "aes-192-cbc",
    "2.16.840.1.101.3.4.1.42" -> "aes-256-cbc",
    "2.16.840.1.101.3.4.1.6" -> "aes-128-gcm",
    "2.16.840.1.101.3.4.1.26" -> "aes-192-gcm",
    "2.16.840.1.101.3.4.1.46" -> "aes-256-gcm",
    "1.2.840.113549.3.7" -> "des-ede3-cbc",
    "1.2.840.113549.3.2" -> "rc2-cbc"
  )
}
