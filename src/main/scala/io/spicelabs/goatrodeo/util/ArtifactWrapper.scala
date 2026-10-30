package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.apache.commons.io.FilenameUtils
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.mime.MediaType
import org.apache.tika.utils.XMLReaderUtils

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal

/** In OmniBOR, everything is seen as a byte stream.
  *
  * `ArtifactWrapper` provides a wrapper around artifacts... byte streams.
  *
  * `ArtifactWrapper` is somewhat modeled on the JVM `java.io.File` class so
  * that it can represent both files on a filesystem with directories and
  * virtual files contained in a Zip or TAR or other container file.
  *
  * Those byte streams may be in-memory or they may be on disk.
  */
sealed trait ArtifactWrapper {

  /** Convert the Artifact to a stream of bytes. Note that this is mostly used
    * for Hashing which is a block operation. No need for any buffering because
    * all the operations will be pulling large blocks of bytes.
    *
    * @return
    */
  protected def asStream(): BufferedInputStream

  /** Execute a function with an InputStream to this artifact's content.
    *
    * The stream is automatically closed after the function completes.
    *
    * @param f
    *   the function to execute with the stream
    * @tparam T
    *   the return type of the function
    * @return
    *   the result of the function
    */
  def withStream[T](f: BufferedInputStream => T): T = {
    Using.resource(asStream()) { stream =>
      f(stream)
    }
  }

  /** The name of the Artifact. This corresponds to the name of a `File` on disk
    * or the name of the artifact if it was extracted from a container (Zip,
    * TAR, etc.).
    *
    * The path may be removed in certain cases. This is just a name and should
    * not be considered a path to the file on local storage
    *
    * @return
    */
  def path(): String

  /** The number of bytes in the artifact.
    *
    * @return
    */
  def size(): Long

  private lazy val _mimeType
      : (Set[String], AtomicReference[MimeAugmenter.Evidence]) = {
    val base = Try {
      ExtensionMimeDetector.detect(this) match {
        case Some(mime) => Set(mime)
        case None =>
          Set(Using.resource(getTikaInputStream()) { stream =>
            ArtifactWrapper.mimeTypeFor(stream, this.path())
          })
      }
    }.toOption.getOrElse(Set("application/octet-stream"))
    val base2 = mimeHint ++ base
    // The hint is authoritative (never re-checked against content), so an
    // augmenter failure must not drop it: fall back to base2 (hint ++
    // detected) with no evidence, not to the detected set alone.
    Try { ArtifactWrapper.augmentMimeTypes(this, base2) }.toOption
      .getOrElse(
        (
          base2,
          AtomicReference(Map.empty[String, Vector[AugmentationEvidence]])
        )
      )
  }

  /** The effective MIME set: the detected/augmented set UNIONED with the
    * producer-stamped hint . The hint is authoritative (never re-checked
    * against content) and never produced by sniffing.
    */
  def mimeType: Set[String] = _mimeType._1

  /** What the augmenters established about this artifact, keyed by the MIME
    * type each record speaks about.
    *
    * A key may name a MIME type that is not in [[mimeType]]: that is how a
    * negative or inconclusive probe is recorded. The MIME set states what is
    * true; this map states what was probed and learned, so a later consumer can
    * continue from it instead of probing again.
    */
  def mimeTypeEvidence: MimeAugmenter.Evidence = _mimeType._2.get

  /** Cleans up evidence *AFTER* the strategies have been selected
    */
  def wipeEvidence(): Unit = {
    _mimeType._2.set(Map())
  }

  /** Optional authoritative MIME hint stamped by the producer that created this
    * wrapper. None when no producer stamped one. Non-producers never set it;
    * content sniffing never produces kind MIMEs.
    */
  protected def mimeHint: Set[String] = Set()

  protected def getTikaInputStream(): TikaInputStream

  lazy val uuid: String = UUID.randomUUID().toString()

  /** Get the name for the file irregardless of the path
    */
  lazy val filenameWithNoPath: String = (new File(path())).getName()

  protected def fixPath(p: String): String = ArtifactWrapper.fixPath(p)

  def tempDir: Option[File]

  /** When the ArtifactWrapper is done being processed and it was processed
    * successfully, call this method
    */
  def finished(): Unit

  // these methods implement the RodeoArtifact interface for the API

  /** Returns the mime type of the artifact
    *
    * @return
    *   a string representing the mime type of the artifact
    */
  def getMimeType(): Set[String] = mimeType

  /** Get a path or a name of the artifact. If the artifact is not represented
    * by a real file, then this may be simple the name of the artifact within a
    * container.
    *
    * @return
    *   the path or name of the artifact
    */
  def getPath(): String = path()

  /** Gets the size of the artifact in bytes
    *
    * @return
    *   the size of the artifact in bytes
    */
  def getSize(): Long = size()

  /** Gets a unique identifier for the artifact
    *
    * @return
    *   a unique identifier for the artifact
    */
  def getUuid(): String = uuid

  /** Get the name of the file without the path
    *
    * @return
    */
  def getFilenameWithNoPath(): String = filenameWithNoPath

  /** Execute the function with a file on the filesystem. The file may be
    * temporary and may only exist for the duration of the scope of `withFile`,
    * however, files are on a POSIX filesystem so having a handle to the file
    * (e.g., `FileInputStream`), the FileInputStream will live for the duration
    * of the reference.
    *
    * This is a *VERY* expensive call. Do not use it casually. Use it *ONLY* if
    * `withStream` will not suffice
    */
  def withFile[T](func: File => T): T
}

/** Companion object for ArtifactWrapper with factory methods and MIME type
  * detection.
  */
object ArtifactWrapper {
  private val logger = Logger(getClass())

  // ---------- MIME-augmentation probe --------------------------------------

  /** The one-block probe every augmenter gets unless it declares more. Defined
    * in [[MimeProbe]] so augmenters can read it during their own initialization
    * without closing a class-initialization cycle with this companion.
    */
  val MaxMimeProbeBytes: Int = MimeProbe.MaxBytes

  /** Thrown when an augmenter calls `withFile` during augmentation. */
  final class WithFileDuringMimeProbe(what: String)
      extends RuntimeException(
        s"withFile is forbidden during MIME augmentation ($what): it may spill an " +
          "in-memory artifact to a temporary file. Augmentation reads a bounded " +
          "prefix through the probe and nothing else."
      )

  private val augmentScope: ThreadLocal[Boolean] =
    ThreadLocal.withInitial[Boolean](() => false)

  /** Run `f` in the augmentation scope: `withFile` throws, and nothing else is
    * restricted (the probe bounds the bytes).
    */
  private[goatrodeo] def inAugmentScope[T](f: => T): T = {
    val previous = augmentScope.get()
    augmentScope.set(true)
    try f
    finally augmentScope.set(previous)
  }

  private[goatrodeo] def forbidWithFileDuringMimeProbe(): Unit =
    if (augmentScope.get())
      throw new WithFileDuringMimeProbe("during MIME augmentation")

  /** Read up to `n` bytes from the front of the artifact; the result may be
    * shorter when the artifact is smaller or the stream ends early.
    */
  private def readPrefix(artifact: ArtifactWrapper, n: Int): Array[Byte] =
    artifact.withStream { stream =>
      val want = math.min(n.toLong, artifact.size()).toInt
      val buffer = new Array[Byte](want)
      var total = 0
      var done = false
      while (total < want && !done) {
        val read = stream.read(buffer, total, want - total)
        if (read <= 0) done = true else total += read
      }
      if (total == want) buffer else java.util.Arrays.copyOf(buffer, total)
    }

  /** The probe handed to one augmenter for one artifact.
    *
    * The retained prefix only ever grows through [[extend]], and only within
    * the augmenter's declared [[ProbeBudget]]. Re-reading from the front makes
    * the retained bytes contiguous; the total I/O is at most twice the final
    * prefix, and only for the one augmenter that declares an extension.
    */
  private final class RetainedProbe(
      artifact: ArtifactWrapper,
      budget: ProbeBudget
  ) extends AugmentProbe {
    private var target: Int =
      math.min(budget.startBytes.toLong, artifact.size()).toInt
    private var doublings: Int = 0
    private var data: Array[Byte] = readPrefix(artifact, target)

    private def nextTarget: Int =
      math
        .min(
          math.min(target.toLong * 2L, artifact.size()),
          budget.maxBytes.toLong
        )
        .toInt

    def bytes: Array[Byte] = data
    def length: Int = data.length

    def canExtend(): Boolean =
      doublings < budget.maxDoublings && nextTarget > data.length

    def extend(): Boolean =
      if (!canExtend()) false
      else {
        doublings += 1
        target = nextTarget
        data = readPrefix(artifact, target)
        true
      }
  }

  /** Maximum size for in-memory artifact storage (32MB). */
  val maxInMemorySize: Long = 32L * 1024 * 1024;

  /** The largest item kept in memory when items should mostly go to disk: with
    * a RAM-disk `tempDir`, and for the files of a disk image (see
    * `FileWalker.asSaffronFilesystem`).
    */
  val smallInMemorySize: Long = 64L * 1024

  private val tika = new TikaConfig()
  // avoid the WARN Contention waiting for a SAXParser. Consider increasing the XMLReaderUtils.POOL_SIZE warning
  XMLReaderUtils.setPoolSize(200)

  /** Given an input stream and a filename, get the mime type
    *
    * @param data
    *   -- the input stream
    * @param fileName
    *   -- the name of the file
    */
  protected def mimeTypeFor(
      rawData: TikaInputStream,
      fileName: String
  ): String = {
    try {
      val data = rawData
      val len = rawData.getLength()

      val metadata = new Metadata()
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
      val detected = Try { tika.getDetector.detect(data, metadata) }.toOption
        .getOrElse(MediaType.OCTET_STREAM)
      // Whatever Tika says is the base type. The types Tika cannot name (JSON,
      // LuaRocks rockspecs) are claimed by MIME augmenters, which are additive
      // and bounded to one block; the artifact's MIME set is not overwritten here.
      detected.toString()
    } catch {
      // NonFatal rather than Exception: this runs on the builder's worker
      // threads, which are interrupted on shutdown, and catching Exception
      // swallows the InterruptedException that carries the cancellation --
      // leaving the thread to carry on as though the run were still live.
      // NonFatal also still lets OutOfMemoryError and StackOverflowError
      // through, which is what the earlier catch of Throwable did not.
      case NonFatal(e) =>
        // logger.error(
        //   f"Tika failed, ${e.getMessage()}. Returning application/octet-stream",
        //   e
        // )
        "application/octet-stream"
    }
  }

  private val mimeTypeAugmenters: AtomicReference[Vector[MimeAugmenter]] =
    AtomicReference(Vector())

  /** Per-augmenter block predicate: true when the MIME set contains none of the
    * blocked names. A blocked name that ends in a slash plus an asterisk -- the
    * family wildcard, written as &#34;text/&#42;&#34; for the text family --
    * matches as a prefix, so it blocks `text/plain` and `text/x-vhdl`; any
    * other name matches exactly.
    *
    * This helper carries no policy of its own. The blocked list is supplied by
    * one augmenter and consulted only by that augmenter's own rule: a MIME type
    * is an additive claim about what an artifact is, never a verdict that
    * nothing further can be found, so no aggregate list may silence the chain
    * on everyone's behalf.
    *
    * Rules are deliberately block-shaped: blocking only MIMEs that provably
    * cannot match keeps every unknown/degenerate MIME probed, so a wrong rule
    * can only cost performance, never a false negative.
    */
  /** Admission predicate for per-augmenter applicability rules: true when at
    * least one MIME in the set could be this augmenter's target.
    *
    * Structurally the mirror of `noneOf`, and for the same reason: the
    * candidate list is iterated ONCE and every exact entry is answered by a
    * hash lookup in the MIME set (O(1) per entry). Only a suffix entry -- the
    * text family wildcard, `image/x-raw-*`, or a bare `"text/"` -- falls back
    * to scanning the MIME set, and `mimes` holds a handful of entries.
    * Iterating the MIME set on the outside and the candidate list on the inside
    * -- the shape this replaces -- pays for every candidate once per MIME,
    * which is the quadratic behaviour `noneOf` was rewritten to avoid.
    *
    * `exists` short-circuits on the first hit, and the caller passes a `Seq` it
    * holds as a `val` (`Admissible*`), so no allocation happens per call.
    */
  def anyOf(candidates: String*)(mimes: Set[String]): Boolean =
    candidates.exists { c =>
      if (c.endsWith("*")) {
        val realC = c.substring(0, c.length() - 1)
        mimes.exists(m => m.startsWith(realC))
      } else if (c.endsWith("/")) {
        mimes.exists(m => m.startsWith(c))
      } else mimes.contains(c)
    }

  def noneOf(blocked: String*)(mimes: Set[String]): Boolean = {
    !blocked.exists { b =>
      if (b.endsWith("*")) {
        val realB = b.substring(0, b.length() - 1)
        mimes.exists(m => m.startsWith(realB))
      } else if (b.endsWith("/")) {
        mimes.exists(m => m.startsWith(b))
      } else mimes.contains(b)
    }
  }

  /** Run the augmenter chain.
    *
    * Each augmenter receives the MIME set so far, the evidence accumulated by
    * the augmenters before it, and its own bounded probe. It returns the new
    * MIME set and the evidence map. A single augmenter that throws is skipped
    * (its evidence and MIMEs are dropped, the rest keep running), so one bug
    * cannot silently disable every detector for an artifact.
    */
  def augmentMimeTypes(
      artifact: ArtifactWrapper,
      mimes: Set[String]
  ): (Set[String], AtomicReference[MimeAugmenter.Evidence]) = {
    val (mimesOut: Set[String], evidence: MimeAugmenter.Evidence) =
      inAugmentScope {
        mimeTypeAugmenters
          .get()
          .foldLeft(
            (mimes, Map.empty[String, Vector[AugmentationEvidence]])
          ) { case ((curMimes, curEvidence), augmenter) =>
            if (!augmenter.rule(curMimes, artifact.filenameWithNoPath))
              (curMimes, curEvidence)
            else {
              val probe = new RetainedProbe(artifact, augmenter.budget)
              Try(augmenter.fn(artifact, curMimes, curEvidence, probe)) match {
                case Success(result) => result
                case Failure(e) if e.isInstanceOf[WithFileDuringMimeProbe] =>
                  logger.error(
                    s"MIME augmenter ${augmenter.id} violated the rules for ${artifact
                        .path()}: ${e.getMessage}"
                  )
                  (curMimes, curEvidence)
                case Failure(_) => (curMimes, curEvidence)
              }
            }
          }
      }

    (mimesOut, AtomicReference(evidence))
  }

  /** The result of running one augmenter: the new MIME set, the evidence it
    * produced, and how much of the artifact it inspected.
    */
  final case class AugmentOutcome(
      mimes: Set[String],
      evidence: MimeAugmenter.Evidence,
      inspectedBytes: Int
  )

  /** Run one augmenter exactly as the chain would.
    *
    * Used by the chain itself and by tests that pin an individual augmenter's
    * behaviour — including how much of the artifact it inspected, which is the
    * rule the probe exists to enforce.
    */
  def runAugmenter(
      augmenter: MimeAugmenter,
      artifact: ArtifactWrapper,
      mimes: Set[String],
      evidence: MimeAugmenter.Evidence = Map.empty
  ): AugmentOutcome =
    inAugmentScope {
      val probe = new RetainedProbe(artifact, augmenter.budget)
      val (outMimes, outEvidence) =
        augmenter.fn(artifact, mimes, evidence, probe)
      AugmentOutcome(outMimes, outEvidence, probe.length)
    }

  /** Register a MIME augmenter. */
  def addMimeTypeAugmenter(augmenter: MimeAugmenter): Unit = {
    mimeTypeAugmenters.getAndUpdate(v => v :+ augmenter)
  }

  // constructor
  addMimeTypeAugmenter(DotnetDetector.augmenter)
  addMimeTypeAugmenter(JsonLikeDetector.augmenter)
  addMimeTypeAugmenter(CarvedCertAugmenter.augmenter)
  addMimeTypeAugmenter(SaffronDetector.augmenter)
  addMimeTypeAugmenter(CryptoDetector.augmenter)
  addMimeTypeAugmenter(OpenSSLConfigDetector.augmenter)
  addMimeTypeAugmenter(JavaSecurityDetector.augmenter)
  addMimeTypeAugmenter(JavaArchiveDetector.augmenter)
  addMimeTypeAugmenter(CryptoContentDetector.augmenter)

  protected def fixPath(p: String): String = {
    if (p.startsWith("./")) fixPath(p.substring(2))
    else if (p.startsWith("/")) fixPath(p.substring(1))
    else if (p.startsWith("../")) fixPath(p.substring(3))
    else p
  }

  /** Give some data, create a new wrapper for it
    *
    * @param nominalPath
    *   the path
    * @param size
    *   the size of the data
    * @param data
    *   the InputStream of the data
    * @param tempPath
    *   the temporary directory to put a file in
    * @param inMemoryLimit
    *   the largest item kept in memory rather than written to `tempPath`; by
    *   default 64KiB with a `tempDir` and [[maxInMemorySize]] without
    *
    * @return
    *   the built item
    */
  def newWrapper(
      nominalPath: String,
      size: Long,
      data: InputStream,
      tempDir: Option[File],
      tempPath: Path,
      mimeHint: Set[String] = Set(),
      inMemoryLimit: Option[Long] = None
  ): Try[ArtifactWrapper] = Try {
    val name = sanitizeName(fixPath(nominalPath))
    val forceTempFile = requireTempFile(name)

    // a defined temp dir implies a RAM disk... copy everything but the smallest items
    val limit = inMemoryLimit.getOrElse(
      if (tempDir.isDefined) smallInMemorySize else maxInMemorySize
    )
    if (
      size != -1 && // if the size is unknown, create a temp file... we don't know the size
      !forceTempFile && size <= limit
    ) {
      val bos = ByteArrayOutputStream()
      Helpers.copy(data, bos)
      val bytes = bos.toByteArray()
      if (size != bytes.length) {
        // A corrupt or lying entry declared a different size than what the
        // stream actually yielded. The wrapper carries the bytes that
        // arrived; failing the whole container over a size lie is worse
        // than wrapping the truth.
        logger.warn(
          f"Wrapper for ${name} expected ${size} bytes, but got ${bytes.length}; wrapping actual content"
        )
      }
      ByteWrapper(
        bytes,
        name,
        tempDir = tempDir,
        mimeHint = mimeHint
      )
    } else {
      // Preserve the original extension so MIME-type augmenters and disk-format
      // detectors (e.g. Saffron for .img / .img.gz) can fall back to the filename.
      val extension =
        Option(extensionFromName(name)).filter(_.nonEmpty).getOrElse("temp")
      val tempFile =
        Files.createTempFile(tempPath, "goats", "." + extension).toFile()
      val fos = FileOutputStream(tempFile)
      Helpers.copy(data, fos)
      fos.flush()
      fos.close()
      FileWrapper(
        tempFile,
        name,
        tempDir = tempDir,
        mimeHint = mimeHint
      )
    }
  }

  /** Determine if a file must be written to a temp file rather than kept in
    * memory.
    *
    * Some file types (like .NET assemblies) require actual files on disk for
    * processing by external tools.
    *
    * @param name
    *   the filename to check
    * @return
    *   true if the file must be a temp file, false if it can be in memory
    */
  def requireTempFile(name: String): Boolean = {
    // for .NET assemblies, we'll force a temp file so that cilantro can open them.
    FilenameUtils.getExtension(name) match {
      case "dll" | "exe" => true
      case _             => false
    }
  }

  /** Return the full filename extension, preserving multi-part extensions such
    * as `.img.gz` or `.tar.gz`. This is important for disk-format detectors
    * (e.g. Saffron) that look at the actual on-disk filename when probing a
    * temp file spilled from an in-memory artifact.
    *
    * @param name
    *   the filename to check
    * @return
    *   the extension after the first dot of the basename, or the empty string
    *   if there is none
    */
  def extensionFromName(name: String): String = {
    val base = Try { FilenameUtils.getName(name) }.toOption
      .flatMap(v => Option(v))
      .getOrElse(name)
    val dot = base.indexOf('.')
    if (dot > 0 && dot < base.length - 1) base.substring(dot + 1)
    else ""
  }

  /** Remove characters that are illegal in a filename from an artifact name.
    *
    * Archive formats (notably GNU/Berkeley `ar`) NUL-pad member names to a
    * fixed width, so a static-library entry's name (and its extension) can end
    * with `\u0000` bytes. Passing those into `Files.createTempFile` throws
    * `InvalidPathException: Nul character not allowed`. NUL is the only
    * character POSIX filenames cannot contain, so stripping it is safe and
    * sufficient; anything else is left untouched.
    *
    * @param name
    *   the artifact (file) name
    * @return
    *   the name with NUL characters removed
    */
  def sanitizeName(name: String): String = name.replaceAll("\u0000", "")
}

/** An ArtifactWrapper backed by an actual file on disk.
  *
  * @param wrappedFile
  *   the actual File on disk
  * @param thePath
  *   the logical path of this artifact (may differ from wrappedFile's path)
  * @param tempDir
  *   optional temp directory for creating temporary files
  * @param finishedFunc
  *   callback function called when processing is complete
  */
final case class FileWrapper(
    wrappedFile: File,
    thePath: String,
    tempDir: Option[File],
    finishedFunc: File => Unit = f => (),
    protected override val mimeHint: Set[String] = Set()
) extends ArtifactWrapper {

  // constructor
  if (!wrappedFile.exists()) {
    throw Exception(
      f"Tried to create file wrapper for ${wrappedFile.getCanonicalPath()} that does not exist"
    )
  }

  override protected def getTikaInputStream(): TikaInputStream = {
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, thePath)
    TikaInputStream.get(wrappedFile.toPath(), metadata)
  }

  override protected def asStream(): BufferedInputStream =
    new BufferedInputStream(
      FileInputStream(wrappedFile)
    )

  /** Execute the function with a file on the filesystem. The file may be
    * temporary and may only exist for the duration of the scope of `withFile`,
    * however, files are on a POSIX filesystem so having a handle to the file
    * (e.g., `FileInputStream`), the FileInputStream will live for the duration
    * of the reference.
    */
  def withFile[T](func: File => T): T = {
    ArtifactWrapper.forbidWithFileDuringMimeProbe()
    func(wrappedFile)
  }

  override def path(): String = fixPath(thePath)

  override def size(): Long = wrappedFile.length()

  /** When the ArtifactWrapper is done being processed and it was processed
    * successfully, call this method
    */
  def finished(): Unit = finishedFunc(wrappedFile)
}

/** Companion object for FileWrapper with factory methods. */
object FileWrapper {

  /** Create a FileWrapper from a file path.
    *
    * @param name
    *   the path to the file
    * @param tempDir
    *   optional temp directory for creating temporary files
    * @return
    *   a FileWrapper for the specified file
    */
  def fromName(name: String, tempDir: Option[File]): FileWrapper = {
    FileWrapper(new File(name), name, tempDir = tempDir)
  }
}

/** An ArtifactWrapper backed by an in-memory byte array.
  *
  * Used for small files or archive contents that can be held in memory.
  *
  * @param bytes
  *   the byte content of this artifact
  * @param fileName
  *   the logical filename of this artifact
  * @param tempDir
  *   optional temp directory for creating temporary files
  */
final case class ByteWrapper(
    bytes: Array[Byte],
    fileName: String,
    tempDir: Option[File],
    protected override val mimeHint: Set[String] = Set()
) extends ArtifactWrapper {

  override protected def getTikaInputStream(): TikaInputStream = {
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
    TikaInputStream.get(bytes, metadata)
  }
  override def asStream(): BufferedInputStream = new BufferedInputStream(
    ByteArrayInputStream(bytes)
  )

  /** Execute the function with a file on the filesystem. The file may be
    * temporary and may only exist for the duration of the scope of `withFile`,
    * however, files are on a POSIX filesystem so having a handle to the file
    * (e.g., `FileInputStream`), the FileInputStream will live for the duration
    * of the reference.
    */
  def withFile[T](func: File => T): T = {
    ArtifactWrapper.forbidWithFileDuringMimeProbe()
    FileWalker.withinTempDir { path =>
      // Preserve the original extension so MIME-type augmenters and disk-format
      // detectors (e.g. Saffron for .img / .img.gz) can fall back to the filename.
      val extension =
        Option(ArtifactWrapper.extensionFromName(fileName))
          .filter(_.nonEmpty)
          .getOrElse("temp")
      val file =
        Helpers.tempFileFromStream(asStream(), false, path, "." + extension)
      try {
        func(file)
      } finally {
        file.delete()
      }
    }.get
  }

  override def path(): String = fileName

  override def size(): Long = bytes.length

  /** When the ArtifactWrapper is done being processed and it was processed
    * successfully, call this method
    */
  def finished(): Unit = {} // do nothing
}
