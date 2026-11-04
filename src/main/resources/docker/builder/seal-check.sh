#!/usr/bin/env bash
# noir seal checks (spec §5.3). This script only produces artifacts in /work/out: one
# <step>.log and <step>.exit per step, JUnit XML reports, and PIT's mutations.xml.
# noir's SealJudge decides what they mean.
#
# Inputs (read-only mounts): /work/base.bundle, /work/stubs, /work/holdout, /work/reference
# Env: BASE_SHA, HOLDOUT_PKG, TARGET_CLASSES (comma-separated), TEST_TIMEOUT (e.g. 10s), RERUNS
set -u

OUT=/work/out
REPO=/tmp/repo
LAUNCHER=/opt/noir/lib/junit/junit-platform-console-standalone.jar
DEPENDENCY_PLUGIN=org.apache.maven.plugins:maven-dependency-plugin:3.11.0
HELP_PLUGIN=org.apache.maven.plugins:maven-help-plugin:3.5.2
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

deps() { # joined with $1
  find "$OUT/deps" -name '*.jar' 2>/dev/null | sort | paste -sd "$1" -
}

clone() {
  git clone -q /work/base.bundle "$REPO" && git -C "$REPO" checkout -q "$BASE_SHA"
}

assumptions() {
  cd "$REPO" || return 1
  if [ ! -f pom.xml ]; then
    echo no >"$OUT/pom.txt"
    return 0
  fi
  echo yes >"$OUT/pom.txt"
  if grep -q "<modules>" pom.xml; then echo yes >"$OUT/modules.txt"; else echo no >"$OUT/modules.txt"; fi
  mvn -B -q "$HELP_PLUGIN:evaluate" -Dexpression=maven.compiler.release -DforceStdout >"$OUT/release.txt"
}

stub_build() {
  cp -R /work/stubs/. "$REPO/" && cd "$REPO" \
    && mvn -B -DskipTests compile "$DEPENDENCY_PLUGIN:copy-dependencies" \
      -DincludeScope=runtime -DoutputDirectory="$OUT/deps"
}

holdout_compile() {
  mkdir -p "$OUT/holdout-classes"
  local sources
  sources=$(find /work/holdout -name '*.java')
  [ -n "$sources" ] || { echo "no holdout sources"; return 1; }
  # shellcheck disable=SC2086
  javac -d "$OUT/holdout-classes" -cp "$REPO/target/classes:$(deps :):$LAUNCHER" $sources
}

holdout_run() { # holdout_run <reports-dir-name>
  java -jar "$LAUNCHER" execute --disable-banner --details=none \
    --class-path "$REPO/target/classes:$(deps :):$OUT/holdout-classes" \
    --select-package "$HOLDOUT_PKG" \
    --reports-dir "$OUT/$1" \
    --config=junit.jupiter.extensions.autodetection.enabled=false \
    --config=junit.jupiter.execution.timeout.default="$TEST_TIMEOUT"
}

ref_build() {
  cp -R /work/reference/. "$REPO/" && cd "$REPO" && rm -rf target/classes \
    && mvn -B -o -DskipTests compile
}

pit() {
  local cp="$REPO/target/classes"
  local d
  d=$(deps ,)
  [ -n "$d" ] && cp="$cp,$d"
  java -cp '/opt/noir/lib/pitest/*:/opt/noir/lib/junit/*' \
    org.pitest.mutationtest.commandline.MutationCoverageReport \
    --reportDir "$OUT/pit" \
    --targetClasses "$TARGET_CLASSES" \
    --targetTests "$HOLDOUT_PKG.*" \
    --sourceDirs "$REPO/src/main/java" \
    --classPath "$cp,$OUT/holdout-classes,$LAUNCHER" \
    --outputFormats XML \
    --timestampedReports=false
}

run clone clone || exit 0
run assumptions assumptions || exit 0
[ "$(cat "$OUT/pom.txt" 2>/dev/null)" = yes ] || exit 0
run stub-build stub_build || exit 0
run holdout-compile holdout_compile || exit 0
run stub-run holdout_run stub-run # failures are expected here
run ref-build ref_build || exit 0
for n in $(seq 1 "${RERUNS:-3}"); do
  run "ref-run-$n" holdout_run "ref-run-$n"
done
run pit pit
exit 0
