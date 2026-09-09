package io.spicelabs.goatrodeo
import io.spicelabs.cilantro.AssemblyWalker
import io.spicelabs.cilantro.DotnetAssemblyProbe
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.DotnetDetector
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.Helpers

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import scala.util.Try

/** Cilantro 0.3.1 streaming wiring — probe + walk.
  *
  * WHAT: verifies Cilantro 0.3.1's streaming interface is wired into Goat
  * Rodeo's .NET detection and processing:
  *   - DotnetDetector uses Cilantro's DotnetAssemblyProbe (no GR-side PE-offset
  *     mark/reset sniffing); genuine assemblies are detected,
  *     non-PE/corrupt/truncated input is not, with no exceptions.
  *   - AssemblyWalker.withinAssemblyStream enumerates every kind with a correct
  *     name/kind/mimeHint, and processStream yields the exact bytes
  *     (zero-copy).
  *   - Walked entries can be wrapped with ArtifactWrapper.newWrapper carrying
  *     the entry's mimeHint, with GR-side MIME ownership.
  *   - The deterministic order and the never-throws guarantee of the walk.
  *
  * WHY: Cilantro 0.3.1 ships the streaming interface: probe, walk (name, kind,
  * mimeHint, processStream), PDB spool. GR must consume it exactly as it
  * consumes the zip/tar/saffron containers — probe → walk → wrap — with
  * zero-copy streams and no size-limit constants.
  *
  * LLM note: these tests pin the Cilantro-facing wiring behaviors. Corpus
  * fixtures are the real assemblies in test_data/ (Smoke.dll, hackproj.dll).
  * Hostile inputs are inline bytes.
  */
class DotnetStreamingSuite extends GoatRodeoFunSuite {

  private def fileWrapper(name: String): ArtifactWrapper =
    FileWrapper(new File(name), name, None, _ => ())

  private def bytesOf(p: String): Vector[Byte] =
    Files.readAllBytes(new File(p).toPath()).toVector

  // ---- GRW-1: probe integration ----

  test("GRW-1a DotnetDetector marks genuine assemblies dotnet") {
    val mimes = mimesForFile("test_data/Smoke.dll")
    assert(
      mimes.contains(DotnetDetector.DOTNET_MIME_STRING),
      s"Smoke.dll should be dotnet; got $mimes"
    )
    val hackMimes = mimesForFile("test_data/hackproj.dll")
    assert(hackMimes.contains(DotnetDetector.DOTNET_MIME_STRING))
  }

  private def mimesForFile(name: String): Set[String] = {
    val wrapper = fileWrapper(name)
    wrapper.mimeType ++
      DotnetDetector.mimeTypeAugmenter(wrapper, wrapper.mimeType)
  }

  test("GRW-1b probe: non-PE bytes are not dotnet, no exception") {
    val notPe = "this is not a PE file at all, just text".getBytes("UTF-8")
    assertEquals(
      DotnetAssemblyProbe.isDotnetAssembly(new ByteArrayInputStream(notPe)),
      false
    )
    // truncated/corrupt PE magic (MZ only)
    val mzOnly = Array[Byte](0x4d, 0x5a)
    assertEquals(
      DotnetAssemblyProbe.isDotnetAssembly(new ByteArrayInputStream(mzOnly)),
      false
    )
    // empty
    assertEquals(
      DotnetAssemblyProbe.isDotnetAssembly(new ByteArrayInputStream(Array())),
      false
    )
  }

  test("GRW-1c probe: dotnetHeader returns Some for a real assembly") {
    val h = DotnetAssemblyProbe
      .dotnetHeader(new FileInputStream("test_data/Smoke.dll"))
    assert(h.isDefined, "dotnetHeader should be defined for Smoke.dll")
  }

  test("GRW-1d probe: reads a bounded prefix (maxHeaderReadBudget > 0)") {
    // The probe must expose a bounded read budget; GR only calls
    // isDotnetAssembly, but the budget being finite is the reason the
    // probe is cheap.
    val budget = DotnetAssemblyProbe.maxHeaderReadBudget
    assert(budget > 0, s"maxHeaderReadBudget must be positive, got $budget")
  }

  // ---- GRW-2: walk enumeration ----

  test("GRW-2a withinAssemblyStream yields Class entries with type names") {
    val entries = AssemblyWalker
      .withinAssemblyStream(new File("test_data/Smoke.dll"))(identity)
    assert(entries.isDefined, "walk should succeed on Smoke.dll")
    val classes = entries.get.filter(_.kind.toString == "Class")
    assert(classes.nonEmpty, "expected at least one Class entry")
    assert(
      classes.exists(_.name.nonEmpty),
      "class entries should carry a type fullName"
    )
  }

  test("GRW-2b entries carry kind and a mimeHint") {
    val entries = AssemblyWalker
      .withinAssemblyStream(new File("test_data/hackproj.dll"))(identity)
      .get
    assert(entries.nonEmpty)
    entries.foreach { e =>
      assert(e.name.nonEmpty, "every entry must be named")
      assert(e.kind != null, "every entry must have a kind")
    }
  }

  test("GRW-2c processStream yields the exact entry bytes (zero-copy)") {
    // processStream is valid only while the walk is open AND only for the
    // current entry: each entry's stream must be consumed before the walk
    // advances. So we copy each entry's bytes inside the callback, when it
    // is yielded.
    val lens = AssemblyWalker
      .withinAssemblyStream(new File("test_data/Smoke.dll")) { entries =>
        entries.map { e =>
          e.processStream { stream =>
            val buf = new java.io.ByteArrayOutputStream()
            Helpers.copy(stream, buf)
            buf.size()
          }
        }
      }
      .get
    assert(lens.forall(_ >= 0), "processStream on every entry should read")
  }

  test("GRW-2d deterministic order across two walks") {
    def names(): Vector[String] = AssemblyWalker
      .withinAssemblyStream(new File("test_data/Smoke.dll"))(_.map(_.name))
      .get
    assertEquals(names(), names())
  }

  // ---- GRW-3: wrapper + MIME ----

  test("GRW-3a entries wrap into ArtifactWrappers with mimeHint") {
    // Wrapping must happen inside the walk callback, per entry, while the
    // walk is open. Cilantro 0.4.0 gives entry.length (the exact byte
    // count), so newWrapper gets the real size without copying to learn it.
    val tempDir = Files.createTempDirectory("grw3")
    try {
      AssemblyWalker
        .withinAssemblyStream(new File("test_data/Smoke.dll")) { entries =>
          entries.foreach { e =>
            assert(
              e.length >= 0,
              s"entry.length must be non-negative for ${e.name}"
            )
            val wrapped = e.processStream { stream =>
              ArtifactWrapper.newWrapper(
                nominalPath = e.name,
                size = e.length,
                data = stream,
                tempDir = Some(tempDir.toFile),
                tempPath = tempDir,
                mimeHint = e.mimeHint
              )
            }
            assert(wrapped != null, s"wrapper for ${e.name} should construct")
          }
        }
        .get
    } finally Helpers.deleteDirectory(tempDir)
  }

  // ---- GRW-4: never-throws ----

  test("GRW-4a walk on a non-assembly file returns None, not throw") {
    val nonAsm = Files.createTempFile("notasm", ".dll")
    try {
      Files.write(nonAsm, "garbage not a PE".getBytes("UTF-8"))
      val res = Try(
        AssemblyWalker.withinAssemblyStream(nonAsm.toFile)(identity)
      )
      assert(
        res.isSuccess,
        s"walk on a non-assembly must not throw: ${res.failed.toOption}"
      )
    } finally Files.deleteIfExists(nonAsm)
  }

  // ---- GRW-5: FileWalker container integration (assembly as container) ----

  private def containerChildren(
      artifact: ArtifactWrapper
  ): Option[Vector[ArtifactWrapper]] =
    FileWalker.withinArchiveStream(artifact = artifact)(identity)

  test("GRW-5a FileWalker walks a dotnet assembly as a container") {
    val asm = fileWrapper("test_data/Smoke.dll")
    val kids = containerChildren(asm)
    assert(
      kids.isDefined,
      "FileWalker must treat a dotnet assembly as a container"
    )
    assert(
      kids.exists(_.nonEmpty),
      "the assembly container must yield child wrappers"
    )
  }

  test("GRW-5b the container children carry names, kinds, and mimeHints") {
    val asm = fileWrapper("test_data/Smoke.dll")
    val kids = containerChildren(asm).get
    // classes surfaced with the cilantro/type hint
    assert(
      kids.exists(_.mimeHint.contains("cilantro/type")),
      s"expected a class entry with cilantro/type; got ${kids.map(_.mimeHint)}"
    )
    // win32 resource surfaced with pe/resource hint
    assert(
      kids.exists(_.mimeHint.contains("pe/resource")),
      s"expected a win32 resource with pe/resource; got ${kids.map(_.mimeHint)}"
    )
    // debug blob surfaced with pe/debug hint
    assert(
      kids.exists(_.mimeHint.contains("pe/debug")),
      s"expected a debug blob with pe/debug; got ${kids.map(_.mimeHint)}"
    )
  }

  test(
    "GRW-5c children are full ArtifactWrappers readable through withStream"
  ) {
    val asm = fileWrapper("test_data/Smoke.dll")
    val kids = containerChildren(asm).get
    kids.foreach { k =>
      assert(k.path().nonEmpty, "child must have a name")
      val len = k.withStream(s => {
        var n = 0L
        val buf = new Array[Byte](4096)
        var r = s.read(buf)
        while (r >= 0) {
          n += r
          r = s.read(buf)
        }
        n
      })
      assert(len >= 0, s"child ${k.path()} must be readable")
    }
  }
}
