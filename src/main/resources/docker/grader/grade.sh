#!/usr/bin/env bash
# noir grader (spec §8). Runs the sealed holdout tests against the builder's compiled classes.
# This script only produces artifacts in /work/out: one <step>.log and <step>.exit per step plus
# JUnit XML reports under run-N/. noir's GradeJudge decides what they mean.
#
# Mounts: /work/classes (ro), /work/deps (ro), /work/holdout (ro), /work/out (rw).
# Env: HOLDOUT_PKG, TEST_TIMEOUT (e.g. 10s), SUITE_TIMEOUT (e.g. 600s)
#
# Builder code is untrusted and runs in the test JVM, so that JVM runs under a SecurityManager
# with /opt/noir/grader.policy (builder code cannot write /work/out, exit, or add shutdown hooks).
set -u

OUT=/work/out
LAUNCHER=/opt/noir/lib/junit-platform-console-standalone.jar
POLICY=/opt/noir/grader.policy
HOLDOUT_CLASSES=$OUT/holdout-classes
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

# Safe because the pre-scan guarantees plain jar names before any test runs.
deps() {
  local out="" f
  while IFS= read -r -d '' f; do
    out="${out:+$out:}$f"
  done < <(find /work/deps -type f -name '*.jar' -print0 2>/dev/null | sort -z)
  echo "$out"
}

# Compile classpath: launcher, builder classes, dependencies.
compile_classpath() {
  local cp="$LAUNCHER:/work/classes"
  local d
  d=$(deps)
  [ -n "$d" ] && cp="$cp:$d"
  echo "$cp"
}

# Test classpath, handed to the launcher's own class loader: holdout classes, builder classes,
# dependencies. Holdout classes come before builder classes so a builder class cannot shadow a
# sealed test. It must not be the JVM classpath: the application class loader grants every class it
# loads RuntimePermission("exitVM"), which would let builder code halt the JVM.
test_classpath() {
  local cp="$HOLDOUT_CLASSES:/work/classes"
  local d
  d=$(deps)
  [ -n "$d" ] && cp="$cp:$d"
  echo "$cp"
}

# Pre-scan for builder entries that could subvert grading (see ForbiddenScan.java). It runs in its
# own JVM, never loads builder classes, and fails closed. Tests only run when its list is empty.
forbidden_check() {
  java /opt/noir/bin/ForbiddenScan.java /work/classes /work/deps "$OUT/forbidden.txt"
}

holdout_compile() {
  mkdir -p "$HOLDOUT_CLASSES"
  find /work/holdout -name '*.java' >"$OUT/holdout-sources.txt"
  [ -s "$OUT/holdout-sources.txt" ] || { echo "no holdout sources"; return 1; }
  javac -proc:none -implicit:none -d "$HOLDOUT_CLASSES" -cp "$(compile_classpath)" \
    @"$OUT/holdout-sources.txt"
}

holdout_run() { # holdout_run <reports-dir-name>
  timeout "$SUITE_TIMEOUT" java \
    -Djava.security.manager=default -Djava.security.policy==$POLICY \
    -cp "$LAUNCHER" \
    org.junit.platform.console.ConsoleLauncher execute --disable-banner --details=none \
    --class-path "$(test_classpath)" \
    --include-engine=junit-jupiter \
    --select-package "$HOLDOUT_PKG" \
    --reports-dir "$OUT/$1" \
    --config=junit.jupiter.extensions.autodetection.enabled=false \
    --config='junit.platform.execution.listeners.deactivate=*' \
    --config=junit.jupiter.execution.timeout.default="$TEST_TIMEOUT"
}

run forbidden forbidden_check
[ -s "$OUT/forbidden.txt" ] && exit 0
run holdout-compile holdout_compile || exit 0
run run-1 holdout_run run-1
if [ "$(cat "$OUT/run-1.exit")" != 0 ]; then
  run run-2 holdout_run run-2
  run run-3 holdout_run run-3
fi
exit 0
