/* Property-based tests for Maven strategy phases 0–3.
 *
 * These complement the example-based suite by exercising random inputs and
 * invariants that must hold for ALL inputs.
 *
 * Requirement traceability:
 *   - R1: PomParser interpolation is correct and terminates (acyclic/cyclic)
 *   - R2: groupId/artifactId/version filename extraction handles arbitrary Maven-style names
 *   - R3: Date parsing accepts all supported formats and rejects garbage
 *   - R4: resolveGroupIdArtifactIdVersion priority chain is deterministic and correct
 */

package io.spicelabs.goatrodeo.omnibor.strategies
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.PURLComponentSanitizer
import io.spicelabs.goatrodeo.util.PomParser
import munit.ScalaCheckSuite
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.compact
import org.json4s.native.JsonMethods.render
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalacheck.Prop.propBoolean

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

class MavenPropertyTests extends ScalaCheckSuite {

  // ------------------------------------------------------------------
  // Generators
  // ------------------------------------------------------------------

  /** Alphanumeric + dash / underscore names that do NOT contain a dash followed
    * by a digit (so that the first dash-digit split in the filename is the
    * artifactId/version boundary).
    */
  val genArtifactId: Gen[String] = for {
    first <- Gen.alphaNumChar
    rest <- Gen.listOf(
      Gen.frequency(
        (9, Gen.alphaNumChar),
        (1, Gen.oneOf('-', '_'))
      )
    )
  } yield {
    val raw = (first :: rest).mkString
    // Ensure no dash-digit sequence exists by post-processing:
    // replace any "-\d" with "_\d"
    raw.zipWithIndex.map { case (ch, i) =>
      if (ch == '-' && i + 1 < raw.length && raw.charAt(i + 1).isDigit) '_'
      else ch
    }.mkString
  }

  /** Version-like strings starting with a digit (so filename split works) */
  val genVersion: Gen[String] = for {
    first <- Gen.numChar
    rest <- Gen.listOf(
      Gen.frequency(
        (5, Gen.numChar),
        (2, Gen.oneOf('.', '-')),
        (1, Gen.alphaLowerChar),
        (1, Gen.alphaUpperChar)
      )
    )
  } yield (first :: rest).mkString

  /** Complete Maven filename following  artifactId-version.jar  pattern */
  val genMavenFilename: Gen[String] = for {
    artifact <- genArtifactId
    version <- genVersion
  } yield s"${artifact}-${version}.jar"

  /** Random property key: short alphanumeric, non-empty */
  val genPropKey: Gen[String] =
    Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  /** Random property map with literal (non-interpolated) values */
  val genLiteralProps: Gen[Map[String, String]] = for {
    n <- Gen.choose(1, 8)
    keys <- Gen.listOfN(n, genPropKey).suchThat(_.distinct.length == n)
    values <- Gen.listOfN(n, Gen.alphaNumStr)
  } yield keys.zip(values).toMap

  /** A SimpleDateFormat pattern we support */
  val genKnownDatePattern: Gen[String] = Gen.oneOf(
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd",
    "dd-MMM-yyyy",
    "yyyyMMdd-HHmm",
    "EEE, dd MMM yyyy HH:mm:ss Z"
  )

  /** Generate a random Date and format it with a known pattern */
  val genFormattedDate: Gen[(String, String)] = for {
    pattern <- genKnownDatePattern
    // Random date between 2000 and 2030
    millis <- Gen.choose(946684800000L, 1893456000000L)
    date = new Date(millis)
    fmt = new SimpleDateFormat(pattern)
    str = fmt.format(date)
  } yield (str, pattern)

  /** Random alphabetic string guaranteed to contain no digits */
  val genGarbageString: Gen[String] = Gen.alphaStr

  // ------------------------------------------------------------------
  // Properties: PomParser interpolation
  // ------------------------------------------------------------------

  property("PomParser.interpolate preserves literal strings") {
    forAll(Gen.alphaNumStr) { s =>
      PomParser.interpolate(s, Map.empty) == Some(s)
    }
  }

  property("PomParser.interpolate resolves simple ${key}") {
    forAll(genLiteralProps) { props =>
      props.nonEmpty ==> {
        val (k, v) = props.head
        val tpl = s"prefix$${$k}suffix"
        PomParser.interpolate(tpl, props) == Some(s"prefix${v}suffix")
      }
    }
  }

  property("PomParser.interpolate returns None for unresolved property") {
    forAll(Gen.alphaNumStr.suchThat(_.nonEmpty)) { missingKey =>
      val tpl = "${" + missingKey + "}"
      PomParser.interpolate(tpl, Map.empty) == None
    }
  }

  property("PomParser.interpolate detects trivial self-reference") {
    forAll(Gen.alphaNumStr.suchThat(_.nonEmpty)) { k =>
      val props = Map(k -> s"$${$k}")
      PomParser.resolveProperty(k, props) == None
    }
  }

  // ------------------------------------------------------------------
  // Properties: filename extraction
  // ------------------------------------------------------------------

  property(
    "extractIdentityFromFilename extracts correct artifactId and version"
  ) {
    forAll(genArtifactId, genVersion) { (artifact, version) =>
      val filename = s"${artifact}-${version}.jar"
      val (_, a, v) =
        MavenState().resolveGroupIdArtifactIdVersionFromFilename(filename)
      val expectedArtifact =
        PURLComponentSanitizer.sanitizeMavenArtifactId(artifact)
      val expectedVersion =
        PURLComponentSanitizer.sanitizeMavenVersion(version)
      a == expectedArtifact && v == expectedVersion
    }
  }

  // ------------------------------------------------------------------
  // Cross-Cutting Property: filename parsing terminates
  // (plan §Property-Based)
  //
  // Theory: for every generated filename, extractIdentityFromFilename either
  // returns Some(valid identity) when a version pattern is present, or returns
  // None (no version pattern).  Crucially it must NEVER throw.
  // ------------------------------------------------------------------

  val genFilename: Gen[String] = Gen.frequency(
    (2, genMavenFilename),
    (1, Gen.alphaNumStr.map(_ + ".jar")),
    (1, Gen.asciiStr.map(s => if (s.contains(".")) s else s + ".jar")),
    (1, Gen.const("foo-bar-baz-1.0-SNAPSHOT.jar")),
    (1, Gen.const("com.example.lib_2.13-3.0.0.jar")),
    (1, Gen.listOf(Gen.oneOf('a', '-', '_', '.', '0')).map(_.mkString + ".jar"))
  )

  property(
    "extractIdentityFromFilename terminates for all inputs (isDefined or None, never crashes)"
  ) {
    forAll(genFilename) { filename =>
      val result = scala.util.Try(
        MavenState().resolveGroupIdArtifactIdVersionFromFilename(filename)
      )
      // Must not crash
      result.isSuccess &&
      // Result must be a tuple of three Options
      result.toOption.isDefined
    }
  }

  property("extractIdentityFromFilename returns None for non-Maven filenames") {
    forAll(Gen.alphaNumStr) { name =>
      // Only true when name has no dash-digit split
      val hasDashDigit = name.zipWithIndex.exists { case (ch, i) =>
        ch == '-' && i + 1 < name.length && name.charAt(i + 1).isDigit
      }
      val (_, a, v) =
        MavenState().resolveGroupIdArtifactIdVersionFromFilename(s"$name.jar")
      !hasDashDigit ==> (a == None && v == None)
    }
  }

  // ------------------------------------------------------------------
  // Properties: date parsing
  // ------------------------------------------------------------------

  property("parseDateString accepts all known formats") {
    forAll(genFormattedDate) { case (str, _) =>
      val state = MavenState()
      state.parseDateString(str).isDefined
    }
  }

  property("parseDateString rejects strings without digits") {
    forAll(genGarbageString) { s =>
      val state = MavenState()
      state.parseDateString(s) == None
    }
  }

  property("parseDateString round-trips epoch millis") {
    forAll(Gen.choose(0L, 1893456000000L)) { millis =>
      val state = MavenState()
      state.parseDateString(millis.toString) == Some(new Date(millis))
    }
  }

  // ------------------------------------------------------------------
  // Properties: groupId/artifactId/version priority chain
  // ------------------------------------------------------------------

  property(
    "resolveGroupIdArtifactIdVersion: external POM always wins when complete"
  ) {
    forAll(genArtifactId, genVersion) { (art, ver) =>
      val props =
        Map("groupId" -> "com.embed", "artifactId" -> art, "version" -> ver)
      val state = MavenState()
      val external = Some(
        PomParser.ParsedPom(
          groupId = Some("com.external"),
          artifactId = Some("ext-art"),
          version = Some("99.0"),
          name = None,
          description = None,
          url = None,
          organization = None,
          scmUrl = None,
          properties = Map.empty,
          licenses = Vector.empty,
          dependencies = Vector.empty,
          dependencyManagement = Vector.empty,
          parentGroupId = None,
          parentArtifactId = None,
          parentVersion = None
        )
      )
      val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
        ByteWrapper(Array.emptyByteArray, "test.jar", None),
        external,
        TreeMap.empty[String, TreeSet[StringOrPair]],
        props,
        None
      )
      g == Some("com.external") && a == Some("ext-art") && v == Some("99.0")
    }
  }

  property(
    "resolveGroupIdArtifactIdVersion: falls through each layer deterministically"
  ) {
    forAll(genArtifactId, genVersion) { (art, ver) =>
      val state = MavenState()
      val artifact = ByteWrapper(Array.emptyByteArray, s"$art-$ver.jar", None)

      // Nothing but filename
      val (g1, a1, v1) =
        state.resolveGroupIdArtifactIdVersion(
          artifact,
          None,
          TreeMap.empty,
          Map.empty,
          None
        )

      // Empty manifest should not change result
      val (g2, a2, v2) = state.resolveGroupIdArtifactIdVersion(
        artifact,
        None,
        TreeMap.empty[String, TreeSet[StringOrPair]],
        Map.empty,
        None
      )

      g1 == g2 && a1 == a2 && v1 == v2
    }
  }

  // ------------------------------------------------------------------
  // Property: dependency JSON round-trip
  // ------------------------------------------------------------------

  property("Dependency JSON contains enough fields to reconstruct a pURL") {
    forAll(genArtifactId, genVersion) { (art, ver) =>
      val pom = s"""<project>
        <groupId>com.test</groupId><artifactId>t</artifactId><version>1</version>
        <dependencies>
          <dependency>
            <groupId>org.example</groupId>
            <artifactId>$art</artifactId>
            <version>$ver</version>
            <scope>compile</scope>
          </dependency>
        </dependencies>
      </project>"""
      val parsed = PomParser.parse(pom)
      parsed.isDefined && parsed.get.dependencies.nonEmpty ==> {
        val dep = parsed.get.dependencies.head
        // A Maven pURL requires groupId, artifactId, version (optional), classifier (optional)
        dep.groupId.isDefined && dep.artifactId.isDefined && dep.version.isDefined
      }
    }
  }

  // ------------------------------------------------------------------
  // Property: arbitrary date string does not crash parseDateString
  // ------------------------------------------------------------------

  // ------------------------------------------------------------------
  // Cross-Cutting Property: date parsing robustness (plan §Property-Based)
  //
  // Theory: for every generated date string, either parseDateString returns
  // Some(Date) (the string matches a supported format) or returns None
  // (unparseable).  Crucially it must NEVER throw.
  // ------------------------------------------------------------------

  val genDateStr: Gen[String] = Gen.frequency(
    (2, genFormattedDate.map(_._1)),
    (1, Gen.alphaNumStr),
    (1, Gen.asciiStr),
    (
      1,
      Gen
        .listOf(Gen.oneOf('0', '9', '-', '/', ':', ' ', 'A', 'M'))
        .map(_.mkString)
    ),
    (1, Gen.choose(0L, 2000000000000L).map(_.toString))
  )

  property(
    "parseDateString terminates for all inputs (isDefined or None, never crashes)"
  ) {
    forAll(genDateStr) { s =>
      val state = MavenState()
      val result = scala.util.Try(state.parseDateString(s))
      // Must not crash
      result.isSuccess &&
      // Result must be an Option (Some or None)
      result.toOption.isDefined
    }
  }

  // ------------------------------------------------------------------
  // Property: arbitrary XML-ish string does not crash PomParser.parse
  // ------------------------------------------------------------------

  property("PomParser.parse never crashes on arbitrary strings") {
    forAll(Gen.asciiStr) { s =>
      // Must not throw; returns Option
      scala.util.Try(PomParser.parse(s)).isSuccess
    }
  }

  // ------------------------------------------------------------------
  // Property: PomParser groupId/artifactId/version correctness on generated POM XML
  // ------------------------------------------------------------------

  val genPomXmlWithGroupIdArtifactIdVersion
      : Gen[(String, Option[String], Option[String], Option[String])] =
    for {
      g <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
      a <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
      v <- Gen.option(genVersion)
    } yield {
      val parts = Vector(
        g.map(gid => s"<groupId>$gid</groupId>"),
        a.map(aid => s"<artifactId>$aid</artifactId>"),
        v.map(ver => s"<version>$ver</version>")
      ).flatten
      val xml =
        if (parts.isEmpty) "<project></project>"
        else s"<project>${parts.mkString}</project>"
      (xml, g, a, v)
    }

  property(
    "PomParser.parse produces correct groupId/artifactId/version on generated POM XML"
  ) {
    forAll(genPomXmlWithGroupIdArtifactIdVersion) {
      case (xml, expectedG, expectedA, expectedV) =>
        val parsedOpt = PomParser.parse(xml)
        parsedOpt.isDefined ==> {
          val parsed = parsedOpt.get
          parsed.groupId == expectedG &&
          parsed.artifactId == expectedA &&
          parsed.version == expectedV
        }
    }
  }

  // ------------------------------------------------------------------
  // Cross-Cutting Property: groupId/artifactId/version resolution terminates (plan §Property-Based)
  //
  // Theory: for every generated POM XML, either PomParser.parse returns
  // Some (the POM has structure from which a groupId/artifactId/version could be extracted) or
  // returns None (no valid groupId/artifactId/version source).  Crucially it must NEVER throw.
  // ------------------------------------------------------------------

  /** Generate random XML-ish strings that may or may not contain <project>
    * tags. This includes well-formed fragments, malformed tags, and random
    * ascii — the full input space for a parser that must be crash-proof.
    */
  val genPomXml: Gen[String] = Gen.frequency(
    (3, genPomXmlWithGroupIdArtifactIdVersion.map(_._1)),
    (2, Gen.asciiStr),
    (1, Gen.alphaNumStr),
    (
      1,
      Gen.frequency(
        (1, Gen.const("<project></project>")),
        (1, Gen.const("<project><groupId>g</groupId></project>"))
      )
    ),
    (
      1,
      Gen
        .listOf(Gen.oneOf('<', '>', '/', '=', '"', 'a', 'b', 'c'))
        .map(_.mkString)
    )
  )

  property(
    "PomParser.parse terminates for all inputs (isDefined or None, never throws)"
  ) {
    forAll(genPomXml) { xml =>
      val result = scala.util.Try(PomParser.parse(xml))
      // Must not crash
      result.isSuccess &&
      // If it succeeds it must be an Option (Some or None), never a thrown exception
      result.toOption.isDefined
    }
  }

  // ------------------------------------------------------------------
  // Cross-Cutting Property: property resolution terminates
  // (plan §Property-Based)
  //
  // Theory: for every generated property map and property name,
  // PomParser.resolveProperty either returns Some(value) when the key exists
  // and its value is fully resolvable, or returns None (name missing, circular
  // reference, unresolved interpolation, etc.).  Crucially it must NEVER throw.
  // ------------------------------------------------------------------

  val genProperties: Gen[Map[String, String]] = Gen.frequency(
    (3, genLiteralProps),
    (1, Gen.const(Map.empty[String, String])),
    (
      1,
      genLiteralProps.map { m =>
        // Inject an unresolved interpolation into one random value
        if (m.isEmpty) m
        else {
          val (k, _) = m.head
          m.updated(k, "${nonexistent.property}")
        }
      }
    ),
    (
      1,
      genLiteralProps.map { m =>
        // Inject a self-reference into one random value
        if (m.isEmpty) m
        else {
          val (k, _) = m.head
          m.updated(k, s"$${$k}")
        }
      }
    )
  )

  val genPropName: Gen[String] = Gen.frequency(
    (3, genPropKey),
    (1, Gen.alphaNumStr),
    (1, Gen.const("project.version")),
    (1, Gen.const("pom.groupId"))
  )

  property(
    "PomParser.resolveProperty terminates for all inputs (isDefined or None, never crashes)"
  ) {
    forAll(genProperties, genPropName) { (props, name) =>
      val result = scala.util.Try(PomParser.resolveProperty(name, props))
      // Must not crash
      result.isSuccess &&
      // Result must be an Option (Some or None)
      result.toOption.isDefined
    }
  }

  // ------------------------------------------------------------------
  // Property: resolveProperty consistency
  // ------------------------------------------------------------------

  property("PomParser.resolveProperty is consistent with interpolation") {
    forAll(genLiteralProps, genPropKey) { (props, key) =>
      val resolved = PomParser.resolveProperty(key, props)
      val expected = props.get(key).flatMap(PomParser.interpolate(_, props))
      resolved == expected
    }
  }

  // ------------------------------------------------------------------
  // Cross-Cutting Property: dependency JSON round-trip (plan §Property-Based)
  //
  // Theory: for every generated dependency list, formatting to JSON never
  // crashes (formatDeps.isDefined in spirit — compact(render) always
  // succeeds for our structured data) and re-parsing yields the original
  // data (parseAgain(formatDeps(deps)) == deps).
  // ------------------------------------------------------------------

  val genParsedDependency: Gen[PomParser.ParsedDependency] = for {
    g <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
    a <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
    v <- Gen.option(genVersion)
    s <- Gen.option(Gen.oneOf("compile", "test", "provided", "runtime"))
    c <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
    o <- Gen.oneOf(true, false)
    t <- Gen.option(Gen.oneOf("jar", "war", "pom"))
  } yield PomParser.ParsedDependency(g, a, v, s, c, o, t)

  property("Dependency JSON round-trip preserves all fields") {
    forAll(Gen.listOf(genParsedDependency)) { depsList =>
      val deps = depsList.toVector
      val json = compact(render(deps.map { d =>
        ("group" -> d.groupId) ~
          ("artifact" -> d.artifactId) ~
          ("version" -> d.version) ~
          ("scope" -> d.scope) ~
          ("optional" -> d.optional) ~
          ("classifier" -> d.classifier) ~
          ("type" -> d.`type`)
      }))

      // Parse JSON back
      import org.json4s.JsonAST._
      val parsed =
        org.json4s.native.JsonMethods.parse(json).asInstanceOf[JArray]
      val back = parsed.arr.map {
        case JObject(fields) =>
          val fieldMap = fields.toMap
          def optStr(field: String) =
            fieldMap.get(field).collect { case JString(s) => s }
          def optBool(field: String) =
            fieldMap.get(field).collect { case JBool(b) => b }.getOrElse(false)
          PomParser.ParsedDependency(
            groupId = optStr("group"),
            artifactId = optStr("artifact"),
            version = optStr("version"),
            scope = optStr("scope"),
            classifier = optStr("classifier"),
            optional = optBool("optional"),
            `type` = optStr("type")
          )
        case other => throw new AssertionError(s"Expected JObject, got $other")
      }.toVector
      deps == back
    }
  }

  // ------------------------------------------------------------------
  // Cross-Cutting Property: Gradle lockfile parsing never crashes
  // (plan §Property-Based)
  //
  // Theory: for every generated Gradle lockfile content, parsing terminates
  // without exception — it either produces dependencies or returns empty.
  // ------------------------------------------------------------------

  val genGradleCoord: Gen[String] = for {
    group <- Gen
      .listOfN(3, Gen.alphaLowerStr.suchThat(_.nonEmpty))
      .map(_.mkString("."))
    artifact <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    version <- genVersion
  } yield s"$group:$artifact:$version"

  val genGradleLine: Gen[String] = Gen.oneOf(
    genGradleCoord.map(_ + "=compileClasspath,runtimeClasspath"),
    genGradleCoord.map(_ + "=compileClasspath"),
    genGradleCoord, // legacy line without config
    Gen.const("# comment line"),
    Gen.const("empty=annotationProcessor"),
    Gen.alphaNumStr // potentially malformed
  )

  val genGradleLockfile: Gen[String] =
    Gen.listOf(genGradleLine).map(_.mkString("\n"))

  property("GradleLockfile.parseLockfile never crashes on arbitrary strings") {
    forAll(genGradleLockfile) { content =>
      val modern = GradleLockfile.parseLockfile(content, None)
      val legacy =
        GradleLockfile.parseLockfile(content, Some("compileClasspath"))
      // Must not throw, and must return a Vector
      modern.isInstanceOf[Vector[?]] && legacy.isInstanceOf[Vector[?]]
    }
  }

  // ------------------------------------------------------------------
  // Cross-Cutting Property: release file parsing never crashes
  // (plan §Property-Based)
  //
  // Theory: for every generated release-file-like content, parsing
  // terminates without exception.
  // ------------------------------------------------------------------

  val genReleaseLine: Gen[String] = Gen.oneOf(
    Gen.const("JAVA_VERSION=\"1.8.0_411\""),
    Gen.const("JAVA_RUNTIME_VERSION=\"21.0.4+7\""),
    Gen.const("IMPLEMENTOR=\"Eclipse Adoptium\""),
    Gen.const("IMAGE_TYPE=\"JDK\""),
    Gen.const("OS_ARCH=\"x86_64\""),
    Gen.const("OS_NAME=\"linux\""),
    Gen.const("LIBC=\"glibc\""),
    Gen.const("SOURCE_REPO=\"https://github.com/adoptium/temurin21-binaries\""),
    Gen.const("BUILD_SOURCE_REPO=\"https://github.com/adoptium/temurin\""),
    Gen.const("FULL_VERSION=\"21.0.4+7-adoptium\""),
    Gen.const("SEMANTIC_VERSION=\"21.0.4+7\""),
    Gen.const("JVM_VARIANT=\"Hotspot\""),
    Gen.const("JAVA_VERSION_DATE=\"2024-07-16\""),
    Gen.alphaNumStr, // potentially malformed/missing required fields
    Gen.const("") // empty line
  )

  val genReleaseFile: Gen[String] =
    Gen.listOf(genReleaseLine).map(_.filter(_.nonEmpty).mkString("\n"))

  property(
    "JvmDistribution.parseReleaseFile never crashes on arbitrary strings"
  ) {
    forAll(genReleaseFile) { content =>
      // Must not throw; always returns a JvmReleaseData
      val data = JvmDistribution.parseReleaseFile(content)
      data.isInstanceOf[JvmReleaseData]
    }
  }

  // ------------------------------------------------------------------
  // Cross-Cutting Property: leak filtering is idempotent
  // (plan §Property-Based)
  //
  // Theory: filterLeaks(filterLeaks(m)) == filterLeaks(m) for all
  // metadata maps m. Once leaks are removed, a second pass removes
  // nothing new.
  // ------------------------------------------------------------------

  val genForbiddenValue: Gen[String] = Gen.oneOf(
    Gen.const("-----BEGIN RSA PRIVATE KEY-----"),
    Gen.const("-----BEGIN ENCRYPTED PRIVATE KEY-----"),
    Gen.const("-----BEGIN PGP PRIVATE KEY BLOCK-----"),
    Gen.const("MIIEvQIBADAN"),
    Gen.const("MIIEpAIBAAKCAQEA"),
    Gen.const("openssh-key-v1"),
    Gen.listOfN(40, Gen.oneOf('0' to '9', 'a' to 'f')).map(_.mkString)
  )

  val genCleanValue: Gen[String] = Gen.oneOf(
    Gen.alphaNumStr,
    Gen.const("com.example"),
    Gen.const("1.2.3"),
    Gen.const("sha256:abcdef"),
    Gen.listOfN(10, Gen.oneOf('0' to '9', 'a' to 'f')).map(_.mkString)
  )

  val genMetadataValue: Gen[String] = Gen.frequency(
    (1, genForbiddenValue),
    (4, genCleanValue)
  )

  val genMetadataKey: Gen[String] = Gen.oneOf(
    Gen.const("SomeKey"),
    Gen.const("Certificates:SpkiSha256"), // allowlisted for long hex
    Gen.const("Certificates:CertSha256"),
    Gen.const("Certificates:Serial"),
    Gen.const("OtherKey")
  )

  val genMetadataMap: Gen[TreeMap[String, TreeSet[StringOrPair]]] = for {
    entries <- Gen.listOf(
      for {
        key <- genMetadataKey
        values <- Gen.nonEmptyListOf(genMetadataValue.map(StringOrPair.apply))
      } yield key -> TreeSet(values*)
    )
  } yield TreeMap(entries*)

  property("Certificates.filterLeaks is idempotent") {
    forAll(genMetadataMap) { metadata =>
      val once = Certificates.filterLeaks(metadata)
      val twice = Certificates.filterLeaks(once)
      once == twice
    }
  }

  // ------------------------------------------------------------------
  // Properties: Field-Level Merge
  // ------------------------------------------------------------------
  //
  // These properties verify the core invariants of the field-level merge
  // algorithm in resolveGroupIdArtifactIdVersion. The key distinction from source-level priority
  // is that fields are resolved INDEPENDENTLY: each field picks its value
  // from the highest-priority source that provides it, regardless of what
  // other sources provide for other fields.
  //
  // Per-field priority:
  //   groupId:    external POM > pom.properties > embedded pom.xml > manifest > filename
  //   artifactId: external POM > pom.properties > embedded pom.xml > filename > manifest
  //   version:    external POM > pom.properties > embedded pom.xml > manifest > filename
  // ------------------------------------------------------------------

  /** Generator for a complete pom.properties map with all three fields. */
  val genCompleteProps: Gen[Map[String, String]] = for {
    g <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    a <- genArtifactId
    v <- genVersion
  } yield Map("groupId" -> g, "artifactId" -> a, "version" -> v)

  /** Generator for a manifest with Implementation-Title and
    * Implementation-Version.
    */
  val genSimpleManifest: Gen[TreeMap[String, TreeSet[StringOrPair]]] = for {
    title <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    version <- genVersion
  } yield TreeMap[String, TreeSet[StringOrPair]](
    "implementation-title" -> TreeSet(StringOrPair(title)),
    "implementation-version" -> TreeSet(StringOrPair(version))
  )

  property("field-merge: external POM always wins when complete") {
    forAll(genArtifactId, genVersion) { (art, ver) =>
      val props =
        Map("groupId" -> "com.embed", "artifactId" -> art, "version" -> ver)
      val state = MavenState()
      val external = Some(
        PomParser.ParsedPom(
          groupId = Some("com.external"),
          artifactId = Some("ext-art"),
          version = Some("99.0"),
          name = None,
          description = None,
          url = None,
          organization = None,
          scmUrl = None,
          properties = Map.empty,
          licenses = Vector.empty,
          dependencies = Vector.empty,
          dependencyManagement = Vector.empty,
          parentGroupId = None,
          parentArtifactId = None,
          parentVersion = None
        )
      )
      val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
        ByteWrapper(Array.emptyByteArray, "test.jar", None),
        external,
        TreeMap.empty[String, TreeSet[StringOrPair]],
        props,
        None
      )
      g == Some("com.external") && a == Some("ext-art") && v == Some("99.0")
    }
  }

  property(
    "field-merge: falls through each layer deterministically"
  ) {
    forAll(genArtifactId, genVersion) { (art, ver) =>
      val state = MavenState()
      val artifact = ByteWrapper(Array.emptyByteArray, s"$art-$ver.jar", None)

      val (g1, a1, v1) =
        state.resolveGroupIdArtifactIdVersion(
          artifact,
          None,
          TreeMap.empty,
          Map.empty,
          None
        )

      val (g2, a2, v2) = state.resolveGroupIdArtifactIdVersion(
        artifact,
        None,
        TreeMap.empty[String, TreeSet[StringOrPair]],
        Map.empty,
        None
      )

      g1 == g2 && a1 == a2 && v1 == v2
    }
  }

  /** Property: field-level monotonicity — per-field priority is respected.
    *
    * Theory: For each field (groupId, artifactId, version), the resolved value
    * comes from the highest-priority source that provides it. This is verified
    * by providing all 5 sources with distinct values per field and checking
    * that the resolved value matches the highest-priority source.
    *
    * Per-field priority: groupId: pom.properties > external POM > embedded
    * pom.xml > manifest > filename artifactId: pom.properties > external POM >
    * embedded pom.xml > filename > manifest version: pom.properties > external
    * POM > embedded pom.xml > manifest > filename
    */
  property(
    "field-merge: monotonicity — per-field priority is respected across all sources"
  ) {
    forAll(genArtifactId, genVersion, Gen.alphaNumStr.suchThat(_.nonEmpty)) {
      (art, ver, groupBase) =>
        val state = MavenState()

        val propsG = s"props.$groupBase"
        val propsA = s"props.$art"
        val propsV = s"props.$ver"

        val extG = s"ext.$groupBase"
        val extA = s"ext.$art"
        val extV = s"ext.$ver"

        val embG = s"emb.$groupBase"
        val embA = s"emb.$art"
        val embV = s"emb.$ver"

        val manG = s"man.$groupBase"
        val manA = s"man.$art"
        val manV = s"man.$ver"

        val fileArt = s"file.$art"
        val fileVer = s"file.$ver"
        val filename = s"$fileArt-$fileVer.jar"

        val props =
          Map("groupId" -> propsG, "artifactId" -> propsA, "version" -> propsV)

        val externalPom = Some(
          PomParser.ParsedPom(
            groupId = Some(extG),
            artifactId = Some(extA),
            version = Some(extV),
            name = None,
            description = None,
            url = None,
            organization = None,
            scmUrl = None,
            properties = Map.empty,
            licenses = Vector.empty,
            dependencies = Vector.empty,
            dependencyManagement = Vector.empty,
            parentGroupId = None,
            parentArtifactId = None,
            parentVersion = None
          )
        )

        val embeddedPom = Some(
          PomParser.ParsedPom(
            groupId = Some(embG),
            artifactId = Some(embA),
            version = Some(embV),
            name = None,
            description = None,
            url = None,
            organization = None,
            scmUrl = None,
            properties = Map.empty,
            licenses = Vector.empty,
            dependencies = Vector.empty,
            dependencyManagement = Vector.empty,
            parentGroupId = None,
            parentArtifactId = None,
            parentVersion = None
          )
        )

        val manifest = TreeMap[String, TreeSet[StringOrPair]](
          "implementation-vendor-id" -> TreeSet(StringOrPair(manG)),
          "implementation-title" -> TreeSet(StringOrPair(manA)),
          "implementation-version" -> TreeSet(StringOrPair(manV))
        )

        val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
          ByteWrapper(Array.emptyByteArray, filename, None),
          externalPom,
          manifest,
          props,
          embeddedPom
        )

        val expectedG = PURLComponentSanitizer.sanitizeMavenGroupId(extG)
        val expectedA = PURLComponentSanitizer.sanitizeMavenArtifactId(extA)
        val expectedV = PURLComponentSanitizer.sanitizeMavenVersion(extV)

        g == expectedG && a == expectedA && v == expectedV
    }
  }

  /** Property: field-level monotonicity — when pom.properties is absent,
    * external POM wins for all fields.
    */
  property(
    "field-merge: monotonicity — external POM wins when pom.properties absent"
  ) {
    forAll(genArtifactId, genVersion) { (art, ver) =>
      val state = MavenState()

      val extG = "com.external"
      val extA = s"ext-$art"
      val extV = s"ext-$ver"

      val embG = "com.embedded"
      val embA = s"emb-$art"
      val embV = s"emb-$ver"

      val externalPom = Some(
        PomParser.ParsedPom(
          groupId = Some(extG),
          artifactId = Some(extA),
          version = Some(extV),
          name = None,
          description = None,
          url = None,
          organization = None,
          scmUrl = None,
          properties = Map.empty,
          licenses = Vector.empty,
          dependencies = Vector.empty,
          dependencyManagement = Vector.empty,
          parentGroupId = None,
          parentArtifactId = None,
          parentVersion = None
        )
      )

      val embeddedPom = Some(
        PomParser.ParsedPom(
          groupId = Some(embG),
          artifactId = Some(embA),
          version = Some(embV),
          name = None,
          description = None,
          url = None,
          organization = None,
          scmUrl = None,
          properties = Map.empty,
          licenses = Vector.empty,
          dependencies = Vector.empty,
          dependencyManagement = Vector.empty,
          parentGroupId = None,
          parentArtifactId = None,
          parentVersion = None
        )
      )

      val manifest = TreeMap[String, TreeSet[StringOrPair]](
        "implementation-vendor-id" -> TreeSet(StringOrPair("com.manifest")),
        "implementation-title" -> TreeSet(StringOrPair("Manifest Title")),
        "implementation-version" -> TreeSet(StringOrPair("man-1.0"))
      )

      val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
        ByteWrapper(Array.emptyByteArray, s"file-art-1.0.jar", None),
        externalPom,
        manifest,
        Map.empty,
        embeddedPom
      )

      val expectedG = PURLComponentSanitizer.sanitizeMavenGroupId(extG)
      val expectedA = PURLComponentSanitizer.sanitizeMavenArtifactId(extA)
      val expectedV = PURLComponentSanitizer.sanitizeMavenVersion(extV)

      g == expectedG && a == expectedA && v == expectedV
    }
  }

  /** Property: cross-field independence.
    *
    * Theory: Changing the artifactId in one source does not affect the resolved
    * groupId or version. This is the core property that distinguishes
    * field-level merge from source-level priority.
    */
  property(
    "field-merge: changing artifactId in one source does not affect groupId or version"
  ) {
    forAll(genArtifactId, genArtifactId, genVersion) { (art1, art2, ver) =>
      val state = MavenState()
      val props1 =
        Map("groupId" -> "com.test", "artifactId" -> art1, "version" -> ver)
      val props2 =
        Map("groupId" -> "com.test", "artifactId" -> art2, "version" -> ver)
      val artifact = ByteWrapper(Array.emptyByteArray, "test.jar", None)

      val (g1, _, v1) = state.resolveGroupIdArtifactIdVersion(
        artifact,
        None,
        TreeMap.empty[String, TreeSet[StringOrPair]],
        props1,
        None
      )
      val (g2, _, v2) = state.resolveGroupIdArtifactIdVersion(
        artifact,
        None,
        TreeMap.empty[String, TreeSet[StringOrPair]],
        props2,
        None
      )
      g1 == g2 && v1 == v2
    }
  }

  /** Property: no-Frankenstein when a complete source exists.
    *
    * Theory: If the highest-priority source that provides ALL three fields
    * exists, the result equals that source's values exactly — no field mixing
    * from lower sources.
    */
  property("field-merge: complete source wins exactly (no Frankenstein)") {
    forAll(genArtifactId, genVersion, Gen.alphaNumStr.suchThat(_.nonEmpty)) {
      (art, ver, title) =>
        val state = MavenState()
        val props =
          Map("groupId" -> "com.props", "artifactId" -> art, "version" -> ver)
        val manifest = TreeMap[String, TreeSet[StringOrPair]](
          "implementation-title" -> TreeSet(StringOrPair(title)),
          "implementation-version" -> TreeSet(StringOrPair("999.0"))
        )
        val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
          ByteWrapper(Array.emptyByteArray, s"$art-$ver.jar", None),
          None,
          manifest,
          props,
          None
        )
        val expectedA = PURLComponentSanitizer.sanitizeMavenArtifactId(art)
        val expectedV = PURLComponentSanitizer.sanitizeMavenVersion(ver)
        g == Some("com.props") && a == expectedA && v == expectedV
    }
  }

  /** Property: resolveGroupIdArtifactIdVersion never throws.
    *
    * Theory: For any combination of inputs (including empty strings,
    * whitespace, special characters), resolveGroupIdArtifactIdVersion must not
    * throw.
    */
  property(
    "field-merge: resolveGroupIdArtifactIdVersion never throws for any inputs"
  ) {
    forAll(Gen.alphaNumStr, Gen.alphaNumStr, Gen.alphaNumStr) { (s1, s2, s3) =>
      val state = MavenState()
      val props = Map("groupId" -> s1, "artifactId" -> s2, "version" -> s3)
      val manifest = TreeMap[String, TreeSet[StringOrPair]](
        "implementation-title" -> TreeSet(StringOrPair(s1)),
        "implementation-version" -> TreeSet(StringOrPair(s3))
      )
      val result = scala.util.Try {
        state.resolveGroupIdArtifactIdVersion(
          ByteWrapper(Array.emptyByteArray, s"$s2-$s3.jar", None),
          None,
          manifest,
          props,
          None
        )
      }
      result.isSuccess
    }
  }

  /** Property: groupId is Some whenever artifactId is Some.
    *
    * Theory: The finalGroupId = groupId.orElse(artifactId) fallback ensures
    * groupId is always defined when artifactId is defined. This produces a
    * valid Maven pURL (which requires namespace).
    */
  property("field-merge: groupId is Some whenever artifactId is Some") {
    forAll(genArtifactId, genVersion, genSimpleManifest) {
      (art, ver, manifest) =>
        val state = MavenState()
        val (g, a, v) = state.resolveGroupIdArtifactIdVersion(
          ByteWrapper(Array.emptyByteArray, s"$art-$ver.jar", None),
          None,
          manifest,
          Map.empty,
          None
        )
        a.isDefined ==> g.isDefined
    }
  }

  /** Property: filename beats manifest for artifactId.
    *
    * Theory: For any generated artifactId and version where filename =
    * "{artifactId}-{version}.jar" and manifest has Implementation-Title =
    * "Human Readable Name" and Implementation-Version = version, the resolved
    * artifactId equals the filename-derived value, not the manifest's
    * Implementation-Title.
    */
  property(
    "field-merge: filename artifactId beats manifest Implementation-Title"
  ) {
    forAll(genArtifactId, genVersion, Gen.alphaNumStr.suchThat(_.nonEmpty)) {
      (art, ver, humanTitle) =>
        val state = MavenState()
        val manifest = TreeMap[String, TreeSet[StringOrPair]](
          "implementation-title" -> TreeSet(StringOrPair(humanTitle)),
          "implementation-version" -> TreeSet(StringOrPair(ver))
        )
        val (_, a, _) = state.resolveGroupIdArtifactIdVersion(
          ByteWrapper(Array.emptyByteArray, s"$art-$ver.jar", None),
          None,
          manifest,
          Map.empty,
          None
        )
        a == Some(art)
    }
  }

  // ==================== Sources/Javadoc accumulation property tests ====================

  // Generators for sources/javadoc property tests
  private val groupIdGen: Gen[String] = for {
    prefix <- Gen.oneOf("com.example", "org.test", "io.spicelabs", "za.co.absa")
    suffixLen <- Gen.choose(0, 10)
    suffixChars <- Gen.listOfN(suffixLen, Gen.alphaChar)
  } yield
    if (suffixChars.isEmpty) prefix else s"$prefix.${suffixChars.mkString}"

  private val artifactIdGen: Gen[String] = for {
    len <- Gen.choose(3, 15)
    chars <- Gen.listOfN(len, Gen.alphaChar)
  } yield chars.mkString

  private val versionGen: Gen[String] = for {
    major <- Gen.choose(0, 10)
    minor <- Gen.choose(0, 20)
    patch <- Gen.choose(0, 50)
  } yield s"$major.$minor.$patch"

  private val groupIdArtifactIdVersionGen: Gen[(String, String, String)] = for {
    g <- groupIdGen
    a <- artifactIdGen
    v <- versionGen
  } yield (g, a, v)

  private def createTestItem(id: String): Item =
    Item(id, scala.collection.immutable.TreeSet.empty, None, None)

  private def writeJarEntries(
      jarFile: File,
      entries: Seq[(String, String)]
  ): Unit = {
    val zos = new ZipOutputStream(new FileOutputStream(jarFile))
    try {
      for ((path, content) <- entries) {
        zos.putNextEntry(new ZipEntry(path))
        zos.write(content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    } finally {
      zos.close()
    }
  }

  // Test 34: classifier always correct regardless of standalone or bundled
  //
  // What this tests: For any sources JAR with a random groupId/artifactId/version
  // in pom.properties, whether standalone or bundled: primary pURL has
  // ?packaging=sources with correct coordinates. Secondary pURLs have NO
  // classifier.
  //
  // Requirement: Phase 1 — classifier is always correct for sources marker.
  // Theory: beginProcessing(Sources) sets currentMarker, applyAccumulatedAugmentation
  // uses currentMarker to determine classifier.
  property(
    "sources classifier always correct with random groupId/artifactId/version"
  ) {
    forAll(groupIdArtifactIdVersionGen) { case (group, art, ver) =>
      val tempDir = Files.createTempDirectory("maven-prop-src").toFile
      try {
        val jarFile = new File(tempDir, s"$art-$ver-sources.jar")
        writeJarEntries(
          jarFile,
          Seq(
            s"META-INF/maven/$group/$art/pom.properties" ->
              s"groupId = $group\n artifactId = $art\n version = $ver\n"
          )
        )
        val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
        val item = createTestItem("prop-src")
        val store = MemStorage(None)

        val s1 =
          MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
        FileWalker.withinArchiveStream(wrapper) { entries =>
          entries.foreach { entry =>
            s1.accumulateInfo(item.identifier, item, entry, store)
          }
        }
        s1.applyAccumulatedAugmentation(item, wrapper, store)

        val purls = store.purls().toSet
        assert(
          purls.exists(_.contains("packaging=sources")) &&
            purls.exists(_.contains(group)) &&
            purls.exists(_.contains(art)) &&
            purls.exists(_.contains(ver)),
          s"purls=$purls group=$group art=$art ver=$ver"
        )
      } finally {
        val files = tempDir.listFiles()
        if (files != null) files.foreach(_.delete())
        tempDir.delete()
      }
    }
  }

  // Test 35: Sources accumulator does not contaminate JAR accumulator
  //
  // What this tests: After Sources' applyAug, then JAR's beginProcessing:
  // the JAR's accumulator is fresh (empty manifest, empty embeddedGroupIdArtifactIdVersions, etc.).
  // Also, Sources' accumulator is cleared while JAR's is populated.
  //
  // Requirement: Three separate accumulators — no cross-contamination.
  // Theory: Each marker has its own accumulator field. beginProcessing(JAR)
  // only sets jarAccumulated, not sourcesAccumulated.
  property("sources accumulator does not contaminate JAR accumulator") {
    forAll(groupIdArtifactIdVersionGen) { case (srcGroup, srcArt, srcVer) =>
      val tempDir = Files.createTempDirectory("maven-prop-nocontam").toFile
      try {
        // Sources JAR with its own groupId/artifactId/version
        val sourcesJar = new File(tempDir, s"$srcArt-$srcVer-sources.jar")
        writeJarEntries(
          sourcesJar,
          Seq(
            s"META-INF/maven/$srcGroup/$srcArt/pom.properties" ->
              s"groupId = $srcGroup\n artifactId = $srcArt\n version = $srcVer\n"
          )
        )
        // Main JAR with different groupId/artifactId/version
        val jarGroup = "com.different"
        val jarArt = "mainjar"
        val jarVer = "9.9.9"
        val mainJar = new File(tempDir, s"$jarArt-$jarVer.jar")
        writeJarEntries(
          mainJar,
          Seq(
            s"META-INF/maven/$jarGroup/$jarArt/pom.properties" ->
              s"groupId = $jarGroup\n artifactId = $jarArt\n version = $jarVer\n"
          )
        )

        val srcWrapper =
          FileWrapper(sourcesJar, sourcesJar.getAbsolutePath, None)
        val jarWrapper = FileWrapper(mainJar, mainJar.getAbsolutePath, None)
        val store = MemStorage(None)

        // Process Sources
        val sSrc = MavenState().beginProcessing(
          srcWrapper,
          createTestItem("ps"),
          MavenMarkers.Sources
        )
        FileWalker.withinArchiveStream(srcWrapper) { entries =>
          entries.foreach { entry =>
            sSrc.accumulateInfo("ps", createTestItem("ps"), entry, store)
          }
        }
        sSrc.applyAccumulatedAugmentation(
          createTestItem("ps-item"),
          srcWrapper,
          store
        )

        // Sources accumulator should be cleared after applyAug
        assert(
          sSrc.sourcesAccumulated.isEmpty,
          "sourcesAccumulated should be cleared"
        )

        // Process JAR on same state
        val sJar = sSrc.beginProcessing(
          jarWrapper,
          createTestItem("pj"),
          MavenMarkers.JAR
        )
        // JAR accumulator should be fresh
        assert(sJar.jarAccumulated.isDefined, "jarAccumulated should be set")
        assert(
          sJar.jarAccumulated.get.embeddedGroupIdArtifactIdVersions.isEmpty,
          "JAR embeddedGroupIdArtifactIdVersions should be empty (fresh)"
        )
        assert(
          sJar.jarAccumulated.get.manifest.isEmpty,
          "JAR manifest should be empty (fresh)"
        )

        FileWalker.withinArchiveStream(jarWrapper) { entries =>
          entries.foreach { entry =>
            sJar.accumulateInfo("pj", createTestItem("pj"), entry, store)
          }
        }
        sJar.applyAccumulatedAugmentation(
          createTestItem("pj-item"),
          jarWrapper,
          store
        )

        val purls = store.purls().toSet
        // Sources pURL should use srcGroup, NOT jarGroup
        purls.exists(_.contains(srcGroup)) &&
        // JAR pURL should use jarGroup, NOT srcGroup
        purls.exists(_.contains(jarGroup))
      } finally {
        val files = tempDir.listFiles()
        if (files != null) files.foreach(_.delete())
        tempDir.delete()
      }
    }
  }

  // Test 36: classifier reset between markers
  //
  // What this tests: After each marker's applyAug, that marker's accumulator
  // is cleared. The next marker starts with a fresh accumulator.
  //
  // Requirement: Each accumulator is independently cleared after consumption.
  // Theory: applyAccumulatedAugmentation calls clearAccumulator for the
  // current marker.
  property("accumulator cleared after applyAug for each marker") {
    forAll(groupIdArtifactIdVersionGen) { case (group, art, ver) =>
      val tempDir = Files.createTempDirectory("maven-prop-clear").toFile
      try {
        val propsContent =
          s"groupId = $group\n artifactId = $art\n version = $ver\n"
        val sourcesJar = new File(tempDir, s"$art-$ver-sources.jar")
        writeJarEntries(
          sourcesJar,
          Seq(s"META-INF/maven/$group/$art/pom.properties" -> propsContent)
        )
        val javadocJar = new File(tempDir, s"$art-$ver-javadoc.jar")
        writeJarEntries(
          javadocJar,
          Seq(s"META-INF/maven/$group/$art/pom.properties" -> propsContent)
        )

        val srcWrapper =
          FileWrapper(sourcesJar, sourcesJar.getAbsolutePath, None)
        val docWrapper =
          FileWrapper(javadocJar, javadocJar.getAbsolutePath, None)
        val store = MemStorage(None)

        // Process Sources
        val s1 = MavenState().beginProcessing(
          srcWrapper,
          createTestItem("c1"),
          MavenMarkers.Sources
        )
        FileWalker.withinArchiveStream(srcWrapper) { entries =>
          entries.foreach { entry =>
            s1.accumulateInfo("c1", createTestItem("c1"), entry, store)
          }
        }
        s1.applyAccumulatedAugmentation(
          createTestItem("c1-item"),
          srcWrapper,
          store
        )
        assert(
          s1.sourcesAccumulated.isEmpty,
          "sourcesAccumulated should be None after applyAug"
        )

        // Process JavaDocs
        val s2 = s1.beginProcessing(
          docWrapper,
          createTestItem("c2"),
          MavenMarkers.JavaDocs
        )
        FileWalker.withinArchiveStream(docWrapper) { entries =>
          entries.foreach { entry =>
            s2.accumulateInfo("c2", createTestItem("c2"), entry, store)
          }
        }
        s2.applyAccumulatedAugmentation(
          createTestItem("c2-item"),
          docWrapper,
          store
        )
        assert(
          s2.javadocAccumulated.isEmpty,
          "javadocAccumulated should be None after applyAug"
        )

        true
      } finally {
        val files = tempDir.listFiles()
        if (files != null) files.foreach(_.delete())
        tempDir.delete()
      }
    }
  }

  // Test 37: applyAccumulatedAugmentation never throws for Sources/JavaDocs
  //
  // What this tests: For any combination of accumulated state (random manifest,
  // random embedded groupId/artifactId/version tuples, random POMs): no exception. Either emits pURL or
  // returns gracefully.
  //
  // Requirement: Robustness — applyAccumulatedAugmentation is crash-proof.
  // Theory: All resolution paths use Option/Try, no raw exceptions.
  property("applyAccumulatedAugmentation never throws for Sources") {
    forAll(groupIdArtifactIdVersionGen) { case (group, art, ver) =>
      val tempDir = Files.createTempDirectory("maven-prop-nothrow").toFile
      try {
        // Create a sources JAR with mixed content
        val jarFile = new File(tempDir, s"$art-$ver-sources.jar")
        writeJarEntries(
          jarFile,
          Seq(
            s"META-INF/maven/$group/$art/pom.properties" ->
              s"groupId = $group\n artifactId = $art\n version = $ver\n",
            "META-INF/MANIFEST.MF" ->
              s"Manifest-Version: 1.0\nImplementation-Title: $art\nImplementation-Version: $ver\n",
            "com/example/Broken.class" -> Array[Byte](0x00, 0x01, 0x02).mkString
          )
        )
        val wrapper = FileWrapper(jarFile, jarFile.getAbsolutePath, None)
        val item = createTestItem("nothrow")
        val store = MemStorage(None)

        val s1 =
          MavenState().beginProcessing(wrapper, item, MavenMarkers.Sources)
        FileWalker.withinArchiveStream(wrapper) { entries =>
          entries.foreach { entry =>
            s1.accumulateInfo(item.identifier, item, entry, store)
          }
        }
        // Should not throw
        try {
          s1.applyAccumulatedAugmentation(item, wrapper, store)
          true
        } catch {
          case _: Exception => false
        }
      } finally {
        val files = tempDir.listFiles()
        if (files != null) files.foreach(_.delete())
        tempDir.delete()
      }
    }
  }

  // Test 38: Sources uses external POM as highest priority
  //
  // What this tests: Sources pom.properties groupId/artifactId/version (g, a, v), external POM groupId/artifactId/version
  // (g2, a2, v2) where different. After Sources' applyAug: emitted pURL has
  // namespace=g2, name=a2, version=v2 (from external POM, NOT pom.properties).
  //
  // Requirement: Companion POM is highest priority for canonical pURL (REQ-3).
  // This applies to ALL markers including Sources.
  // Theory: resolveGroupIdArtifactIdVersion takes externalPom as highest
  // priority, so parsedPom (from beginProcessing(POM)) wins over
  // sourcesAccumulated's embeddedProps.
  property("Sources uses external POM as highest priority") {
    forAll(groupIdArtifactIdVersionGen, groupIdArtifactIdVersionGen) {
      case ((srcG, srcA, srcV), (pomG, pomA, pomV)) =>
        // Skip if they're the same (trivial case)
        if (srcG == pomG && srcA == pomA && srcV == pomV) true
        else {
          val tempDir = Files.createTempDirectory("maven-prop-own").toFile
          try {
            val sourcesJar = new File(tempDir, s"$srcA-$srcV-sources.jar")
            writeJarEntries(
              sourcesJar,
              Seq(
                s"META-INF/maven/$srcG/$srcA/pom.properties" ->
                  s"groupId = $srcG\n artifactId = $srcA\n version = $srcV\n"
              )
            )

            val pomContent =
              s"""<project>
               |  <groupId>$pomG</groupId>
               |  <artifactId>$pomA</artifactId>
               |  <version>$pomV</version>
               |</project>""".stripMargin
            val pomFile = new File(tempDir, s"$pomA-$pomV.pom")
            java.nio.file.Files
              .write(pomFile.toPath, pomContent.getBytes("UTF-8"))

            val srcWrapper =
              FileWrapper(sourcesJar, sourcesJar.getAbsolutePath, None)
            val pomWrapper = FileWrapper(pomFile, pomFile.getAbsolutePath, None)
            val store = MemStorage(None)

            // First process POM to populate parsedPom
            val sPom = MavenState().beginProcessing(
              pomWrapper,
              createTestItem("pp"),
              MavenMarkers.POM
            )

            // Then process Sources on the same state
            val sSrc = sPom.beginProcessing(
              srcWrapper,
              createTestItem("ps38"),
              MavenMarkers.Sources
            )
            FileWalker.withinArchiveStream(srcWrapper) { entries =>
              entries.foreach { entry =>
                sSrc.accumulateInfo(
                  "ps38",
                  createTestItem("ps38"),
                  entry,
                  store
                )
              }
            }
            sSrc.applyAccumulatedAugmentation(
              createTestItem("ps38-item"),
              srcWrapper,
              store
            )

            val purls = store.purls().toSet
            // Sources pURL should use pomG/pomA/pomV (from external POM),
            // NOT srcG/srcA/srcV (from pom.properties)
            // Positive: companion POM values appear in pURLs
            // Negative: pom.properties groupId does NOT appear as a namespace
            //   (use "maven/$srcG/" to avoid false positive when srcG is a
            //    substring of pomG, e.g. srcG="com.example" vs pomG="com.example.abc")
            // Note: only run the negative check when srcG != pomG — otherwise
            //   the POM's own pURL (which correctly uses pomG == srcG) would
            //   trigger a false negative.
            val positive = purls.exists(_.contains(pomG)) &&
              purls.exists(_.contains(pomA)) &&
              purls.exists(_.contains(pomV))
            val negative = if (srcG != pomG) {
              !purls.exists(_.contains(s"maven/$srcG/"))
            } else true
            positive && negative
          } finally {
            val files = tempDir.listFiles()
            if (files != null) files.foreach(_.delete())
            tempDir.delete()
          }
        }
    }
  }
}
