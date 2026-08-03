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

import com.typesafe.scalalogging.Logger
import org.json4s.*
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import scala.annotation.tailrec
import scala.util.Try

/** Optional CycloneDX cryptographic bill-of-materials (CBOM) emitter.
  *
  * Emits one CBOM JSON file per top-level input file (root Item) after the
  * Artifact Dependency Graph has been built. The emitter walks `contains` edges
  * transitively, collects Items that carry cryptographic metadata, redacts
  * private-key Items, and maps the remainder to CycloneDX 1.6 or 1.7
  * `cryptographic-asset` components.
  */
object CbomEmitter {
  private val logger: Logger = Logger(getClass())

  private val MaxTraversalDepth: Int = 32
  private val MaxComponentsPerRoot: Int = 100000
  private val OutputPermissions: String = "rwxr-x---"
  private val FilePermissions: String = "rw-r-----"

  /** Emit CBOM files for every root Item in the given Storage.
    *
    * @param storage
    *   the populated storage backend
    * @param version
    *   CycloneDX version ("1.6" or "1.7")
    * @param outDir
    *   destination directory for CBOM JSON files
    * @return
    *   the emitted files, or a failure if directory creation / writing fails
    */
  def emitForStorage(
      storage: Storage,
      version: String,
      outDir: File
  ): Try[Seq[File]] = {
    val roots = findRoots(storage)
    emit(storage, roots, version, outDir)
  }

  /** Emit one CBOM file per root Item.
    *
    * @param storage
    *   the populated storage backend
    * @param roots
    *   root Items to emit CBOMs for
    * @param version
    *   CycloneDX version ("1.6" or "1.7")
    * @param outDir
    *   destination directory
    * @return
    *   the emitted files, or a failure
    */
  def emit(
      storage: Storage,
      roots: Seq[Item],
      version: String,
      outDir: File
  ): Try[Seq[File]] = {
    for {
      dir <- safeOutputDir(outDir)
      files <- roots.foldLeft(Try(Vector[File]())) { case (accTry, root) =>
        for {
          acc <- accTry
          (items, truncated) = collectCryptoItems(storage, root)
          doc = buildDocument(root, items, version, truncated)
          filename = cbomFilename(root.identifier)
          file <- atomicWrite(dir, filename, doc)
        } yield acc :+ file
      }
    } yield files
  }

  /** Find all root Items in storage. */
  private def findRoots(storage: Storage): Vector[Item] = {
    storage.keys().toVector.flatMap(key => storage.read(key).filter(_.isRoot()))
  }

  /** Collect cryptographic Items reachable from a root via `contains` edges.
    *
    * Returns the collected Items and a flag indicating whether the collection
    * was truncated to [[MaxComponentsPerRoot]].
    */
  private def collectCryptoItems(
      storage: Storage,
      root: Item
  ): (Vector[Item], Boolean) = {
    val rootCollected =
      if (isCryptoItem(root) && !isPrivateKey(root)) Vector(root) else Vector()

    @tailrec
    def loop(
        toVisit: Vector[(String, Int)],
        visited: Set[String],
        collected: Vector[Item],
        truncated: Boolean
    ): (Vector[Item], Boolean) = {
      if (toVisit.isEmpty) {
        (collected, truncated)
      } else if (collected.length >= MaxComponentsPerRoot) {
        (collected, true)
      } else {
        val (gitoid, depth) = toVisit.head
        val rest = toVisit.tail
        if (visited.contains(gitoid) || depth > MaxTraversalDepth) {
          loop(rest, visited, collected, truncated)
        } else {
          storage.read(gitoid) match {
            case Some(item) =>
              val nextCollected =
                if (isCryptoItem(item) && !isPrivateKey(item)) collected :+ item
                else collected
              val children =
                if (depth < MaxTraversalDepth)
                  item.listContains().map(_ -> (depth + 1))
                else Vector()
              loop(rest ++ children, visited + gitoid, nextCollected, truncated)
            case None =>
              loop(rest, visited + gitoid, collected, truncated)
          }
        }
      }
    }

    loop(
      root.listContains().map(_ -> 1),
      Set(root.identifier),
      rootCollected,
      false
    )
  }

  /** True when the Item carries cryptographic metadata from a known strategy.
    */
  private def isCryptoItem(item: Item): Boolean = {
    item.bodyAsItemMetaData.exists { meta =>
      meta.extra.keys.exists(k =>
        k.startsWith("Certificates:") ||
          k.startsWith("openssl.cnf:") ||
          k.startsWith("java.security:") ||
          k.startsWith("PasswordHash:") ||
          k.startsWith("Usign:") ||
          k.startsWith("SSH:") ||
          k.startsWith("TLSConfig:") ||
          k.startsWith("EmbeddedCertificates:")
      )
    }
  }

  /** True when the Item is a private key (or secret key) that must be redacted.
    */
  private def isPrivateKey(item: Item): Boolean = {
    item.bodyAsItemMetaData.exists { meta =>
      val extra = meta.extra
      val derivedFromPrivateKey = extra
        .get("Certificates:DerivedFromPrivateKey")
        .exists(_.exists(_.value == "true"))
      val descriptionPrivate = extra
        .get("Description")
        .flatMap(_.headOption)
        .map(_.value)
        .exists(_.toLowerCase.contains("private key"))
      val sshPrivateKey = extra
        .get("SSH:MaterialType")
        .exists(_.exists(_.value == "private-key"))
      derivedFromPrivateKey || descriptionPrivate || sshPrivateKey
    }
  }

  /** Build the CycloneDX document for a root and its collected crypto Items. */
  private def buildDocument(
      root: Item,
      items: Vector[Item],
      version: String,
      truncated: Boolean
  ): JObject = {
    if (truncated) {
      logger.warn(
        f"CBOM for root ${root.identifier} truncated to ${MaxComponentsPerRoot}%,d components"
      )
    }

    val serialNumber = "urn:uuid:" + UUID
      .nameUUIDFromBytes(root.identifier.getBytes("UTF-8"))
      .toString()
    val timestamp = Instant.now().toString()
    val toolVersion = hellogoat.BuildInfo.version
    val components = {
      val all = items.flatMap(buildComponentsForItem)
      // Deduplicate synthetic algorithm components across the root by bom-ref,
      // preserving the first occurrence of each referenced algorithm.
      val seen = scala.collection.mutable.LinkedHashSet[String]()
      all.filter { c =>
        val ref = (c \ "bom-ref") match {
          case JString(s) => s
          case _          => ""
        }
        if (ref.isEmpty) true
        else if (seen.contains(ref)) false
        else {
          seen += ref
          true
        }
      }.toList
    }

    val baseFields: List[JField] = List(
      "bomFormat" -> JString("CycloneDX"),
      "specVersion" -> JString(version),
      "serialNumber" -> JString(serialNumber),
      "version" -> JInt(1),
      "metadata" -> JObject(
        "timestamp" -> JString(timestamp),
        "tools" -> JObject(
          "components" -> JArray(
            List(
              JObject(
                "type" -> JString("application"),
                "name" -> JString("goatrodeo"),
                "version" -> JString(toolVersion)
              )
            )
          )
        )
      ),
      "components" -> JArray(components)
    )

    val fields = if (truncated) {
      baseFields :+
        ("properties" -> JArray(
          List(
            JObject(
              "name" -> JString("cbom:truncated"),
              "value" -> JString("true")
            )
          )
        ))
    } else {
      baseFields
    }

    JObject(fields)
  }

  /** Description of an algorithm asset emitted alongside a crypto component. */
  private case class AlgorithmSpec(
      name: String,
      primitive: String,
      parameter: Option[String] = None,
      curve: Option[String] = None
  )

  private val HashNames: Set[String] = Set(
    "md5",
    "sha1",
    "sha224",
    "sha256",
    "sha384",
    "sha512",
    "sha3-256",
    "sha3-512",
    "blake2b",
    "blake2s",
    "whirlpool",
    "ripemd160",
    "bcrypt",
    "scrypt",
    "yescrypt",
    "argon2",
    "argon2d",
    "argon2i",
    "argon2id"
  )

  private val BlockCipherNames: Set[String] =
    Set("aes", "des", "3des", "camellia", "aria", "seed", "blowfish", "twofish")

  private val StreamCipherNames: Set[String] = Set("chacha", "salsa", "rc4")

  private val KdfNames: Set[String] =
    Set("pbkdf", "hkdf", "kdf", "scrypt", "argon2")

  private val KeyAgreeNames: Set[String] =
    Set("dh", "ecdh", "x25519", "x448", "ml-kem", "kyber", "kem")

  private val SignatureNames: Set[String] =
    Set("dsa", "ed25519", "ed448", "falcon", "slh-dsa", "ml-dsa", "with")

  /** Normalize an algorithm string for use in a stable bom-ref. */
  private def normalizeAlgName(name: String): String = {
    val sb = new StringBuilder
    name.toLowerCase.foreach { c =>
      if (c.isLetterOrDigit || c == '-') sb.append(c)
      else if (sb.nonEmpty && sb.last != '-') sb.append('-')
    }
    val s = sb.toString.stripPrefix("-").stripSuffix("-")
    if (s.isEmpty) "unknown" else s
  }

  /** Synthetic bom-ref for an algorithm asset. */
  private def algorithmRef(spec: AlgorithmSpec): String =
    s"alg:${spec.primitive}:${normalizeAlgName(spec.name)}"

  /** Heuristic primitive classification from an algorithm name. */
  private def inferPrimitive(alg: String): String = {
    val lower = alg.toLowerCase
    if (HashNames.exists(lower.contains(_))) "hash"
    else if (lower.contains("hmac")) "mac"
    else if (BlockCipherNames.exists(lower.contains(_))) "block-cipher"
    else if (StreamCipherNames.exists(lower.contains(_))) "stream-cipher"
    else if (KdfNames.exists(lower.contains(_))) "kdf"
    else if (KeyAgreeNames.exists(lower.contains(_))) "key-agree"
    else if (SignatureNames.exists(lower.contains(_))) "signature"
    else if (lower.contains("rsa")) "pke"
    else "other"
  }

  /** Choose the CycloneDX primitive based on the usage context. */
  private def primitiveFor(alg: String, context: String): String =
    context match {
      case "pke"       => "pke"
      case "signature" => "signature"
      case "hash"      => "hash"
      case "mac"       => "mac"
      case _           => inferPrimitive(alg)
    }

  /** Extract a numeric parameter (key length, digest length, etc.) from an
    * algorithm name when no explicit size or curve is available.
    */
  private def parameterFromName(name: String): Option[String] = {
    val lower = name.toLowerCase
    if (
      lower.contains("ed25519") || lower.contains("ed448") ||
      lower.contains("x25519") || lower.contains("x448")
    ) {
      None
    } else {
      """(\d+)""".r.findFirstIn(lower)
    }
  }

  /** Build an `algorithm` cryptographic-asset component for an algorithm spec.
    */
  private def algorithmComponent(
      spec: AlgorithmSpec
  ): Option[(String, JObject)] = {
    val normalized = normalizeAlgName(spec.name)
    if (normalized.isEmpty || normalized == "unknown") return None
    val ref = algorithmRef(spec)
    val param = spec.parameter
      .orElse(spec.curve)
      .orElse(parameterFromName(spec.name))
    val apFields: List[JField] = List(
      "primitive" -> JString(spec.primitive)
    ) ++ param.map(p => "parameterSetIdentifier" -> JString(p)) ++
      spec.curve.map(c => "curve" -> JString(c))
    val component = JObject(
      "type" -> JString("cryptographic-asset"),
      "bom-ref" -> JString(ref),
      "name" -> JString(spec.name),
      "cryptoProperties" -> JObject(
        "assetType" -> JString("algorithm"),
        "algorithmProperties" -> JObject(apFields)
      )
    )
    Some(ref -> component)
  }

  /** Map a single Item to a list of CycloneDX components: the main item plus
    * any algorithm assets referenced by it.
    */
  private def buildComponentsForItem(item: Item): List[JObject] = {
    val extra = metaExtra(item)
    val name = extra
      .get("Name")
      .flatMap(_.headOption)
      .orElse(extra.get("Description").flatMap(_.headOption))
      .getOrElse(item.identifier)

    if (name.isEmpty()) {
      return Nil
    }

    val baseFields: List[JField] = List(
      Some("type" -> JString("cryptographic-asset")),
      Some("bom-ref" -> JString(item.identifier)),
      Some("name" -> JString(name)),
      extra
        .get("Description")
        .flatMap(_.headOption)
        .map(d => "description" -> JString(d))
    ).flatten

    val props = propertiesFromExtra(extra)
    val withProps =
      if (props.arr.isEmpty) JObject(baseFields)
      else JObject(baseFields :+ ("properties" -> props))

    val (mainOpt, algs) = cryptoPropertiesFor(item, extra)
    val mainComponent =
      mainOpt.map(cp => withProps ~ ("cryptoProperties" -> cp))
    mainComponent.toList ++ algs.values.toList
  }

  /** Extract metadata extra as a plain Map for easier lookups. */
  private def metaExtra(item: Item): Map[String, Set[String]] = {
    item.bodyAsItemMetaData
      .map { meta =>
        meta.extra.map { case (k, v) =>
          k -> v.map(_.value).toSet
        }.toMap
      }
      .getOrElse(Map())
  }

  /** Build CycloneDX `properties` from crypto-specific metadata keys. */
  private def propertiesFromExtra(extra: Map[String, Set[String]]): JArray = {
    val props = extra.flatMap { case (k, vs) =>
      if (
        k.startsWith("Certificates:") ||
        k.startsWith("openssl.cnf:") ||
        k.startsWith("java.security:") ||
        k.startsWith("PasswordHash:") ||
        k.startsWith("Usign:") ||
        k.startsWith("SSH:") ||
        k.startsWith("TLSConfig:") ||
        k.startsWith("EmbeddedCertificates:")
      ) {
        vs.map(v => JObject("name" -> JString(k), "value" -> JString(v))).toList
      } else {
        Nil
      }
    }.toList
    JArray(props)
  }

  /** Build `cryptoProperties` for an Item based on its metadata family.
    *
    * Returns the main component's cryptoProperties (if any) plus a map of
    * synthetic bom-ref -> algorithm components referenced by that item.
    */
  private def cryptoPropertiesFor(
      item: Item,
      extra: Map[String, Set[String]]
  ): (Option[JObject], Map[String, JObject]) = {
    val algs = scala.collection.mutable.Map[String, JObject]()

    def addAlg(
        raw: String,
        context: String,
        size: Option[Int] = None,
        curve: Option[String] = None
    ): Option[String] = {
      val param = size.map(_.toString)
      val spec = AlgorithmSpec(raw, primitiveFor(raw, context), param, curve)
      algorithmComponent(spec).map { case (ref, comp) =>
        algs += ref -> comp
        ref
      }
    }

    def firstCertOrTop(key: String): Option[String] =
      first(extra, key).orElse(
        first(extra, "Certificates:Cert:0:" + key.stripPrefix("Certificates:"))
      )

    def keySize(prefix: String): Option[Int] = {
      val top = first(extra, s"${prefix}KeySize")
        .orElse(first(extra, "Usign:KeySize"))
        .orElse(first(extra, "SSH:KeySize"))
        .orElse(first(extra, "Certificates:KeySize"))
      top.flatMap(s => Try(s.toInt).toOption)
    }

    def curve(prefix: String): Option[String] = {
      first(extra, s"${prefix}Curve")
        .orElse(first(extra, "SSH:Curve"))
        .orElse(first(extra, "Certificates:Curve"))
    }

    def usignKeySize: Option[Int] =
      first(extra, "Usign:KeySize").flatMap(s => Try(s.toInt).toOption)

    if (hasCertificate(extra)) {
      val keyAlg = firstCertOrTop("Certificates:KeyAlgorithm")
      val sigAlg = firstCertOrTop("Certificates:SigAlgorithm")
      val keyRef = keyAlg.flatMap(
        addAlg(_, "pke", keySize("Certificates:"), curve("Certificates:"))
      )
      val sigRef = sigAlg.flatMap(addAlg(_, "signature"))
      (
        Some(buildCertificateProperties(extra, keyRef, sigRef)),
        algs.toMap
      )
    } else if (hasOpenSSLConfig(extra)) {
      (Some(buildProtocolProperties(extra)), algs.toMap)
    } else if (hasJavaSecurity(extra)) {
      (Some(buildRelatedCryptoMaterialProperties(extra, "other")), algs.toMap)
    } else if (hasKeystore(extra)) {
      (Some(buildRelatedCryptoMaterialProperties(extra, "key")), algs.toMap)
    } else if (hasCrl(extra)) {
      val sigRef =
        first(extra, "Certificates:SigAlgorithm").flatMap(
          addAlg(_, "signature")
        )
      (
        Some(buildRelatedCryptoMaterialProperties(extra, "other", sigRef)),
        algs.toMap
      )
    } else if (hasPublicKey(extra)) {
      val keyRef = first(extra, "Certificates:KeyAlgorithm")
        .flatMap(
          addAlg(_, "pke", keySize("Certificates:"), curve("Certificates:"))
        )
      val sigRef =
        first(extra, "Certificates:SshCertSigAlgorithm").flatMap(
          addAlg(_, "signature")
        )
      (
        Some(
          buildRelatedCryptoMaterialProperties(
            extra,
            "public-key",
            keyRef,
            sigRef
          )
        ),
        algs.toMap
      )
    } else if (hasPasswordHash(extra)) {
      val hashRefs =
        extra
          .getOrElse("PasswordHash:Algorithm", Set())
          .flatMap(addAlg(_, "hash"))
      val hashRef = hashRefs.headOption
      (
        Some(buildRelatedCryptoMaterialProperties(extra, "password", hashRef)),
        algs.toMap
      )
    } else if (hasUsign(extra)) {
      val keyRef =
        first(extra, "Usign:KeyAlgorithm").flatMap(
          addAlg(_, "pke", usignKeySize, None)
        )
      (
        Some(buildRelatedCryptoMaterialProperties(extra, "public-key", keyRef)),
        algs.toMap
      )
    } else if (hasSSH(extra)) {
      val materialType =
        extra.get("SSH:MaterialType").flatMap(_.headOption).getOrElse("other")
      val keyAlg =
        first(extra, "SSH:KeyAlgorithm").orElse(
          first(extra, "Certificates:KeyAlgorithm")
        )
      val keyRef = keyAlg.flatMap(
        addAlg(_, "pke", keySize("SSH:"), curve("SSH:"))
      )
      val sigRef =
        first(extra, "Certificates:SshCertSigAlgorithm").flatMap(
          addAlg(_, "signature")
        )
      val cp =
        if (
          materialType == "public-key" || materialType == "private-key" || materialType == "private-key-placeholder"
        ) {
          buildRelatedCryptoMaterialProperties(extra, materialType, keyRef)
        } else {
          buildRelatedCryptoMaterialProperties(
            extra,
            materialType,
            keyRef,
            sigRef
          )
        }
      (Some(cp), algs.toMap)
    } else if (hasTLSConfig(extra)) {
      (Some(buildRelatedCryptoMaterialProperties(extra, "other")), algs.toMap)
    } else if (hasEmbeddedCertificate(extra)) {
      (Some(buildRelatedCryptoMaterialProperties(extra, "other")), algs.toMap)
    } else {
      (None, algs.toMap)
    }
  }

  private def hasCertificate(extra: Map[String, Set[String]]): Boolean = {
    extra.contains("Certificates:SubjectDN") ||
    extra.keys.exists(_.startsWith("Certificates:Cert:"))
  }

  private def hasOpenSSLConfig(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("openssl.cnf:"))
  }

  private def hasJavaSecurity(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("java.security:"))
  }

  private def hasKeystore(extra: Map[String, Set[String]]): Boolean = {
    extra.contains("Certificates:KeystoreType")
  }

  private def hasCrl(extra: Map[String, Set[String]]): Boolean = {
    extra.contains("Certificates:CrlSha256")
  }

  private def hasPublicKey(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("Certificates:"))
  }

  private def hasPasswordHash(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("PasswordHash:"))
  }

  private def hasUsign(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("Usign:"))
  }

  private def hasSSH(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("SSH:"))
  }

  private def hasTLSConfig(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("TLSConfig:"))
  }

  private def hasEmbeddedCertificate(
      extra: Map[String, Set[String]]
  ): Boolean = {
    extra.keys.exists(_.startsWith("EmbeddedCertificates:"))
  }

  /** Build related-crypto-material properties for SSH keys, using the material
    * type captured in `SSH:MaterialType` (public-key or private-key).
    */
  private def buildSSHProperties(
      extra: Map[String, Set[String]],
      keyRef: Option[String],
      sigRef: Option[String]
  ): JObject = {
    val materialType =
      extra.get("SSH:MaterialType").flatMap(_.headOption).getOrElse("other")
    buildRelatedCryptoMaterialProperties(extra, materialType, keyRef, sigRef)
  }

  private def buildCertificateProperties(
      extra: Map[String, Set[String]],
      keyRef: Option[String],
      sigRef: Option[String]
  ): JObject = {
    val subject = first(extra, "Certificates:SubjectDN")
      .orElse(first(extra, "Certificates:Cert:0:SubjectDN"))
    val issuer = first(extra, "Certificates:IssuerDN")
      .orElse(first(extra, "Certificates:Cert:0:IssuerDN"))
    val notBefore = first(extra, "Certificates:NotBefore")
      .orElse(first(extra, "Certificates:Cert:0:NotBefore"))
    val notAfter = first(extra, "Certificates:NotAfter")
      .orElse(first(extra, "Certificates:Cert:0:NotAfter"))

    val certFields: List[JField] = List(
      subject.map(s => "subjectName" -> JString(s)),
      issuer.map(s => "issuerName" -> JString(s)),
      notBefore.map(s => "notValidBefore" -> JString(s)),
      notAfter.map(s => "notValidAfter" -> JString(s)),
      keyRef.map(r => "subjectPublicKeyRef" -> JString(r)),
      sigRef.map(r => "signatureAlgorithmRef" -> JString(r)),
      Some("certificateFormat" -> JString("X.509"))
    ).flatten

    ("assetType" -> "certificate") ~
      ("certificateProperties" -> JObject(certFields))
  }

  private def buildProtocolProperties(
      extra: Map[String, Set[String]]
  ): JObject = {
    val minProtocol = first(extra, "openssl.cnf:min_protocol")
    val maxProtocol = first(extra, "openssl.cnf:max_protocol")
    val cipherString = first(extra, "openssl.cnf:cipher_string")
    val cipherSuites = extra.getOrElse("openssl.cnf:cipher_suites", Set())
    val options = extra.getOrElse("openssl.cnf:options", Set())

    val version = (minProtocol, maxProtocol) match {
      case (Some(min), Some(max)) => Some(s"min:$min max:$max")
      case (Some(min), None)      => Some(s"min:$min")
      case (None, Some(max))      => Some(s"max:$max")
      case _                      => None
    }

    val suiteNames = cipherString.map(_.split(":").toList).getOrElse(List()) ++
      cipherSuites.toList ++
      options.toList

    val suites = suiteNames.map(name =>
      JObject(
        "name" -> JString(name),
        "identifiers" -> JArray(List(JString(name)))
      )
    )

    val protoFields: List[JField] = List(
      Some("type" -> JString("tls")),
      version.map(v => "version" -> JString(v)),
      if (suites.nonEmpty) Some("cipherSuites" -> JArray(suites)) else None
    ).flatten

    ("assetType" -> "protocol") ~
      ("protocolProperties" -> JObject(protoFields))
  }

  private def buildRelatedCryptoMaterialProperties(
      extra: Map[String, Set[String]],
      materialType: String,
      algorithmRef: Option[String] = None,
      signatureRef: Option[String] = None
  ): JObject = {
    val size = first(extra, "Certificates:KeySize")
      .orElse(first(extra, "Usign:KeySize"))
      .orElse(first(extra, "SSH:KeySize"))
      .orElse(first(extra, "Certificates:Cert:0:KeySize"))
      .flatMap(s => Try(s.toInt).toOption)

    val ref = algorithmRef.orElse(signatureRef)
    val matFields: List[JField] = List(
      Some("type" -> JString(materialType)),
      ref.map(r => "algorithmRef" -> JString(r)),
      size.map(s => "size" -> JInt(s))
    ).flatten

    ("assetType" -> "related-crypto-material") ~
      ("relatedCryptoMaterialProperties" -> JObject(matFields))
  }

  private def first(
      extra: Map[String, Set[String]],
      key: String
  ): Option[String] = {
    extra.get(key).flatMap(_.headOption)
  }

  /** Stable, filesystem-safe filename derived from a root GitOID. */
  private def cbomFilename(rootIdentifier: String): String = {
    val safe = rootIdentifier.replace(":", "_").replace("/", "_")
    f"cbom_${safe}.json"
  }

  /** Validate the output directory, reject symlinks, and create it safely. */
  private def safeOutputDir(dir: File): Try[File] = Try {
    val path = pathOf(dir)

    @tailrec
    def checkSymlinks(p: Path | Null): Unit = {
      if (p != null) {
        if (Files.exists(p) && Files.isSymbolicLink(p)) {
          throw new IllegalArgumentException(
            f"CBOM output directory contains symlink: $p"
          )
        }
        checkSymlinks(p.getParent())
      }
    }
    checkSymlinks(path)

    if (!dir.exists()) {
      try {
        Files.createDirectories(
          path,
          PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString(OutputPermissions)
          )
        )
      } catch {
        case _: UnsupportedOperationException =>
          dir.mkdirs()
      }
    }

    if (!dir.isDirectory()) {
      throw new IllegalArgumentException(
        f"CBOM output path is not a directory: $dir"
      )
    }
    if (!dir.canWrite()) {
      throw new IllegalArgumentException(
        f"CBOM output directory not writable: $dir"
      )
    }
    dir
  }

  /** Write a CBOM file atomically with restrictive permissions. */
  private def atomicWrite(
      dir: File,
      filename: String,
      json: JObject
  ): Try[File] = {
    Try {
      val target = new File(dir, filename)
      val temp = File.createTempFile("cbom-", ".json.tmp", dir)
      try {
        val content = compact(render(json))
        Files.writeString(pathOf(temp), content, StandardCharsets.UTF_8)
        try {
          Files.setPosixFilePermissions(
            pathOf(temp),
            PosixFilePermissions.fromString(FilePermissions)
          )
        } catch {
          case _: UnsupportedOperationException => // non-POSIX filesystem
        }
        Files.move(
          pathOf(temp),
          pathOf(target),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
        try {
          Files.setPosixFilePermissions(
            pathOf(target),
            PosixFilePermissions.fromString(FilePermissions)
          )
        } catch {
          case _: UnsupportedOperationException => // non-POSIX filesystem
        }
      } catch {
        case e: Throwable =>
          temp.delete()
          throw e
      }
      target
    }
  }

  private def pathOf(f: File): Path = {
    java.nio.file.Path.of(f.getAbsolutePath())
  }
}
