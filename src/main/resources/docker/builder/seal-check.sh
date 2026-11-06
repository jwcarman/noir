#!/usr/bin/env bash
# noir seal checks (spec §5.3). This script only produces artifacts in /work/out: one
# <step>.log and <step>.exit per step, JUnit XML reports, and PIT's mutations.xml.
# noir's SealJudge decides what they mean.
#
# Usage: seal-check.sh prepare   (network on)  clone, assumptions, stub build, dependencies
#        seal-check.sh check     (network off) holdout compile/run, reference build/runs, PIT,
#                                              existing tests against the reference
# State shared between phases lives in /work/out (repo, Maven cache, dependency jars).
#
# Inputs (read-only mounts): /work/base.bundle, /work/stubs, and for "check" also
# /work/holdout and /work/reference.
# Env: BASE_SHA, HOLDOUT_PKG, TARGET_CLASSES (comma-separated), TEST_TIMEOUT (e.g. 10s), RERUNS,
#      EXISTING_TESTS (yes|no: run the repository's tests against the reference) and
#      EXCLUDE_TESTS (comma-separated repository paths of test files left out of that run)
set -u

OUT=/work/out
REPO=/work/out/repo
M2=/work/out/m2
# The image's warm cache, read-only underneath $M2, so only what it lacks is downloaded.
MVN_REPO=(-Dmaven.repo.local="$M2" -Dmaven.repo.local.tail=/home/noir/.m2/repository)
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
  mvn -B -q "${MVN_REPO[@]}" "$HELP_PLUGIN:evaluate" -Dexpression=maven.compiler.release -DforceStdout >"$OUT/release.txt"
}

stub_build() {
  cp -R /work/stubs/. "$REPO/" && cd "$REPO" \
    && mvn -B "${MVN_REPO[@]}" -DskipTests compile "$DEPENDENCY_PLUGIN:copy-dependencies" \
      -DincludeScope=runtime -DoutputDirectory="$OUT/deps"
}

holdout_compile() {
  mkdir -p "$OUT/holdout-classes"
  find /work/holdout -name '*.java' >"$OUT/holdout-sources.txt"
  [ -s "$OUT/holdout-sources.txt" ] || { echo "no holdout sources"; return 1; }
  javac -d "$OUT/holdout-classes" -cp "$REPO/target/classes:$(deps :):$LAUNCHER" \
    @"$OUT/holdout-sources.txt"
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
    && mvn -B -o "${MVN_REPO[@]}" -DskipTests compile
}

# Restores src/test to the base commit: the stubs and the reference are overlaid on the whole
# repository, and must not replace, add or remove the repository's own tests.
restore_tests() {
  if git -C "$REPO" cat-file -e "$BASE_SHA:src/test" 2>/dev/null; then
    git -C "$REPO" checkout -q "$BASE_SHA" -- src/test || return 1
  fi
  git -C "$REPO" clean -fdxq -- src/test
}

# Deletes the EXCLUDE_TESTS files (the spec's tests to update) from the repository.
# exclude_tests: the files listed under "Tests to update" stay in place, so a listed helper still
# compiles for the tests that use it; surefire just does not run them. Writes the excludes file
# (one source path relative to src/test/java per line) that the existing-test steps pass on.
EXCLUDES_FILE=/tmp/noir-excluded-tests.txt
exclude_tests() {
  local path
  local -a excluded=()
  : >"$EXCLUDES_FILE"
  IFS=, read -ra excluded <<<"${EXCLUDE_TESTS:-}"
  for path in "${excluded[@]}"; do
    case "$path" in
      "" | /* | *..*) continue ;;
      src/test/java/*) printf '%s\n' "${path#src/test/java/}" >>"$EXCLUDES_FILE" ;;
    esac
  done
}

# Downloads what the offline existing-tests run needs. Surefire resolves its test-framework
# provider only when it finds test classes to run (-DskipTests, or a class filter matching
# nothing, skips that), so it runs every test class here with a method filter matching nothing.
test_deps() {
  restore_tests || return 1
  exclude_tests
  cd "$REPO" && mvn -B "${MVN_REPO[@]}" test "-Dsurefire.excludesFile=$EXCLUDES_FILE" \
    '-Dtest=*#noirResolveProvidersOnly' -Dsurefire.failIfNoSpecifiedTests=false
}

# The repository's own tests (as of the base commit) with the reference in place, minus the
# tests to update. A test fork still running after 600 seconds is killed.
existing_tests() {
  restore_tests || return 1
  exclude_tests
  cd "$REPO" || return 1
  rm -rf target/surefire-reports
  mvn -B -o "${MVN_REPO[@]}" -Dsurefire.timeout=600 "-Dsurefire.excludesFile=$EXCLUDES_FILE" test
  local rc=$?
  if [ -d target/surefire-reports ]; then
    cp -R target/surefire-reports "$OUT/existing-tests"
  fi
  return $rc
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

prepare_phase() {
  run clone clone || exit 0
  run assumptions assumptions || exit 0
  [ "$(cat "$OUT/pom.txt" 2>/dev/null)" = yes ] || exit 0
  run stub-build stub_build || exit 0
  [ "${EXISTING_TESTS:-no}" = yes ] && run test-deps test_deps
  exit 0
}

[ "${1:-}" = prepare ] && prepare_phase
[ "${1:-}" = check ] || { echo "usage: seal-check.sh prepare|check" >&2; exit 2; }
[ "$(cat "$OUT/stub-build.exit" 2>/dev/null)" = 0 ] || exit 0

run holdout-compile holdout_compile || exit 0
run stub-run holdout_run stub-run # failures are expected here
run ref-build ref_build || exit 0
for n in $(seq 1 "${RERUNS:-3}"); do
  run "ref-run-$n" holdout_run "ref-run-$n"
done
run pit pit
[ "${EXISTING_TESTS:-no}" = yes ] && run existing-tests existing_tests
exit 0
