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

import io.spicelabs.goatrodeo.omnibor.strategies.CryptoFootprintStrategy
import io.spicelabs.goatrodeo.omnibor.strategies.ShadowPasswordStrategy
import io.spicelabs.goatrodeo.util.CipherSuiteResolver
import munit.FunSuite

/** Shared canonical algorithm-name registry tests (phase H).
  *
  * The registry is the single source of truth for algorithm-name vocabulary,
  * primitive classification, and parameter extraction. These tests pin:
  *   - R-T-01 totality: every canonical name a producer can emit is in the
  *     registry vocabulary (closed vocabulary = no spelling drift in CBOMs).
  *   - R-T-02 the new hash-family names classify as `hash`.
  *   - R-T-03 the parameter table and legacy first-digit-run fallback.
  *   - R-T-04 regression: pre-existing names keep their exact (primitive,
  *     parameter) behavior except the approved deltas (C1/C2/C3).
  *   - R-T-05 vocabulary hygiene: lowercase-hyphenated canonical form.
  *   - R-T-06 no new classification name is a substring of a canonical name
  *     that classifies as something else (false-positive minting guard).
  */
class CryptoAlgorithmsSuite extends FunSuite {

  private val ApprovedParameterDeltas: Set[String] = Set(
    "sha3-256", // C1: "3" -> "256"
    "sha3-512", // C1: "3" -> "512"
    "sha-3", // C2 bundle: "3" -> None (version digit, not a parameter)
    "argon2", // C3: "2" -> None
    "argon2d", // C3: "2" -> None
    "argon2i", // C3: "2" -> None
    "argon2id" // C3: "2" -> None
  )

  private val ApprovedPrimitiveDeltas: Map[String, String] = Map(
    "sha-3" -> "other" // C2: Rust `sha-3` reclassifies other -> hash
  )

  // Old (pre-phase) behavior of the classification sets: name -> primitive.
  // `parameterFor` old behavior = first digit run, except the four
  // curve names parameterFromName special-cased to None.
  private val OldPrimitives: Map[String, String] = Map(
    "md5" -> "hash",
    "sha1" -> "hash",
    "sha224" -> "hash",
    "sha256" -> "hash",
    "sha384" -> "hash",
    "sha512" -> "hash",
    "sha3-256" -> "hash",
    "sha3-512" -> "hash",
    "blake2b" -> "hash",
    "blake2s" -> "hash",
    "whirlpool" -> "hash",
    "ripemd160" -> "hash",
    "bcrypt" -> "hash",
    "scrypt" -> "hash",
    "yescrypt" -> "hash",
    "argon2" -> "hash",
    "argon2d" -> "hash",
    "argon2i" -> "hash",
    "argon2id" -> "hash",
    "aes" -> "block-cipher",
    "des" -> "block-cipher",
    "3des" -> "block-cipher",
    "camellia" -> "block-cipher",
    "aria" -> "block-cipher",
    "seed" -> "block-cipher",
    "blowfish" -> "block-cipher",
    "twofish" -> "block-cipher",
    "chacha" -> "stream-cipher",
    "salsa" -> "stream-cipher",
    "rc4" -> "stream-cipher",
    "pbkdf" -> "kdf",
    "hkdf" -> "kdf",
    "kdf" -> "kdf",
    "dh" -> "key-agree",
    "ecdh" -> "key-agree",
    "x25519" -> "key-agree",
    "x448" -> "key-agree",
    "ml-kem" -> "key-agree",
    "kyber" -> "key-agree",
    "kem" -> "key-agree",
    "dsa" -> "signature",
    "ed25519" -> "signature",
    "ed448" -> "signature",
    "falcon" -> "signature",
    "slh-dsa" -> "signature",
    "ml-dsa" -> "signature",
    "with" -> "signature",
    "sha-3" -> "other"
  )

  private val OldParameters: Map[String, Option[String]] = Map(
    "md5" -> Some("5"),
    "sha1" -> Some("1"),
    "sha224" -> Some("224"),
    "sha256" -> Some("256"),
    "sha384" -> Some("384"),
    "sha512" -> Some("512"),
    "sha3-256" -> Some("3"),
    "sha3-512" -> Some("3"),
    "blake2b" -> Some("2"),
    "blake2s" -> Some("2"),
    "whirlpool" -> None,
    "ripemd160" -> Some("160"),
    "bcrypt" -> None,
    "scrypt" -> None,
    "yescrypt" -> None,
    "argon2" -> Some("2"),
    "argon2d" -> Some("2"),
    "argon2i" -> Some("2"),
    "argon2id" -> Some("2"),
    "aes" -> None,
    "des" -> None,
    "3des" -> Some("3"),
    "camellia" -> None,
    "aria" -> None,
    "seed" -> None,
    "blowfish" -> None,
    "twofish" -> None,
    "chacha" -> None,
    "salsa" -> None,
    "rc4" -> Some("4"),
    "pbkdf" -> None,
    "hkdf" -> None,
    "kdf" -> None,
    "dh" -> None,
    "ecdh" -> None,
    "x25519" -> None,
    "x448" -> None,
    "ml-kem" -> None,
    "kyber" -> None,
    "kem" -> None,
    "dsa" -> None,
    "ed25519" -> None,
    "ed448" -> None,
    "falcon" -> None,
    "slh-dsa" -> None,
    "ml-dsa" -> None,
    "with" -> None,
    "sha-3" -> Some("3")
  )

  private val NewHashNames: Vector[String] = Vector(
    "sha3-224",
    "sha3-384",
    "sha512-224",
    "sha512-256",
    "blake3",
    "shake128",
    "shake256",
    "sm3",
    "streebog",
    "sha-3",
    "md4",
    "mdc2",
    "whirlpool",
    "blake2b-256",
    "blake2b-512",
    "blake2s-256",
    "tiger192",
    "haval",
    "double-sha",
    "nt-hash",
    "apr1",
    "argon2id"
  )

  test("R-T-01 producer-emitted names are a subset of the vocabulary") {
    // Closed vocabulary: everything a discovery strategy can emit must be
    // inside CryptoAlgorithms.canonicalVocabulary, or the CBOM classifier
    // will see names it never registered.
    val producers: Vector[(String, Set[String])] = Vector(
      "CryptoFootprint" -> CryptoFootprintStrategy.knownAlgorithms,
      "CipherSuiteResolver.suites" -> CipherSuiteResolver.knownAlgorithms,
      "CipherSuiteResolver.standalone" ->
        CipherSuiteResolver.standaloneAlgorithms,
      "ShadowPassword" -> Set(
        "$1$salt$hash",
        "$2a$10$salt",
        "$5$salt$hash",
        "$6$salt$hash",
        "$y$j9s$salt$hash",
        "$7$10$hash",
        "$argon2id$v=19$m=65536,t=3,p=4$salt$hash",
        "$3$$8846f7eaee8fb117ad06bdd830b7586c",
        "$apr1$Jh7M.9rR$hash"
      ).map(ShadowPasswordStrategy.hashAlgorithm)
        .filterNot(a => a == "locked" || a == "other")
    )
    val vocabulary = CryptoAlgorithms.canonicalVocabulary
    producers.foreach { case (producer, names) =>
      names.foreach { n =>
        assert(
          vocabulary.contains(n),
          s"[$producer] emits '$n' which is not in the registry vocabulary"
        )
      }
    }
    // Classifier-only names (no producer yet) must also be present so that
    // R1 classification works when they first appear.
    Vector("blake3", "streebog").foreach { n =>
      assert(
        vocabulary.contains(n),
        s"classifier-only name '$n' missing from vocabulary"
      )
    }
  }

  test("R-T-02 new hash-family names classify as hash") {
    NewHashNames.foreach { n =>
      assertEquals(
        CryptoAlgorithms.inferPrimitive(n),
        "hash",
        s"'$n' must classify as hash"
      )
    }
    // Sanity for the other primitives so the classifier is not hash-everything.
    assertEquals(CryptoAlgorithms.inferPrimitive("aes-128-gcm"), "block-cipher")
    assertEquals(CryptoAlgorithms.inferPrimitive("chacha20"), "stream-cipher")
    assertEquals(CryptoAlgorithms.inferPrimitive("pbkdf2"), "kdf")
    assertEquals(CryptoAlgorithms.inferPrimitive("x25519"), "key-agree")
    assertEquals(CryptoAlgorithms.inferPrimitive("ed25519"), "signature")
    assertEquals(CryptoAlgorithms.inferPrimitive("rsa"), "pke")
  }

  test("R-T-03 parameter rule: table, no-parameter names, and fallback") {
    val pinned: Map[String, Option[String]] = Map(
      "sha512-224" -> Some("224"),
      "sha512-256" -> Some("256"),
      "blake2b-512" -> Some("512"),
      "blake2b-256" -> Some("256"),
      "blake2s-256" -> Some("256"),
      "sha3-224" -> Some("224"),
      "sha3-384" -> Some("384"),
      "sha3-256" -> Some("256"), // C1
      "sha3-512" -> Some("512"), // C1
      "argon2id" -> None, // C3
      "argon2" -> None, // C3
      "argon2i" -> None, // C3
      "argon2d" -> None, // C3
      "shake128" -> None,
      "shake256" -> None,
      "blake3" -> None,
      "sm3" -> None,
      "streebog" -> None,
      "md4" -> None,
      "mdc2" -> None,
      "nt-hash" -> None,
      "apr1" -> None,
      "double-sha" -> None,
      "haval" -> None,
      "tiger192" -> None,
      "sha-3" -> None
    )
    pinned.foreach { case (name, expected) =>
      assertEquals(
        CryptoAlgorithms.parameterFor(name),
        expected,
        s"parameterFor('$name')"
      )
    }
    // Legacy fallback: names not in any table keep the first-digit-run rule.
    assertEquals(CryptoAlgorithms.parameterFor("aes-128-cbc"), Some("128"))
    assertEquals(CryptoAlgorithms.parameterFor("sha256withrsa"), Some("256"))
    assertEquals(CryptoAlgorithms.parameterFor("whirlpool"), None)
    // Curve names keep their special None rule.
    assertEquals(CryptoAlgorithms.parameterFor("ed25519"), None)
    assertEquals(CryptoAlgorithms.parameterFor("x25519"), None)
  }

  test("R-T-04 pre-existing names keep old behavior except approved deltas") {
    OldPrimitives.foreach { case (name, oldPrimitive) =>
      val newPrimitive = CryptoAlgorithms.inferPrimitive(name)
      ApprovedPrimitiveDeltas.get(name) match {
        case Some(_) => // C2: assert the intended new value, not the old one
          assertEquals(
            newPrimitive,
            "hash",
            s"'$name' must reclassify to hash (C2)"
          )
        case None =>
          assertEquals(
            newPrimitive,
            oldPrimitive,
            s"primitive of '$name' must be unchanged"
          )
      }
    }
    OldParameters.foreach { case (name, oldParam) =>
      val newParam = CryptoAlgorithms.parameterFor(name)
      if (ApprovedParameterDeltas.contains(name)) {
        // C1/C3: assert the intended new value.
        val expected =
          if (name == "sha3-256") Some("256")
          else if (name == "sha3-512") Some("512")
          else None
        assertEquals(
          newParam,
          expected,
          s"parameter of '$name' must be the approved delta"
        )
      } else {
        assertEquals(
          newParam,
          oldParam,
          s"parameter of '$name' must be unchanged"
        )
      }
    }
  }

  test("R-T-05 vocabulary and classification names are canonical") {
    val canonical = "^[a-z0-9][a-z0-9-]*$".r
    val allSets = Vector(
      CryptoAlgorithms.hashNames,
      CryptoAlgorithms.blockCipherNames,
      CryptoAlgorithms.streamCipherNames,
      CryptoAlgorithms.kdfNames,
      CryptoAlgorithms.keyAgreeNames,
      CryptoAlgorithms.signatureNames
    )
    allSets.zipWithIndex.foreach { case (names, idx) =>
      names.foreach { n =>
        assert(
          canonical.matches(n),
          s"set #$idx entry '$n' is not lowercase-hyphenated canonical"
        )
      }
    }
  }

  test(
    "R-T-06 a new hash name is never a substring of a differently-classified name"
  ) {
    val vocabulary = CryptoAlgorithms.canonicalVocabulary
    NewHashNames.foreach { newName =>
      vocabulary.foreach { v =>
        if (v != newName && v.contains(newName)) {
          assertEquals(
            CryptoAlgorithms.inferPrimitive(v),
            "hash",
            s"'$newName' is a substring of '$v' which classifies " +
              s"as ${CryptoAlgorithms.inferPrimitive(v)} — false-positive risk"
          )
        }
      }
    }
  }
}
