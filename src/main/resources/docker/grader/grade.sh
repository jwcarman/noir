#!/usr/bin/env bash
# noir grader (spec §8). Runs the sealed holdout tests against the builder's compiled classes.
# This script only produces artifacts in /work/out: one <step>.log and <step>.exit per step plus
# JUnit XML reports under run-N/. noir's GradeJudge decides what they mean.
#
# Mounts: /work/classes (ro), /work/deps (ro), /work/holdout (ro), /work/out (rw).
# Env: HOLDOUT_PKG, TEST_TIMEOUT (e.g. 10s), SUITE_TIMEOUT (e.g. 600s)
set -u

OUT=/work/out
LAUNCHER=/opt/noir/lib/junit-platform-console-standalone.jar
mkdir -p "$OUT"

# run <step> <function>: runs the function, capturing its output and exit code.
run() {
  local name=$1
  shift
  ("$@") >"$OUT/$name.log" 2>&1
  local rc=$?
  echo "$rc" >"$OUT/$name.exit"
  return $rc
}

deps() {
  find /work/deps -name '*.jar' 2>/dev/null | sort | paste -sd : -
}

# Classpath order: launcher, builder classes, dependencies, compiled holdout tests.
classpath() {
  local cp="$LAUNCHER:/work/classes"
  local d
  d=$(deps)
  [ -n "$d" ] && cp="$cp:$d"
  echo "$cp"
}

holdout_compile() {
  mkdir -p "$OUT/holdout-classes"
  find /work/holdout -name '*.java' >"$OUT/holdout-sources.txt"
  [ -s "$OUT/holdout-sources.txt" ] || { echo "no holdout sources"; return 1; }
  javac -d "$OUT/holdout-classes" -cp "$(classpath)" @"$OUT/holdout-sources.txt"
}

holdout_run() { # holdout_run <reports-dir-name>
  timeout "$SUITE_TIMEOUT" java -cp "$(classpath):$OUT/holdout-classes" \
    org.junit.platform.console.ConsoleLauncher execute --disable-banner --details=none \
    --select-package "$HOLDOUT_PKG" \
    --reports-dir "$OUT/$1" \
    --config=junit.jupiter.extensions.autodetection.enabled=false \
    --config=junit.jupiter.execution.timeout.default="$TEST_TIMEOUT"
}

run holdout-compile holdout_compile || exit 0
run run-1 holdout_run run-1
if [ "$(cat "$OUT/run-1.exit")" != 0 ]; then
  run run-2 holdout_run run-2
  run run-3 holdout_run run-3
fi
exit 0
