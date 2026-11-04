#!/usr/bin/env bash
# noir builder handoff (spec §6.4). Runs after the agent, with no network and no credentials.
# Captures the agent's working tree as ONE commit, "noir: attempt work", on $BRANCH, and writes
# /work/out.bundle (that branch) and /work/commits.txt (commits this attempt added beyond its input
# head: the branch tip in input.bundle, or $BASE_SHA when the bundle has no such branch; 0 or 1).
#
# Git never runs in the builder-written repository: its .git (config, filter drivers, includes,
# attribute files, nested repositories and gitlinks, submodule gitfiles) is attacker-controlled and
# git executes code named there. Instead this builds a fresh repository in /tmp/fresh (noir-owned,
# not under /work) from /work/input.bundle (noir's own bundle, re-copied by the host just before
# this step), copies the builder's working tree into it without any .git (directory or file, at
# any depth; symlinks are stored as symlinks and never followed; at most 1 GiB apparent size,
# sparse files kept sparse), and commits that. The builder's
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
INPUT_HEAD=$(g rev-parse -q --verify "refs/heads/$BRANCH^{commit}") || fail "cannot resolve $BRANCH"

# 2. Replace the fresh working tree with the builder's. /work/repo must be a real directory: a
# missing or symlinked repository is NO_OUTPUT, not a commit that deletes everything.
REPO=/work/repo
[ -d "$REPO" ] && [ ! -L "$REPO" ] || fail "$REPO is missing or not a directory"

# The copy lands in this container's writable layer on the Docker host's disk, so its size is
# bounded before copying. du measures exactly what tar copies below, by apparent size (a sparse
# file costs the agent no blocks but would cost git its full length), counting every hard link
# separately (-l) and never following symlinks (they are copied as links). It excludes every .git
# and only the top-level target (git never commits it). A tree du cannot fully read is refused.
LIMIT=$((1024 * 1024 * 1024))
SIZE=$(timeout 120 du -s -l --apparent-size -B1 --exclude=.git --exclude="$REPO/target" "$REPO" \
  2>/dev/null) || fail "cannot measure the working tree"
SIZE=${SIZE%%[[:space:]]*}
[[ $SIZE =~ ^[0-9]+$ ]] || fail "cannot measure the working tree"
[ "$SIZE" -le "$LIMIT" ] || fail "working tree too large: $SIZE bytes (limit $LIMIT)"

# Copy minus every .git (any case) and the top-level target. tar does not follow symlinks when
# archiving (they are stored as links), keeps holes (-S, so sparse files stay sparse on extract)
# and refuses unsafe names on extraction.
find "$FRESH" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} + \
  || fail "cannot clear the fresh working tree"
tar -C "$REPO" -S --ignore-case --exclude=.git --no-ignore-case --exclude=./target -cf - . \
  | tar -C "$FRESH" --no-same-owner -xf - \
  || fail "cannot copy the working tree"

# 3. One commit with everything that changed.
g add -A -- . ':(exclude)target' || fail "git add failed"
if ! g diff --cached --quiet --no-ext-diff; then
  g commit -q --no-verify -m "noir: attempt work" || fail "git commit failed"
fi
g bundle create -q /work/out.bundle "refs/heads/$BRANCH" || fail "git bundle failed"
g rev-list --count "$INPUT_HEAD..refs/heads/$BRANCH" >/work/commits.txt || fail "git rev-list failed"
