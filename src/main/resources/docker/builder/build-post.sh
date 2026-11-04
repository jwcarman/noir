#!/usr/bin/env bash
# noir builder handoff (spec §6.4). Runs after the agent, with no network and no credentials.
# Commits work the agent left uncommitted, makes sure the work is on $BRANCH, and writes
# /work/out.bundle (that branch) and /work/commits.txt (commits beyond $BASE_SHA).
#
# The repository was under the agent's control, so git runs with hooks and fsmonitor disabled and
# without system or global config. Everything here is confined to the attempt directory, which the
# agent controlled anyway; noir re-derives everything it judges from out.bundle.
#
# Mount: the attempt directory at /work (rw). Env: BRANCH, BASE_SHA.
set -euo pipefail

export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
g() {
  git -c core.hooksPath=/dev/null -c core.fsmonitor=false -c core.untrackedCache=false \
    -c safe.directory='*' -c commit.gpgsign=false \
    -c user.name=noir -c user.email=noir@noir.invalid "$@"
}

rm -f /work/out.bundle /work/commits.txt
cd /work/repo
if [ "$(g symbolic-ref -q HEAD || true)" != "refs/heads/$BRANCH" ]; then
  g checkout -q -B "$BRANCH"
fi
g add -A -- . ':(exclude)target'
if ! g diff --cached --quiet --no-ext-diff; then
  g commit -q --no-verify -m "noir: uncommitted work at agent exit"
fi
g bundle create -q /work/out.bundle "refs/heads/$BRANCH"
g rev-list --count "$BASE_SHA..refs/heads/$BRANCH" >/work/commits.txt
