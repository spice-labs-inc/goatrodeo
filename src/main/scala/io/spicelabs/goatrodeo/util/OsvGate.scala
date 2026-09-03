package io.spicelabs.goatrodeo.util

import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import scala.util.Try

/** OSV dependency gate (sbt-native, fail-open).
  *
  * WHAT: reads the raw resolved-dependency dump written by the sbt
  * `osvDumpJson` task (target/osv-dump.json), filters it to the
  * product's own configurations, builds an OSV batch-query body, POSTs
  * it to api.osv.dev/v1/querybatch over plain JDK HTTP (no
  * java.net.http.HttpClient, no shell, no Python), and classifies the
  * advisories.
  *
  * WHY: the gate semantics live in one place — product code — so the
  * sbt task `osvCheck` and the test suite exercise the same
  * implementation. The build tool resolves the dependency set; this
  * object turns the resolved set into a gate verdict.
  *
  * Semantics (user decision 2026-09-02):
  *   - FAIL-OPEN on unscored advisories: an advisory without a
  *     resolvable CVSS score is reported but never fails the gate.
  *   - An advisory with a concrete CVSS score >= 7.0 fails the gate.
  *   - Transport errors and malformed responses are a distinct
  *     infrastructure failure ("INFRA"), never a silent pass and never
  *     a dependency verdict.
  *   - The build-tool-only configurations (scala-tool, scala-doc-tool,
  *     scala-repl-tool) are excluded: the gate covers what the product
  *     ships, builds, and tests.
  *
  * LLM note: `check` returns a string "OUTCOME:payload" (OUTCOME is
  * PASS, FAIL, or INFRA) so the sbt task needs no reflection over
  * product case classes. The test suite calls `check` directly against
  * a loopback stub and pins the exact POST body and verdict matrix.
  */
object OsvGate {

  /** One resolved-module row from the dump. */
  final case class OsvRow(
      configuration: String,
      organization: String,
      name: String,
      revision: String
  )

  /** The product's own configurations (spec §2): everything the product
    * ships, builds, and tests. Anything else in the dump (scala-* tool
    * configs) is build-tool-only and excluded.
    */
  val ProductConfigurations: Set[String] = Set(
    "compile",
    "compile-internal",
    "test",
    "test-internal",
    "runtime",
    "runtime-internal",
    "provided",
    "provided-internal"
  )

  /** Path of the OSV batch endpoint relative to the API base URL. */
  val ApiPath: String = "/v1/querybatch"

  /** The guava `listenablefuture` placeholder revision is excluded. */
  val PlaceholderPrefix: String = "9999.0"

  /** A single advisory, classified from the OSV response. */
  final case class Advisory(id: String, summary: String, score: Option[Double])

  /** Loads and parses the dump file into rows.
    *
    * @param dump the JSON dump written by `osvDumpJson`
    * @return the rows; throws IllegalArgumentException when the file is
    *         not a JSON array of objects
    */
  def loadRows(dump: File): Vector[OsvRow] = {
    val json = parse(scala.io.Source.fromFile(dump).mkString)
    json match {
      case JArray(items) =>
        items.map {
          case JObject(fields) =>
            val map = fields.collect { case (k, JString(v)) => k -> v }.toMap
            OsvRow(
              configuration = map.getOrElse("configuration", ""),
              organization = map.getOrElse("organization", ""),
              name = map.getOrElse("name", ""),
              revision = map.getOrElse("revision", "")
            )
          case other =>
            throw new IllegalArgumentException(s"bad dump row: $other")
        }.toVector
      case other =>
        throw new IllegalArgumentException(s"dump not an array: $other")
    }
  }

  /** Filters to product configs, drops the guava 9999.0 placeholder and
    * rows missing fields, dedupes, and sorts deterministically.
    */
  def productRows(rows: Vector[OsvRow]): Vector[OsvRow] = {
    // Dedupe by (organization, name, revision): the same module can
    // appear in several product configurations; the OSV query is per
    // module, not per configuration.
    rows
      .filter(r =>
        ProductConfigurations.contains(r.configuration) &&
          r.organization.nonEmpty && r.name.nonEmpty && r.revision.nonEmpty &&
          !r.revision.startsWith(PlaceholderPrefix)
      )
      .groupBy(r => (r.organization, r.name, r.revision))
      .values
      .map(_.head)
      .toVector
      .sortBy(r => (r.organization, r.name, r.revision))
  }

  /** Builds the OSV batch-query JSON body for the given (already
    * filtered/sorted) rows.
    */
  def batchBody(rows: Vector[OsvRow]): String = {
    val queries = rows.map { r =>
      s"""{"package":{"name":"${esc(r.organization)}:${esc(r.name)}","ecosystem":"Maven"},"version":"${esc(r.revision)}"}"""
    }
    s"""{"queries":[${queries.mkString(",")}]}"""
  }

  /** Returns the CVSS base score of an OSV vulnerability, or None when
    * the advisory carries no usable score (fail-open per user decision).
    *
    * Follows the OSV conventions: a "severity" array of
    * [{"type": "CVSS_V3", "score": "8.1"}] entries, with a
    * database_specific.severity string fallback
    * (LOW/MODERATE/HIGH/CRITICAL).
    */
  def cvssScore(vuln: JValue): Option[Double] = {
    val severity = (vuln \ "severity") match {
      case JArray(items) => items
      case _             => Nil
    }
    val fromSeverity = severity.iterator
      .flatMap { item =>
        (item \ "score") match {
          case JString(s) =>
            Try(s.trim.toDouble).toOption.filter(d => d >= 0.0 && d <= 10.0)
          case JDouble(d) if d >= 0.0 && d <= 10.0 => Some(d)
          case JInt(i) if i >= 0 && i <= 10         => Some(i.toDouble)
          case _                                    => None
        }
      }
      .nextOption()
    fromSeverity.orElse {
      (vuln \ "database_specific" \ "severity") match {
        case JString(s) =>
          s.toUpperCase match {
            case "LOW"      => Some(3.0)
            case "MODERATE" => Some(4.0)
            case "HIGH"     => Some(7.0)
            case "CRITICAL" => Some(9.0)
            case _          => None
          }
        case _ => None
      }
    }
  }

  /** POSTs the batch body to the endpoint and returns the parsed
    * response, or Left(infrastructure-failure-message).
    *
    * Uses plain JDK HTTP (java.net.HttpURLConnection): no
    * java.net.http.HttpClient, no shell.
    */
  def postBatch(endpoint: String, body: String): Either[String, JValue] = {
    val transport = Try {
      val url = new URL(endpoint.stripSuffix("/") + ApiPath)
      val connection = url.openConnection().asInstanceOf[HttpURLConnection]
      try {
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.setConnectTimeout(30000)
        connection.setReadTimeout(180000)
        connection.setDoOutput(true)
        val out = connection.getOutputStream
        try out.write(body.getBytes(StandardCharsets.UTF_8))
        finally out.close()
        val code = connection.getResponseCode
        val stream =
          if (code >= 200 && code < 300) connection.getInputStream
          else connection.getErrorStream
        val text = Option(stream)
          .map(is => scala.io.Source.fromInputStream(is, "UTF-8").mkString)
          .getOrElse("")
        parse(text)
      } finally connection.disconnect()
    }.toEither.left.map(e => s"request failed: ${e.getMessage}")

    transport.flatMap { json =>
      (json \ "results") match {
        case JArray(_) => Right(json)
        case _ =>
          Left("malformed response: expected {\"results\": [...]}")
      }
    }
  }

  /** Runs the gate.
    *
    * @param dump     the osvDumpJson output file
    * @param endpoint the OSV API base URL (e.g. https://api.osv.dev)
    * @return "PASS:<summary-json>" when no advisory has a concrete
    *         score >= 7.0, "FAIL:<summary-json>" otherwise, or
    *         "INFRA:<message>" for transport/malformed failures
    */
  def check(dump: File, endpoint: String): String = {
    Try(loadRows(dump)).toEither match {
      case Left(e) => s"INFRA:cannot read dump: ${e.getMessage}"
      case Right(rows) =>
        val batch = batchBody(productRows(rows))
        postBatch(endpoint, batch) match {
          case Left(message) => s"INFRA:$message"
          case Right(response) =>
            val advisories = for {
              case JArray(results) <- Vector(response \ "results")
              result <- results
              case JArray(vulns) <- Vector(result \ "vulns")
              vuln <- vulns
            } yield {
              val id = (vuln \ "id") match {
                case JString(s) => s
                case _          => "unknown"
              }
              val summary = (vuln \ "summary") match {
                case JString(s) => s
                case _          => ""
              }
              Advisory(id, summary, cvssScore(vuln))
            }
            val findings = advisories.filter(_.score.isDefined)
            val unscored = advisories.filter(_.score.isEmpty)
            val high = findings.filter(_.score.exists(_ >= 7.0))
            val outcome = if (high.nonEmpty) "FAIL" else "PASS"
            s"$outcome:${summaryJson(productRows(rows).size, findings, unscored, high)}"
        }
    }
  }

  /** Renders the JSON summary (mirrors the gate's reported shape). */
  private def summaryJson(
      batchQueries: Int,
      findings: Vector[Advisory],
      unscored: Vector[Advisory],
      high: Vector[Advisory]
  ): String = {
    def entry(a: Advisory): JObject =
      JObject(
        List(
          "id" -> JString(a.id),
          "summary" -> JString(a.summary),
          "score" -> a.score.map(JDouble(_)).getOrElse(JNull)
        )
      )
    val summary = JObject(
      List(
        "queries" -> JInt(batchQueries),
        "findings" -> JArray(findings.map(entry).toList),
        "unscored" -> JArray(unscored.map(entry).toList),
        "high" -> JArray(high.map(entry).toList),
        "unscored_count" -> JInt(unscored.size),
        "high_count" -> JInt(high.size),
        "gate" -> JString(if (high.nonEmpty) "FAIL" else "PASS")
      )
    )
    compact(render(summary))
  }

  private def esc(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
}