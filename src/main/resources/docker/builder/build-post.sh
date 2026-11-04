#!/usr/bin/env bash
# noir builder handoff (spec §6.4). Runs after the agent, with no network and no credentials.
# Commits work the agent left uncommitted, makes sure the work is on $BRANCH, and writes
# /work/out.bundle (that branch) and /work/commits.txt (commits beyond $BASE_SHA).
#
# The repository was under the agent's control, and git runs code named in its configuration
# (filter drivers, fsmonitor, includes, attribute files). Before any git command, this replaces
# .git/config with a minimal noir-written one and deletes .git/info/attributes; git then runs with
# hooks and fsmonitor disabled and without system or global config. An undefined filter named in a
# committed .gitattributes is ignored by git. Everything here is confined to the attempt
# directory; noir re-derives everything it judges from out.bundle.
#
# If the agent left the repository unusable (no working git repository, or $BRANCH no longer
# descends from $BASE_SHA), this writes /work/post-error.txt instead of a bundle and exits 0: that
# is the builder's failure (NO_OUTPUT), not an infrastructure error.
#
# Mount: the attempt directory at /work (rw). Env: BRANCH, BASE_SHA.
set -uo pipefail

export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
g() {
  git -c core.hooksPath=/dev/null -c core.fsmonitor=false -c core.untrackedCache=false \
    -c safe.directory='*' -c commit.gpgsign=false \
    -c user.name=noir -c user.email=noir@noir.invalid "$@"
}

fail() {
  rm -f /work/out.bundle /work/commits.txt
  printf 'noir post step: %s\n' "$1" >/work/post-error.txt
  exit 0
}

# sanitize: a plain repository at /work/repo/.git with only noir's configuration.
sanitize() {
  local gitdir=/work/repo/.git
  [ -d /work/repo ] && [ ! -L /work/repo ] || fail "/work/repo is missing"
  [ -d "$gitdir" ] && [ ! -L "$gitdir" ] || fail "/work/repo/.git is not a plain directory"
  [ ! -e "$gitdir/commondir" ] && [ ! -L "$gitdir/commondir" ] \
    || fail "/work/repo is a linked worktree"
  rm -rf "$gitdir/config" "$gitdir/config.worktree" || fail "cannot replace .git/config"
  printf '[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = false\n' \
    >"$gitdir/config" || fail "cannot replace .git/config"
  if [ -L "$gitdir/info" ]; then
    rm -f "$gitdir/info" || fail "cannot remove .git/info"
  fi
  rm -rf "$gitdir/info/attributes" || fail "cannot remove .git/info/attributes"
  cd /work/repo || fail "/work/repo is missing"
  [ "$(g rev-parse --absolute-git-dir 2>/dev/null)" = "$gitdir" ] \
    && [ "$(g rev-parse --is-inside-work-tree 2>/dev/null)" = true ] \
    && [ "$(g rev-parse --show-toplevel 2>/dev/null)" = /work/repo ] \
    || fail "/work/repo is not a usable git repository"
}

rm -f /work/out.bundle /work/commits.txt /work/post-error.txt
sanitize
g rev-parse -q --verify "$BASE_SHA^{commit}" >/dev/null \
  || fail "the base commit $BASE_SHA is missing from the repository"
if [ "$(g symbolic-ref -q HEAD || true)" != "refs/heads/$BRANCH" ]; then
  g checkout -q -B "$BRANCH" || fail "cannot put the work on branch $BRANCH"
fi
g add -A -- . ':(exclude)target' || fail "git add failed"
if ! g diff --cached --quiet --no-ext-diff; then
  g commit -q --no-verify -m "noir: uncommitted work at agent exit" || fail "git commit failed"
fi
g merge-base --is-ancestor "$BASE_SHA" "refs/heads/$BRANCH" \
  || fail "the base commit $BASE_SHA is not an ancestor of $BRANCH (history was rewritten)"
g bundle create -q /work/out.bundle "refs/heads/$BRANCH" || fail "git bundle failed"
g rev-list --count "$BASE_SHA..refs/heads/$BRANCH" >/work/commits.txt || fail "git rev-list failed"
