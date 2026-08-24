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
  * transitively, collects Items that carry cryptographic metadata and maps them
  * to CycloneDX 1.6 or 1.7 `cryptographic-asset` components.
  *
  * No redaction happens here by design: the private-key hard constraint is
  * enforced at capture time (decoded key bytes are discarded and never enter
  * ADG metadata), so every ADG field that maps to a valid CBOM field is
  * included, including private-key marker flags such as
  * `Certificates:DerivedFromPrivateKey` / `SSH:MaterialType`.
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
      outDir: File,
      correlationId: String = ""
  ): Try[Seq[File]] = {
    val roots = findRoots(storage)
    emit(storage, roots, version, outDir, correlationId)
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
    * @param correlationId
    *   the run's correlation ID, recorded as a top-level property (empty when
    *   not part of a run)
    * @return
    *   the emitted files, or a failure
    */
  def emit(
      storage: Storage,
      roots: Seq[Item],
      version: String,
      outDir: File,
      correlationId: String = ""
  ): Try[Seq[File]] = {
    for {
      dir <- safeOutputDir(outDir)
      files <- roots.foldLeft(Try(Vector[File]())) { case (accTry, root) =>
        for {
          acc <- accTry
          (items, truncated) = collectCryptoItems(storage, root)
          doc = buildDocument(root, items, version, truncated, correlationId)
          filename = cbomFilename(root)
          file <- atomicWrite(dir, filename, doc)
        } yield acc :+ file
      }
    } yield files
  }

  /** Find all root Items in storage. */
  private def findRoots(storage: Storage): Vector[Item] = {
    storage.keys().toVector.flatMap(key => storage.read(key).filter(_.isRoot()))
  }

  /** Collect cryptographic Items reachable from a root via `contains` edges,
    * threading the full container chain (root → … → item) so each collected
    * Item can be addressed by its traversal hierarchy.
    *
    * Returns the collected Items (with their ancestor chain) and a flag
    * indicating whether the collection was truncated to
    * [[MaxComponentsPerRoot]].
    */
  private def collectCryptoItems(
      storage: Storage,
      root: Item
  ): (Vector[(Item, Vector[Item])], Boolean) = {
    val rootChain = Vector(root)
    val rootCollected: Vector[(Item, Vector[Item])] =
      if (isCryptoItem(root)) Vector((root, rootChain)) else Vector()

    @tailrec
    def loop(
        toVisit: Vector[(String, Vector[Item])],
        visited: Set[String],
        collected: Vector[(Item, Vector[Item])],
        truncated: Boolean
    ): (Vector[(Item, Vector[Item])], Boolean) = {
      if (toVisit.isEmpty) {
        (collected, truncated)
      } else if (collected.length >= MaxComponentsPerRoot) {
        (collected, true)
      } else {
        val (gitoid, parentChain) = toVisit.head
        val rest = toVisit.tail
        if (
          visited.contains(gitoid) ||
          parentChain.size >= MaxTraversalDepth
        ) {
          loop(rest, visited, collected, truncated)
        } else {
          storage.read(gitoid) match {
            case Some(item) =>
              val childChain = parentChain :+ item
              val nextCollected =
                if (isCryptoItem(item)) collected :+ (item, childChain)
                else collected
              val children =
                if (childChain.size < MaxTraversalDepth)
                  item.listContains().map(_ -> childChain)
                else Vector()
              loop(rest ++ children, visited + gitoid, nextCollected, truncated)
            case None =>
              loop(rest, visited + gitoid, collected, truncated)
          }
        }
      }
    }

    loop(
      root.listContains().map(_ -> rootChain),
      Set(root.identifier),
      rootCollected,
      false
    )
  }

  /** True when the Item carries cryptographic metadata from a known strategy.
    *
    * Includes the Phase A–G extended-capture prefixes (ServiceCrypto, Kerberos,
    * JWT, JWK, EmbeddedKey, CryptoAlgorithms, CryptoDependency, MobileTls) so
    * the emitter covers the same families as the captured ADG metadata can
    * express.
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
          k.startsWith("EmbeddedCertificates:") ||
          k.startsWith("ServiceCrypto:") ||
          k.startsWith("Kerberos:") ||
          k.startsWith("JWT:") ||
          k.startsWith("JWK:") ||
          k.startsWith("EmbeddedKey:") ||
          k.startsWith("CryptoAlgorithms:") ||
          k.startsWith("CryptoDependency:") ||
          k.startsWith("MobileTls:")
      )
    }
  }

  /** Build the CycloneDX document for a root and its collected crypto Items. */
  private def buildDocument(
      root: Item,
      items: Vector[(Item, Vector[Item])],
      version: String,
      truncated: Boolean,
      correlationId: String
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
      val all = items.flatMap { case (item, chain) =>
        buildComponentsForItem(item, chain)
      }
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

    val topProps: List[JObject] = List(
      if (truncated)
        Some(
          JObject(
            "name" -> JString("cbom:truncated"),
            "value" -> JString("true")
          )
        )
      else None,
      Option(correlationId)
        .filter(_.nonEmpty)
        .map(corr =>
          JObject(
            "name" -> JString("goatrodeo:correlation-id"),
            "value" -> JString(corr)
          )
        )
    ).flatten

    val fields =
      if (topProps.isEmpty) baseFields
      else baseFields :+ ("properties" -> JArray(topProps))

    JObject(fields)
  }

  /** Description of an algorithm asset emitted alongside a crypto component. */
  private case class AlgorithmSpec(
      name: String,
      primitive: String,
      parameter: Option[String] = None,
      curve: Option[String] = None
  )

  /** Heuristic primitive classification from an algorithm name. Delegates to
    * the shared registry (see `CryptoAlgorithms`).
    */
  private def inferPrimitive(alg: String): String =
    CryptoAlgorithms.inferPrimitive(alg)

  /** Canonicalize a signature-OID algorithm name to its gallery name. The ADG
    * captures unknown signature OIDs verbatim (`<unknown-sig-oid-…>`); these
    * are known to map to ML-DSA, so the emitter emits the canonical name.
    */
  private def canonicalSigName(name: String): String = {
    if (name.startsWith("<") && name.endsWith(">")) {
      name.drop(1).dropRight(1) match {
        case "unknown-sig-oid-2.16.840.1.101.3.4.3.40" => "ml-dsa-65"
        case "unknown-sig-oid-2.16.840.1.101.3.4.3.17" => "ml-dsa-44"
        case "unknown-sig-oid-2.16.840.1.101.3.4.3.18" => "ml-dsa-65"
        case "unknown-sig-oid-2.16.840.1.101.3.4.3.19" => "ml-dsa-87"
        case other                                     => other
      }
    } else name
  }

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
    * algorithm name when no explicit size or curve is available. Delegates to
    * the shared registry's parameter rule.
    */
  private def parameterFromName(name: String): Option[String] =
    CryptoAlgorithms.parameterFor(name)

  /** Build an `algorithm` cryptographic-asset component for an algorithm spec.
    */
  private def algorithmComponent(
      spec: AlgorithmSpec,
      chain: Vector[Item]
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
    val pathProp = JObject("properties" -> JArray(pathProps(chain)))
    val component = JObject(
      "type" -> JString("cryptographic-asset"),
      "bom-ref" -> JString(ref),
      "name" -> JString(spec.name),
      "cryptoProperties" -> JObject(
        "assetType" -> JString("algorithm"),
        "algorithmProperties" -> JObject(apFields)
      )
    ) ~ pathProp
    Some(ref -> component)
  }

  /** SWHID core identifier (`swh:1:cnt:<sha1>`) derived from the item's
    * `alias:from` `gitoid:blob:sha1:<hex>` edge, when present and well-formed.
    * The SWHID content identifier is the same sha1 bytes with a different
    * prefix, so no extra hashing is needed — the alias the Item already carries
    * is translated. Malformed aliases (non-hex, wrong length, uppercase) yield
    * `None` rather than a bogus identifier.
    */
  private def swhidFor(item: Item): Option[String] = {
    val prefix = "gitoid:blob:sha1:"
    val hex = item.connections
      .collect {
        case (EdgeType.aliasFrom, value) if value.startsWith(prefix) =>
          value.stripPrefix(prefix)
      }
      .find(h =>
        h.length == 40 && h.forall(c =>
          (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
        )
      )
    hex.map(h => s"swh:1:cnt:${h}")
  }

  /** Separator between container nodes in a traversal path. Deliberately not
    * `/`, which is used *within* a container's own logical path.
    */
  private val PathSeparator: String = "|:|"

  /** The name of an Item as it appears in a container path: its file name(s)
    * (the path within its parent), falling back to its gitoid identifier.
    */
  private def itemName(item: Item): String =
    item.bodyAsItemMetaData
      .flatMap(_.fileNames.headOption)
      .getOrElse(item.identifier)

  /** The three traversal-path properties for a chain of container Items (root →
    * … → item): the file path, the OmniBOR (`gitoid:blob:sha256`) path, and the
    * SWHID path — each joining its nodes with [[PathSeparator]].
    */
  private def pathProps(chain: Vector[Item]): List[JObject] = {
    val sep = PathSeparator
    List(
      JObject(
        "name" -> JString("goatrodeo:path"),
        "value" -> JString(chain.map(itemName).mkString(sep))
      ),
      JObject(
        "name" -> JString("goatrodeo:omnibor-path"),
        "value" -> JString(chain.map(_.identifier).mkString(sep))
      ),
      JObject(
        "name" -> JString("goatrodeo:swhid-path"),
        "value" -> JString(chain.flatMap(swhidFor).mkString(sep))
      )
    )
  }

  /** Map a single Item to a list of CycloneDX components: the main item plus
    * any algorithm assets referenced by it.
    */
  private def buildComponentsForItem(
      item: Item,
      chain: Vector[Item]
  ): List[JObject] = {
    val extra = metaExtra(item)

    // Lockfile crypto dependencies → `library` components (not
    // cryptographic-asset components keyed by the item gitoid).
    if (hasCryptoDependency(extra)) {
      return dependencyLibraryComponents(extra)
    }

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

    val extraProps = propertiesFromExtra(extra)
    // `swhid:core` (the SWHID content id, from the item's sha1 gitoid alias)
    // is always emitted together with `omnibor:core` (the item's own
    // `gitoid:blob:sha256` OmniBOR id): neither appears without the other.
    val coreProps: List[JObject] = swhidFor(item)
      .map { s =>
        List(
          JObject("name" -> JString("swhid:core"), "value" -> JString(s)),
          JObject(
            "name" -> JString("omnibor:core"),
            "value" -> JString(item.identifier)
          )
        )
      }
      .toList
      .flatten
    val allProps = extraProps.arr ++ coreProps ++ pathProps(chain)
    val withProps =
      if (allProps.isEmpty) JObject(baseFields)
      else JObject(baseFields :+ ("properties" -> JArray(allProps)))

    val (mainOpt, algs) = cryptoPropertiesFor(item, extra, chain)
    val mainComponent =
      mainOpt.map(cp => withProps ~ ("cryptoProperties" -> cp))
    mainComponent.toList ++ algs.values.toList
  }

  /** CycloneDX `library` components for a lockfile crypto dependency: one
    * component per `CryptoDependency:name`, with `crypto-family` properties and
    * a joined `algorithms` property. `bom-ref` is `dep-<name>` (schema-legal,
    * distinct from item gitoids).
    */
  private def dependencyLibraryComponents(
      extra: Map[String, Set[String]]
  ): List[JObject] = {
    val versions =
      extra.getOrElse("CryptoDependency:version", Set()).toList.sorted
    val families =
      extra.getOrElse("CryptoDependency:algorithms", Set()).toList.sorted
    val familyProps: List[JObject] = families.map(f =>
      JObject("name" -> JString("crypto-family"), "value" -> JString(f))
    )
    val algoProp: List[JObject] =
      if (families.isEmpty) Nil
      else
        List(
          JObject(
            "name" -> JString("algorithms"),
            "value" -> JString(families.mkString(","))
          )
        )
    val props = familyProps ++ algoProp
    extra
      .getOrElse("CryptoDependency:name", Set())
      .toList
      .sorted
      .filter(_.nonEmpty)
      .map { name =>
        val ref =
          "dep-" + name.toLowerCase.replace("/", "-").replace(" ", "-")
        val fields: List[JField] =
          List(
            Some("type" -> JString("library")),
            Some("bom-ref" -> JString(ref)),
            Some("name" -> JString(name)),
            versions.headOption.map(v => "version" -> JString(v))
          ).flatten ++
            (if (props.nonEmpty) List("properties" -> JArray(props)) else Nil)
        JObject(fields)
      }
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
        k.startsWith("EmbeddedCertificates:") ||
        k.startsWith("ServiceCrypto:") ||
        k.startsWith("Kerberos:") ||
        k.startsWith("JWT:") ||
        k.startsWith("JWK:") ||
        k.startsWith("EmbeddedKey:") ||
        k.startsWith("CryptoAlgorithms:") ||
        k.startsWith("CryptoDependency:") ||
        k.startsWith("MobileTls:")
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
      extra: Map[String, Set[String]],
      chain: Vector[Item]
  ): (Option[JObject], Map[String, JObject]) = {
    val algs = scala.collection.mutable.Map[String, JObject]()

    def addAlg(
        raw: String,
        context: String,
        size: Option[Int] = None,
        curve: Option[String] = None
    ): Option[String] = {
      val canonical = canonicalSigName(raw)
      val param = size.map(_.toString)
      val spec =
        AlgorithmSpec(canonical, primitiveFor(canonical, context), param, curve)
      algorithmComponent(spec, chain).map { case (ref, comp) =>
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
        .orElse(first(extra, "Certificates:Cert:0:KeySize"))
      top.flatMap(s => Try(s.toInt).toOption)
    }

    def curve(prefix: String): Option[String] = {
      first(extra, s"${prefix}Curve")
        .orElse(first(extra, "SSH:Curve"))
        .orElse(first(extra, "Certificates:Curve"))
        .orElse(first(extra, "Certificates:Cert:0:Curve"))
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
      // Keys detected by the certificates strategy appear as
      // `Certificates:Entry:<alias>:KeyAlgorithm[/KeySize/Curve]`. Only
      // entries that carry a certificate chain (`Chain:0:` metadata) are key
      // entries — trusted-cert entries also emit KeyAlgorithm via their
      // per-cert metadata and must not mint key assets.
      val keyAliases = extra.collect {
        case (k, _)
            if k.startsWith("Certificates:Entry:") &&
              k.contains(":Chain:0:") =>
          k.stripPrefix("Certificates:Entry:").takeWhile(_ != ':')
      }.toSet
      val entryKeys = extra
        .collect {
          case (k, vs)
              if k.startsWith("Certificates:Entry:") &&
                k.endsWith(":KeyAlgorithm") =>
            k.stripPrefix("Certificates:Entry:")
              .stripSuffix(":KeyAlgorithm") -> vs
        }
        .filter { case (alias, _) => keyAliases.contains(alias) }
        .toList
        .sortBy(_._1)
      val refs = entryKeys.flatMap { case (alias, algs) =>
        algs.toList.sorted.flatMap { raw =>
          val size = first(extra, s"Certificates:Entry:${alias}:KeySize")
            .flatMap(s => Try(s.toInt).toOption)
          val curve = first(extra, s"Certificates:Entry:${alias}:Curve")
          addAlg(raw, "pke", size, curve)
        }
      }
      (
        Some(
          buildRelatedCryptoMaterialProperties(extra, "key", refs.headOption)
        ),
        algs.toMap
      )
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
      // CycloneDX's relatedCryptoMaterialType enum has no
      // `private-key-placeholder`; map it to the closest legal value. The
      // placeholder distinction rides in the `SSH:MaterialType` property
      // (emitted verbatim by propertiesFromExtra).
      val emittedMaterialType =
        if (materialType == "private-key-placeholder") "private-key"
        else materialType
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
          buildRelatedCryptoMaterialProperties(
            extra,
            emittedMaterialType,
            keyRef
          )
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
    } else if (hasEmbeddedKey(extra)) {
      // Embedded (inline/base64) key discovery: material type reflects
      // `EmbeddedKey:kind`; the derived algorithm (SPKI-hash-only metadata)
      // links as a pke asset. No key bytes ever reach the CBOM.
      val materialType = first(extra, "EmbeddedKey:kind") match {
        case Some("private-key") => "private-key"
        case Some("public-key")  => "public-key"
        case _                   => "other"
      }
      val keySize = first(extra, "EmbeddedKey:key_size")
        .flatMap(s => Try(s.toInt).toOption)
      val keyRef =
        first(extra, "EmbeddedKey:key_algorithm").flatMap(
          addAlg(_, "pke", keySize, None)
        )
      (
        Some(buildRelatedCryptoMaterialProperties(extra, materialType, keyRef)),
        algs.toMap
      )
    } else if (hasServiceCrypto(extra)) {
      val refs = extra
        .getOrElse("ServiceCrypto:algorithms", Set())
        .toList
        .sorted
        .flatMap(addAlg(_, "other"))
      (
        Some(
          buildRelatedCryptoMaterialProperties(extra, "other", refs.headOption)
        ),
        algs.toMap
      )
    } else if (hasKerberos(extra)) {
      val refs = extra
        .getOrElse("Kerberos:algorithms", Set())
        .toList
        .sorted
        .flatMap(addAlg(_, "other"))
      (
        Some(
          buildRelatedCryptoMaterialProperties(extra, "other", refs.headOption)
        ),
        algs.toMap
      )
    } else if (hasJwt(extra)) {
      // JWT `alg` headers are attacker-controlled; the values are signature
      // algorithms, so they are emitted with the `signature` context rather
      // than free-text substring classification (red-team finding; §13 C4).
      val refs = extra
        .getOrElse("JWT:signature_algorithm", Set())
        .toList
        .sorted
        .filter(_ != "none")
        .flatMap(addAlg(_, "signature"))
      (
        Some(
          buildRelatedCryptoMaterialProperties(extra, "other", refs.headOption)
        ),
        algs.toMap
      )
    } else if (hasJwk(extra)) {
      // JWK inventory: `JWK:private_present` selects the material type
      // (private-key / public-key); it always surfaces as a property too.
      val privatePresent = extra
        .getOrElse("JWK:private_present", Set())
        .exists(_ == "true")
      val materialType =
        if (privatePresent) "private-key" else "public-key"
      val jwkSize = first(extra, "JWK:size")
        .flatMap(s => Try(s.toInt).toOption)
      val ktyRef = first(extra, "JWK:kty")
        .flatMap {
          case "RSA" => Some("rsa")
          case "EC"  => Some("ec")
          case _     => None
        }
        .flatMap(addAlg(_, "pke", jwkSize, None))
      (
        Some(
          buildRelatedCryptoMaterialProperties(extra, materialType, ktyRef)
        ),
        algs.toMap
      )
    } else if (hasCryptoAlgorithms(extra)) {
      // Binary footprint inventory → pure algorithm assets (deduped); no
      // material component is emitted.
      val algNames = extra.getOrElse("CryptoAlgorithms:algorithm", Set())
      if (algNames.nonEmpty) {
        algNames.toList.sorted.foreach(a => addAlg(a, "other"))
        (None, algs.toMap)
      } else {
        // Unknown-flagged footprint (e.g. mbedTLS symbols with no canonical
        // algorithm): the artifact is still crypto-bearing — surface it as
        // related-crypto-material instead of silently dropping it.
        (
          Some(buildRelatedCryptoMaterialProperties(extra, "other")),
          algs.toMap
        )
      }
    } else if (hasCryptoDependency(extra)) {
      // Lockfile crypto dependencies become `library` components (built in
      // `buildComponentsForItem`); no material / algorithm assets here.
      (None, algs.toMap)
    } else if (hasMobileTls(extra)) {
      val refs = extra
        .getOrElse("MobileTls:algorithms", Set())
        .toList
        .sorted
        .flatMap(addAlg(_, "other"))
      (
        Some(
          buildRelatedCryptoMaterialProperties(extra, "other", refs.headOption)
        ),
        algs.toMap
      )
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

  // Phase A–G extended-capture families (classification precedence:
  // EmbeddedKey, ServiceCrypto, Kerberos, JWT, JWK, CryptoAlgorithms,
  // CryptoDependency, MobileTls).
  private def hasEmbeddedKey(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("EmbeddedKey:"))
  }

  private def hasServiceCrypto(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("ServiceCrypto:"))
  }

  private def hasKerberos(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("Kerberos:"))
  }

  private def hasJwt(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("JWT:"))
  }

  private def hasJwk(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("JWK:"))
  }

  private def hasCryptoAlgorithms(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("CryptoAlgorithms:"))
  }

  private def hasCryptoDependency(
      extra: Map[String, Set[String]]
  ): Boolean = {
    extra.keys.exists(_.startsWith("CryptoDependency:"))
  }

  private def hasMobileTls(extra: Map[String, Set[String]]): Boolean = {
    extra.keys.exists(_.startsWith("MobileTls:"))
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
  /** Maximum length of the escaped file-name portion of a CBOM filename. */
  private val MaxCbomNameChars: Int = 80

  /** Deterministic CBOM filename for a root Item.
    *
    * Format: `cbom_<escaped-first-file-name>_<last-16-of-gitoid>.json`. The
    * full gitoid is inside the CBOM, so the filename only needs enough of it to
    * disambiguate (16 hex chars = 64 bits). The file name is the root's first
    * `fileNames` entry (a `TreeSet`, so deterministic sorted order), escaped by
    * replacing every character outside `[A-Za-z0-9_-]` with `_`; full paths are
    * truncated (keeping the tail) so filenames do not grow unboundedly.
    */
  private def cbomFilename(root: Item): String = {
    val gitoidHex = root.identifier.stripPrefix("gitoid:blob:sha256:")
    val short = gitoidHex.takeRight(16)
    val rawName = itemName(root)
    val escaped = rawName.map { c =>
      val ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
        (c >= '0' && c <= '9') || c == '-' || c == '_'
      if (ok) c else '_'
    }
    val name =
      if (escaped.length > MaxCbomNameChars) escaped.takeRight(MaxCbomNameChars)
      else escaped
    f"cbom_${name}_${short}.json"
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
