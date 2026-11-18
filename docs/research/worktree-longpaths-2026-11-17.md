# Windows worktree long paths, 2026-11-17

## Failure and cause

Run 44100293698 attempt 1 failed in `WorkspaceLongPathTest.windowsLongObjectPathsPreserveOriginalWorkspaceAndReview(String)[3]` while preparing the WORKTREE strategy. Git for Windows 2.56.0.windows.4 emitted `fatal: '$GIT_DIR' too big`. Its source checks the UTF-8 `GIT_DIR` length against `PATH_MAX - 40`; with `PATH_MAX` 260, the rejected value was 221 bytes. The original runner ZIP contains all 30 files from the preserved PREPARED fixture, and every copied file matches its recorded SHA-256.

The check is in [`setup.c` at the exact CI Git commit](https://github.com/git-for-windows/git/blob/581291682ad5a238c08c93d1f5bd4518382a3230/setup.c). That version of [`builtin/worktree.c`](https://github.com/git-for-windows/git/blob/581291682ad5a238c08c93d1f5bd4518382a3230/builtin/worktree.c) registers the worktree and passes its absolute `.git` path to a child checkout process through `GIT_DIR`. `core.longpaths=true` enables filesystem long path handling but does not bypass this separate setup validation.

## Fix and regression

On Windows, register the same locked detached worktree with `--no-checkout`, then run `git reset --hard --no-recurse-submodules <fixed-sha>` from the worktree. The fixture runs the pre-fix form and records the same Git error, then verifies the two-step form completes under Git 2.53.0.windows.2 with local `core.longpaths=false`, retains the same HEAD and lock, and leaves the source config unchanged.

The Windows regression crosses Git's UTF-8 `GIT_DIR` limit while keeping the working directory under the shell's separate path bound. It verifies the detached HEAD, original PREPARED-to-READY receipt and byte-identical recovery, worktree lock ownership, independent review clone, binary and Unicode contents, and unchanged source HEAD, refs, config and index. The prior three-strategy long object path checks remain intact.

Fixed upstream source blobs and hashes are recorded in the [source manifest](./worktree-longpaths-sources-2026-11-18.json). On November 18, the saved eight source/license files were checked against their SHA-256 and Git blob IDs, and the failing CI version's `setup.c` and `builtin/worktree.c` were fetched again at the fixed commit and matched byte for byte. Git for Windows source is used as diagnostic reference only; no upstream implementation is copied into the application.
