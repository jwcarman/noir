#!/usr/bin/env bash
# noir measurement (spec §7). Rebuilds an attempt's branch from its bundle in clean containers and
# produces the artifacts noir's MeasureJudge reads. This script only produces artifacts; the judge
# decides what they mean.
#
# The builder controls the repository, the POM and the tests, so measurement runs in three phases,
# each in its own fresh container (ContainerMeasureRunner). A phase that executes builder code
# never has the judged output directory mounted, and every process it leaves behind is killed
# before its step records are written (and dies with its container in any case):
#
#   build    network on (Maven Central), no credentials. EXECUTES BUILDER CODE (Maven, the POM's
#            plugins, the builder's tests). Mounts: /work/attempt.bundle (ro), /work/build (rw
#            scratch, never judged). Clones the bundle, runs `mvn verify` with noir's JaCoCo agent
#            attached through JAVA_TOOL_OPTIONS (the POM's JaCoCo config is skipped and ignored),
#            and copies test and runtime dependencies. Step records go to /work/build/steps.
#   analyze  network off, no credentials. Runs NO builder code: only git on a fresh clone of the
#            bundle, cp, find, javac -proc:none on the noir-owned stubs, javap, noir's
#            ForbiddenScan and ClassScan, and the JaCoCo CLI (none of these load builder classes).
#            Mounts: /work/attempt.bundle (ro), /work/build (ro), /work/stubs (ro), /work/out (rw).
#            Writes every judged file except the build/deps/pit records and mutations.xml.
#   pit      network off, no credentials. EXECUTES BUILDER CODE (PIT runs the builder's tests).
#            Mounts: /work/build (ro), /work/pit (rw scratch, never judged). PIT's targets come
#            from analyze (TARGET_CLASSES), its tests are '*', and no builder PIT config applies.
#
# The host (ContainerMeasureRunner) then copies build/deps/pit step records and mutations.xml from
# the scratch directories into the output directory (regular files only, no symlinks, size and
# format checked) and writes stubs.txt itself. The data that originates in builder JVMs (the
# coverage exec file, PIT's kill results, the build exit) can be influenced by hostile tests no
# matter where it is written; the quality bar is a builder-facing guide and the holdout suite is
# the acceptance gate (spec §7).
#
# Usage: measure.sh build|analyze|pit
# Env: BRANCH, BASE_SHA; build and pit also STEP_TIMEOUT (seconds per builder-code step: a step
# that hangs is killed and records exit 124, a build or mutation-testing failure for the judge);
# analyze also STUB_CLASSES (comma-separated FQNs); pit also TARGET_CLASSES.
set -u
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0

BUILD=/work/build
OUT=/work/out
PIT=/work/pit
LIB=/opt/noir/lib
JACOCO_AGENT=$LIB/jacoco/org.jacoco.agent-runtime.jar
JACOCO_CLI=$LIB/jacoco/org.jacoco.cli-nodeps.jar
LAUNCHER=$LIB/junit/junit-platform-console-standalone.jar
DEPENDENCY_PLUGIN=org.apache.maven.plugins:maven-dependency-plugin:3.11.0
MAX_EXEC_BYTES=$((256 * 1024 * 1024))

# Kills every other process this user can signal (all but PID 1 and this shell): nothing a
# builder-code step started may outlive it.
reap() {
  kill -KILL -1 2>/dev/null
  return 0
}

# limited <function>: runs a builder-code step under STEP_TIMEOUT (exit 124 when it expires).
limited() {
  timeout -k 10s "${STEP_TIMEOUT:-1200}s" bash -c "$1"
}

# run <records-dir> <step> <function...>: runs the function, captures its output and exit code.
run() {
  local dir=$1 name=$2
  shift 2
  ("$@") >"$dir/$name.log" 2>&1 </dev/null
  local rc=$?
  reap
  echo "$rc" >"$dir/$name.exit"
  return $rc
}

g() {
  git -c core.hooksPath=/dev/null -c core.fsmonitor=false -c core.quotePath=false "$@"
}

# fresh_clone <dir>: a new repository with only the bundle's objects, on $BRANCH.
fresh_clone() {
  rm -rf "$1" && g init -q "$1" && cd "$1" \
    && g fetch -q --update-head-ok /work/attempt.bundle "+refs/heads/$BRANCH:refs/heads/$BRANCH" \
    && g checkout -q "$BRANCH"
}

# The packages of changed main sources, as JaCoCo includes ("a.b.*:c.*"). Only narrows what the
# agent records; it is not a judged artifact.
changed_includes() {
  local f d out=""
  while IFS= read -r f; do
    d=${f#src/main/java/}
    case "$d" in
      */*) d="${d%/*}"; d="${d//\//.}.*" ;;
      *) d="*" ;; # the unnamed package
    esac
    case ":$out:" in *":$d:"*) ;; *) out="${out:+$out:}$d" ;; esac
  done < <(g diff -z --name-only --diff-filter=AMR "$BASE_SHA" "refs/heads/$BRANCH" \
    -- 'src/main/java/*.java' | tr '\0\n' '\n?')
  echo "$out"
}

# ---------------------------------------------------------------- build (builder code runs)

build_clone() {
  fresh_clone "$BUILD/repo"
}

build() {
  cd "$BUILD/repo" || return 1
  rm -rf target # nothing committed under target/ (classes, reports, .exec) is used
  local includes agent=""
  includes=$(changed_includes)
  if [ -n "$includes" ]; then
    agent="-javaagent:$JACOCO_AGENT=destfile=$BUILD/jacoco.exec,append=true,includes=$includes"
  fi
  JAVA_TOOL_OPTIONS="$agent" mvn -B -Djacoco.skip=true verify
}

deps() {
  cd "$BUILD/repo" || return 1
  mvn -B -q "$DEPENDENCY_PLUGIN:copy-dependencies" -DincludeScope=test \
    -DoutputDirectory="$BUILD/test-deps" \
    && mvn -B -q "$DEPENDENCY_PLUGIN:copy-dependencies" -DincludeScope=runtime \
      -DoutputDirectory="$BUILD/runtime-deps" \
    && launcher
}

# PIT's JUnit 5 plugin needs a junit-platform-launcher matching the builder's platform version;
# fetch it when the builder's test dependencies have an engine but no launcher.
launcher() {
  local engine v
  ls "$BUILD/test-deps"/junit-platform-launcher-*.jar >/dev/null 2>&1 && return 0
  engine=$(ls "$BUILD/test-deps"/junit-platform-engine-*.jar 2>/dev/null | head -1)
  [ -n "$engine" ] || return 0
  v=${engine##*/junit-platform-engine-}
  v=${v%.jar}
  mvn -B -q "$DEPENDENCY_PLUGIN:copy" -Dartifact="org.junit.platform:junit-platform-launcher:$v" \
    -DoutputDirectory="$BUILD/test-deps"
}

build_phase() {
  mkdir -p "$BUILD/steps"
  run "$BUILD/steps" clone build_clone || exit 0
  run "$BUILD/steps" build limited build
  run "$BUILD/steps" deps limited deps
  exit 0
}

# ---------------------------------------------------------------- analyze (no builder code)

# real_dir <path>: an existing directory reached without any symlink.
real_dir() {
  [ -d "$1" ] && [ "$(realpath -e -- "$1" 2>/dev/null)" = "$1" ]
}

# copy_tree <src> <dst>: regular files and directories only; symlinks, FIFOs and the like are
# dropped. A missing or symlinked source gives an empty tree.
# Contents are written with cat, never cp: on Docker Desktop's macOS file sharing, cp's chmod of
# a freshly written file into the bind mount intermittently fails with "Operation not permitted"
# (noir-bookmarks #3, #5), which failed the step although every byte was copied.
copy_tree() {
  mkdir -p "$2" || return 1
  real_dir "$1" || return 0
  (
    cd -- "$1" || exit 1
    find . -type d -print0 | while IFS= read -r -d '' d; do
      mkdir -p -- "$2/$d" || exit 1
    done || exit 1
    find . -type f -print0 | while IFS= read -r -d '' f; do
      cat -- "$f" >"$2/$f" || exit 1
    done
  )
}

clone() {
  fresh_clone /tmp/repo
}

export_artifacts() {
  copy_tree "$BUILD/repo/target/classes" "$OUT/export/classes" \
    && copy_tree "$BUILD/runtime-deps" "$OUT/export/deps"
}

holdout_sources() {
  cd /tmp/repo || return 1
  find . -path ./.git -prune -o -type f -path '*/noir/holdout/*' -print | sed 's#^\./##' \
    >"$OUT/holdout-sources.txt"
}

# Paths come NUL-separated (-z), so no name is quoted or split. A name with a line break or other
# control character cannot be one line of changed.txt: it is written with those characters
# replaced by '?', which can never match a real source and so fails the judge's checks (closed).
changed() {
  cd /tmp/repo || return 1
  g diff -z --name-only --no-ext-diff --diff-filter=AMR "$BASE_SHA" "refs/heads/$BRANCH" \
    >/tmp/changed-all || return 1
  local f
  while IFS= read -r -d '' f; do
    case "$f" in
      *.java) printf '%s\n' "${f//[[:cntrl:]]/?}" ;;
    esac
  done </tmp/changed-all >"$OUT/changed.txt"
}

forbidden() {
  java /opt/noir/bin/ForbiddenScan.java "$OUT/export/classes" "$OUT/export/deps" \
    "$OUT/forbidden.txt"
}

generated() {
  java /opt/noir/bin/ClassScan.java "$OUT/export/classes" "$OUT/changed.txt" \
    "$OUT/generated.txt" "$OUT/target-classes.txt"
}

classpath() { # export/classes plus the exported dependency jars, joined with ':'
  local cp="$OUT/export/classes" f
  while IFS= read -r -d '' f; do
    cp="$cp:$f"
  done < <(find "$OUT/export/deps" -maxdepth 1 -type f -name '*.jar' -print0 | sort -z)
  echo "$cp"
}

api() {
  : >"$OUT/javap-stub.txt"
  : >"$OUT/javap-built.txt"
  mkdir -p /tmp/stub-classes
  find /work/stubs -type f -name '*.java' >/tmp/stub-sources.txt
  [ -s /tmp/stub-sources.txt ] || { echo "no stub sources"; return 1; }
  javac -proc:none -implicit:none -d /tmp/stub-classes -cp "$(classpath)" @/tmp/stub-sources.txt
  local rc=$? c
  IFS=, read -ra classes <<<"${STUB_CLASSES:-}"
  for c in "${classes[@]}"; do
    javap -public -cp /tmp/stub-classes "$c" >>"$OUT/javap-stub.txt"
    javap -public -cp "$OUT/export/classes" "$c" >>"$OUT/javap-built.txt"
  done
  return $rc
}

coverage() {
  [ -s "$OUT/target-classes.txt" ] || { echo "no changed classes"; return 0; }
  local exec="$BUILD/jacoco.exec"
  if [ ! -f "$exec" ] || [ -L "$exec" ] || [ "$(stat -c %s "$exec")" -gt "$MAX_EXEC_BYTES" ]; then
    echo "no coverage data (did the tests run?)"
    return 1
  fi
  cp -- "$exec" /tmp/jacoco.exec || return 1
  java -jar "$JACOCO_CLI" report /tmp/jacoco.exec --classfiles "$OUT/export/classes" \
    --sourcefiles /tmp/repo/src/main/java --xml "$OUT/jacoco.xml"
}

analyze_phase() {
  mkdir -p "$OUT"
  run "$OUT" clone clone || exit 0
  run "$OUT" export export_artifacts || exit 0
  run "$OUT" holdout-sources holdout_sources
  run "$OUT" changed changed || exit 0
  run "$OUT" forbidden forbidden
  run "$OUT" generated generated
  run "$OUT" api api
  run "$OUT" coverage coverage
  exit 0
}

# ---------------------------------------------------------------- pit (builder code runs)

pit() {
  # Work on a private copy: the scratch mount is read-only and builder tests may write files.
  cp -R -- "$BUILD/repo" /tmp/repo || return 1
  cd /tmp/repo || return 1
  local cp="/tmp/repo/target/classes,/tmp/repo/target/test-classes" f
  while IFS= read -r -d '' f; do
    cp="$cp,$f"
  done < <(find "$BUILD/test-deps" -maxdepth 1 -type f -name '*.jar' -print0 2>/dev/null | sort -z)
  java -cp "$LIB/pitest/*:$LIB/junit/*" \
    org.pitest.mutationtest.commandline.MutationCoverageReport \
    --reportDir "$PIT/report" \
    --targetClasses "$TARGET_CLASSES" \
    --targetTests '*' \
    --sourceDirs /tmp/repo/src/main/java \
    --classPath "$cp,$LAUNCHER" \
    --outputFormats XML \
    --timestampedReports=false \
    --failWhenNoMutations=false
}

pit_phase() {
  mkdir -p "$PIT"
  run "$PIT" pit limited pit
  exit 0
}

# limited steps run in a child bash: it needs the functions and settings.
export BUILD OUT PIT LIB JACOCO_AGENT JACOCO_CLI LAUNCHER DEPENDENCY_PLUGIN
export -f g changed_includes build deps launcher pit

case "${1:-}" in
  build) build_phase ;;
  analyze) analyze_phase ;;
  pit) pit_phase ;;
  *) echo "usage: measure.sh build|analyze|pit" >&2; exit 2 ;;
esac
