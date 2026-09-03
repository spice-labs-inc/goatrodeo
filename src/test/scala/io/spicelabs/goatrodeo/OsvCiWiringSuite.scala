package io.spicelabs.goatrodeo

import munit.FunSuite
import org.yaml.snakeyaml.Yaml

import java.io.File
import java.util
import scala.jdk.CollectionConverters.*

/** Phase 1 — CI wiring (spec §2, T4.1).
  *
  * WHAT: asserts the OSV gate runs as its own CI job, independent of the
  * build/test job (no `needs:`), and that the job runs the sbt `osvCheck`
  * task (which dumps the resolved set and runs the gate in-process).
  *
  * WHY: spec §2 — "The gate runs as a CI job, independent of the
  * test/build job." Read as YAML data (not text grep) so the assertion is
  * structural.
  *
  * LLM note: snakeyaml is on the test classpath (transitive of tika).
  */
class OsvCiWiringSuite extends FunSuite {

  private def workflow(): util.Map[String, Any] = {
    val f = new File(".github/workflows/build_test.yml")
    assert(f.exists(), "build_test.yml missing")
    val yaml = new Yaml()
    yaml.load(scala.io.Source.fromFile(f).mkString).asInstanceOf[util.Map[String, Any]]
  }

  private def jobs(): util.Map[String, Any] = {
    val wf = workflow()
    val j = wf.get("jobs").asInstanceOf[util.Map[String, Any]]
    assert(j != null, "workflow must declare jobs")
    j
  }

  test("T4.1a workflow declares an osv job") {
    assert(jobs().containsKey("osv"), "workflow must declare an `osv` job")
  }

  test("T4.1b osv job is independent (no needs:)") {
    val osv = jobs().get("osv").asInstanceOf[util.Map[String, Any]]
    assert(!osv.containsKey("needs"), "osv job must not depend on the build/test job (independent CI job)")
  }

  test("T4.1c osv job runs the osvCheck sbt task") {
    val osv = jobs().get("osv").asInstanceOf[util.Map[String, Any]]
    val steps = osv.get("steps").asInstanceOf[util.List[util.Map[String, Any]]]
    val runs = steps.asScala.toList.flatMap(s => Option(s.get("run")).map(_.toString))
    assert(
      runs.exists(_.contains("osvCheck")),
      s"osv job must run the sbt osvCheck task; got $runs"
    )
  }
}