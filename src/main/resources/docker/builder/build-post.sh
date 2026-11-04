#!/usr/bin/env bash
# noir builder handoff (spec §6.4). Runs after the agent, with no network and no credentials.
# Commits work the agent left uncommitted, makes sure the work is on $BRANCH, and writes
# /work/out.bundle (that branch) and /work/commits.txt (commits beyond $BASE_SHA).
#
# The repository was under the agent's control, so git runs with hooks and fsmonitor disabled and
# without system or global config. Everything here is confined to the attempt directory, which the
# agent controlled anyway; noir re-derives everything it judges from out.bundle.
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

rm -f /work/out.bundle /work/commits.txt /work/post-error.txt
cd /work/repo 2>/dev/null || fail "/work/repo is missing"
[ "$(g rev-parse --is-inside-work-tree 2>/dev/null)" = true ] \
  && [ "$(g rev-parse --show-toplevel 2>/dev/null)" = /work/repo ] \
  || fail "/work/repo is not a usable git repository"
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
