package io.spicelabs.goatrodeo

import com.sun.net.httpserver.HttpServer
import io.spicelabs.goatrodeo.util.OsvGate
import munit.FunSuite

import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Phase 1 — OSV gate: request + verdict semantics (spec §2, T3.x).
  *
  * WHAT: verifies the sbt-native OSV gate implementation
  * (`io.spicelabs.goatrodeo.util.OsvGate`, invoked by the `osvCheck`
  * sbt task) against a loopback stub of api.osv.dev and asserts:
  *   (a) it POSTs exactly the filtered batch body to /v1/querybatch
  *       (product configs only, 9999.0 excluded, deduped, sorted),
  *   (b) the verdict matrix (>= 7.0 fails; 6.9999 passes; unscored
  *       passes fail-open; transport/malformed = distinct INFRA).
  *
  * WHY (user decision 2026-09-02): the gate logic is sbt-native — no
  * Python, no shell scripts; the `osvCheck` sbt task filters, POSTs to
  * the OSV API, and fails the build on a concrete CVSS >= 7.0. This
  * suite pins the exact API call and the fail-open semantics in-process
  * (no subprocess).
  *
  * LLM note: `OsvGate.check` reads the dump file and POSTs to a given
  * endpoint; the stub records method/path/body and returns a planted
  * response. `check` returns "PASS:...", "FAIL:...", or "INFRA:...".
  */
class OsvGateScriptSuite extends FunSuite {

  private def writeDump(rows: List[Map[String, String]]): File = {
    val f = File.createTempFile("osv-dump", ".json")
    val json = rows
      .map(r =>
        s"""{"configuration":"${r("configuration")}","organization":"${r("organization")}","name":"${r("name")}","revision":"${r("revision")}"}"""
      )
      .mkString("[", ",", "]")
    Files.write(f.toPath, json.getBytes(StandardCharsets.UTF_8))
    f.deleteOnExit()
    f
  }

  private def startStub(planted: String): (HttpServer, collection.mutable.ArrayBuffer[String]) = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val requests = collection.mutable.ArrayBuffer[String]()
    server.createContext(
      "/v1/querybatch",
      exchange => {
        val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        requests += s"${exchange.getRequestMethod} ${exchange.getRequestURI.getPath} ${exchange.getRequestHeaders.getFirst("Content-Type")} $body"
        val bytes = planted.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    (server, requests)
  }

  private def runGate(dump: File, endpoint: String): String =
    OsvGate.check(dump, endpoint)

  private def sampleDump(): List[Map[String, String]] = List(
    Map("configuration" -> "compile", "organization" -> "io.spicelabs", "name" -> "baharat", "revision" -> "0.2.1"),
    Map("configuration" -> "test", "organization" -> "io.spicelabs", "name" -> "baharat", "revision" -> "0.2.1"), // duplicate across configs
    Map("configuration" -> "compile", "organization" -> "org.eclipse.jgit", "name" -> "org.eclipse.jgit", "revision" -> "7.3.0.202506031305-r"),
    Map("configuration" -> "scala-tool", "organization" -> "org.scala-lang", "name" -> "scala3-compiler_3", "revision" -> "3.8.3"), // tool-only: excluded
    Map("configuration" -> "test", "organization" -> "com.google.guava", "name" -> "listenablefuture", "revision" -> "9999.0-empty-to-avoid-conflict-with-guava"), // placeholder: excluded
    Map("configuration" -> "runtime", "organization" -> "org.xerial", "name" -> "sqlite-jdbc", "revision" -> "3.53.4.0")
  )

  private val expectedBatch =
    """{"queries":[
      |{"package":{"name":"io.spicelabs:baharat","ecosystem":"Maven"},"version":"0.2.1"},
      |{"package":{"name":"org.eclipse.jgit:org.eclipse.jgit","ecosystem":"Maven"},"version":"7.3.0.202506031305-r"},
      |{"package":{"name":"org.xerial:sqlite-jdbc","ecosystem":"Maven"},"version":"3.53.4.0"}]}""".stripMargin.replaceAll("\n", "").replaceAll(" ", "")

  test("T3.1 gatePostsExactlyTheFilteredBatch") {
    val planted = """{"results":[{"vulns":[]},{"vulns":[]},{"vulns":[]}]}"""
    val (server, requests) = startStub(planted)
    try {
      val dump = writeDump(sampleDump())
      val outcome = runGate(dump, s"http://127.0.0.1:${server.getAddress.getPort}")
      assert(outcome.startsWith("PASS"), s"expected PASS, got $outcome")
      assertEquals(requests.size, 1)
      val req = requests.head
      assert(req.startsWith("POST /v1/querybatch"), s"wrong request: $req")
      assert(req.split(' ')(2).contains("application/json"), s"wrong content-type: $req")
      val body = req.substring(req.indexOf("{")).replaceAll(" ", "")
      assertEquals(body, expectedBatch, s"batch body mismatch:\n$body")
    } finally server.stop(0)
  }

  test("T3.2 verdictMatrix — >= 7.0 fails, below passes") {
    def runWith(response: String): String = {
      val (server, _) = startStub(response)
      try {
        runGate(writeDump(sampleDump()), s"http://127.0.0.1:${server.getAddress.getPort}")
      } finally server.stop(0)
    }
    def resultsWith(score: Double): String =
      s"""{"results":[{"vulns":[{"id":"V-1","summary":"s","severity":[{"type":"CVSS_V3","score":"$score"}]}]},{"vulns":[]},{"vulns":[]}]}"""

    assert(runWith(resultsWith(8.1)).startsWith("FAIL"))
    assert(runWith(resultsWith(7.0)).startsWith("FAIL"))
    assert(runWith(resultsWith(6.9)).startsWith("PASS"))
    assert(runWith(resultsWith(6.9999)).startsWith("PASS"))
    assert(runWith("""{"results":[{"vulns":[]},{"vulns":[]},{"vulns":[]}]}""").startsWith("PASS"))
  }

  test("T3.3 failOpenOnUnscored") {
    val planted =
      """{"results":[{"vulns":[{"id":"V-U","summary":"unscored","severity":[]}]},{"vulns":[]},{"vulns":[]}]}"""
    val (server, _) = startStub(planted)
    try {
      val out = runGate(writeDump(sampleDump()), s"http://127.0.0.1:${server.getAddress.getPort}")
      assert(out.startsWith("PASS"), s"unscored advisory must pass fail-open: $out")
      assert(
        out.contains("\"unscored_count\":1"),
        s"summary must report the unscored advisory: $out"
      )
    } finally server.stop(0)
  }

  test("T3.4 transportErrorIsDistinctInfra") {
    // port 1 is not listening on loopback — connection refused is immediate
    val out = runGate(writeDump(sampleDump()), "http://127.0.0.1:1")
    assert(out.startsWith("INFRA"), s"transport error must be INFRA: $out")
  }

  test("T3.5 malformedResponseIsDistinctInfra") {
    val (server, _) = startStub("not json")
    try {
      val out = runGate(writeDump(sampleDump()), s"http://127.0.0.1:${server.getAddress.getPort}")
      assert(out.startsWith("INFRA"), s"malformed response must be INFRA: $out")
    } finally server.stop(0)
  }
}