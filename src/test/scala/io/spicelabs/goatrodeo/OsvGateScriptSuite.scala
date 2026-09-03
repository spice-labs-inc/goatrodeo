package io.spicelabs.goatrodeo

import com.sun.net.httpserver.HttpServer
import munit.FunSuite

import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/** Phase 1 — OSV gate script: request + verdict semantics (spec §2, T3.x).
  *
  * WHAT: spawns the standalone gate script (`housekeeping/osv_check.py`)
  * against a loopback stub of api.osv.dev and asserts:
  *   (a) it POSTs exactly the filtered batch body to /v1/querybatch
  *       (product configs only, 9999.0 excluded, deduped, sorted),
  *   (b) the verdict matrix (>= 7.0 fails; 6.999 passes; unscored
  *       passes fail-open; transport/malformed = distinct exit 2).
  *
  * WHY (user decision 2): the gate logic is a standalone script run in
  * CI; this suite pins the exact API call and the fail-open semantics so
  * we do not cycle changes because CI keeps breaking. The spawn is the
  * accepted deviation: testing the standalone artifact itself.
  *
  * LLM note: the script reads a dump file and POSTs to --endpoint; the
  * stub records method/path/body and returns a planted response.
  */
class OsvGateScriptSuite extends FunSuite {

  private val script = new File("housekeeping/osv_check.py")
  assert(script.exists(), "housekeeping/osv_check.py missing")

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

  private def runScript(dump: File, endpoint: String): (Int, String, String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val logger = ProcessLogger(o => out.append(o).append('\n'), e => err.append(e).append('\n'))
    val code = Process(
      Seq("python3", script.getAbsolutePath, "--input", dump.getAbsolutePath, "--endpoint", endpoint)
    ).!(logger)
    (code, out.toString, err.toString)
  }

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

  test("T3.1 scriptPostsExactlyTheFilteredBatch") {
    val planted = """{"results":[{"vulns":[]},{"vulns":[]},{"vulns":[]}]}"""
    val (server, requests) = startStub(planted)
    try {
      val dump = writeDump(sampleDump())
      val (code, _, _) = runScript(dump, s"http://127.0.0.1:${server.getAddress.getPort}")
      assertEquals(code, 0)
      assertEquals(requests.size, 1)
      val req = requests.head
      assert(req.startsWith("POST /v1/querybatch"), s"wrong request: $req")
      assert(req.split(' ')(2).contains("application/json"), s"wrong content-type: $req")
      val body = req.substring(req.indexOf("{")).replaceAll(" ", "")
      assertEquals(body, expectedBatch, s"batch body mismatch:\n$body")
    } finally server.stop(0)
  }

  test("T3.2 verdictMatrix — >= 7.0 fails, below passes") {
    def runWith(response: String): Int = {
      val (server, _) = startStub(response)
      try {
        val (code, _, _) = runScript(writeDump(sampleDump()), s"http://127.0.0.1:${server.getAddress.getPort}")
        code
      } finally server.stop(0)
    }
    def resultsWith(score: Double): String =
      s"""{"results":[{"vulns":[{"id":"V-1","summary":"s","severity":[{"type":"CVSS_V3","score":"$score"}]}]},{"vulns":[]},{"vulns":[]}]}"""

    assertEquals(runWith(resultsWith(8.1)), 1)
    assertEquals(runWith(resultsWith(7.0)), 1)
    assertEquals(runWith(resultsWith(6.9)), 0)
    assertEquals(runWith(resultsWith(6.9999)), 0)
    assertEquals(runWith("""{"results":[{"vulns":[]},{"vulns":[]},{"vulns":[]}]}"""), 0)
  }

  test("T3.3 failOpenOnUnscored") {
    val planted =
      """{"results":[{"vulns":[{"id":"V-U","summary":"unscored","severity":[]}]},{"vulns":[]},{"vulns":[]}]}"""
    val (server, _) = startStub(planted)
    try {
      val (code, out, err) = runScript(writeDump(sampleDump()), s"http://127.0.0.1:${server.getAddress.getPort}")
      assertEquals(code, 0, s"unscored advisory must pass fail-open; stderr=$err")
      assert(out.contains("\"unscored_count\": 1"), s"summary must report the unscored advisory:\n$out")
      assert(err.contains("fail-open"), s"stderr must warn about the unscored advisory:\n$err")
    } finally server.stop(0)
  }

  test("T3.4 transportErrorIsDistinctExit2") {
    // port 1 is not listening on loopback — connection refused is immediate
    val (code, _, err) = runScript(writeDump(sampleDump()), "http://127.0.0.1:1")
    assertEquals(code, 2, s"transport error must exit 2; stderr=$err")
  }

  test("T3.5 malformedResponseIsDistinctExit2") {
    val (server, _) = startStub("not json")
    try {
      val (code, _, err) = runScript(writeDump(sampleDump()), s"http://127.0.0.1:${server.getAddress.getPort}")
      assertEquals(code, 2, s"malformed response must exit 2; stderr=$err")
      assert(err.contains("malformed") || err.contains("unexpected"), s"stderr=$err")
    } finally server.stop(0)
  }
}