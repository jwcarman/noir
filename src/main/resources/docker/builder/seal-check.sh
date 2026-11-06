#!/usr/bin/env bash
# noir seal checks (spec §5.3). This script only produces artifacts in /work/out: one
# <step>.log and <step>.exit per step, JUnit XML reports, and PIT's mutations.xml.
# noir's SealJudge decides what they mean.
#
# Usage: seal-check.sh prepare   (network on)  clone, assumptions, stub build, dependencies
#        seal-check.sh check     (network off) holdout compile/run, reference build/runs, PIT,
#                                              existing tests against the reference
#        seal-check.sh inventory (network on)  the base commit's libraries and their packages:
#                                              libraries.txt and packages.txt
# State shared between phases lives in /work/out (repo, Maven cache, dependency jars).
#
# Inputs (read-only mounts): /work/base.bundle, /work/stubs, for "prepare" also /work/updated-tests
# (the reference's listed test files only), and for "check" also /work/holdout and /work/reference.
# "inventory" reads only /work/base.bundle.
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
      -DincludeScope=runtime -DoutputDirectory="$OUT/deps" \
    && mvn -B "${MVN_REPO[@]}" "$DEPENDENCY_PLUGIN:copy-dependencies" \
      -DincludeScope=test -DoutputDirectory="$OUT/deps" \
    && drop_junit_jars "$OUT/deps" \
    && drop_flagged_jars "$OUT/deps" \
    && ls "$OUT/deps" >"$OUT/deps.txt"
}

# JUnit's own jars never go on a hidden-test classpath from the repository; noir's launcher
# supplies JUnit.
drop_junit_jars() {
  mkdir -p "$1" && rm -f -- "$1"/junit-*.jar "$1"/opentest4j-*.jar "$1"/apiguardian-api-*.jar
}

# Neither do jars the grader's ForbiddenScan would reject (a test engine's JUnit service
# registration, a manifest Class-Path, ...): grading leaves them out the same way. Prints the
# names of the jars it deleted.
drop_flagged_jars() {
  java /opt/noir/bin/ForbiddenScan.java --drop-flagged-jars "$1"
}

# The repository's runtime and test-scope libraries at the base commit, without the stubs:
# libraries.txt (group:artifact:version, sorted) and packages.txt (the package of every class in
# their jars, sorted), both without the jars a hidden-test classpath leaves out (JUnit's own and
# those ForbiddenScan flags). A repository without a pom has neither.
inventory() {
  : >"$OUT/libraries.txt" && : >"$OUT/packages.txt" && cd "$REPO" || return 1
  [ -f pom.xml ] || return 0
  local listing=/tmp/noir-dependencies.txt dropped=/tmp/noir-dropped-jars.txt scope jar
  rm -f "$listing"
  for scope in runtime test; do
    mvn -B "${MVN_REPO[@]}" "$DEPENDENCY_PLUGIN:list" -DincludeScope="$scope" \
      -DoutputFile="$listing" -DappendOutput=true || return 1
    mvn -B "${MVN_REPO[@]}" "$DEPENDENCY_PLUGIN:copy-dependencies" -DincludeScope="$scope" \
      -DoutputDirectory="$OUT/deps" || return 1
  done
  drop_junit_jars "$OUT/deps" || return 1
  drop_flagged_jars "$OUT/deps" >"$dropped" || return 1
  cat "$dropped"
  # Lines look like "   group:artifact:type[:classifier]:version:scope[ -- module ...]"; the
  # artifact, version and classifier name the jar (artifact-version[-classifier].jar), so a library
  # is left out by the same file-name patterns as JUnit's jars, or when its jar was dropped.
  sed -e 's/ -- .*//' -e 's/^[[:space:]]*//' "$listing" \
    | awk -F: 'NF >= 5 {
        jar = $2 "-" $(NF - 1) (NF >= 6 ? "-" $4 : "") ".jar"
        print $1 ":" $2 ":" $(NF - 1) "\t" jar
      }' \
    | awk -F'\t' 'FILENAME == ARGV[1] { dropped[$0] = 1; next }
        !($2 in dropped) && $2 !~ /^(junit-|opentest4j-|apiguardian-api-)/ { print $1 }' \
      "$dropped" - \
    | LC_ALL=C sort -u >"$OUT/libraries.txt" || return 1
  local entries=/tmp/noir-jar-entries.txt
  : >"$entries"
  for jar in "$OUT"/deps/*.jar; do
    [ -f "$jar" ] || continue
    jar tf "$jar" >>"$entries" || return 1
  done
  # A class's package is its entry's directory (a multi-release jar's versions/N/ prefix dropped);
  # entries without one (module-info, the default package) have none.
  sed -nE '/\.class$/ { s#^META-INF/versions/[0-9]+/##; /\//! d; s#/[^/]*$##; s#/#.#g; p; }' \
    "$entries" | LC_ALL=C sort -u >"$OUT/packages.txt"
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

# Copies the Examiner's updated versions of the listed test files (EXCLUDE_TESTS) from the
# reference into the repository, after restore_tests; no other reference path reaches src/test.
# The listed test classes stay excluded from the run; an updated helper is what the other tests
# compile and run against. A listed path the reference does not contain keeps its base version.
copy_updated_tests() {
  local path dir=/work/reference
  local -a listed=()
  [ -d "$dir" ] || dir=/work/updated-tests # prepare: only the listed files, staged by noir
  IFS=, read -ra listed <<<"${EXCLUDE_TESTS:-}"
  for path in "${listed[@]}"; do
    case "$path" in
      "" | /* | *..*) continue ;;
    esac
    if [ -f "$dir/$path" ]; then
      mkdir -p "$(dirname "$REPO/$path")" && cp "$dir/$path" "$REPO/$path" || return 1
    fi
  done
}

# Downloads what the offline existing-tests run needs. Surefire resolves its test-framework
# provider only when it finds test classes to run (-DskipTests, or a class filter matching
# nothing, skips that), so it runs every test class here with a method filter matching nothing.
test_deps() {
  restore_tests || return 1
  copy_updated_tests || return 1
  exclude_tests
  cd "$REPO" && mvn -B "${MVN_REPO[@]}" test "-Dsurefire.excludesFile=$EXCLUDES_FILE" \
    '-Dtest=*#noirResolveProvidersOnly' -Dsurefire.failIfNoSpecifiedTests=false
}

# The repository's own tests (as of the base commit) with the reference in place, minus the
# tests to update. A test fork still running after 600 seconds is killed.
existing_tests() {
  restore_tests || return 1
  copy_updated_tests || return 1
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

inventory_phase() {
  run clone clone || exit 0
  run inventory inventory
  exit 0
}

[ "${1:-}" = prepare ] && prepare_phase
[ "${1:-}" = inventory ] && inventory_phase
[ "${1:-}" = check ] || { echo "usage: seal-check.sh prepare|check|inventory" >&2; exit 2; }
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
