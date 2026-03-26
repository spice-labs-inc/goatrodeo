/* Copyright 2025-2026 Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.IncludeExclude
import io.spicelabs.goatrodeo.util.StaticMetadata
import org.json4s.*

import java.io.File
import io.spicelabs.goatrodeo.omnibor.ToProcess
import scala.collection.immutable.TreeSet
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import scala.collection.immutable.HashSet

object MetadataSuite {
  val failSop = TreeSet(StringOrPair("fail"))

}
class MetadataSuite extends munit.FunSuite {
  test("Metadata collections works") {
    if (StaticMetadata.hasSyft) {
      val file = File("test_data/jar_test/slf4j-simple-1.6.1.jar")
      val fileWrapper =
        FileWrapper(file, "slf4j-simple-1.6.1.jar", None, f => ())
      val runner =
        StaticMetadata.runStaticMetadataGather(
          fileWrapper,
          file.toPath(),
          IncludeExclude()
        )
      val (str, json) = runner.get.runForMillis(100000).get

      assert(str.length() > 400, s"Metadata answer too small ${str}")
    } else {
      assert(true)
    }
  }

  test("Metadata collections works on nested stuff") {
    if (StaticMetadata.hasSyft) {
      val file = File("test_data/nested.tar")
      val fileWrapper =
        FileWrapper(file, "slf4j-simple-1.6.1.jar", None, f => ())
      val runner =
        StaticMetadata.runStaticMetadataGather(
          fileWrapper,
          file.toPath(),
          IncludeExclude()
        )
      val (str, json) = runner.get.runForMillis(100000).get

      // val artifacts =

      val purls = for {
        case JArray(artifacts) <- json \ "artifacts"
        artifact <- artifacts
        case JString(purl) <- artifact \ "purl"
      } yield purl

      assert(
        purls == List(
          "pkg:maven/org.apache.logging.log4j/log4j-core@2.22.1",
          "pkg:deb/tk8.6@8.6.14-1build1?arch=amd64"
        ),
        f"expected to get proper purls, got ${purls}"
      )
    } else {
      assert(true)
    }
  }

  test("deb metadata") {
    val store = getStore("debwithmetadata.deb")

    val metadata = assertPurlsAndMainItem(
      store,
      "pkg:deb/not-a-real-package@1.2.3",
      "gitoid:blob:sha256:9ae1be82894af5681fcd9947792bc9e8e5dcea5f81b848dc5a0068d50b93ac51"
    )
    val extra = metadata.extra

    assertPresent(
      metadata,
      Seq(
        "Baharat:Arch",
        "Baharat:Installed_size",
        "Baharat:Maintainer",
        "Baharat:Provides",
        "Baharat:Summary",
        "Dependencies"
      )
    )

    val desc = extra.getOrElse("Description", MetadataSuite.failSop)
    val name = extra.getOrElse("Name", MetadataSuite.failSop)
    val version = extra.getOrElse("Version", MetadataSuite.failSop)

    assertContents(desc, "This is a basic description of this package")
    assertContents(name, "not-a-real-package")
    assertContents(version, "1.2.3")
  }

  test("apk-tiny1") {
    val store = getStore("acf-squid-0.11.0-r4.apk")
    val metadata = assertPurlsAndMainItem(
      store,
      "pkg:apk/acf-squid@0.11.0-r4",
      "gitoid:blob:sha256:5781f775b64f2ab814668b201ee92bc5894188e991fda98dd2257775e01ad07a"
    )
    val extra = metadata.extra

    assertPresent(
      metadata,
      Seq(
        "Baharat:Arch",
        "Baharat:Installed_size",
        "Baharat:Maintainer",
        "Baharat:Provides",
        "Baharat:Release",
        "Baharat:Summary",
        "Dependencies"
      )
    )

    val desc = extra.getOrElse("Description", MetadataSuite.failSop)
    val lisc = extra.getOrElse("License", MetadataSuite.failSop)
    val name = extra.getOrElse("Name", MetadataSuite.failSop)
    val date = extra.getOrElse("PublicationDate", MetadataSuite.failSop)
    val url = extra.getOrElse("Url", MetadataSuite.failSop)
    val vers = extra.getOrElse("Version", MetadataSuite.failSop)

    assertContents(desc, "Web-based system administration interface for squid")
    assertContents(lisc, "GPL-2.0-only")
    assertContents(name, "acf-squid")
    assertContents(date, "2021-05-15T14:07:53Z")
    assertContents(url, "https://gitlab.alpinelinux.org/acf/acf-squid")
    assertContents(vers, "0.11.0-r4")
  }

  test("apk-tiny2") {
    val store = getStore("axel-doc-2.17.14-r1.apk")
    val metadata = assertPurlsAndMainItem(
      store,
      "pkg:apk/axel-doc@2.17.14-r1",
      "gitoid:blob:sha256:b345b85b60077889633adac722c369396b7d7ba803c97e0546eb108c66017b70"
    )
    val extra = metadata.extra
    val keys = extra.keySet.toArray

    assertPresent(
      metadata,
      Seq(
        "Baharat:Arch",
        "Baharat:Installed_size",
        "Baharat:Maintainer",
        "Baharat:Provides",
        "Baharat:Release",
        "Baharat:Summary",
        "Dependencies"
      )
    )

    val arch = extra.getOrElse("Baharat:Arch", MetadataSuite.failSop)
    val desc = extra.getOrElse("Description", MetadataSuite.failSop)
    val lisc = extra.getOrElse("License", MetadataSuite.failSop)
    val name = extra.getOrElse("Name", MetadataSuite.failSop)
    val date = extra.getOrElse("PublicationDate", MetadataSuite.failSop)
    val url = extra.getOrElse("Url", MetadataSuite.failSop)
    val vers = extra.getOrElse("Version", MetadataSuite.failSop)

    assertContents(
      desc,
      "A multiple-connection concurrent downloader (documentation)"
    )
    assertContents(lisc, "GPL-2.0-or-later WITH OpenSSL-Exception")
    assertContents(name, "axel-doc")
    assertContents(date, "2025-05-15T10:29:07Z")
    assertContents(url, "https://github.com/axel-download-accelerator/axel")
    assertContents(vers, "2.17.14-r1")
  }

  test("rpm-smallish") {
    val store = getStore("busybox-1.37.0-160099.8.2.aarch64.rpm")
    val metadata = assertPurlsAndMainItem(
      store,
      "pkg:rpm/busybox@1.37.0-160099.8.2?arch=aarch64",
      "gitoid:blob:sha256:7a73dfe7ec1f3328ee9a4fecef20285cb31eaee3fff3de1d6735b6cfc738deb1"
    )
    val extra = metadata.extra

    assertPresent(
      metadata,
      Seq(
        "Baharat:Arch",
        "Baharat:Group",
        "Baharat:Installed_size",
        "Baharat:Maintainer",
        "Baharat:Provides",
        "Baharat:Release",
        "Baharat:Summary",
        "Dependencies"
      )
    )

    val desc = extra.getOrElse("Description", MetadataSuite.failSop)
    val lisc = extra.getOrElse("License", MetadataSuite.failSop)
    val name = extra.getOrElse("Name", MetadataSuite.failSop)
    val date = extra.getOrElse("PublicationDate", MetadataSuite.failSop)
    val publ = extra.getOrElse("Publisher", MetadataSuite.failSop)
    val url = extra.getOrElse("Url", MetadataSuite.failSop)
    val vers = extra.getOrElse("Version", MetadataSuite.failSop)

    assertContents(
      desc,
      """BusyBox combines tiny versions of many common UNIX utilities into a
single executable. It provides minimalist replacements for utilities
usually found in fileutils, shellutils, findutils, textutils, grep,
gzip, tar, and more. BusyBox provides a fairly complete POSIX
environment for small or embedded systems. The utilities in BusyBox
generally have fewer options than their GNU cousins. The options that
are included provide the expected functionality and behave much like
their GNU counterparts.
BusyBox is for emergency and special use cases. Replacing the standard
tools in a system is not supported. Some tools don't work out of the
box but need special configuration, like udhcpc, the dhcp client."""
    )
    assertContents(lisc, "GPL-2.0-or-later")
    assertContents(name, "busybox")
    assertContents(date, "2026-02-13T13:44:52Z")
    assertContents(publ, "SUSE LLC <https://www.suse.com/>")
    assertContents(url, "https://www.busybox.net/")
    assertContents(vers, "1.37.0")

    var gitoids = store.gitoidKeys()

    var expectedFiles = HashSet(
      "usr/share/licenses/busybox/LICENSE",
      "busybox-1.37.0-160099.8.2.aarch64.rpm",
      "usr/share/man/man1/busybox.1.gz",
      "pkg:rpm/busybox@1.37.0-160099.8.2?arch=aarch64",
      "usr/bin/busybox",
      "usr/share/doc/packages/busybox/mdev.txt",
      "etc/man.conf",
      "usr/bin/busybox.install",
      "usr/share/busybox/busybox.links"
    )

    var meta = gitoids
      .map(oid => {
        store.read(oid)
      })
      .flatten
      .map(item => item.body)
      .flatten
      .collect { case meta: ItemMetaData =>
        meta
      }
      .map(meta => meta.fileNames)
      .flatten
      .toArray
    assertEquals(meta.length, 9)
    meta.foreach(fileName => {
      assert(
        expectedFiles.contains(fileName),
        s"failed to find file $fileName in expected"
      )
    })
  }

  test("pacman-small") {
    val store = getStore("cpufreqctl-8-1-x86_64.pkg.tar.zst")
    val metadata = assertPurlsAndMainItem(
      store,
      "pkg:alpm/cpufreqctl@8-1?arch=x86_64",
      "gitoid:blob:sha256:1709591f89ea5594900f3be2f0ec2bb3848783953d0f3ce558825f74cc56c31b"
    )
    val extra = metadata.extra

    assertPresent(
      metadata,
      Seq(
        "Baharat:Arch",
        "Baharat:Installed_size",
        "Baharat:Maintainer",
        "Baharat:Provides",
        "Baharat:Summary",
        "Dependencies"
      )
    )

    val desc = extra.getOrElse("Description", MetadataSuite.failSop)
    val lisc = extra.getOrElse("License", MetadataSuite.failSop)
    val name = extra.getOrElse("Name", MetadataSuite.failSop)
    val date = extra.getOrElse("PublicationDate", MetadataSuite.failSop)
    val url = extra.getOrElse("Url", MetadataSuite.failSop)
    val vers = extra.getOrElse("Version", MetadataSuite.failSop)

    assertContents(
      desc,
      """A intel_pstate CPU freq controller for regular user (extracted from extension 'CPU Power Manager for Gnome')"""
    )
    assertContents(lisc, "GPL")
    assertContents(name, "cpufreqctl")
    assertContents(date, "2021-11-09T03:39:05Z")
    assertContents(url, "https://github.com/martin31821/cpupower")
    assertContents(vers, "8-1")
  }

  test("other-deb") {
    val store = getStore("lxterminal_0.4.0-2build2_amd64.deb")
    val metadata = assertPurlsAndMainItem(
      store,
      "pkg:deb/lxterminal@0.4.0-2build2?arch=amd64",
      "gitoid:blob:sha256:3d442511c5eedcdebacc42c8e9eeb654fcb5ea531b398c3837dd4b0c2ec2014d"
    )
    val extra = metadata.extra

    assertPresent(
      metadata,
      Seq(
        "Baharat:Arch",
        "Baharat:Group",
        "Baharat:Installed_size",
        "Baharat:Maintainer",
        "Baharat:Provides",
        "Baharat:Summary",
        "Dependencies"
      )
    )

    val desc = extra.getOrElse("Description", MetadataSuite.failSop)
    val name = extra.getOrElse("Name", MetadataSuite.failSop)
    val url = extra.getOrElse("Url", MetadataSuite.failSop)
    val vers = extra.getOrElse("Version", MetadataSuite.failSop)

    assertContents(
      desc,
      """LXDE terminal emulator
LXTerminal is a VTE-based terminal emulator for the Lightweight X11 Desktop
Environment (LXDE).


It supports multiple tabs and has only minimal dependencies thus being
completely desktop-independent. In order to reduce memory usage and increase
the performance, all instances of the terminal are sharing a single process."""
    )
    assertContents(name, "lxterminal")
    assertContents(url, "http://www.lxde.org/")
    assertContents(vers, "0.4.0-2build2")
  }

  test("pacman-moderate") {
    val store = getStore("paru-1.11.0-1-x86_64.pkg.tar.zst")
    val metadata = assertPurlsAndMainItem(
      store,
      "pkg:alpm/paru@1.11.0-1?arch=x86_64",
      "gitoid:blob:sha256:ce0fd905ba8bb4886fc67e573a07540afa17752c22dd12bf8cd2317250e76fdb"
    )
    val extra = metadata.extra
    var keys = extra.keySet.toArray

    assertPresent(
      metadata,
      Seq(
        "Baharat:Arch",
        "Baharat:Installed_size",
        "Baharat:Maintainer",
        "Baharat:Provides",
        "Baharat:Summary",
        "Dependencies"
      )
    )

    val desc = extra.getOrElse("Description", MetadataSuite.failSop)
    val name = extra.getOrElse("Name", MetadataSuite.failSop)
    val date = extra.getOrElse("PublicationDate", MetadataSuite.failSop)
    val url = extra.getOrElse("Url", MetadataSuite.failSop)
    val vers = extra.getOrElse("Version", MetadataSuite.failSop)

    assertContents(desc, "Feature packed AUR helper")
    assertContents(name, "paru")
    assertContents(date, "2022-06-28T01:29:08Z")
    assertContents(url, "https://github.com/morganamilo/paru")
    assertContents(vers, "1.11.0-1")
  }

  def assertPresent(metadata: ItemMetaData, keys: Seq[String]): Unit = {
    for key <- keys do {
      val value = metadata.extra.getOrElse(key, MetadataSuite.failSop)
      assertNotEquals(
        value,
        MetadataSuite.failSop,
        s"Looking for key $key, no value found"
      )
    }
  }

  def assertContents(ts: TreeSet[StringOrPair], expected: String): Unit = {
    val arr = ts.toArray
    assertEquals(arr.length, 1)
    val sop = arr(0)
    assertEquals(sop.value, expected)
  }

  def assertPurlsAndMainItem(
      store: Storage,
      expectedPurl: String,
      mainItemKey: String
  ): ItemMetaData = {
    val purls = store.purls()
    assertEquals(TreeSet(expectedPurl), purls)
    val mainItemOpt = store.read(mainItemKey)
    assert(mainItemOpt.isDefined)
    val metadataOpt = mainItemOpt.get.bodyAsItemMetaData
    assert(metadataOpt.isDefined)
    metadataOpt.get
  }

  def getStore(filename: String): Storage = {
    val file = File(s"test_data/$filename")
    val nested = FileWrapper(file, filename, None, f => ())
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())
    store1
  }
}
