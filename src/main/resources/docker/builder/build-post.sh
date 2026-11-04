#!/usr/bin/env bash
# noir builder handoff (spec §6.4). Runs after the agent, with no network and no credentials.
# Captures the agent's working tree as ONE commit, "noir: attempt work", on $BRANCH, and writes
# /work/out.bundle (that branch) and /work/commits.txt (commits beyond $BASE_SHA).
#
# Git never runs in the builder-written repository: its .git (config, filter drivers, includes,
# attribute files, nested repositories and gitlinks, submodule gitfiles) is attacker-controlled and
# git executes code named there. Instead this builds a fresh repository in /tmp/fresh (noir-owned,
# not under /work) from /work/input.bundle (noir's own bundle, re-copied by the host just before
# this step), copies the builder's working tree into it without any .git (directory or file, at
# any depth; symlinks are stored as symlinks and never followed), and commits that. The builder's
# own history is discarded: an attempt is one commit on top of the previous attempt's bundle.
#
# If this cannot complete, it writes /work/post-error.txt instead of a bundle and exits 0: the
# builder's failure (NO_OUTPUT), not an infrastructure error.
#
# Mount: the attempt directory at /work (rw). Env: BRANCH, BASE_SHA.
set -uo pipefail

export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_NO_REPLACE_OBJECTS=1
FRESH=/tmp/fresh
g() {
  git -C "$FRESH" -c core.hooksPath=/dev/null -c core.fsmonitor=false \
    -c core.untrackedCache=false -c commit.gpgsign=false \
    -c user.name=noir -c user.email=noir@noir.invalid "$@"
}

fail() {
  rm -f /work/out.bundle /work/commits.txt
  printf 'noir post step: %s\n' "$1" >/work/post-error.txt
  exit 0
}

rm -f /work/out.bundle /work/commits.txt /work/post-error.txt

# 1. A fresh repository from noir's bundle, on $BRANCH.
rm -rf "$FRESH" && git init -q "$FRESH" || fail "cannot create the fresh repository"
g fetch -q /work/input.bundle '+refs/*:refs/bundle/*' || fail "cannot read the input bundle"
if g rev-parse -q --verify "refs/bundle/heads/$BRANCH^{commit}" >/dev/null; then
  g checkout -q -B "$BRANCH" "refs/bundle/heads/$BRANCH" || fail "cannot check out $BRANCH"
else
  g checkout -q -b "$BRANCH" "$BASE_SHA" || fail "cannot create $BRANCH at $BASE_SHA"
fi

# 2. Replace the fresh working tree with the builder's, minus every .git (any case). tar does not
# follow symlinks when archiving (they are stored as links) and refuses unsafe names on extraction.
find "$FRESH" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} + \
  || fail "cannot clear the fresh working tree"
if [ -d /work/repo ] && [ ! -L /work/repo ]; then
  tar -C /work/repo --ignore-case --exclude=.git -cf - . | tar -C "$FRESH" --no-same-owner -xf - \
    || fail "cannot copy the working tree"
fi

# 3. One commit with everything that changed.
g add -A -- . ':(exclude)target' || fail "git add failed"
if ! g diff --cached --quiet --no-ext-diff; then
  g commit -q --no-verify -m "noir: attempt work" || fail "git commit failed"
fi
g bundle create -q /work/out.bundle "refs/heads/$BRANCH" || fail "git bundle failed"
g rev-list --count "$BASE_SHA..refs/heads/$BRANCH" >/work/commits.txt || fail "git rev-list failed"
