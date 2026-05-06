/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package strategies

import munit.FunSuite

import java.io.File
import java.nio.file.Files

/** Unit tests for [[CertificatesFixtureInventoryImpl]] — pairing, orphan
  * detection, category extraction, infrastructure-file exclusion.
  *
  * Traces to: `certificates-strategy/phase-0-corpus.md` sub-goals #2 (200+
  * fixtures) and #3 (harness). Tests run against a scratch temp directory
  * populated programmatically so the production corpus root is untouched.
  *
  * ## LLM-friendly summary of each test
  *
  *   - "pairs fixture and sidecar sharing a stem" — the canonical happy path
  *     (`foo.pem` + `foo.pem.expected.json`).
  *   - "handles fixtures without file extensions" —
  *     `openssh-ed25519-unencrypted` +
  *     `openssh-ed25519-unencrypted.expected.json`.
  *   - "orphan sidecar detected" — sidecar with no matching fixture.
  *   - "orphan fixture detected" — fixture with no matching sidecar.
  *   - "excludes SOURCES.md / README.md / .gitkeep / generate.sh from fixtures"
  *     — infrastructure-file filtering.
  *   - "excludes files under tools/ from fixtures" — infrastructure-dir
  *     filtering.
  *   - "category is the immediate subdirectory" — `pairs.category` equals the
  *     directory name, not the full path.
  *   - "countByCategory aggregates correctly" — multi-fixture, multi- category
  *     scenario.
  *   - "empty root reports zero fixtures" — boundary.
  *   - "nonexistent root reports zero fixtures" — boundary.
  */
class CertificatesFixtureInventoryTests extends FunSuite {

  private def tmpCorpus(body: File => Unit): (File, () => Unit) = {
    val root = Files.createTempDirectory("cert-fixture-inv-").toFile
    body(root)
    (root, () => deleteRecursively(root))
  }

  private def deleteRecursively(f: File): Unit = {
    if (f.isDirectory()) {
      Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    }
    f.delete()
    ()
  }

  private def mk(parent: File, name: String, body: String = ""): File = {
    val f = new File(parent, name)
    f.getParentFile.mkdirs()
    Files.writeString(f.toPath, body)
    f
  }

  test("pairs fixture and sidecar sharing a stem") {
    val (root, cleanup) = tmpCorpus { r =>
      val x509 = new File(r, "x509")
      x509.mkdirs()
      mk(x509, "foo.pem", "PEM BODY")
      mk(x509, "foo.pem.expected.json", "{}")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      assertEquals(inv.pairs.size, 1)
      val pair = inv.pairs.head
      assertEquals(pair.fixture.getName, "foo.pem")
      assertEquals(pair.sidecar.getName, "foo.pem.expected.json")
      assertEquals(pair.category, "x509")
      assert(
        inv.orphanSidecars.isEmpty,
        s"orphanSidecars=${inv.orphanSidecars}"
      )
      assert(
        inv.orphanFixtures.isEmpty,
        s"orphanFixtures=${inv.orphanFixtures}"
      )
    } finally cleanup()
  }

  test("handles fixtures without file extensions") {
    val (root, cleanup) = tmpCorpus { r =>
      val ssh = new File(r, "ssh")
      ssh.mkdirs()
      mk(ssh, "openssh-ed25519-unencrypted", "KEY BODY")
      mk(ssh, "openssh-ed25519-unencrypted.expected.json", "{}")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      assertEquals(inv.pairs.size, 1)
      assertEquals(
        inv.pairs.head.fixture.getName,
        "openssh-ed25519-unencrypted"
      )
    } finally cleanup()
  }

  test("orphan sidecar is detected when no matching fixture exists") {
    val (root, cleanup) = tmpCorpus { r =>
      val x509 = new File(r, "x509")
      x509.mkdirs()
      mk(x509, "lonely.pem.expected.json", "{}")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      assertEquals(inv.pairs.size, 0)
      assertEquals(inv.orphanSidecars.size, 1)
      assertEquals(inv.orphanSidecars.head.getName, "lonely.pem.expected.json")
    } finally cleanup()
  }

  test("orphan fixture is detected when no matching sidecar exists") {
    val (root, cleanup) = tmpCorpus { r =>
      val x509 = new File(r, "x509")
      x509.mkdirs()
      mk(x509, "lonely.pem", "PEM BODY")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      assertEquals(inv.pairs.size, 0)
      assertEquals(inv.orphanFixtures.size, 1)
      assertEquals(inv.orphanFixtures.head.getName, "lonely.pem")
    } finally cleanup()
  }

  test(
    "excludes SOURCES.md, README.md, README_llm.md, .gitkeep, generate.sh from fixture candidates"
  ) {
    val (root, cleanup) = tmpCorpus { r =>
      val x509 = new File(r, "x509")
      x509.mkdirs()
      mk(x509, "SOURCES.md", "# sources")
      mk(x509, "README.md", "# readme")
      mk(x509, "README_llm.md", "# readme llm")
      mk(x509, ".gitkeep", "")
      mk(x509, "generate.sh", "#!/bin/sh")
      // One real pair so the inventory isn't completely empty
      mk(x509, "real.pem", "PEM")
      mk(x509, "real.pem.expected.json", "{}")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      assertEquals(inv.allFixtureCandidates.size, 1)
      assertEquals(inv.allFixtureCandidates.head.getName, "real.pem")
      assertEquals(inv.pairs.size, 1)
      assertEquals(inv.orphanFixtures, Vector.empty)
      assertEquals(inv.orphanSidecars, Vector.empty)
    } finally cleanup()
  }

  test("excludes files under tools/ from fixture candidates") {
    val (root, cleanup) = tmpCorpus { r =>
      val tools = new File(r, "tools")
      tools.mkdirs()
      mk(tools, "compute-expected.sh", "#!/bin/sh")
      mk(tools, "helper.sh", "#!/bin/sh")
      // A legitimate fixture in another dir
      val x509 = new File(r, "x509")
      x509.mkdirs()
      mk(x509, "real.pem", "PEM")
      mk(x509, "real.pem.expected.json", "{}")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      val candidateNames = inv.allFixtureCandidates.map(_.getName).toSet
      assert(!candidateNames.contains("compute-expected.sh"))
      assert(!candidateNames.contains("helper.sh"))
      assertEquals(inv.pairs.size, 1)
    } finally cleanup()
  }

  test("countByCategory aggregates pairs by immediate subdirectory") {
    val (root, cleanup) = tmpCorpus { r =>
      val x509 = new File(r, "x509")
      x509.mkdirs()
      mk(x509, "a.pem", "A")
      mk(x509, "a.pem.expected.json", "{}")
      mk(x509, "b.pem", "B")
      mk(x509, "b.pem.expected.json", "{}")
      val ssh = new File(r, "ssh")
      ssh.mkdirs()
      mk(ssh, "c.pub", "C")
      mk(ssh, "c.pub.expected.json", "{}")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      assertEquals(inv.totalCount, 3)
      assertEquals(inv.countByCategory, Map("x509" -> 2, "ssh" -> 1))
    } finally cleanup()
  }

  test("empty root reports zero fixtures and zero orphans") {
    val (root, cleanup) = tmpCorpus { _ => () }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      assertEquals(inv.totalCount, 0)
      assertEquals(inv.orphanSidecars, Vector.empty)
      assertEquals(inv.orphanFixtures, Vector.empty)
    } finally cleanup()
  }

  test("nonexistent root reports zero fixtures (does not throw)") {
    val root = new File("/nonexistent-path-for-testing-" + System.nanoTime())
    val inv = new CertificatesFixtureInventoryImpl(root)
    assertEquals(inv.totalCount, 0)
    assertEquals(inv.allSidecars, Vector.empty)
    assertEquals(inv.allFixtureCandidates, Vector.empty)
  }

  test(
    "sidecar in one directory does not pair with fixture in a different directory"
  ) {
    val (root, cleanup) = tmpCorpus { r =>
      val x509 = new File(r, "x509")
      x509.mkdirs()
      mk(x509, "shared.pem.expected.json", "{}")
      val ssh = new File(r, "ssh")
      ssh.mkdirs()
      mk(ssh, "shared.pem", "BODY")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      assertEquals(inv.pairs.size, 0)
      assertEquals(inv.orphanSidecars.size, 1)
      assertEquals(inv.orphanFixtures.size, 1)
    } finally cleanup()
  }

  test(
    "hidden files (starting with .) other than .gitkeep are not fixture candidates"
  ) {
    val (root, cleanup) = tmpCorpus { r =>
      val x509 = new File(r, "x509")
      x509.mkdirs()
      mk(x509, ".DS_Store", "")
      mk(x509, ".hidden.pem", "HIDDEN")
      mk(x509, "real.pem", "REAL")
      mk(x509, "real.pem.expected.json", "{}")
    }
    try {
      val inv = new CertificatesFixtureInventoryImpl(root)
      val names = inv.allFixtureCandidates.map(_.getName).toSet
      assert(!names.contains(".DS_Store"))
      assert(!names.contains(".hidden.pem"))
      assert(names.contains("real.pem"))
    } finally cleanup()
  }
}
