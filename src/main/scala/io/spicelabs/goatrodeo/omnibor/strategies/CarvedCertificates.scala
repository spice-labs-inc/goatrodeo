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

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.PurlSet
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.CarvedCertAugmenter
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?

import java.security.cert.X509Certificate
import java.util.Arrays
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects X.509 certificates carved (DER-encoded, at arbitrary offsets) out of
  * binary artifacts — firmware ELF sections, raw blobs.
  *
  * Claims on the carrier evidence the MIME pass recorded — never on a MIME type
  * the augmenter cannot honestly assert: a binary that *might* hold a
  * certificate is not a certificate. Processing performs the bounded carve
  * ([[CarvedCertAugmenter.carveCertificates]], 16 MiB, on a worker thread) and
  * emits per-cert metadata through the existing `perCertMetadata` path under
  * `Certificates:Cert:<idx>:*` keys, so the CBOM emitter produces ordinary
  * certificate components — including `KeySize` (e.g. an RSA-1024 cert surfaces
  * with KeySize 1024).
  *
  * Safety: only fully parsed certificates are emitted; no raw bytes and no
  * private key material leave the artifact.
  */
object CarvedCertificatesStrategy {

  /** The carrier records the augmenter produced for this artifact. */
  private def hasInterestingAugmentations(
      wrapper: ArtifactWrapper
  ): Vector[CarvedCertAugmenter.Carrier] = {
    wrapper.mimeTypeEvidence
      .getOrElse(CarvedCertAugmenter.CarvedMime, Vector.empty)
      .collect { case v: CarvedCertAugmenter.Carrier => v }
  }

  /** A span of the carrier that a likely-named symbol points at. */
  final case class CarvedSpan(offset: Long, length: Long)

  /** Name fragments that mark an embedded certificate, case-insensitive.
    *
    * Chosen from the conventions that actually appear in the wild: Linux kernel
    * cert insertion (`system_extra_cert`, `__cert_list_start`), `ld -r -b
    * binary` (`_binary_cacert_pem_start`), mbedTLS (`cacert_pem`,
    * `test_srv_crt`), Zephyr (`ca_certificate`), wolfSSL (`ca_cert_der`) and
    * OpenSSL/curl CA bundles (`ca_bundle`, `trust_store`, `root_ca`). Bare
    * `pem` and `der` are excluded: they match `pem_password` and `derived_*`
    * and would drag in unrelated spans.
    */
  private val CertificateNameFragments: Seq[String] = Seq(
    "cert",
    "crt",
    "x509",
    "trust",
    "anchor",
    "cabundle",
    "ca_bundle",
    "rootca",
    "root_ca"
  )

  /** A span smaller than this cannot be a certificate; a larger one is not a
    * single certificate blob.
    */
  private val MinCertificateBytes = 300
  private val MaxCertificateBytes = 64 * 1024

  /** Ceiling on any table this walks, so a corrupt header cannot ask for a big
    * allocation.
    */
  private val MaxTableBytes = 4 * 1024 * 1024

  private def nameMatches(name: String): Boolean = {
    val lower = name.toLowerCase(java.util.Locale.ROOT)
    CertificateNameFragments.exists(lower.contains)
  }

  /** Reads `length` bytes at `offset` through a fresh stream. `skip` is an
    * lseek on a file-backed artifact, so reaching an offset costs no bytes;
    * this is how the section table at the end of the file and the section data
    * before it are both reached from a stream that can only move forward.
    */
  private[goatrodeo] def readSpan(
      wrapper: ArtifactWrapper,
      span: CarvedSpan
  ): Array[Byte] =
    readAt(wrapper, span.offset, span.length.toInt)

  private def readAt(
      wrapper: ArtifactWrapper,
      offset: Long,
      length: Int
  ): Array[Byte] = {
    if (offset < 0 || length <= 0 || length > MaxTableBytes)
      Array.emptyByteArray
    else
      Try {
        wrapper.withStream { stream =>
          var remaining = offset
          var stopped = false
          while (remaining > 0 && !stopped) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) stopped = true else remaining -= skipped
          }
          val buffer = new Array[Byte](length)
          var total = 0
          var eof = false
          while (total < length && !eof) {
            val read = stream.read(buffer, total, length - total)
            if (read <= 0) eof = true else total += read
          }
          if (total == length) buffer else Arrays.copyOf(buffer, total)
        }
      }.getOrElse(Array.emptyByteArray)
  }

  private def i16(b: Array[Byte], at: Int): Int =
    if (at + 2 > b.length) 0 else (b(at) & 0xff) | ((b(at + 1) & 0xff) << 8)

  private def i32(b: Array[Byte], at: Int): Int =
    if (at + 4 > b.length) 0
    else
      (b(at) & 0xff) | ((b(at + 1) & 0xff) << 8) |
        ((b(at + 2) & 0xff) << 16) | ((b(at + 3) & 0xff) << 24)

  private def i64(b: Array[Byte], at: Int): Long =
    if (at + 8 > b.length) 0L
    else i32(b, at).toLong & 0xffffffffL | (i32(b, at + 4).toLong << 32)

  private def cString(b: Array[Byte], from: Int): String = {
    if (from < 0 || from >= b.length) ""
    else {
      var end = from
      while (end < b.length && b(end) != 0) end += 1
      new String(
        b,
        from,
        end - from,
        java.nio.charset.StandardCharsets.ISO_8859_1
      )
    }
  }

  /** The section header table, when the carrier points at one that fits inside
    * the read budget. `None` is the answer for a stripped image and for a table
    * that would cost more than it is worth.
    */
  private def sectionTable(
      wrapper: ArtifactWrapper,
      carrier: CarvedCertAugmenter.Carrier
  ): Option[Array[Byte]] = {
    val entry = carrier.sectionEntrySize
    val tableBytes = entry.toLong * carrier.sectionCount.toLong
    for {
      _ <- Option.when(carrier.tablesKnown && entry > 0)(())
      _ <- Option.when(tableBytes > 0 && tableBytes <= MaxTableBytes)(())
      table <- Option(
        readAt(wrapper, carrier.sectionTableOffset, tableBytes.toInt)
      )
        .filter(_.length == tableBytes.toInt)
    } yield table
  }

  /** Walks the ELF section and symbol tables and returns the spans that
    * likely-named, non-executable symbols point at.
    */
  private def elfSpans(
      wrapper: ArtifactWrapper,
      carrier: CarvedCertAugmenter.Carrier
  ): Vector[CarvedSpan] =
    sectionTable(wrapper, carrier) match {
      case None => Vector.empty
      case Some(sections) =>
        val entry = carrier.sectionEntrySize
        val is64 = entry == 64

        def secField(idx: Int, off64: Int, off32: Int): Long =
          if (is64) i64(sections, idx * entry + off64)
          else i32(sections, idx * entry + off32).toLong & 0xffffffffL

        def sectionType(idx: Int): Int = i32(sections, idx * entry + 4)

        // SHT_SYMTAB, the first one: a section table has at most one in practice.
        val symtab = (0 until carrier.sectionCount).find(sectionType(_) == 2)

        symtab match {
          case None => Vector.empty
          case Some(symIdx) =>
            val symOffset = secField(symIdx, 24, 16)
            val symSize = secField(symIdx, 32, 20)
            val symLink =
              if (is64) i32(sections, symIdx * entry + 40)
              else i32(sections, symIdx * entry + 24)
            val declaredEnt = secField(symIdx, 56, 36)
            val symEnt =
              if (declaredEnt > 0) declaredEnt.toInt else if (is64) 24 else 16

            val usable =
              symSize > 0 && symSize <= MaxTableBytes &&
                symLink >= 0 && symLink < carrier.sectionCount && symEnt > 0

            if (!usable) Vector.empty
            else {
              val strOffset = secField(symLink, 24, 16)
              val strSize = secField(symLink, 32, 20)
              val symBytes =
                readAt(
                  wrapper,
                  symOffset,
                  math.min(symSize, MaxTableBytes).toInt
                )
              val strBytes =
                readAt(
                  wrapper,
                  strOffset,
                  math.min(strSize, MaxTableBytes).toInt
                )

              if (symBytes.length < symEnt || strBytes.isEmpty) Vector.empty
              else {
                val out = Vector.newBuilder[CarvedSpan]
                var at = 0
                while (at + symEnt <= symBytes.length) {
                  val stName = i32(symBytes, at)
                  val stShndx =
                    if (is64) i16(symBytes, at + 6) else i16(symBytes, at + 14)
                  val stValue =
                    if (is64) i64(symBytes, at + 8)
                    else i32(symBytes, at + 4).toLong & 0xffffffffL
                  val stSize =
                    if (is64) i64(symBytes, at + 16)
                    else i32(symBytes, at + 8).toLong & 0xffffffffL
                  val name = cString(strBytes, stName)
                  val candidate =
                    stShndx > 0 && stShndx < carrier.sectionCount &&
                      stSize >= MinCertificateBytes && stSize <= MaxCertificateBytes &&
                      nameMatches(name)
                  if (candidate) {
                    val flags = secField(stShndx, 8, 8)
                    val executable = (flags & 0x4L) != 0L // SHF_EXECINSTR
                    if (!executable) {
                      val secOffset = secField(stShndx, 24, 16)
                      val secAddr = secField(stShndx, 16, 12)
                      val start = secOffset + (stValue - secAddr)
                      if (start >= 0) out += CarvedSpan(start, stSize)
                    }
                  }
                  at += symEnt
                }
                out.result().distinct
              }
            }
        }
    }

  /** Walks the PE COFF symbol table and returns the spans that likely-named
    * symbols point at. A COFF record carries no size, so the span runs from the
    * symbol's offset to the end of its section, capped at one certificate.
    */
  private def peSpans(
      wrapper: ArtifactWrapper,
      carrier: CarvedCertAugmenter.Carrier
  ): Vector[CarvedSpan] =
    sectionTable(wrapper, carrier) match {
      case None => Vector.empty
      case Some(sections) =>
        val hasSymbols =
          carrier.symbolTableOffset > 0 && carrier.symbolCount > 0
        if (!hasSymbols) Vector.empty
        else {
          val wanted =
            math
              .min(carrier.symbolCount.toLong * 18L, MaxTableBytes.toLong)
              .toInt
          val symbols = readAt(wrapper, carrier.symbolTableOffset, wanted)
          val strings = readAt(
            wrapper,
            carrier.symbolTableOffset + carrier.symbolCount.toLong * 18L,
            64 * 1024
          )
          if (symbols.length < 18) Vector.empty
          else {
            val out = Vector.newBuilder[CarvedSpan]
            var at = 0
            while (at + 18 <= symbols.length) {
              val first = symbols(at) & 0xff
              val name =
                if (first == 0) cString(symbols, at + 4)
                else if (first == '/' && (symbols(at + 1) & 0xff) != 0)
                  cString(
                    strings,
                    Try(new String(symbols, at, 8).trim.drop(1).toInt)
                      .getOrElse(0)
                  )
                else cString(symbols, at)
              val value = i32(symbols, at + 8)
              val sectionNumber = i16(symbols, at + 12)
              val aux = symbols(at + 17) & 0xff
              val candidate =
                sectionNumber > 0 && sectionNumber <= carrier.sectionCount &&
                  nameMatches(name)
              if (candidate) {
                val secAt = (sectionNumber - 1) * 40
                val virtualAddress = i32(sections, secAt + 12)
                val rawSize = i32(sections, secAt + 16)
                val rawPointer = i32(sections, secAt + 20)
                val delta = value - virtualAddress
                if (delta >= 0 && delta < rawSize) {
                  val length =
                    math.min(
                      (rawSize - delta).toLong,
                      MaxCertificateBytes.toLong
                    )
                  if (length >= MinCertificateBytes)
                    out += CarvedSpan(rawPointer.toLong + delta, length)
                }
              }
              at += 18 * (1 + aux)
            }
            out.result().distinct
          }
        }
    }

  /** The spans this carrier's symbols point at. Empty means "no name-directed
    * span", which is the case for a stripped binary and for a carrier that was
    * claimed by its first block rather than by a symbol.
    */
  private[goatrodeo] def carrierSpans(
      wrapper: ArtifactWrapper,
      carrier: CarvedCertAugmenter.Carrier
  ): Vector[CarvedSpan] = {
    if (carrier.isElf) elfSpans(wrapper, carrier)
    else if (carrier.isPe) peSpans(wrapper, carrier)
    else Vector.empty
  }

  /** Compute artifacts carrying carved certificates.
    *
    * Two ways in. A carrier whose *first block* already held a DER object with
    * an SPKI needle carries the carved MIME and is claimed outright. Otherwise
    * the carrier's own tables are walked and the artifact is claimed when a
    * symbol named like a certificate points at a plausible span — the `.data`
    * bytes are one `skip` away, so no sequential scan is needed and the "deep"
    * certificate larger than any streaming window is still reachable.
    *
    * There is deliberately no needle fallback for a stripped binary: reasonable
    * effort, not exhaustive discovery. A stripped artifact that also failed the
    * first-block test is not claimed.
    */
  def computeCarvedCertificateFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val claimed: Vector[(ArtifactWrapper, Vector[CarvedSpan])] =
      byUUID.values.toVector.flatMap { v =>
        if (v.mimeType.contains(CarvedCertAugmenter.CarvedMime))
          Some(v -> Vector.empty)
        else {
          val spans =
            hasInterestingAugmentations(v).flatMap(c => carrierSpans(v, c))
          if (spans.nonEmpty) Some(v -> spans.distinct) else None
        }
      }

    val uuids = claimed.map(_._1.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    // The evidence has served its purpose once the claims are made: the spans
    // are in hand, the carve re-reads the file, and no later stage consults a
    // Carrier. Holding it would keep a record per artifact alive for the run.
    claimed.foreach { case (artifact, _) => artifact.wipeEvidence() }

    (
      claimed.map { case (a, spans) =>
        new CarvedCertificatesToProcess(a, spans)
      },
      revisedByUUID,
      revisedByName,
      "CarvedCertificates"
    )
  }
}

class CarvedCertificatesToProcess(
    val artifact: ArtifactWrapper,
    val spans: Vector[CarvedCertificatesStrategy.CarvedSpan] = Vector.empty
) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = CarvedCertificatesState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new CarvedCertificatesState(
      artifact,
      spans
    )
}

class CarvedCertificatesState(
    artifact: ArtifactWrapper,
    spans: Vector[CarvedCertificatesStrategy.CarvedSpan] = Vector.empty
) extends ProcessingState[SingleMarker, CarvedCertificatesState] {

  private val adHoc = MKC.adHoc("Certificates")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): CarvedCertificatesState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, CarvedCertificatesState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], CarvedCertificatesState) = {
    val path = artifact.path()
    val fileName = path.split('/').lastOption.getOrElse(path)
    val (certs, capExceeded) = readAndCarve(artifact)

    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(MKC.NAME -> TreeSet(StringOrPair(fileName))) +?
        Some(
          MKC.DESCRIPTION -> TreeSet(
            StringOrPair(
              "Binary artifact with carved X.509 certificate material"
            )
          )
        ) +?
        Some(
          adHoc("CarvedCertCount") -> TreeSet(
            StringOrPair(certs.length.toString)
          )
        ) +?
        (if (capExceeded)
           Some(
             adHoc("CarvedCertScanCapExceeded") -> TreeSet(
               StringOrPair("true")
             )
           )
         else None)

    certs.zipWithIndex.foreach { case (cert, idx) =>
      val certAdHoc: String => String =
        sub => MKC.adHoc("Certificates")(s"Cert:$idx:$sub")
      tm = tm ++ Certificates.perCertMetadata(certAdHoc, cert)
    }
    tm -> this
  }

  /** Carve the spans the symbols point at, or, when the claim came from the
    * first block rather than from a symbol, the artifact's front as before.
    * Carving a span reads exactly that span, so a certificate 319 KB into a 324
    * KB binary costs 791 bytes rather than a 16 MiB scan.
    */
  private def readAndCarve(
      artifact: ArtifactWrapper
  ): (Vector[X509Certificate], Boolean) = {
    if (spans.isEmpty) {
      val bytes = Try {
        artifact.withStream { s =>
          val buf = new Array[Byte](CarvedCertAugmenter.MaxScanBytes)
          val n = s.read(buf, 0, CarvedCertAugmenter.MaxScanBytes)
          if (n <= 0) Array.emptyByteArray else Arrays.copyOf(buf, n)
        }
      }.getOrElse(Array.emptyByteArray)
      if (bytes.isEmpty) (Vector(), false)
      else
        CarvedCertAugmenter.carveCertificates(
          bytes,
          CarvedCertAugmenter.MaxScanBytes,
          CarvedCertAugmenter.MaxCerts
        )
    } else {
      var found = Vector.empty[X509Certificate]
      var capExceeded = false
      spans.foreach { span =>
        if (found.length < CarvedCertAugmenter.MaxCerts) {
          val bytes =
            CarvedCertificatesStrategy.readSpan(artifact, span)
          if (bytes.nonEmpty) {
            val (certs, capped) = CarvedCertAugmenter.carveCertificates(
              bytes,
              bytes.length,
              CarvedCertAugmenter.MaxCerts - found.length
            )
            found = (found ++ certs).distinct
            capExceeded = capExceeded || capped
          }
        } else capExceeded = true
      }
      (found.distinct, capExceeded)
    }
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, CarvedCertificatesState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): CarvedCertificatesState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): CarvedCertificatesState = this
}
