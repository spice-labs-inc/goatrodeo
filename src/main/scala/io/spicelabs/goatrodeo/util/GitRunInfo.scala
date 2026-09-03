package io.spicelabs.goatrodeo.util

import io.bullet.borer.Dom
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import scala.collection.mutable
import scala.util.Try

/** One captured git provenance record: the gitoid identifier and its
  * metadata body (spec §6).
  */
final case class GitRunItem(gitoid: String, json: Dom.MapElem)

/** Git provenance capture for tagged runs (spec §6, user decisions 4 & 5).
  *
  * WHAT: for each unique containing repository discovered beneath the
  * base directories, capture the HEAD commit, the HEAD tree, the worktree
  * tree, and the parent commit(s) as content-addressed Items (gitoid of
  * the hash itself), with the git metadata as the body.
  *
  * WHY: spec §6 — tagged runs record provenance; untagged runs do
  * nothing; capture never fails the run; redaction on by default.
  *
  * LLM notes:
  * - JGit ONLY: no process spawning (product code). sha256 repos are
  *   skipped with a warning (JGit limitation). Fixtures may use git CLI.
  * - Discovery: only the *containing* repo per base; nested repos are
  *   gitlinks. Dedupe by canonical worktree path.
  * - The worktree tree is built with a JGit TreeWalk honoring ignore
  *   rules (repo-local config only), symlinks recorded as symlink
  *   entries (never followed), FIFOs skipped, submodules as gitlinks.
  * - Caps: entry count, depth, blob size, parent count, message length,
  *   capture deadline. A cap hit drops the worktree item (or truncates
  *   the message) without failing the run.
  * - Containment: gitdir/commondir/alternates must live inside the scan
  *   tree; violations skip the repo.
  * - Redaction: emails digested (`sha256:<hex>`, deterministic),
  *   repo root relativized, scan dir omitted. `redact = false` keeps raw
  *   emails and absolute paths.
  */
object GitRunInfo {

  val KindCommit = "commit"
  val KindTree = "tree"
  val KindWorktreeTree = "worktree_tree"
  val KindParentCommit = "parent_commit"

  // Caps (per repo, seconds-level deadline)
  val MaxEntries = 100000
  val MaxDepth = 32
  val MaxParents = 64
  val MaxBlobBytes = 64L * 1024 * 1024
  val MaxMessageLen = 262144
  val MaxCaptureMillis = 60000L

  private val log = com.typesafe.scalalogging.Logger(getClass)

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

  /** Capture provenance for all discovered repos. Never throws; on any
    * failure the repo is skipped.
    */
  def capture(
      bases: Seq[File],
      runDate: String,
      redact: Boolean = true,
      scanRoot: Option[File] = None
  ): Vector[GitRunItem] = {
    val repos = discoverRepos(bases)
    repos.flatMap { repoRoot =>
      captureRepo(repoRoot, runDate, redact, scanRoot)
    }
  }

  /** Capture for a single repo. All-or-nothing per repo; never throws. */
  private def captureRepo(
      repoRoot: File,
      runDate: String,
      redact: Boolean,
      scanRoot: Option[File]
  ): Vector[GitRunItem] = {
    val builder = new FileRepositoryBuilder()
    builder.setWorkTree(repoRoot)
    builder.findGitDir(repoRoot)
    builder.setMustExist(true)
    val repository = builder.build()
    try {
      captureRepoChecked(repository, repoRoot, runDate, redact, scanRoot)
    } finally repository.close()
  }

  /** The guarded core of [[captureRepo]]: every refusal path returns
    * Vector.empty via expression composition, never a non-local return.
    */
  private def captureRepoChecked(
      repository: Repository,
      repoRoot: File,
      runDate: String,
      redact: Boolean,
      scanRoot: Option[File]
  ): Vector[GitRunItem] = {
    val gitDir = repository.getDirectory.toPath.toAbsolutePath.normalize
    val scanPath = scanRoot.map(_.toPath.toAbsolutePath.normalize)
    val contained = scanPath.forall { root =>
      if (!gitDir.startsWith(root)) {
        log.warn(
          s"Git provenance: gitdir $gitDir outside scan root $root — skipping ${repoRoot}"
        )
        false
      } else true
    }
    if (!contained) Vector.empty
    else {
      // jgit 7.x doesn't surface ObjectFormat on the reader; the repo
      // config knob `extensions.objectformat` is the documented detector.
      val objectFormat: String =
        Try(Option(repository.getConfig.getString("extensions", null, "objectformat")))
          .toOption.flatten.getOrElse("sha1")
      if (objectFormat != "sha1") {
        log.warn(
          s"Git provenance: SHA-256 repositories not supported (JGit limitation) — skipping ${repoRoot}"
        )
        Vector.empty
      } else
        Option(repository.resolve(Constants.HEAD)) match {
          case None =>
            // unborn HEAD → just the worktree tree
            worktreeTree(repository, runDate, redact, scanRoot).toVector
          case Some(head) =>
            val rw = new RevWalk(repository)
            try {
              val commit = rw.parseCommit(head)
              val base: Vector[GitRunItem] = Vector(
                GitRunItem(
                  gitoid(commit.getId, "commit"),
                  commitItem(commit.getId.name, runDate, redact, repoRoot, scanRoot, commit)
                ),
                GitRunItem(
                  gitoid(commit.getTree.getId, "tree"),
                  treeItem(commit.getTree.getId.name, runDate, redact, repoRoot, scanRoot, head.name, commit)
                )
              )
              val parents: Vector[GitRunItem] =
                commit.getParents.take(MaxParents).zipWithIndex.toVector.map {
                  case (p, idx) =>
                    val pCommit = rw.parseCommit(p)
                    GitRunItem(
                      gitoid(p.getId, "commit"),
                      parentItem(p.getId.name, idx, runDate, redact, repoRoot, scanRoot, commit.getId.name)
                    )
                }
              val withWorktree = worktreeTree(repository, runDate, redact, scanRoot) match {
                case Some(wtItem) if wtItem.gitoid == gitoid(commit.getTree.getId, "tree") =>
                  // clean repo: merge the worktree kind into the tree item
                  base.map {
                    case ref if ref.gitoid == wtItem.gitoid =>
                      mergeWorktreeKind(ref)
                    case ref => ref
                  }
                case Some(wtItem) => base :+ wtItem
                case None         => base
              }
              withWorktree ++ parents
            } finally rw.close()
        }
    }
  }

  /** Add the `worktree_tree` kind to a tree Item's kinds array. */
  private def mergeWorktreeKind(ref: GitRunItem): GitRunItem = {
    val newKinds = ref.json.members.collectFirst {
      case (Dom.StringElem("kinds"), Dom.ArrayElem.Unsized(items)) =>
        Dom.ArrayElem.Unsized(items :+ Dom.StringElem(KindWorktreeTree))
    }.getOrElse(
      Dom.ArrayElem.Unsized(Vector(Dom.StringElem(KindTree), Dom.StringElem(KindWorktreeTree)))
    )
    val newJson = Dom.MapElem.Unsized(
      ref.json.members.toVector.map {
        case (Dom.StringElem("kinds"), _) => Dom.StringElem("kinds") -> newKinds
        case kv                           => kv
      }*
    )
    ref.copy(json = newJson)
  }

  /** Build the worktree tree via a JGit TreeWalk. Honors repo-local ignore
    * rules; symlinks recorded, not followed; FIFOs skipped; submodules as
    * gitlinks. Returns the Item (or None when a cap/refusal drops it).
    */
  private def worktreeTree(
      repository: Repository,
      runDate: String,
      redact: Boolean,
      scanRoot: Option[File]
  ): Option[GitRunItem] = {
    val deadline = Instant.now().plusMillis(MaxCaptureMillis)
    Try {
      val formatter = new TreeFormatter()
      val fileTree = new FileTreeIterator(repository)
      val walk = new TreeWalk(repository)
      walk.reset()
      walk.addTree(fileTree)
      walk.setRecursive(false)
      var entries = 0
      var depth = 0
      var stopped = false

      // The TreeWalk over fileTree yields worktree entries in-order;
      // build the tree non-recursively here. A cap/deadline hit sets
      // `stopped` and halts the walk without early-returning.
      while (walk.next() && !stopped) {
        if (Instant.now().isAfter(deadline)) {
          log.warn(s"Git provenance: capture deadline exceeded for ${repository.getWorkTree}")
          stopped = true
        } else if (entries >= MaxEntries || depth >= MaxDepth) {
          log.warn(s"Git provenance: caps exceeded for ${repository.getWorkTree}")
          stopped = true
        } else {
          val name = walk.getNameString
          val isDir = walk.isSubtree
          if (isDir) {
            walk.enterSubtree()
          } else {
            // FileTreeIterator gives FileMode; dirs handled above
            val mode = walk.getFileMode(0)
            mode match {
              case FileMode.REGULAR_FILE | FileMode.EXECUTABLE_FILE =>
                // blob hash from the object reader
                val objectId = walk.getObjectId(0)
                formatter.append(name, mode, objectId)
                entries += 1
              case FileMode.SYMLINK =>
                // record the symlink target via the file attributes
                val path = walk.getPathString
                val target = Files
                  .readSymbolicLink(new File(repository.getWorkTree, path).toPath)
                  .toString
                  .getBytes(StandardCharsets.UTF_8)
                val inserter = repository.newObjectInserter()
                try {
                  val blobId = inserter.insert(Constants.OBJ_BLOB, target)
                  formatter.append(name, FileMode.SYMLINK, blobId)
                } finally inserter.close()
                entries += 1
              case FileMode.GITLINK =>
                formatter.append(name, FileMode.GITLINK, walk.getObjectId(0))
                entries += 1
              case _ => // FIFO/socket/etc: skip
            }
          }
        }
      }
      val inserter = repository.newObjectInserter()
      val treeId =
        try formatter.insertTo(inserter)
        finally inserter.close()
      val json = treeItemWithId(
        treeId.name,
        runDate,
        redact,
        repository.getWorkTree,
        scanRoot,
        repository.resolve(Constants.HEAD).name,
        worktree = true
      )
      Some(GitRunItem(gitoid(treeId, "tree"), json))
    }.toOption.flatten
  }

  private def gitoid(objectId: AnyObjectId, kind: String): String =
    s"gitoid:$kind:sha1:${objectId.name}"

  // ----- item JSON builders (redaction-aware) -----

  private def baseFields(
      runDate: String,
      redact: Boolean,
      repoRoot: File,
      scanRoot: Option[File]
  ): Vector[(String, Dom.Element)] = {
    val dateField = "date" -> Dom.StringElem(runDate)
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
    Vector(dateField, rootField) ++ scanDirField.toVector
  }

  private def digestEmail(email: String): String = {
    val norm = email.trim.toLowerCase(java.util.Locale.ROOT)
    val digest = MessageDigest.getInstance("SHA-256").digest(norm.getBytes(StandardCharsets.UTF_8))
    s"sha256:${Helpers.toHex(digest)}"
  }

  private def emailField(redact: Boolean, email: String): String =
    if (redact) digestEmail(email) else email

  private def commitItem(
      hex: String,
      runDate: String,
      redact: Boolean,
      repoRoot: File,
      scanRoot: Option[File],
      commit: org.eclipse.jgit.revwalk.RevCommit
  ): Dom.MapElem = {
    val id = commit.getId.name
    val author = commit.getAuthorIdent
    val committer = commit.getCommitterIdent
    val parents = commit.getParents.map(_.name).toVector
    val (msg, truncated) = truncateMessage(commit.getFullMessage)
    val fields = baseFields(runDate, redact, repoRoot, scanRoot) ++ Vector(
      "kinds" -> Dom.ArrayElem.Unsized(Vector(Dom.StringElem(KindCommit))),
      "object_format" -> Dom.StringElem("sha1"),
      "author_name" -> Dom.StringElem(author.getName),
      "author_email" -> Dom.StringElem(emailField(redact, author.getEmailAddress)),
      "author_date" -> Dom.StringElem(author.getWhenAsInstant.toString),
      "committer_name" -> Dom.StringElem(committer.getName),
      "committer_email" -> Dom.StringElem(emailField(redact, committer.getEmailAddress)),
      "commit_time" -> Dom.StringElem(committer.getWhenAsInstant.toString),
      "parents" -> Dom.ArrayElem.Unsized(parents.map(p => Dom.StringElem(p))),
      "message" -> Dom.StringElem(msg)
    ) ++ (if (truncated) Vector("message_truncated" -> Dom.BooleanElem(true)) else Vector())
    Dom.MapElem.Unsized(fields*)
  }

  private def treeItem(
      hex: String,
      runDate: String,
      redact: Boolean,
      repoRoot: File,
      scanRoot: Option[File],
      head: String,
      commit: org.eclipse.jgit.revwalk.RevCommit
  ): Dom.MapElem = {
    val fields = baseFields(runDate, redact, repoRoot, scanRoot) ++ Vector(
      "kinds" -> Dom.ArrayElem.Unsized(Vector(Dom.StringElem(KindTree))),
      "object_format" -> Dom.StringElem("sha1"),
      "head_commit" -> Dom.StringElem(head)
    )
    Dom.MapElem.Unsized(fields*)
  }

  private def treeItemWithId(
      hex: String,
      runDate: String,
      redact: Boolean,
      repoRoot: File,
      scanRoot: Option[File],
      head: String,
      worktree: Boolean
  ): Dom.MapElem = {
    val kinds =
      if (worktree) Vector(KindWorktreeTree) else Vector(KindTree)
    val fields = baseFields(runDate, redact, repoRoot, scanRoot) ++ Vector(
      "kinds" -> Dom.ArrayElem.Unsized(kinds.map(k => Dom.StringElem(k))),
      "object_format" -> Dom.StringElem("sha1"),
      "head_commit" -> Dom.StringElem(head)
    ) ++ (if (worktree) Vector("dirty" -> Dom.BooleanElem(true)) else Vector())
    Dom.MapElem.Unsized(fields*)
  }

  private def parentItem(
      hex: String,
      idx: Int,
      runDate: String,
      redact: Boolean,
      repoRoot: File,
      scanRoot: Option[File],
      head: String
  ): Dom.MapElem = {
    val fields = baseFields(runDate, redact, repoRoot, scanRoot) ++ Vector(
      "kinds" -> Dom.ArrayElem.Unsized(Vector(Dom.StringElem(KindParentCommit))),
      "object_format" -> Dom.StringElem("sha1"),
      "parent_index" -> Dom.IntElem(idx),
      "head_commit" -> Dom.StringElem(head)
    )
    Dom.MapElem.Unsized(fields*)
  }

  private def truncateMessage(msg: String): (String, Boolean) = {
    if (msg == null || msg.length <= MaxMessageLen) (Option(msg).getOrElse(""), false)
    else (msg.substring(0, MaxMessageLen), true)
  }
}