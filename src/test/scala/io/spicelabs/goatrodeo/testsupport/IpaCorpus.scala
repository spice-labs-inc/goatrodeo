package io.spicelabs.goatrodeo.testsupport

import java.io.File
import java.security.MessageDigest

/** Test-support for the iOS (.ipa) corpus.
  *
  * The real `.ipa` corpus item is `allsafe-ios.ipa` from the
  * `t0thkr1s/allsafe-ios` GitHub release v1.0 (owner decision 2026-09-25:
  * real corpus first, size-gated at 6 MB; this item is 4.11 MB). The build
  * (`build.sbt` Tests.Setup) downloads it once into
  * `test_data/download/ipa_tests/`, skipping the fetch (with a warning) when
  * the network is unavailable, so the corpus tests `assume` the fixture
  * rather than failing the run.
  *
  * The digest below is pinned from the downloaded artifact
  * (sha256 809d7fe9f38cd271215024d8dd1d4a2ea831cd7a94781f5ab1efbd1160de0e80);
  * if the upstream release were ever replaced, the digest check catches the
  * drift and the test fails loudly instead of silently testing different
  * bytes.
  */
object IpaCorpus {

  /** Path of the fetched corpus `.ipa` (relative to the repo root, the
    * working directory of the test JVM).
    */
  val corpusFile: File =
    new File("test_data/download/ipa_tests/allsafe-ios.ipa")

  /** Pinned SHA-256 of the corpus `.ipa`. */
  val expectedSha256: String =
    "809d7fe9f38cd271215024d8dd1d4a2ea831cd7a94781f5ab1efbd1160de0e80"

  /** True when the corpus item is present on disk (the munit `assume` gate
    * for corpus tests).
    */
  def present: Boolean = corpusFile.exists()

  /** Compute the SHA-256 hex digest of a file.
    *
    * @param f
    *   the file to digest
    * @return
    *   the lowercase hex digest
    */
  def sha256Of(f: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = new java.io.FileInputStream(f)
    try {
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while (n >= 0) {
        if (n > 0) digest.update(buf, 0, n)
        n = in.read(buf)
      }
    } finally in.close()
    digest.digest().map("%02x".format(_)).mkString
  }

  /** The corpus fixture passes its integrity check: present AND matching the
    * pinned sha256. A present-but-drifting file is treated as absent so the
    * tests fail with a clear message rather than testing wrong bytes.
    */
  def verified: Boolean =
    present && sha256Of(corpusFile) == expectedSha256
}