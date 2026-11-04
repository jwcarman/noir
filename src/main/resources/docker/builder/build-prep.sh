#!/usr/bin/env bash
# noir builder prep (spec §6.4). Runs before the agent, with no network and no credentials.
# Clones /work/input.bundle into /work/repo and puts it on $BRANCH: the branch from the bundle if
# it has one (a rework attempt), else a new branch at $BASE_SHA.
#
# Mount: the attempt directory at /work (rw). Env: BRANCH, BASE_SHA.
set -euo pipefail

export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
REPO=/work/repo

rm -rf "$REPO"
git init -q "$REPO"
cd "$REPO"
git fetch -q /work/input.bundle '+refs/*:refs/bundle/*'
if git rev-parse -q --verify "refs/bundle/heads/$BRANCH^{commit}" >/dev/null; then
  git checkout -q -B "$BRANCH" "refs/bundle/heads/$BRANCH"
else
  git checkout -q -b "$BRANCH" "$BASE_SHA"
fi
# Drop the fetched refs so the agent sees an ordinary repository on one branch.
git for-each-ref --format='delete %(refname)' refs/bundle/ | git update-ref --stdin
git config user.name noir
git config user.email noir@noir.invalid
git config commit.gpgsign false
