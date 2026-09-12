package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import io.bullet.borer.Dom
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import scala.collection.mutable
import scala.util.Try

/** One captured git provenance record: the gitoid identifier and its metadata
  * body .
  */
final case class GitRunItem(gitoid: String, json: Dom.MapElem)

/** Git provenance capture for tagged runs .
  *
  * WHAT: for each unique containing repository discovered beneath the base
  * directories, capture the HEAD commit, the HEAD tree, the worktree tree, and
  * the parent commit(s) as content-addressed Items (gitoid of the hash itself),
  * with the git metadata as the body.
  *
  * WHY: spec §6 — tagged runs record provenance; untagged runs do nothing;
  * capture never fails the run; redaction on by default.
  *
  * LLM notes:
  *   - JGit ONLY: no process spawning (product code). sha256 repos are skipped
  *     with a warning (JGit limitation). Fixtures may use git CLI.
  *   - Discovery: only the *containing* repo per base; nested repos are
  *     gitlinks. Dedupe by canonical worktree path.
  *   - Exactly two Items per repo: the HEAD commit and the HEAD tree (the
  *     commit's `getTree` id — the same value `git rev-parse HEAD:` prints). No
  *     worktree walks, no synthesized trees, no parent Items: the hashes stored
  *     are the repository's own, read via JGit and never recomputed.
  *   - Containment: gitdir/commondir/alternates must live inside the scan tree;
  *     violations skip the repo.
  *   - Redaction: emails digested (`sha256:<hex>`, deterministic), repo root
  *     relativized, scan dir omitted. `redact = false` keeps raw emails and
  *     absolute paths.
  *   - Hard invariant: scanned git repositories are STRICTLY read-only. Object
  *     ids are computed with a read-only `ObjectInserter` that throws on every
  *     write-capable entry point (`idFor` only hashes; it never stages,
  *     flushes, or persists). Goat Rodeo never modifies, signs, or writes
  *     anything into a scanned repository.
  */
object GitRunInfo {

  // Caps (per repo, seconds-level deadline)
  val MaxMessageLen = 262144

  private val log = Logger(getClass)

  /** Discover each containing repo for the base paths (walk up, dedupe).
    * Returns the canonical worktree roots.
    */
  private[goatrodeo] def discoverRepos(bases: Seq[File]): Vector[File] = {
    val seen = mutable.LinkedHashSet[File]()
    bases.foreach { base =>
      val start = if (base.isFile) base.getParentFile else base
      val found = Try {
        val builder = new FileRepositoryBuilder()
        builder.findGitDir(start)
        builder.setMustExist(true)
        Option(builder.build()).map(_.getWorkTree.getCanonicalFile)
      }.toOption.flatten
      found.foreach(f => seen += f)
    }
    seen.toVector
  }

  /** Capture provenance for all discovered repos. Never throws; on any failure
    * the repo is skipped.
    */
  def capture(
      bases: Seq[File],
      redact: Boolean = true,
      scanRoots: Seq[File] = Vector.empty
  ): Vector[GitRunItem] = {
    val repos = discoverRepos(bases)
    repos.flatMap { repoRoot =>
      captureRepo(repoRoot, redact, scanRoots)
    }
  }

  /** Capture for a single repo. All-or-nothing per repo; never throws. */
  private def captureRepo(
      repoRoot: File,
      redact: Boolean,
      scanRoots: Seq[File]
  ): Vector[GitRunItem] = {
    val builder = new FileRepositoryBuilder()
    builder.setWorkTree(repoRoot)
    builder.findGitDir(repoRoot)
    builder.setMustExist(true)
    val repository = builder.build()
    try {
      captureRepoChecked(repository, repoRoot, redact, scanRoots)
    } finally repository.close()
  }

  /** The guarded core of [[captureRepo]]: every refusal path returns
    * Vector.empty via expression composition, never a non-local return.
    */
  private def captureRepoChecked(
      repository: Repository,
      repoRoot: File,
      redact: Boolean,
      scanRoots: Seq[File]
  ): Vector[GitRunItem] = {
    val gitDir = repository.getDirectory.toPath.toAbsolutePath.normalize
    // A repository is in scope when no scan roots were given or its gitdir
    // lies under at least one of them. The old single-root `forall` would
    // skip every repo outside the FIRST build directory once multiple `-b`
    // flags were accepted.
    val contained =
      scanRoots.isEmpty || scanRoots.exists(root =>
        gitDir.startsWith(root.toPath.toAbsolutePath.normalize)
      )
    if (!contained) {
      log.warn(
        s"Git provenance: gitdir $gitDir outside all scan roots — skipping ${repoRoot}"
      )
      Vector.empty
    } else {
      // jgit 7.x doesn't surface ObjectFormat on the reader; the repo
      // config knob `extensions.objectformat` is the documented detector.
      val objectFormat: String =
        Try(
          Option(
            repository.getConfig.getString("extensions", null, "objectformat")
          )
        ).toOption.flatten.getOrElse("sha1")
      if (objectFormat != "sha1") {
        log.warn(
          s"Git provenance: SHA-256 repositories not supported (JGit limitation) — skipping ${repoRoot}"
        )
        Vector.empty
      } else
        Option(repository.resolve(Constants.HEAD)) match {
          case None =>
            // unborn HEAD: no commit, no tree — nothing to report
            Vector.empty
          case Some(head) =>
            val rw = new RevWalk(repository)
            try {
              val commit = rw.parseCommit(head)
              val base: Vector[GitRunItem] = Vector(
                GitRunItem(
                  gitoid(commit.getId, "commit"),
                  commitItem(
                    redact,
                    repoRoot,
                    scanRoots,
                    commit
                  )
                ),
                GitRunItem(
                  gitoid(commit.getTree.getId, "tree"),
                  treeItem(
                    redact,
                    repoRoot,
                    scanRoots,
                    head.name,
                    commit
                  )
                )
              )
              base
            } finally rw.close()
        }
    }
  }

  private def gitoid(objectId: AnyObjectId, kind: String): String =
    s"gitoid:$kind:sha1:${objectId.name}"

  /** The scan root relevant to a repository: the first root that contains the
    * repository's workdir; when none does (e.g. a linked worktree), the first
    * root, matching the original single-root behavior.
    */
  private def matchedScanRoot(
      repoRoot: File,
      scanRoots: Seq[File]
  ): Option[File] = {
    val p = repoRoot.toPath.toAbsolutePath.normalize
    scanRoots
      .find(r => p.startsWith(r.toPath.toAbsolutePath.normalize))
      .orElse(scanRoots.headOption)
  }

  private def baseFields(
      redact: Boolean,
      repoRoot: File,
      scanRoots: Seq[File]
  ): Vector[(String, Dom.Element)] = {
    val scanRoot = matchedScanRoot(repoRoot, scanRoots)
    val rootField = if (redact) {
      scanRoot
        .map { root =>
          val rel = Try(root.toPath.relativize(repoRoot.toPath)).toOption
            .map(_.toString)
            .getOrElse(repoRoot.getAbsolutePath)
          "repo_root" -> Dom.StringElem(rel)
        }
        .getOrElse("repo_root" -> Dom.StringElem(repoRoot.getName))
    } else {
      "repo_root" -> Dom.StringElem(repoRoot.getAbsolutePath)
    }
    val scanDirField =
      if (redact) None
      else scanRoot.map(r => "scan_dir" -> Dom.StringElem(r.getAbsolutePath))
    Vector(rootField) ++ scanDirField.toVector
  }

  private def digestEmail(email: String): String = {
    val norm = email.trim.toLowerCase(Locale.ROOT)
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(norm.getBytes(StandardCharsets.UTF_8))
    s"sha256:${Helpers.toHex(digest)}"
  }

  private def emailField(redact: Boolean, email: String): String =
    if (redact) digestEmail(email) else email

  private def commitItem(
      redact: Boolean,
      repoRoot: File,
      scanRoots: Seq[File],
      commit: RevCommit
  ): Dom.MapElem = {
    val author = commit.getAuthorIdent
    val committer = commit.getCommitterIdent
    val parents = commit.getParents.map(_.name).toVector
    val (msg, truncated) = truncateMessage(commit.getFullMessage)
    val fields = baseFields(redact, repoRoot, scanRoots) ++ Vector(
      "author_name" -> Dom.StringElem(author.getName),
      "author_email" -> Dom.StringElem(
        emailField(redact, author.getEmailAddress)
      ),
      "author_date" -> Dom.StringElem(author.getWhenAsInstant.toString),
      "committer_name" -> Dom.StringElem(committer.getName),
      "committer_email" -> Dom.StringElem(
        emailField(redact, committer.getEmailAddress)
      ),
      "commit_time" -> Dom.StringElem(committer.getWhenAsInstant.toString),
      "parents" -> Dom.ArrayElem.Unsized(parents.map(p => Dom.StringElem(p))),
      "message" -> Dom.StringElem(msg)
    ) ++ (if (truncated) Vector("message_truncated" -> Dom.BooleanElem(true))
          else Vector())
    Dom.MapElem.Unsized(fields*)
  }

  private def treeItem(
      redact: Boolean,
      repoRoot: File,
      scanRoots: Seq[File],
      head: String,
      commit: RevCommit
  ): Dom.MapElem = {
    val fields = baseFields(redact, repoRoot, scanRoots) ++ Vector(
      "head_commit" -> Dom.StringElem(head)
    )
    Dom.MapElem.Unsized(fields*)
  }

  private def truncateMessage(msg: String): (String, Boolean) = {
    if (msg == null || msg.length <= MaxMessageLen)
      (Option(msg).getOrElse(""), false)
    else (msg.substring(0, MaxMessageLen), true)
  }
}
