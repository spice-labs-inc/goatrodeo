package io.spicelabs.goatrodeo.util

/** Constants shared by the probe machinery.
  *
  * Deliberately dependency-free: augmenters read these during their own class
  * initialization, and `ArtifactWrapper`'s companion registers the augmenters
  * during its initialization. A constant that lived on `ArtifactWrapper` would
  * close that loop, and two threads initializing the two classes in opposite
  * orders deadlock.
  */
object MimeProbe {

  /** One disk block: the prefix every augmenter sees by default. */
  val MaxBytes: Int = 4096
}

/** Open contract between a MIME augmenter and any consumer of what it learned.
  *
  * This trait is deliberately **not sealed**. Each augmenter declares its own
  * case classes, with its own vocabulary, for the facts it can establish. A
  * consumer (a strategy selector, the file walker, a test) pattern-matches the
  * evidence types it understands and ignores the rest.
  *
  * Evidence is keyed, in [[MimeAugmenter.Evidence]], by the MIME type it speaks
  * about. Two consequences matter:
  *
  *   - the key may name a MIME type that is **not** in the artifact's MIME set.
  *     That is how "probed 4 KiB, this is not an Alpine package" and "PE image
  *     but no CLI directory" are recorded. The MIME set states what is true;
  *     the evidence map states what was probed;
  *   - evidence is only ever *additional* knowledge. It never removes a MIME
  *     type, and a MIME type is only added when it is known to be true, never
  *     because it might be.
  */
/** A record an augmenter leaves behind when it is *indeterminate*.
  *
  * Augmenters are designed to be fast and have a limited IO and compute budget.
  * If an augmenter cannot positively or negatively assert that a MIME type
  * should be added, but it has collected some information, it puts that
  * information in Evidence. The downstream evaluator -- strategy selection or
  * the file walker -- can pick up this evidence as a starting point for further
  * evaluation to determine whether the strategy or the walker should apply.
  *
  * Evidence is ONLY for this purpose. It is NOT a scratchpad. It captures
  * information only when the augmenter is indeterminate, and for no other
  * reason: not as a log of what was probed, not as a record of a negative
  * result, and not as a cache for an augmenter that already answered the
  * question itself.
  */
trait AugmentationEvidence {

  /** The id of the augmenter that produced this record. */
  def augmenter: String
}

/** Per-augmenter probe budget policy.
  *
  * Augmentation reads one disk block by default and is not allowed to ask for
  * more. An augmenter that genuinely needs depth declares it here, and the
  * ladder is enforced by [[ArtifactWrapper]] rather than by the augmenter.
  *
  * @param startBytes
  *   the retained prefix at the first probe, 4 KiB unless declared otherwise
  * @param maxBytes
  *   the largest prefix the augmenter may ever retain
  * @param maxDoublings
  *   how many times the augmenter may ask for the budget to double; `0` means
  *   it can never extend, and `canExtend()` will say so
  */
final case class ProbeBudget(
    startBytes: Int = 4096,
    maxBytes: Int = 4096,
    maxDoublings: Int = 0
)

/** The retained prefix of one artifact, and the augmenter's only handle on the
  * probe budget.
  *
  * Created, bounded and owned by `ArtifactWrapper` for one augmenter and one
  * artifact. An augmenter cannot raise its budget, cannot see the policy, and
  * cannot read past [[bytes]]. `canExtend()` exists so an augmenter can ask
  * whether more bytes are available without provoking an exception.
  */
trait AugmentProbe {

  /** The retained prefix. Valid for `[0, length)`; the array may be longer than
    * `length` and must not be read past it.
    */
  def bytes: Array[Byte]

  /** How many bytes of the prefix are valid. */
  def length: Int

  /** True when [[extend]] would succeed: the augmenter's policy allows another
    * doubling and the artifact has more bytes to give.
    */
  def canExtend(): Boolean

  /** Double the retained prefix within the declared policy.
    *
    * @return
    *   true when the prefix grew, false when the policy or the artifact's size
    *   has been reached
    */
  def extend(): Boolean
}

object MimeAugmenter {

  /** The fast reject. It answers "can this artifact possibly be mine?" from the
    * MIME set and the artifact's filename/suffix, and from nothing else: no
    * bytes, no I/O, no parsing. The filename is part of the filter because a
    * format's name can say what its bytes are when Tika's guess says something
    * else -- a `.vhd` typed `text/x-vhdl`, a `.raw` typed `image/x-raw-*`.
    */
  type Rule = (Set[String], String) => Boolean

  /** What the augmenters learned, keyed by the MIME type it is evidence about.
    */
  type Evidence = Map[String, Vector[AugmentationEvidence]]

  /** One augmenter's entry point.
    *
    * It receives the artifact, the MIME set so far, the evidence accumulated by
    * earlier augmenters, and the probe. It returns the new MIME set and the
    * evidence map (accumulated ones included), so a later augmenter can build
    * on what an earlier one established instead of probing again.
    */
  type Fn = (
      ArtifactWrapper,
      Set[String],
      Evidence,
      AugmentProbe
  ) => (Set[String], Evidence)
}

/** A registered MIME augmenter.
  *
  * @param id
  *   stable name, used in logs and as the `augmenter` field of its evidence
  * @param rule
  *   applicability rule: the augmenter runs only when this returns true. Rules
  *   are block-shaped and must not read the artifact.
  * @param budget
  *   the probe budget policy for this augmenter
  * @param fn
  *   the augmentation itself
  */
/** A MIME augmenter: a fast reject, then a surgical probe, then — only if the
  * probe is indeterminate — evidence.
  *
  * ==Order of work==
  *
  *   1. '''Fast reject'''. `rule` answers "can this artifact possibly be mine?"
  *      from the MIME set and the artifact's name/suffix alone. It is a
  *      Bloom-filter-shaped filter: no I/O, no parsing, and it can say both
  *      "this is known to be impossible for me" and "nothing here says it is
  *      possible". A probe never runs before this has said yes. 2. '''Surgical
  *      probe'''. `fn` reads the smallest span that can answer the question,
  *      through the [[AugmentProbe]] it is given, within [[ProbeBudget]]: one 4
  *      KiB block by default, with a documented exception where a format
  *      genuinely needs more (a DER prologue, Saffron's own prefix). It never
  *      reads the artifact whole and never materialises it. 3. '''Three
  *      outcomes'''. (a) the probe ''''matches''''' — add the MIME type; (b) it
  *      ''''does not match''''' — add nothing; (c) it is
  *      ''''indeterminate''''', because answering would need more probing than
  *      the budget allows — and '''only then''' is an [[AugmentationEvidence]]
  *      record created, carrying what has been collected so far so that the
  *      downstream evaluator (strategy selection or the file walker) can
  *      continue from it with its larger budget.
  *
  * Evidence is not a log of what was probed, not a scratchpad and not a cache
  * of a question the augmenter already answered. See [[AugmentationEvidence]].
  *
  * @param rule
  *   the fast reject: the MIME set and the artifact's filename, nothing else.
  *   See [[MimeAugmenter.Rule]].
  * @param budget
  *   how much the probe may read
  * @param fn
  *   the probe and the decision
  */
final case class MimeAugmenter(
    id: String,
    rule: MimeAugmenter.Rule,
    budget: ProbeBudget,
    fn: MimeAugmenter.Fn
)
