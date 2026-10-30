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

package io.spicelabs.goatrodeo.util

/** The MIME families every consumer uses to decide, cheaply, that it cannot
  * handle an artifact.
  *
  * The augmenters, the strategies and the file walker all ask the same two
  * questions about the same families: "is something here known to be impossible
  * for me?" and "is anything here the sort of thing I exist for?". Those
  * answers have to agree — a family the walker refuses to expand must not be
  * probed by the augmenter that exists to serve it — so the family lists and
  * the matching live here, once, and every consumer composes them.
  *
  * A change to a family here changes every consumer at the same time. That is
  * the point: the Saffron augmenter and the file walker hold the same view of
  * what a container is, because they are paired, and the container strategies
  * hold the same view of what an archive is.
  *
  * Matching: an entry ending in an asterisk is a prefix (so the text wildcard
  * matches `text/plain`, and the Saffron wildcard matches every Saffron kind),
  * an entry ending in a slash is a prefix, and anything else matches exactly.
  */
object MimeFamilies {

  /** The two families Tika assigns by extension alone, where the bytes
    * contradict the name: a `.vhd` virtual disk arrives as `text/x-vhdl` and a
    * `.raw` disk as `image/x-raw-*`. Neither is ever a contradiction for
    * container or disk handling, whatever its family says.
    */
  val misreadTypes: Set[String] = Set("text/x-vhdl")

  /** Family wildcards for the misreads, so a consumer can admit them
    * explicitly.
    */
  val misreads: Seq[String] = Seq("text/x-vhdl", "image/x-raw-*")

  /** Text. A text artifact is not an archive, not a disk and not a guest image.
    */
  val text: Seq[String] = Seq("text/*")

  /** Media and fonts: no archive, no disk, no guest image lives in these. */
  val media: Seq[String] =
    Seq("image/*", "audio/*", "video/*", "font/*")

  /** Class files: a constant pool is not an archive entry, a disk or a
    * container.
    */
  val classFiles: Seq[String] = Seq("application/java-vm")

  /** Structured documents that are not containers: a parser owns them. */
  val documents: Seq[String] = Seq(
    "application/xml",
    "application/json",
    "application/pdf",
    "application/x-sqlite3"
  )

  /** Compressed streams and archive formats the walker expands. */
  val archives: Seq[String] = Seq(
    "application/zip",
    "application/x-zip-compressed",
    "application/java-archive",
    "application/vnd.android.package-archive",
    "application/epub+zip",
    "application/vnd.openxmlformats-officedocument.*",
    "application/vnd.oasis.opendocument.*",
    "application/x-debian-package",
    "application/x-rpm",
    "application/x-archive",
    "application/x-gtar",
    "application/x-tar",
    "application/gzip",
    "application/zstd",
    "application/x-xz",
    "application/x-bzip2",
    "application/x-7z-compressed"
  )

  /** Virtual disk and optical image formats. */
  val diskImages: Seq[String] = Seq(
    "application/x-qcow2",
    "application/x-vmdk",
    "application/x-vhd",
    "application/x-vhdx",
    "application/x-vdi",
    "application/x-ami",
    "application/x-saffron-*",
    "application/x-iso9660-image",
    "application/x-apple-diskimage"
  )

  /** Executable formats that are containers of guest content (ELF, PE). */
  val binaries: Seq[String] = Seq(
    "application/x-executable",
    "application/x-sharedlib",
    "application/x-pie-executable",
    "application/x-object",
    "application/x-elf",
    "application/x-msdownload",
    "application/x-msdownload; format=pe32",
    "application/x-msdownload; format=pe64"
  )

  /** "Tika cannot tell": the only honest answer for the long tail of artifacts.
    */
  val unknown: Seq[String] = Seq("application/octet-stream")

  /** Sidecars that carry a wrapped payload. */
  val wrappers: Seq[String] = Seq("multipart/appledouble")

  // ---------------------------------------------------------------------------
  // The composed pairs
  // ---------------------------------------------------------------------------

  /** Certificate, key and keystore families Tika assigns by name or magic. */
  val certificateFamilies: Seq[String] = Seq(
    "application/x-x509-*",
    "application/pkix-*",
    "application/pkcs7-*",
    "application/pkcs10",
    "application/pkcs8",
    "application/x-pkcs7-*",
    "application/x-pkcs12",
    "application/pgp-*",
    "application/x-pem-*",
    "application/pem-*",
    "application/x-java-keystore",
    "application/x-java-jce-keystore",
    "application/x-ssh-*"
  )

  /** Every claim that says "this artifact is a container". No content consumer
    * may claim such an artifact: the container is expanded into children, and a
    * child carries the content claim.
    */
  val containerClaims: Seq[String] =
    // Archives and class files only. A *disk or guest* claim is not here: those
    // come from another augmenter's probe, they accumulate with the artifact's own
    // claims, and they do not make a shared library or a config file impossible --
    // the failure that proved it was an ELF `.so` tagged as a Saffron disk and then
    // refused by EmbeddedCertificates, losing a CBOM component.
    archives ++ classFiles

  /** Suffixes that name a disk or guest image, whatever Tika decided from the
    * bytes. This is the algorithmic half of the fast reject: a `.vhd` arrives
    * as `text/x-vhdl` (Tika reads the extension as VHDL), a `.raw` as
    * `image/x-raw-*`, and a name is cheap and exact where a guess is not.
    */
  val diskNameSuffixes: Seq[String] = Seq(
    ".vhd",
    ".vhdx",
    ".vdi",
    ".vmdk",
    ".qcow2",
    ".raw",
    ".img",
    ".iso",
    ".dmg",
    ".ami",
    ".ova",
    ".wim"
  )

  /** Does the artifact's name declare a disk or guest image? */
  def nameSuggestsDisk(name: String): Boolean = {
    val lower = name.toLowerCase(java.util.Locale.ROOT)
    diskNameSuffixes.exists(lower.endsWith)
  }

  /** The fast reject for disks and guest containers: the MIME set OR the name.
    *
    * A name that declares a disk admits the artifact even when Tika's answer
    * puts it in a family that is otherwise refused -- that is the only way a
    * `.vhd` typed as text can reach the probe without admitting every text
    * file.
    */
  def couldBeDiskOrGuest(mimes: Set[String], name: String): Boolean =
    nameSuggestsDisk(name) || couldBe(mimes, couldBeDiskOrGuest)

  /** The negative half for disks and guest containers, name-aware: a name that
    * declares a disk is never contradicted by a text or image claim.
    */
  def contradictsDiskOrGuest(mimes: Set[String], name: String): Boolean =
    !nameSuggestsDisk(name) && contradicts(mimes, cannotBeDiskOrGuest)

  // ---------------------------------------------------------------------------
  // The paired domains. Each augmenter and the consumer of its MIME type share one
  // pair, so a change to a family updates both.
  // ---------------------------------------------------------------------------

  /** Archives the walker expands: its `notArchive` question. */
  val couldBeArchive: Seq[String] =
    archives ++ diskImages ++ binaries ++ unknown ++ wrappers ++ misreads ++
      // ZIP files under a foreign name: Tika's own spellings for an `.ipa` and for
      // an OOXML container. Both are walked by the archive path.
      Seq("application/x-itunes-ipa", "application/x-tika-ooxml")
  val cannotBeArchive: Seq[String] = text ++ media ++ classFiles ++ documents

  /** Compressed streams the uncompress path opens. A ZIP or a JAR is an
    * archive, not a stream, so the ZIP family is absent here on purpose.
    */
  val couldBeCompressed: Seq[String] = Seq(
    "application/gzip",
    "application/zstd",
    "application/x-xz",
    "application/x-bzip2",
    "application/x-compress",
    "application/x-debian-package",
    "application/x-rpm",
    "application/x-gtar",
    "application/x-tar",
    "application/x-archive",
    "application/x-7z-compressed",
    "application/octet-stream",
    "multipart/appledouble",
    "text/x-vhdl",
    "image/x-raw-*"
  )

  /** Disk images and guest containers: `SaffronDetector` and the walker's disk
    * handling. `application/gzip` stays admissible because a gzip may wrap a
    * raw disk.
    */
  val couldBeDiskOrGuest: Seq[String] =
    diskImages ++ binaries ++ unknown ++ wrappers ++ misreads ++
      Seq("application/gzip")
  val cannotBeDiskOrGuest: Seq[String] =
    // Everything a disk cannot be, plus the archive families that are not disks.
    // `application/gzip` and the disk images themselves are absent on purpose: a
    // gzip may wrap a raw disk, and a disk image is exactly what Saffron reads.
    text ++ media ++ classFiles ++ documents ++
      archives.filterNot(_ == "application/gzip")

  /** PE images: `DotnetDetector` and the `Dotnet` strategy. */
  val couldBePeImage: Seq[String] = Seq(
    "application/octet-stream",
    "application/x-msdownload",
    "application/x-msdownload; format=pe32",
    "application/x-msdownload; format=pe64",
    "application/vnd.microsoft.portable-executable",
    "application/x-dosexec",
    "application/x-msdos-program",
    "multipart/appledouble"
  )
  val cannotBePeImage: Seq[String] =
    // Not `containerClaims`: a disk-image claim from another augmenter does not
    // make a PE impossible, and claims accumulate. Archives are excluded because
    // they are walked into children.
    text ++ media ++ classFiles ++ documents ++ archives

  /** Binary carriers whose sections hold arbitrary bytes: `CarvedCertAugmenter`
    * and `CarvedCertificatesStrategy`.
    */
  val couldBeCarvedCarrier: Seq[String] = binaries ++ unknown ++ wrappers
  val cannotBeCarvedCarrier: Seq[String] =
    // As above: another augmenter calling an ELF carrier a raw disk must not
    // forbid carving it.
    text ++ media ++ classFiles ++ documents ++ archives

  /** Certificate material: `CryptoDetector` and the certificate strategies. */
  val couldBeCryptoMaterial: Seq[String] =
    text ++ Seq("application/xml", "application/json", "application/pdf") ++
      certificateFamilies ++ binaries ++ archives ++ unknown ++ wrappers
  val cannotBeCryptoMaterial: Seq[String] =
    media ++ classFiles ++ containerClaims

  /** Text content — PEM blocks, shadow files, TLS configs, lock files, release
    * files: `CryptoContentDetector` and every strategy that consumes its
    * claims.
    */
  val couldBeTextContent: Seq[String] =
    text ++ Seq(
      "application/xml",
      "application/json",
      "application/x-sh",
      "application/x-bat"
    ) ++
      unknown ++ wrappers
  val cannotBeTextContent: Seq[String] =
    media ++ classFiles ++ containerClaims

  /** ZIP archives: `JavaArchiveDetector`, `IpaStrategy` and `Maven`. */
  val couldBeZipArchive: Seq[String] = Seq(
    "application/octet-stream",
    "application/zip",
    "application/x-zip-compressed",
    "application/java-archive",
    "application/vnd.android.package-archive",
    "application/epub+zip",
    "application/vnd.openxmlformats-officedocument.*",
    "application/vnd.oasis.opendocument.*",
    "application/x-itunes-ipa",
    "application/x-tika-ooxml",
    "multipart/appledouble"
  )
  val cannotBeZipArchive: Seq[String] =
    text ++ media ++ classFiles ++ documents ++ binaries ++
      Seq(
        "application/x-debian-package",
        "application/x-rpm",
        "application/x-archive",
        "application/x-iso9660-image"
      )

  /** Text configuration syntax: `JavaSecurityDetector`, `OpenSSLConfigDetector`
    * and the two strategies that consume their claims.
    */
  val couldBeTextConfig: Seq[String] = text ++ unknown ++ wrappers
  val cannotBeTextConfig: Seq[String] =
    media ++ classFiles ++ documents ++ containerClaims ++ binaries

  /** JSON-like text: `JsonLikeDetector`. Nothing downstream consumes the claim
    * beyond the MIME set, so the pair is used only by the augmenter.
    */
  val couldBeJsonLike: Seq[String] = text ++ unknown
  val cannotBeJsonLike: Seq[String] =
    media ++ classFiles ++ containerClaims ++
      Seq("application/pdf", "application/x-sqlite3")

  /** Older name kept for the walker: the archive, disk and guest questions were
    * once separate predicates.
    */
  val cannotBeContainer: Seq[String] = cannotBeArchive

  // ---------------------------------------------------------------------------
  // Matching
  // ---------------------------------------------------------------------------

  private def isMisread(m: String): Boolean =
    misreadTypes.contains(m) || m.startsWith("image/x-raw-")

  /** Does this single MIME type match one of the entries? An entry ending in
    * `*` or `/` is a prefix; anything else is exact.
    */
  private[goatrodeo] def matchesAny(
      m: String,
      families: Seq[String]
  ): Boolean = {
    var i = 0
    var hit = false
    while (i < families.length && !hit) {
      val f = families(i)
      hit =
        if (f.endsWith("*")) m.startsWith(f.substring(0, f.length - 1))
        else if (f.endsWith("/")) m.startsWith(f)
        else m == f
      i += 1
    }
    hit
  }

  /** Is this artifact, by some MIME in its set, known to be impossible for the
    * given families? The misreads are exempt before the test, which is what
    * keeps a `.vhd` that Tika calls `text/x-vhdl` from being refused as prose.
    */
  def contradicts(mimes: Set[String], families: Seq[String]): Boolean =
    mimes.exists(m => !isMisread(m) && matchesAny(m, families))

  /** Does anything in the MIME set say this artifact could be one of the given
    * families?
    */
  def couldBe(mimes: Set[String], families: Seq[String]): Boolean =
    mimes.exists(m => matchesAny(m, families))
}
