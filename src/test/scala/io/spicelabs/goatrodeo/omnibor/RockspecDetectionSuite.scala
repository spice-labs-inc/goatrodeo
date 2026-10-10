package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.annatto.Ecosystem
import io.spicelabs.annatto.EcosystemRouter
import io.spicelabs.annatto.LanguagePackageReader
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** Detection of LuaRocks `.rockspec` files.
  *
  * Tika has no glob for `.rockspec`, so it reports them as `text/plain`, which
  * is not one of Annatto's supported MIME types; the Annatto strategy then
  * never offers them to Annatto (annatto#14). `ArtifactWrapper.massageMimeType`
  * maps `.rockspec` files detected as plain text to `text/x-lua`.
  *
  * LLM note: Rockspec-xx = test id.
  */
class RockspecDetectionSuite extends GoatRodeoFunSuite {

  private val rockspec =
    """package = "fun"
      |version = "0.1.3-1"
      |source = { url = "git://github.com/luafun/luafun.git", tag = "0.1.3" }
      |description = {
      |  summary = "High-performance functional programming library for Lua",
      |  license = "MIT/X11"
      |}
      |dependencies = { "lua" }
      |build = { type = "builtin", modules = { fun = "fun.lua" } }
      |""".stripMargin.getBytes("UTF-8")

  private def mimesFor(bytes: Array[Byte], name: String): Set[String] =
    ByteWrapper(bytes, name, None).mimeType

  // Rockspec-0.1 — a .rockspec is massaged to text/x-lua, which Annatto supports
  test("Rockspec-0.1 .rockspec is massaged to text/x-lua") {
    val mimes = mimesFor(rockspec, "fun-0.1.3-1.rockspec")
    assert(mimes.contains("text/x-lua"), s"got $mimes")
    assert(
      mimes.intersect(EcosystemRouter.supportedMimeTypes().asScala.toSet).nonEmpty,
      s"$mimes must include a MIME type Annatto supports"
    )
  }

  // Rockspec-0.2 — the same text under another name is left as plain text
  test("Rockspec-0.2 plain text without the extension is not massaged") {
    val mimes = mimesFor(rockspec, "notes.txt")
    assert(!mimes.contains("text/x-lua"), s"got $mimes")
  }

  // Rockspec-0.3 — Annatto reads the massaged rockspec as a LuaRocks package
  test("Rockspec-0.3 Annatto reads a .rockspec") {
    val dir = Files.createTempDirectory("rockspec")
    val file = dir.resolve("fun-0.1.3-1.rockspec")
    try {
      Files.write(file, rockspec)
      val pkg = LanguagePackageReader.read(file)
      try {
        assertEquals(pkg.ecosystem(), Ecosystem.LUAROCKS)
        assertEquals(pkg.name(), "fun")
        assertEquals(pkg.version(), "0.1.3-1")
      } finally pkg.close()
    } finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
    }
  }
}
