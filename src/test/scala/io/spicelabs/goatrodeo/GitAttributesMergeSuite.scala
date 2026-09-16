package io.spicelabs.goatrodeo

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import java.nio.charset.StandardCharsets

/** Regression: source files must keep git's textual merge driver.
  *
  * Pins `.gitattributes`: the line `* -filter -merge -text` unsets `merge` for
  * every path. Per git-attributes(5), an unset `merge` attribute means "take
  * the current branch's version and declare a conflict", i.e. no three-way text
  * merge is ever attempted for any file in the repository. Only `-filter` is
  * needed to neutralise a global git-lfs filter.
  */
class GitAttributesMergeSuite extends GoatRodeoFunSuite {

  test("source files are not marked merge-unset") {
    val target = "src/main/scala/io/spicelabs/goatrodeo/Main.scala"
    assume(new File(target).exists(), "must run from the repository root")
    val proc = new ProcessBuilder("git", "check-attr", "merge", "--", target)
      .redirectErrorStream(true)
      .start()
    val out = new String(
      proc.getInputStream.readAllBytes(),
      StandardCharsets.UTF_8
    ).trim
    val code = proc.waitFor()
    assume(code == 0, s"git check-attr unavailable (exit $code): $out")
    assert(
      !out.endsWith("merge: unset"),
      s"`.gitattributes` disables text merging for $target: $out"
    )
  }
}
