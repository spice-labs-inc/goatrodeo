# Git Provenance Capture

> Human documentation; the LLM copy is `git_provenance_llm.md`.

## What it is

For **tagged runs only**, Goat Rodeo records provenance for every unique
git repository containing a listed base directory: the HEAD commit, the
HEAD tree, the worktree tree, and the parent commit(s) — as content-
addressed ADG Items. Untagged runs perform zero git detection.

## When it runs

- A run is tagged (`--tag` or `--tag-json`) → capture happens once, and
  the git Items are written into every batch alongside the run tag.
- A run is untagged → nothing.

## What gets captured (per repo)

| Item | Identifier | Body fields |
|---|---|---|
| HEAD commit | `gitoid:commit:sha1:<hex>` | kinds, date, repo_root, object_format, author/committer name+email+date, parents, message (+ message_truncated) |
| HEAD tree | `gitoid:tree:sha1:<hex>` | kinds, date, repo_root, object_format, head_commit |
| Worktree tree | `gitoid:tree:sha1:<hex>` | kinds includes `worktree_tree`, dirty, head_commit |
| Parent commits | `gitoid:commit:sha1:<hex>` | kinds `parent_commit`, parent_index, head_commit |

A clean repo's worktree tree equals the HEAD tree, so one tree Item
carries both `tree` and `worktree_tree` kinds (no duplicate).

## Implementation notes

- **JGit only.** The product never shells out to `git`; fixtures may.
- **Discovery:** the containing repo per base (walk up, dedupe). Nested
  checkouts are gitlinks, not separately captured.
- **Redaction (default on):** author/committer emails are replaced by a
  pseudonymous `sha256:<hex>` digest; repo_root is relativized to the
  scan base; scan_dir omitted. `--no-redact-git-info` (or TOML
  `redact_git_info = false`) disables it for raw capture.
- **Caps:** entry count (100k), depth (32), blob size (64 MiB), parent
  count (64), message length (256 KiB, truncated with flag), and a
  capture deadline (60 s, checked per directory). A cap hit drops the
  worktree item — never fails the run.
- **Containment:** gitdir/commondir/alternates must live inside the scan
  tree; planted `.git` files/symlinks to foreign repos and alternates
  escaping the repo are refused (zero items + warning).
- **Never fails the run:** corrupt object DBs, refusals, and JGit
  limitations (e.g. sha256 repos → skip with warning) all degrade to
  zero/partial items, never an exception.
- The **tag date** (configured or JSON-overridden) is carried verbatim
  into every git Item, so tag and provenance always agree on the run
  date.

## CBOM note

Git provenance Items are **not** CBOM inputs: they carry `ItemTagData`
bodies (not `ItemMetaData`) and no cryptographic `extra` keys, so the
CBOM emitter's crypto-detection set never matches them. Downstream CBOM
builders should ignore `gitoid:commit:`/`gitoid:tree:` nodes.

## Claims → tests

| Claim | Test |
|---|---|
| Containing repo discovered from nested bases; dedupe; not-a-repo → zero; nested repos not captured | `GitRunInfoSuite.T8.1, T8.2, T8.4, T8.5` |
| Clean/root/dirty item shapes; identifiers are gitoids; body fields | `GitRunInfoSuite.T9.1–T9.3` |
| Symlink-base containment | `GitRunInfoSuite.T8.6` |
| Redaction default + override; raw email absent when redacting | `GitRunInfoSuite.T11.1, T11.2` |
| Containment refusals | `GitRunInfoSuite.T11.3` |
| Caps/never-fail + corrupt DB | `GitRunInfoSuite.T11.5, T11.6` |
| Tagged runs produce git items; untagged produce none; run-tag date verbatim | `GitTaggedRunIntegrationSuite.T10.1, T10.2`, `T12.x` |
| `--no-redact-git-info` flag + TOML `redact_git_info` | `ConfigTestSuite`, `ConfigurationTomlSuite` |
| Git items never CBOM crypto inputs | `GitProvenanceNotInCbomSuite` |