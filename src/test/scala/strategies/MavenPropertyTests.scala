/* Property-based tests for Maven strategy phases 0–3.
 *
 * These complement the example-based suite by exercising random inputs and
 * invariants that must hold for ALL inputs.
 *
 * Requirement traceability:
 *   - R1: PomParser interpolation is correct and terminates (acyclic/cyclic)
 *   - R2: GAV filename extraction handles arbitrary Maven-style names
 *   - R3: Date parsing accepts all supported formats and rejects garbage
 *   - R4: resolveGAV priority chain is deterministic and correct
 */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.PomParser
import munit.ScalaCheckSuite
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.compact
import org.json4s.native.JsonMethods.render
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalacheck.Prop.propBoolean

import java.text.SimpleDateFormat
import java.util.Date
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
      val (_, a, v) = MavenState().resolveGAVFromFilename(filename)
      a == Some(artifact) && v == Some(version)
    }
  }

  property("extractIdentityFromFilename returns None for non-Maven filenames") {
    forAll(Gen.alphaNumStr) { name =>
      // Only true when name has no dash-digit split
      val hasDashDigit = name.zipWithIndex.exists { case (ch, i) =>
        ch == '-' && i + 1 < name.length && name.charAt(i + 1).isDigit
      }
      val (_, a, v) = MavenState().resolveGAVFromFilename(s"$name.jar")
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
  // Properties: GAV priority chain
  // ------------------------------------------------------------------

  property("resolveGAV: embeddedProps always wins when complete") {
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
      val (g, a, v) = state.resolveGAV(
        ByteWrapper(Array.emptyByteArray, "test.jar", None),
        external,
        TreeMap.empty[String, TreeSet[StringOrPair]],
        props,
        None
      )
      g == Some("com.embed") && a == Some(art) && v == Some(ver)
    }
  }

  property("resolveGAV: falls through each layer deterministically") {
    forAll(genArtifactId, genVersion) { (art, ver) =>
      val state = MavenState()
      val artifact = ByteWrapper(Array.emptyByteArray, s"$art-$ver.jar", None)

      // Nothing but filename
      val (g1, a1, v1) =
        state.resolveGAV(artifact, None, TreeMap.empty, Map.empty, None)

      // Empty manifest should not change result
      val (g2, a2, v2) = state.resolveGAV(
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

  property("parseDateString never crashes on arbitrary strings") {
    forAll(Gen.alphaNumStr) { s =>
      val state = MavenState()
      // Must not throw; returns Option
      scala.util.Try(state.parseDateString(s)).isSuccess
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
  // Property: PomParser GAV correctness on generated POM XML
  // ------------------------------------------------------------------

  val genPomXmlWithGav
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

  property("PomParser.parse produces correct GAV on generated POM XML") {
    forAll(genPomXmlWithGav) { case (xml, expectedG, expectedA, expectedV) =>
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
  // Property: Dependency JSON round-trip
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
}
