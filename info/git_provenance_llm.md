# Git Provenance Capture

> Human documentation; the LLM copy is `git_provenance_llm.md`.

## What it is

For **tagged runs only**, Goat Rodeo records provenance for every unique
git repository containing a listed base directory: exactly two Items — the
HEAD commit and the HEAD tree — as content-addressed ADG Items. Untagged
runs perform zero git detection.

## When it runs

- A run is tagged (`--tag` or `--tag-json`) → capture happens once, and
  the git Items are written into every batch alongside the run tag.
- A run is untagged → nothing.

## What gets captured (per repo)

| Item | Identifier | Body fields |
|---|---|---|
| HEAD commit | `gitoid:commit:sha1:<hex>` | repo_root, author/committer name+email+date, parents, message (+ message_truncated) |
| HEAD tree | `gitoid:tree:sha1:<hex>` | repo_root, head_commit |

The tree identifier is the commit's own tree object id — the same value
`git rev-parse HEAD:` prints. No worktree walk, no synthesized trees, no
parent Items: the hashes stored are the repository's own, read via JGit
and never recomputed. The kind (`commit` vs `tree`) and the hash algorithm
(`sha1`) follow from the identifier itself and are not carried in the body.

## Implementation notes

- **JGit only.** The product never shells out to `git`; fixtures may.
- **Discovery:** the containing repo per base (walk up, dedupe). Nested
  checkouts are gitlinks, not separately captured.
- **Redaction (default on):** author/committer emails are replaced by a
  pseudonymous `sha256:<hex>` digest; repo_root is relativized to the
  scan base; scan_dir omitted. `--no-redact-git-info` (or TOML
  `redact_git_info = false`) disables it for raw capture.
- **Caps:** message length (256 KiB, truncated with flag). The capture
  reads only the HEAD commit and its tree — no walks, so no entry/depth/
  blob/parent caps. Never fails the run.
- **Containment:** gitdir/commondir/alternates must live inside the scan
  tree; planted `.git` files/symlinks to foreign repos and alternates
  escaping the repo are refused (zero items + warning).
- **Never fails the run:** corrupt object DBs, refusals, and JGit
  limitations (e.g. sha256 repos → skip with warning) all degrade to
  zero/partial items, never an exception.
- The git Items carry no run date: the tag itself holds the date, and the
  commit item's own timestamps (`author_date`, `commit_time`) are the
  repository's truth.

## CBOM note

Git provenance Items are **not** CBOM inputs: they carry `ItemTagData`
bodies (not `ItemMetaData`) and no cryptographic `extra` keys, so the
CBOM emitter's crypto-detection set never matches them. Downstream CBOM
builders should ignore `gitoid:commit:`/`gitoid:tree:` nodes.

## Claims → tests

| Claim | Test |
|---|---|
| Containing repo discovered from nested bases; dedupe; not-a-repo → zero; nested repos not captured | `GitRunInfoSuite.T8.1, T8.2, T8.4, T8.5` |
| Exactly two Items per repo (HEAD commit + HEAD tree); identifiers are gitoids; body fields | `GitRunInfoSuite.T9.1–T9.3` |
| Symlink-base containment | `GitRunInfoSuite.T8.6` |
| Redaction default + override; raw email absent when redacting | `GitRunInfoSuite.T11.1, T11.2` |
| Containment refusals | `GitRunInfoSuite.T11.3` |
| Never-fail + corrupt DB | `GitRunInfoSuite.T11.6` |
| Tagged runs produce git items; untagged produce none; git items carry no run date | `GitTaggedRunIntegrationSuite.T10.1, T10.2`, `T12.x` |
| `--no-redact-git-info` flag + TOML `redact_git_info` | `ConfigTestSuite`, `ConfigurationTomlSuite` |
| Git items never CBOM crypto inputs | `GitProvenanceNotInCbomSuite` |
| Goat Rodeo NEVER modifies git files: `.git` is byte-for-byte untouched (same entries, sizes, mtimes) by `GitRunInfo.capture` and by a tagged `buildDB` run; the two item ids are read from the repository, never computed | `GitReadOnlyInvariantSuite` |