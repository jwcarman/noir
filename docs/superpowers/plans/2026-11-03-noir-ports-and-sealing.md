# noir Ports & Sealing Implementation Plan (Plan 2 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a hand-written draft into a sealed work item: pin the base commit, run the Examiner in a container to write holdout tests and a reference implementation, prove those tests are satisfiable, discriminating and deterministic, then create the GitHub issue, post the commitment hash, and move the draft into the store as `SEALED` — via `noir draft init` and `noir seal`.

**Architecture:** All external effects go through a single `CommandRunner` (process execution) wrapped by four ports: `GitPort` (`git`), `GitHubPort` (`gh`), `ContainerPort` (`docker`), `AgentPort` (`claude -p` inside a container). A noir-owned **builder image** carries JDK 21, Maven 3.10.0, Claude Code, the JUnit console launcher and PIT. Seal checks run as one container invocation of a noir-owned shell script that only *produces artifacts* (logs, exit codes, JUnit XML, PIT XML); a pure Java `SealJudge` turns those artifacts into problems. `Sealer` orchestrates and is unit-tested with fakes; Docker-backed tests are tagged `docker` and run in a profile.

**Tech Stack:** Plan 1 stack, plus: Docker (image `maven:3.10.0-eclipse-temurin-21-noble`), Node 22 (NodeSource) + `@anthropic-ai/claude-code@2.1.318`, `junit-platform-console-standalone` 6.1.3, PIT 1.30.1 (`pitest-command-line`) + `pitest-junit5-plugin` 1.2.3, `gh` CLI, `git` CLI.

**Spec:** `docs/superpowers/specs/2026-11-03-noir-v1-design.md` (§4, §5, §9). Builds on Plan 1: `docs/superpowers/plans/2026-11-03-noir-core-foundation.md`.

## Global Constraints

- Java 21, base package `org.jwcarman.noir`; all versions in `pom.xml` are properties.
- Code must pass `spotless:check` (google-java-format, GOOGLE style). Run `mvn -q spotless:apply` before every commit.
- No secrets in process argv: environment variables passed to containers use `docker run -e NAME` (name only) with the value set in the `docker` process environment.
- Claude auth: pass through whichever of `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN` is set in noir's environment; neither set → infrastructure error `"Set ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN"`.
- Containers run as non-root (`--user 1000:1000`), `--security-opt no-new-privileges`, CPU/memory/PID limits from `NoirConfig.limits()`, never with the Docker socket mounted (spec §6.2).
- Seal checks run with **network on** only for the Maven dependency step; the Examiner runs with network on (needs the Anthropic API).
- Examiner attempts: up to **3** (spec §5.2). Discrimination threshold default **0.70** (`NoirConfig.sealDiscrimination`). Reference reruns: **3** (spec §5.3 check 7). Holdout per-test timeout from `NoirConfig.timeouts().perTest()` (10 s).
- Labels and colors (created with `gh label create --force`): `noir:ready` `0e8a16`, `noir:building` `fbca04`, `noir:pr-open` `1d76db`, `noir:failed` `d93f0b`, `noir:blocked` `b60205`.
- Docker-backed tests carry `@Tag("docker")`; excluded by default, included with `mvn verify -Pdocker`. A Docker daemon must be running for that profile.
- Plan 1's `HoldoutAnalyzer`, `Commitments`, `SpecLint`, `SpecRenderer`, `Store`, `Event`, `WorkItem`, `NoirConfig` are reused as-is unless a task says otherwise.

## Review Focus

1. **`gh`/`git`/`docker` missing or unauthenticated:** the user gets one clear line naming the tool and how to fix it (e.g. `gh auth login`), not a stack trace; `noir seal` exits 2 → test in Task 12.
2. **Re-running `noir seal` after a crash between issue creation and the move into the store:** must reuse the recorded issue number, never create a duplicate issue → test in Task 11.
3. **The working tree is dirty or `HEAD` is not on the remote default branch:** sealing must still pin `origin/<default>` (what the PR will target), not the local `HEAD` → test in Task 2 / Task 11.
4. **Examiner writes files outside `holdout/` and `reference/`** (e.g. into `in/`, or symlinks): only regular files under those two dirs are copied into the draft; symlinks rejected → test in Task 10.
5. **Very large tool output** (Maven logs): problems include a bounded tail (last 60 lines), not the whole log → test in Task 9.

---

### Task 1: Command execution layer

**Files:**
- Create: `src/main/java/org/jwcarman/noir/exec/{Command,CommandResult,CommandRunner,ProcessCommandRunner,ToolMissingException}.java`
- Create (test util): `src/test/java/org/jwcarman/noir/exec/FakeCommandRunner.java`
- Test: `src/test/java/org/jwcarman/noir/exec/ProcessCommandRunnerTest.java`

**Interfaces:**
- Produces:
  - `record Command(List<String> argv, Path cwd, Map<String, String> env, Duration timeout, byte[] stdin)` with `static Command of(String... argv)` (cwd null = inherit, env empty, timeout 10 min, stdin null) and withers `inDir(Path)`, `withEnv(Map)`, `withTimeout(Duration)`, `withStdin(byte[])`, `withStdin(String)` (UTF-8).
  - `record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut)` with `boolean ok()` (`exitCode == 0 && !timedOut`) and `String tail(int lines)` (last N lines of stdout+stderr combined).
  - `interface CommandRunner { CommandResult run(Command c); }`
  - `final class ProcessCommandRunner implements CommandRunner` — env entries are **added** to the inherited environment; stdout/stderr drained concurrently; on timeout destroys the process tree (`ProcessHandle.descendants()` then `destroyForcibly`) and returns `timedOut=true, exitCode=-1`; executable not found → `ToolMissingException(String tool)` with message `"<tool> is not installed or not on PATH"`.
  - Test util `FakeCommandRunner implements CommandRunner`: records every `Command` in `List<Command> commands()`; responses queued by `whenArgvStartsWith(List<String> prefix, CommandResult result)`; unmatched → `AssertionError` naming the argv.

- [ ] **Step 1: Write failing tests**

```java
@Test void capturesStdoutStderrAndExit() {
  var r = runner.run(Command.of("sh", "-c", "echo out; echo err >&2; exit 3"));
  assertThat(r.exitCode()).isEqualTo(3);
  assertThat(r.stdout()).isEqualTo("out\n");
  assertThat(r.stderr()).isEqualTo("err\n");
  assertThat(r.ok()).isFalse();
}
@Test void feedsStdin() {
  assertThat(runner.run(Command.of("cat").withStdin("hello")).stdout()).isEqualTo("hello");
}
@Test void addsEnvAndCwd(@TempDir Path dir) {
  var r = runner.run(Command.of("sh", "-c", "echo $NOIR_X; pwd").withEnv(Map.of("NOIR_X", "42")).inDir(dir));
  assertThat(r.stdout()).startsWith("42\n").contains(dir.toRealPath().toString());
}
@Test void timesOutAndKillsChildren() {
  var r = runner.run(Command.of("sh", "-c", "sleep 30 & sleep 30").withTimeout(Duration.ofMillis(300)));
  assertThat(r.timedOut()).isTrue();
  assertThat(r.ok()).isFalse();
}
@Test void missingToolIsNamed() {
  assertThatThrownBy(() -> runner.run(Command.of("noir-no-such-tool")))
      .isInstanceOf(ToolMissingException.class)
      .hasMessage("noir-no-such-tool is not installed or not on PATH");
}
@Test void tailCombinesStreams() {
  assertThat(new CommandResult(1, "a\nb\n", "c\n", false).tail(2)).isEqualTo("b\nc");
}
@Test void largeOutputDoesNotDeadlock() {
  var r = runner.run(Command.of("sh", "-c", "yes x | head -c 2000000; yes y | head -c 2000000 >&2"));
  assertThat(r.stdout()).hasSize(2_000_000);
  assertThat(r.stderr()).hasSize(2_000_000);
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=ProcessCommandRunnerTest` — Expected: compilation failure.
- [ ] **Step 3: Implement** the records, interface, `ProcessCommandRunner`, `ToolMissingException`, and `FakeCommandRunner`.
- [ ] **Step 4: Run** `mvn -q test -Dtest=ProcessCommandRunnerTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `mvn -q spotless:apply && git add -A src && git commit -m "feat: command execution layer"`

---

### Task 2: Git port

**Files:**
- Create: `src/main/java/org/jwcarman/noir/git/{GitPort,GitCli,GitException}.java`
- Test: `src/test/java/org/jwcarman/noir/git/GitCliTest.java` (uses real `git` against temp repos)
- Test util: `src/test/java/org/jwcarman/noir/git/GitFixtures.java` — `static Path originWithCommit(Path root, String ownerRepo)` creates a bare repo at `root/<owner>/<repo>.git` with one commit on `main` containing a single-module Maven project (`pom.xml` with `maven.compiler.release` 21 + `src/main/java/com/example/api/Placeholder.java`), and `static Path cloneOf(Path origin, Path dest)`.

**Interfaces:**
- Consumes: `CommandRunner`, `Command` (Task 1).
- Produces:
  - `interface GitPort`:
    - `void fetch(Path repoDir, String branch)` — `git -C <dir> fetch --quiet origin <branch>`
    - `String revParse(Path repoDir, String ref)` — `git -C <dir> rev-parse --verify <ref>^{commit}`, trimmed
    - `String originRepo(Path repoDir)` — `git -C <dir> remote get-url origin`, parsed to `owner/name`: last two path segments, `.git` stripped; handles `https://github.com/o/r(.git)`, `git@github.com:o/r(.git)`, and local paths `/x/o/r.git`
    - `void bundle(Path repoDir, String sha, Path out)` — `update-ref refs/noir/seal <sha>`, `bundle create <out> refs/noir/seal`, then `update-ref -d refs/noir/seal` in a `finally`
  - `GitException(String message)` — non-zero exit; message = `"git <subcommand> failed: " + result.tail(20)`.

- [ ] **Step 1: Write failing tests**

```java
@Test void pinsOriginDefaultBranchNotLocalHead(@TempDir Path tmp) throws Exception {
  var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");
  var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
  var originSha = git.revParse(clone, "origin/main");
  Files.writeString(clone.resolve("local.txt"), "x");
  run(clone, "git", "add", "."); run(clone, "git", "commit", "-qm", "local only");
  git.fetch(clone, "main");
  assertThat(git.revParse(clone, "origin/main")).isEqualTo(originSha);
  assertThat(git.revParse(clone, "HEAD")).isNotEqualTo(originSha);
}
@Test void parsesOriginRepo(@TempDir Path tmp) throws Exception {
  var clone = GitFixtures.cloneOf(GitFixtures.originWithCommit(tmp.resolve("r"), "jwcarman/demo"), tmp.resolve("c"));
  assertThat(git.originRepo(clone)).isEqualTo("jwcarman/demo");
}
@Test void parsesUrlForms() {
  assertThat(GitCli.ownerRepo("https://github.com/jwcarman/demo.git")).isEqualTo("jwcarman/demo");
  assertThat(GitCli.ownerRepo("https://github.com/jwcarman/demo")).isEqualTo("jwcarman/demo");
  assertThat(GitCli.ownerRepo("git@github.com:jwcarman/demo.git")).isEqualTo("jwcarman/demo");
}
@Test void bundleClonesToPinnedShaAndLeavesNoRef(@TempDir Path tmp) throws Exception {
  var clone = GitFixtures.cloneOf(GitFixtures.originWithCommit(tmp.resolve("r"), "jwcarman/demo"), tmp.resolve("c"));
  var sha = git.revParse(clone, "origin/main");
  git.bundle(clone, sha, tmp.resolve("base.bundle"));
  run(tmp, "git", "clone", "-q", tmp.resolve("base.bundle").toString(), "restored");
  assertThat(git.revParse(tmp.resolve("restored"), "HEAD")).isEqualTo(sha);
  assertThat(runOut(clone, "git", "for-each-ref", "refs/noir")).isEmpty();
}
@Test void badRefFails(@TempDir Path tmp) throws Exception {
  var clone = GitFixtures.cloneOf(GitFixtures.originWithCommit(tmp.resolve("r"), "jwcarman/demo"), tmp.resolve("c"));
  assertThatThrownBy(() -> git.revParse(clone, "nope")).isInstanceOf(GitException.class).hasMessageStartingWith("git rev-parse failed");
}
```

(`run`/`runOut` are local helpers using `ProcessCommandRunner`.)

- [ ] **Step 2: Run** `mvn -q test -Dtest=GitCliTest` — Expected: compilation failure.
- [ ] **Step 3: Implement** `GitPort`, `GitCli` (with `static String ownerRepo(String url)`), `GitException`, `GitFixtures`.
- [ ] **Step 4: Run** `mvn -q test -Dtest=GitCliTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: git port (fetch, rev-parse, origin repo, bundle)"`

---

### Task 3: GitHub port

**Files:**
- Create: `src/main/java/org/jwcarman/noir/github/{GitHubPort,GhCli,GitHubException,NoirLabels}.java`
- Create (test util): `src/test/java/org/jwcarman/noir/github/FakeGitHub.java`
- Test: `src/test/java/org/jwcarman/noir/github/GhCliTest.java` (argv via `FakeCommandRunner`)

**Interfaces:**
- Consumes: `CommandRunner` (Task 1).
- Produces:
  - `interface GitHubPort`: `String defaultBranch(String repo)`; `int createIssue(String repo, String title, String body, List<String> labels)`; `void comment(String repo, int issue, String body)`; `void ensureLabels(String repo)` (creates all of `NoirLabels.ALL`).
  - `record NoirLabel(String name, String color, String description)`; `final class NoirLabels { static final List<NoirLabel> ALL; }` with the five labels/colors from Global Constraints; descriptions: `"Sealed; waiting for the factory"`, `"The factory is working on this"`, `"PR opened by the factory"`, `"The factory could not satisfy this"`, `"Needs human attention"`.
  - `GhCli` argv (bodies always via `--body-file -` on stdin):
    - default branch: `gh api repos/<repo> --jq .default_branch`
    - issue: `gh issue create --repo <repo> --title <title> --body-file - --label <l>...` → issue number parsed from the URL on stdout (`.../issues/<n>`)
    - comment: `gh issue comment <n> --repo <repo> --body-file -`
    - label: `gh label create <name> --repo <repo> --color <c> --description <d> --force`
  - `GitHubException`: non-zero exit; if stderr contains `gh auth login`, message is `"GitHub CLI is not authenticated; run: gh auth login"`, else `"gh <subcommand> failed: " + tail(20)`.
  - Test util `FakeGitHub implements GitHubPort`: in-memory issues (`record Issue(int number, String title, String body, List<String> labels, List<String> comments)`), `defaultBranch` configurable (default `"main"`), `List<String> ensuredRepos()`, and `failNextCreateIssue()` for crash tests.

- [ ] **Step 1: Write failing tests** — for each operation, queue a `CommandResult` on `FakeCommandRunner`, call the method, assert the recorded argv and stdin exactly, and the parsed return value; plus:

```java
@Test void createIssueParsesNumberFromUrl() {
  runner.whenArgvStartsWith(List.of("gh", "issue", "create"), new CommandResult(0, "https://github.com/jwcarman/demo/issues/42\n", "", false));
  assertThat(gh.createIssue("jwcarman/demo", "T", "body", List.of("noir:ready"))).isEqualTo(42);
  var c = runner.commands().getFirst();
  assertThat(c.argv()).containsExactly("gh", "issue", "create", "--repo", "jwcarman/demo", "--title", "T", "--body-file", "-", "--label", "noir:ready");
  assertThat(new String(c.stdin(), UTF_8)).isEqualTo("body");
}
@Test void unauthenticatedIsExplained() {
  runner.whenArgvStartsWith(List.of("gh", "api"), new CommandResult(4, "", "To get started with GitHub CLI, please run:  gh auth login\n", false));
  assertThatThrownBy(() -> gh.defaultBranch("jwcarman/demo")).hasMessage("GitHub CLI is not authenticated; run: gh auth login");
}
@Test void ensureLabelsCreatesAllFive() { /* five `gh label create ... --force` commands with exact colors */ }
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=GhCliTest` — Expected: compilation failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -q test -Dtest=GhCliTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: GitHub port via gh CLI"`

---

### Task 4: Container port

**Files:**
- Create: `src/main/java/org/jwcarman/noir/docker/{ContainerPort,DockerCli,ContainerSpec,Mount,ContainerResult,DockerException}.java`
- Create (test util): `src/test/java/org/jwcarman/noir/docker/FakeContainerPort.java`
- Test: `src/test/java/org/jwcarman/noir/docker/DockerCliTest.java` (argv via `FakeCommandRunner`)

**Interfaces:**
- Consumes: `CommandRunner` (Task 1), `NoirConfig.Limits` (Plan 1).
- Produces:
  - `record Mount(Path host, String container, boolean readOnly)` with `static Mount ro(Path, String)` / `rw(Path, String)`.
  - `record ContainerSpec(String image, List<String> command, List<Mount> mounts, Map<String, String> env, boolean network, NoirConfig.Limits limits, String workdir, Duration timeout, byte[] stdin)` with `static ContainerSpec of(String image, String... command)` and withers for each field.
  - `record ContainerResult(int exitCode, String stdout, String stderr, boolean timedOut, String imageId)` with `ok()` and `tail(int)` as in `CommandResult`.
  - `interface ContainerPort`: `ContainerResult run(ContainerSpec spec)`; `Optional<String> imageId(String image)` (`docker image inspect --format {{.Id}} <image>`; non-zero → empty); `void build(Path contextDir, String tag)` (`docker build -q -t <tag> <dir>`, timeout 30 min).
  - `DockerCli.run` argv, in this order: `docker run --rm --init -i --user 1000:1000 --security-opt no-new-privileges --cpus <cpus> --memory <memory> --pids-limit <pids> --network <bridge|none> [-w <workdir>] [-v <host-real-path>:<container>[:ro]]... [-e <NAME>]... <image> <command...>`. Env **values** go in the `docker` process env, names only in argv. `imageId` is filled from `imageId(image)` before running. Docker daemon unreachable (stderr contains `Cannot connect to the Docker daemon`) → `DockerException("Docker is not running; start Docker Desktop")`.
  - Test util `FakeContainerPort`: records specs; responses via a `Function<ContainerSpec, ContainerResult>` handler that may also write files into mounted host dirs.

- [ ] **Step 1: Write failing tests**

```java
@Test void runArgvIsHardenedAndHidesEnvValues(@TempDir Path dir) throws Exception {
  runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), new CommandResult(0, "sha256:abc\n", "", false));
  runner.whenArgvStartsWith(List.of("docker", "run"), new CommandResult(0, "ok", "", false));
  var spec = ContainerSpec.of("noir-builder:0.1.0", "sh", "-c", "true")
      .withMounts(List.of(Mount.ro(dir, "/work/in")))
      .withEnv(Map.of("ANTHROPIC_API_KEY", "sk-secret"))
      .withNetwork(false)
      .withLimits(new NoirConfig.Limits(2.0, "4g", 512))
      .withWorkdir("/work");
  var r = docker.run(spec);
  var run = runner.commands().get(1);
  assertThat(run.argv()).containsExactly("docker", "run", "--rm", "--init", "-i", "--user", "1000:1000",
      "--security-opt", "no-new-privileges", "--cpus", "2.0", "--memory", "4g", "--pids-limit", "512",
      "--network", "none", "-w", "/work", "-v", dir.toRealPath() + ":/work/in:ro", "-e", "ANTHROPIC_API_KEY",
      "noir-builder:0.1.0", "sh", "-c", "true");
  assertThat(String.join(" ", run.argv())).doesNotContain("sk-secret");
  assertThat(run.env()).containsEntry("ANTHROPIC_API_KEY", "sk-secret");
  assertThat(r.imageId()).isEqualTo("sha256:abc");
}
@Test void neverMountsDockerSocket() {
  assertThatThrownBy(() -> docker.run(ContainerSpec.of("img").withMounts(List.of(Mount.rw(Path.of("/var/run/docker.sock"), "/s")))))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("docker.sock");
}
@Test void daemonDownIsExplained() { /* docker image inspect stderr "Cannot connect to the Docker daemon" → DockerException message */ }
@Test void missingImageIsEmpty() { /* inspect exit 1 → Optional.empty() */ }
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=DockerCliTest` — Expected: compilation failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -q test -Dtest=DockerCliTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: hardened container port via docker CLI"`

---

### Task 5: Config additions, CRAP in the quality bar, and the Docker test profile

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/config/NoirConfig.java`
- Modify: `src/main/java/org/jwcarman/noir/spec/{QualityBar,SpecLint}.java`
- Modify: `src/main/java/org/jwcarman/noir/render/SpecRenderer.java`
- Modify: tests `ConfigLoaderTest`, `SpecLintTest`, `SpecRendererTest`, `Specs` fixture, and any test constructing `QualityBar`
- Modify: `pom.xml`

**Interfaces:**
- Produces:
  - New component on `NoirConfig` (appended last): `Examiner examiner` with `record Examiner(String model, int maxTurns, Duration wallClock, int attempts)`; defaults `(null, 100, 30 min, 3)`. `model == null` means the Claude CLI default.
  - `Images` default changes to `new Images("noir-builder:0.1.0", null)`.
  - `QualityBar` becomes `record QualityBar(double lineCoverage, double mutationScore, int maxCrap)` (spec §4.1, §7.1); default `(0.80, 0.70, 30)` in config and in `Specs.valid()`.
  - `SpecLint` adds `"qualityBar.maxCrap must be at least 1"` when `maxCrap < 1`.
  - `SpecRenderer` quality-bar section gains a third bullet: `- Max CRAP score per method ≤ 30`.
  - `pom.xml`: property `surefire.excludedGroups` = `docker`, surefire `<excludedGroups>${surefire.excludedGroups}</excludedGroups>`; profile `docker` sets the property to empty.

- [ ] **Step 1: Update tests** — `SpecLintTest.maxCrapAtLeastOne` (`new QualityBar(0.8, 0.7, 0)` → exactly that problem); `SpecRendererTest.sectionsInOrder` also expects `"Max CRAP score per method ≤ 30"`; `ConfigLoaderTest` expects default `new QualityBar(0.80, 0.70, 30)` and overlay `qualityBar: { maxCrap: 15 }` keeps the other two values; in `missingFileGivesDefaults` assert `c.examiner()` equals `new NoirConfig.Examiner(null, 100, Duration.ofMinutes(30), 3)` and `c.images()` equals `new NoirConfig.Images("noir-builder:0.1.0", null)`; add `examinerOverlay` loading `examiner: { model: claude-opus-5-5, attempts: 2 }`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=ConfigLoaderTest` — Expected: FAIL (no `examiner()`).
- [ ] **Step 3: Implement** and update `pom.xml`. Add a trivial `@Tag("docker")` test `DockerProfileSmokeTest` asserting `docker version` succeeds via `ProcessCommandRunner`.
- [ ] **Step 4: Run** `mvn -q verify` — Expected: PASS, and `DockerProfileSmokeTest` is **not** run (check `target/surefire-reports` has no file for it). Then `mvn -q verify -Pdocker -Dtest=DockerProfileSmokeTest` with Docker running — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: examiner config, CRAP quality bar, builder image default, docker test profile"`

---

### Task 6: The builder image

**Files:**
- Create: `src/main/resources/docker/builder/Dockerfile`
- Create: `src/main/resources/docker/builder/toolchain/pom.xml`
- Create: `src/main/resources/docker/builder/seal-check.sh` (body written in Task 9; this task creates a placeholder that exits 0 and is copied to `/opt/noir/bin/seal-check.sh`)
- Create: `src/main/java/org/jwcarman/noir/docker/Images.java`
- Test: `src/test/java/org/jwcarman/noir/docker/BuilderImageIT.java` (`@Tag("docker")`)

**Interfaces:**
- Consumes: `ContainerPort` (Task 4), `NoirConfig.images()` (Task 5).
- Produces:
  - `final class Images`: `static String ensureBuilder(ContainerPort docker, String tag)` — if `imageId(tag)` is empty, extracts `docker/builder/**` from the classpath into a temp dir and runs `build(dir, tag)`; returns the image id.
  - Image layout: `/opt/noir/lib/junit/junit-platform-console-standalone.jar`, `/opt/noir/lib/pitest/*.jar` (pitest, pitest-entry, pitest-command-line, pitest-junit5-plugin, and their runtime deps), `/opt/noir/bin/seal-check.sh` (mode 755), user `noir` uid/gid 1000 with `HOME=/home/noir`, `claude` on PATH, Maven local repo at `/home/noir/.m2` owned by `noir`.

Dockerfile decisions (the implementer writes the file from these):
- `FROM maven:3.10.0-eclipse-temurin-21-noble`
- Install `git`, `curl`, `ca-certificates`; Node 22 via `https://deb.nodesource.com/setup_22.x`; `npm install -g @anthropic-ai/claude-code@2.1.318`.
- Version pins as `ARG`s at the top: `CLAUDE_CODE_VERSION=2.1.318`, plus the toolchain pom's properties `junit.version=6.1.3`, `pitest.version=1.30.1`, `pitest-junit5.version=1.2.3`.
- Toolchain jars fetched at build time with `mvn -B -q -f /tmp/toolchain/pom.xml dependency:copy-dependencies -DoutputDirectory=...` (two executions or two invocations: `junit` and `pitest` groups).

- [ ] **Step 1: Write the failing test**

```java
@Tag("docker")
class BuilderImageIT {
  static final String TAG = "noir-builder:it";
  static ContainerPort docker = new DockerCli(new ProcessCommandRunner());

  @BeforeAll static void build() { Images.ensureBuilder(docker, TAG); }

  @Test void hasPinnedTools() {
    var r = docker.run(ContainerSpec.of(TAG, "sh", "-c",
        "java -version 2>&1 | head -1; mvn -v | head -1; claude --version; id -u; ls /opt/noir/lib/junit /opt/noir/lib/pitest; test -x /opt/noir/bin/seal-check.sh && echo seal-ok")
        .withNetwork(false).withLimits(NoirConfig.defaults().limits()));
    assertThat(r.ok()).as(r.tail(40)).isTrue();
    assertThat(r.stdout()).contains("\"21.").contains("Apache Maven 3.10.0").contains("2.1.318")
        .contains("1000").contains("junit-platform-console-standalone").contains("pitest-command-line")
        .contains("pitest-junit5-plugin").contains("seal-ok");
  }

  @Test void pitRunsJUnit6Tests() {
    // Mounts src/test/resources/fixtures/pit-smoke (one class + one JUnit Jupiter test), compiles both
    // with javac in the container, runs PIT command line, and asserts mutations.xml contains a KILLED mutant.
  }
}
```

- [ ] **Step 2: Run** `mvn -q verify -Pdocker -Dtest=BuilderImageIT` — Expected: FAIL (no Dockerfile / `Images`).
- [ ] **Step 3: Implement** the Dockerfile, toolchain pom, placeholder script, `Images`, and the `pit-smoke` fixture.
- [ ] **Step 4: Run** `mvn -q verify -Pdocker -Dtest=BuilderImageIT` — Expected: PASS. **If `pitRunsJUnit6Tests` fails because `pitest-junit5-plugin` 1.2.3 cannot drive JUnit Platform 6**, set the toolchain's `junit.version` to `5.14.4` (holdout tests use only `org.junit.jupiter.*`, which is source-compatible), re-run, and ledger the ruling. Do not change noir's own test dependency.
- [ ] **Step 5: Commit** — `git commit -m "feat: builder image with JDK 21, Maven, Claude Code, JUnit console, PIT"`

---

### Task 7: Agent port (Claude in a container)

**Files:**
- Create: `src/main/java/org/jwcarman/noir/agent/{AgentPort,AgentRequest,AgentResult,ClaudeContainerAgent,ClaudeJson,AgentAuth}.java`
- Create (test util): `src/test/java/org/jwcarman/noir/agent/ScriptedAgent.java`
- Test: `src/test/java/org/jwcarman/noir/agent/{ClaudeJsonTest,ClaudeContainerAgentTest,AgentAuthTest}.java`

**Interfaces:**
- Consumes: `ContainerPort`, `ContainerSpec`, `Mount` (Task 4).
- Produces:
  - `record AgentRequest(String image, String systemPrompt, String prompt, Path workDir, String model, int maxTurns, Duration wallClock, NoirConfig.Limits limits)`. `workDir` is mounted read-write at `/work`; the container's working directory is `/work`.
  - `record AgentResult(int exitCode, boolean error, boolean timedOut, BigDecimal costUsd, int turns, String model, String resultText, String imageId)`.
  - `interface AgentPort { AgentResult run(AgentRequest r); }`
  - `final class AgentAuth`: `static Map<String, String> fromEnvironment(Map<String, String> env)` → the single entry for `ANTHROPIC_API_KEY` if set, else `CLAUDE_CODE_OAUTH_TOKEN` if set, else throws `IllegalStateException("Set ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN")`.
  - `ClaudeContainerAgent(ContainerPort docker, Map<String, String> auth)`: runs `claude -p --output-format json --max-turns <n> --dangerously-skip-permissions --append-system-prompt <systemPrompt> [--model <model>]` with the prompt on **stdin**, network on, env = auth + `HOME=/home/noir`, timeout = `wallClock`. `model` in the result = requested model or `"default"`.
  - `final class ClaudeJson`: `static AgentResult parse(ContainerResult r, String requestedModel)` reads the CLI's JSON object: `is_error` (bool), `num_turns` (int), `total_cost_usd` (number), `result` (string). Missing/unparseable stdout → `error=true`, cost 0, turns 0, `resultText` = `r.tail(40)`.
  - Test util `ScriptedAgent implements AgentPort`: constructed with a `Consumer<Path>` that writes files into `workDir`; returns `AgentResult(0, false, false, ONE, 3, "scripted", "done", "scripted")`; records requests.

- [ ] **Step 1: Write failing tests**

```java
@Test void parsesCliJson() {
  var json = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":7,\"total_cost_usd\":0.4213,\"result\":\"Wrote 9 tests\",\"session_id\":\"s\"}";
  var r = ClaudeJson.parse(new ContainerResult(0, json, "", false, "sha256:x"), "claude-opus-5-5");
  assertThat(r).isEqualTo(new AgentResult(0, false, false, new BigDecimal("0.4213"), 7, "claude-opus-5-5", "Wrote 9 tests", "sha256:x"));
}
@Test void garbageStdoutIsError() {
  var r = ClaudeJson.parse(new ContainerResult(1, "boom", "trace", false, "id"), null);
  assertThat(r.error()).isTrue(); assertThat(r.model()).isEqualTo("default"); assertThat(r.resultText()).contains("trace");
}
@Test void containerInvocation(@TempDir Path work) {
  var docker = new FakeContainerPort(spec -> new ContainerResult(0, "{\"is_error\":false,\"num_turns\":1,\"total_cost_usd\":0.1,\"result\":\"ok\"}", "", false, "id"));
  new ClaudeContainerAgent(docker, Map.of("CLAUDE_CODE_OAUTH_TOKEN", "tok")).run(
      new AgentRequest("noir-builder:0.1.0", "SYS", "PROMPT", work, null, 100, Duration.ofMinutes(30), NoirConfig.defaults().limits()));
  var spec = docker.specs().getFirst();
  assertThat(spec.command()).containsExactly("claude", "-p", "--output-format", "json", "--max-turns", "100",
      "--dangerously-skip-permissions", "--append-system-prompt", "SYS");
  assertThat(new String(spec.stdin(), UTF_8)).isEqualTo("PROMPT");
  assertThat(spec.network()).isTrue();
  assertThat(spec.mounts()).containsExactly(Mount.rw(work, "/work"));
  assertThat(spec.workdir()).isEqualTo("/work");
  assertThat(spec.env()).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "tok").containsEntry("HOME", "/home/noir");
  assertThat(spec.timeout()).isEqualTo(Duration.ofMinutes(30));
}
@Test void authPrefersApiKeyThenOauthThenFails() { /* three cases incl. exact exception message */ }
```

- [ ] **Step 2: Run** `mvn -q test -Dtest='ClaudeJsonTest,ClaudeContainerAgentTest,AgentAuthTest'` — Expected: compilation failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: agent port running headless Claude Code in a container"`

---

### Task 8: Test-report parsers

**Files:**
- Create: `src/main/java/org/jwcarman/noir/reports/{JUnitXmlReports,PitMutations,PitSummary}.java`
- Test: `src/test/java/org/jwcarman/noir/reports/{JUnitXmlReportsTest,PitMutationsTest}.java`
- Fixtures: `src/test/resources/fixtures/reports/junit/TEST-junit-jupiter.xml`, `src/test/resources/fixtures/reports/pit/mutations.xml`

**Interfaces:**
- Consumes: `TestOutcome` (Plan 1).
- Produces:
  - `final class JUnitXmlReports`: `static List<TestOutcome> parse(Path reportsDir)` — reads every `*.xml` (JUnit legacy XML, as written by the console launcher's `--reports-dir`); one `TestOutcome` per `<testcase>`: `testId = classname + "#" + name`, `tags = Set.of()`, result `FAILED` if it has `<failure>` or `<error>` (`TIMED_OUT` if the failure message contains `timed out after`), `PASSED` otherwise; `<skipped>` testcases are omitted; `message` = failure `message` attribute. Missing dir → empty list. XML parsing with external entities disabled.
  - `record PitSummary(int total, int detected)` with `double score()` (`total == 0 ? 0 : detected / (double) total`).
  - `final class PitMutations`: `static PitSummary parse(Path mutationsXml)` — counts `<mutation>` elements and those with `detected="true"`.

- [ ] **Step 1: Write failing tests** — fixture with 4 testcases (pass, failure, error, timeout message, plus one skipped) → assert 4 outcomes with exact ids/results; PIT fixture with 5 mutations, 3 detected → `PitSummary(5, 3)`, `score() == 0.6`; an XML with a `<!DOCTYPE` external entity is rejected (`IllegalArgumentException`).
- [ ] **Step 2: Run** `mvn -q test -Dtest='JUnitXmlReportsTest,PitMutationsTest'` — Expected: compilation failure.
- [ ] **Step 3: Implement** with `javax.xml.parsers.DocumentBuilderFactory` (`disallow-doctype-decl` = true).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: JUnit XML and PIT mutation report parsers"`

---

### Task 9: Seal checks — script, runner, and judge

**Files:**
- Modify: `src/main/resources/docker/builder/seal-check.sh` (real body)
- Create: `src/main/java/org/jwcarman/noir/seal/{SealCheckRunner,ContainerSealCheckRunner,SealArtifacts,SealJudge,SealInputs}.java`
- Test: `src/test/java/org/jwcarman/noir/seal/SealJudgeTest.java` (pure, fixture artifact dirs built in `@TempDir`)
- Test: `src/test/java/org/jwcarman/noir/seal/ContainerSealCheckRunnerIT.java` (`@Tag("docker")`)
- Fixtures: `src/test/resources/fixtures/seal/{stubs,holdout-good,holdout-vacuous,holdout-weak,reference}/` for a tiny `com.example.api.Counter` (`increment()`, `get()`, `reset()`); the repo comes from `GitFixtures`.

**Interfaces:**
- Consumes: `ContainerPort`, `Mount`, `ContainerSpec` (Task 4); `JUnitXmlReports`, `PitMutations` (Task 8); `Spec`, `ItemId.holdoutPackage` (Plan 1); `NoirConfig` (Task 5).
- Produces:
  - `record SealInputs(Spec spec, Path bundle, Path stubsDir, Path holdoutDir, Path referenceDir, List<String> targetClasses)` — `targetClasses` = FQNs of top-level types declared in `referenceDir` (computed by `SealInputs.of(...)` with JavaParser).
  - `interface SealCheckRunner { SealArtifacts run(SealInputs in, Path outDir); }`
  - `ContainerSealCheckRunner(ContainerPort docker, NoirConfig config)` runs `/opt/noir/bin/seal-check.sh` in the builder image with mounts `bundle → /work/base.bundle:ro`, `stubs → /work/stubs:ro`, `holdout → /work/holdout:ro`, `reference → /work/reference:ro`, `outDir → /work/out` (rw), env `BASE_SHA`, `HOLDOUT_PKG`, `TARGET_CLASSES` (comma-separated), `TEST_TIMEOUT` (e.g. `10s`), `RERUNS=3`; network **on** (Maven dependency resolution); timeout 30 min.
  - `record SealArtifacts(Path outDir)` with accessors that read the script's outputs: `Optional<Integer> exit(String step)` (reads `<step>.exit`), `String logTail(String step, int lines)` (reads `<step>.log`), `String read(String file)`.
  - `final class SealJudge`: `static List<String> judge(SealArtifacts a, double discriminationThreshold)` → problems; empty = sealable.

`seal-check.sh` steps (each step writes `<step>.log` and `<step>.exit`; the script stops at the first failing step except `stub-run`, whose non-zero exit is expected):

| Step | Action |
|---|---|
| `clone` | `git clone -q /work/base.bundle /work/repo && git -C /work/repo checkout -q $BASE_SHA` |
| `assumptions` | write `release.txt` from `mvn -B -q help:evaluate -Dexpression=maven.compiler.release -DforceStdout`; write `modules.txt` = `yes` if `pom.xml` contains `<modules>` else `no`; write `pom.txt` = `yes`/`no` for `pom.xml` existence |
| `stub-build` | copy `/work/stubs/.` over `/work/repo/`; `mvn -B -DskipTests compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/work/out/deps` |
| `holdout-compile` | `javac -d /work/out/holdout-classes -cp target/classes:/work/out/deps/*:/opt/noir/lib/junit/* $(find /work/holdout -name '*.java')` |
| `stub-run` | console launcher `execute --disable-banner --details=none --class-path <classes>:<deps>:<holdout-classes> --select-package $HOLDOUT_PKG --reports-dir /work/out/stub-run --config=junit.jupiter.extensions.autodetection.enabled=false --config=junit.jupiter.execution.timeout.default=$TEST_TIMEOUT` |
| `ref-build` | copy `/work/reference/.` over `/work/repo/`; `mvn -B -o -DskipTests compile` |
| `ref-run-1..$RERUNS` | same launcher invocation as `stub-run`, reports to `/work/out/ref-run-<n>` |
| `pit` | `java -cp '/opt/noir/lib/pitest/*:/opt/noir/lib/junit/*' org.pitest.mutationtest.commandline.MutationCoverageReport --reportDir /work/out/pit --targetClasses $TARGET_CLASSES --targetTests "$HOLDOUT_PKG.*" --sourceDirs /work/repo/src/main/java --classPath <classes>,<each dep jar>,<holdout-classes> --outputFormats XML --timestampedReports=false` |

`SealJudge` problem strings (exact; log tails are the last 60 lines):

| Condition | Problem |
|---|---|
| `pom.txt` = `no` | `pom.xml not found at repository root` |
| `modules.txt` = `yes` | `multi-module Maven builds are not supported` |
| `release.txt` ≠ `21` | `maven.compiler.release must be 21 (found <value or "unset">)` |
| `stub-build.exit` ≠ 0 | `API stubs do not compile:\n<tail>` |
| `holdout-compile.exit` ≠ 0 | `holdout tests do not compile against the stubs:\n<tail>` |
| no outcomes in `stub-run` | `no holdout tests were discovered` |
| any `stub-run` outcome `PASSED` | `holdout test passes against the stubs (vacuous): <testId>` (one per test) |
| `ref-build.exit` ≠ 0 | `reference implementation does not compile:\n<tail>` |
| a test fails in every reference run | `holdout test fails against the reference implementation: <testId>` |
| a test passes in some runs, fails in others | `holdout test is nondeterministic: <testId>` |
| PIT missing, or `total == 0` | `mutation testing produced no mutants` |
| `score < threshold` | `holdout tests are not discriminating enough: mutation score 0.55 < 0.70 (11 of 20 mutants killed)` (two decimals) |
| step missing its `.exit` file (script died) | `seal check did not complete step <step>:\n<tail of that step's log>` |

- [ ] **Step 1: Write failing `SealJudgeTest`** — one test per table row, building the artifact files directly (e.g. write `stub-run/TEST-junit-jupiter.xml` with one passing testcase and assert the vacuous message), plus `allGoodHasNoProblems` and `logTailIsBounded` (a 500-line `stub-build.log` yields a problem containing line 500 and not line 440).
- [ ] **Step 2: Run** `mvn -q test -Dtest=SealJudgeTest` — Expected: compilation failure.
- [ ] **Step 3: Implement** `SealArtifacts`, `SealJudge`, `SealInputs`.
- [ ] **Step 4: Run** `mvn -q test -Dtest=SealJudgeTest` — Expected: PASS.
- [ ] **Step 5: Write failing `ContainerSealCheckRunnerIT`** (`@Tag("docker")`, builds the image via `Images.ensureBuilder`, repo from `GitFixtures` bundled with `GitCli.bundle`):

```java
@Test void goodSuiteSeals()        { assertThat(judge(run("holdout-good"))).isEmpty(); }
@Test void vacuousSuiteRejected()  { assertThat(judge(run("holdout-vacuous"))).anyMatch(p -> p.startsWith("holdout test passes against the stubs (vacuous)")); }
@Test void assertionFreeSuiteRejectedByDiscrimination() {
  // holdout-weak calls every method but asserts nothing except that no exception is thrown
  assertThat(judge(run("holdout-weak"))).anyMatch(p -> p.startsWith("holdout tests are not discriminating enough"));
}
```

- [ ] **Step 6: Run** `mvn -q verify -Pdocker -Dtest=ContainerSealCheckRunnerIT` — Expected: FAIL (placeholder script).
- [ ] **Step 7: Implement** `seal-check.sh` and `ContainerSealCheckRunner`. The script uses `set -u`, never `set -e`, and records each step's exit explicitly.
- [ ] **Step 8: Run** `mvn -q verify -Pdocker -Dtest=ContainerSealCheckRunnerIT` — Expected: PASS.
- [ ] **Step 9: Commit** — `git commit -m "feat: seal checks (satisfiable, discriminating, deterministic)"`

---

### Task 10: Examiner

**Files:**
- Create: `src/main/resources/prompts/examiner-system.md`
- Create: `src/main/java/org/jwcarman/noir/seal/{Examiner,ExaminerPrompt,ExaminerOutput}.java`
- Test: `src/test/java/org/jwcarman/noir/seal/{ExaminerPromptTest,ExaminerTest}.java`

**Interfaces:**
- Consumes: `AgentPort`, `AgentRequest`, `AgentResult` (Task 7); `SpecRenderer.specBody`, `StubSource`, `ItemId.holdoutPackage` (Plan 1); `NoirConfig.examiner()` (Task 5).
- Produces:
  - `final class ExaminerPrompt`: `static String system()` (the resource text) and `static String user(Spec spec, List<StubSource> stubs, List<String> previousProblems)`.
  - `record ExaminerOutput(AgentResult agent, Path holdoutDir, Path referenceDir)`.
  - `Examiner(AgentPort agent, NoirConfig config)`: `ExaminerOutput attempt(Spec spec, List<StubSource> stubs, List<String> previousProblems, Path attemptDir, Path draftDir)` — creates `attemptDir/out/holdout` and `attemptDir/out/reference`, runs the agent with `workDir = attemptDir`, then replaces `draftDir/holdout` and `draftDir/reference` with **regular `.java` files only** copied from `attemptDir/out/{holdout,reference}` (symlinks → `SealedFileException` from Plan 1 naming the path; other files ignored).

The system prompt states, in this order: the Examiner's role (write acceptance tests from the spec alone; it will never see an implementation); output locations `/work/out/holdout/` (package `<holdoutPackage>`, path mirroring the package) and `/work/out/reference/` (same paths as the stubs, implementing every stub); every rule from spec §4.3 verbatim in substance (fairness, `@Tag("Rn")` on methods with Javadoc citing the requirement/example, JUnit Jupiter + JDK + stub packages only, determinism bans, several tests per requirement, every test must fail against the stubs); and that it must not write anywhere else. The user prompt is `SpecRenderer.specBody(spec, stubs)` + a `## Holdout package` section with the package name + (when non-empty) a `## Problems with your previous attempt` section listing each problem as a bullet.

- [ ] **Step 1: Write failing tests**

```java
@Test void userPromptCarriesSpecPackageAndProblems() {
  var p = ExaminerPrompt.user(Specs.valid(), List.of(), List.of("holdout test is nondeterministic: X#y"));
  assertThat(p).contains("# Add a bounded cache").contains("## Holdout package")
      .contains(ItemId.holdoutPackage(Specs.ID))
      .contains("## Problems with your previous attempt").contains("- holdout test is nondeterministic: X#y");
}
@Test void firstAttemptHasNoProblemsSection() {
  assertThat(ExaminerPrompt.user(Specs.valid(), List.of(), List.of())).doesNotContain("Problems with your previous attempt");
}
@Test void systemPromptStatesRules() {
  assertThat(ExaminerPrompt.system()).contains("/work/out/holdout").contains("/work/out/reference")
      .contains("@Tag").contains("org.junit.jupiter").contains("UnsupportedOperationException");
}
@Test void copiesOnlyJavaFilesAndRejectsSymlinks(@TempDir Path tmp) { /* ScriptedAgent writes A.java, notes.txt, and a symlink; assert copy result / exception */ }
@Test void replacesPreviousAttemptOutput(@TempDir Path tmp) { /* stale draft holdout file removed after a new attempt */ }
@Test void passesExaminerBudgetsToAgent(@TempDir Path tmp) { /* request.maxTurns()==100, wallClock 30m, model null, image "noir-builder:0.1.0" */ }
```

- [ ] **Step 2: Run** `mvn -q test -Dtest='ExaminerPromptTest,ExaminerTest'` — Expected: compilation failure.
- [ ] **Step 3: Implement** the prompt resource and classes.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: Examiner agent and prompts"`

---

### Task 11: Sealer orchestration

**Files:**
- Create: `src/main/java/org/jwcarman/noir/seal/{Sealer,SealResult,SealProgress,SealException}.java`
- Test: `src/test/java/org/jwcarman/noir/seal/SealerTest.java` (all fakes + real `GitCli` on `GitFixtures` repos)

**Interfaces:**
- Consumes: `Store`, `WorkItem`, `Event`, `SpecJson`, `SpecLint`, `Commitments`, `HoldoutAnalyzer`, `SpecRenderer`, `NoirConfig` (Plan 1); `GitPort` (Task 2); `GitHubPort`, `NoirLabels` (Task 3); `Examiner` (Task 10); `SealCheckRunner`, `SealJudge`, `SealInputs` (Task 9).
- Produces:
  - `sealed interface SealResult` with `record Sealed(String id, int issueNumber, String commitmentSha256)` and `record Rejected(List<String> problems)`.
  - `record SealProgress(Integer issueNumber, boolean commented)` stored as `draftDir/seal.json` (atomic writes).
  - `Sealer(Store store, GitPort git, GitHubPort github, Examiner examiner, SealCheckRunner checks, NoirConfig config, Clock clock)` with `SealResult seal(String draftId, Path repoDir)`.

Algorithm (each step's failure mode in brackets):
1. Read `drafts/<id>/spec.json` [missing → `SealException("No draft <id>")`]. Verify `git.originRepo(repoDir)` equals `spec.repo()` [→ `Rejected("repository at <dir> is <x>, but the draft is for <y>")`].
2. `branch = github.defaultBranch(repo)`; `git.fetch(repoDir, branch)`; `sha = git.revParse(repoDir, "origin/" + branch)`; write `spec.withBaseSha(sha)` back to the draft.
3. `SpecLint.lint` → non-empty → `Rejected(problems)` (Examiner not called).
4. `git.bundle(repoDir, sha, draft/base.bundle)`.
5. For attempt 1..`config.examiner().attempts()`: `examiner.attempt(...)` into `draft/examiner/attempt-<n>`; problems = `HoldoutAnalyzer.analyze(...)`'s problems (stub packages = package declarations of the stub files); if none, problems = `SealJudge.judge(checks.run(...), config.sealDiscrimination())`. Append event `examiner_attempt` with `{attempt, model, costUsd, turns, problems (count), ok}`. Stop at the first attempt with no problems; otherwise feed problems to the next attempt. All attempts fail → `Rejected(lastProblems)`.
6. `commitment = Commitments.create(spec, draftDir, agentModel)`; write `draft/commitment.json`.
7. If `seal.json` has no issue number: `github.ensureLabels(repo)`; `n = github.createIssue(repo, spec.title(), SpecRenderer.issueBody(spec, stubs), List.of("noir:ready"))`; write `seal.json(n, false)`. If not yet commented: `github.comment(repo, n, "noir commitment `<sha256>` (commitmentVersion 1). The holdout suite for this work item is sealed; this hash lets anyone verify it was not changed after the build.")`; write `seal.json(n, true)`.
8. Write `draft/workitem.json` = `WorkItem.sealed(id, repo, n)` and event `sealed` `{issue, commitmentSha256, baseSha}`; then `Files.move(draftDir, store.itemDir(id), ATOMIC_MOVE)`. Return `Sealed`.

Events before the move are appended to `draft/events.jsonl` (they travel with the directory): `Store` gets `void appendEvent(Path itemDir, Event e)`, an overload used for drafts; the existing `appendEvent(Event)` delegates to it.

- [ ] **Step 1: Write failing tests** (each uses `FakeGitHub`, a stub `SealCheckRunner` returning prepared artifacts, `ScriptedAgent`-backed `Examiner`, and a `GitFixtures` clone):

```java
@Test void sealsAndMovesDraftIntoStore() {
  var r = (SealResult.Sealed) sealer.seal(ID, clone);
  assertThat(store.readWorkItem(ID)).isEqualTo(WorkItem.sealed(ID, "jwcarman/demo", r.issueNumber()));
  assertThat(Files.exists(store.draftDir(ID))).isFalse();
  var issue = github.issue(r.issueNumber());
  assertThat(issue.labels()).containsExactly("noir:ready");
  assertThat(issue.body()).endsWith("Edits to this issue do not affect the build._");
  assertThat(issue.comments()).singleElement().asString().contains(r.commitmentSha256());
  assertThat(SpecJson.read(store.itemDir(ID).resolve("spec.json")).baseSha()).isEqualTo(git.revParse(clone, "origin/main"));
  assertThat(store.readEvents(ID)).extracting(Event::type).containsExactly("examiner_attempt", "sealed");
}
@Test void lintFailureRejectsWithoutExaminer() { /* blank title → Rejected(["title must not be blank"]); agent never called */ }
@Test void retriesFeedProblemsToNextAttempt() { /* checks fail once (vacuous) then pass → 2 attempts; 2nd prompt contains the vacuous problem */ }
@Test void threeFailuresReject() { /* Rejected with last problems; draft still in drafts/, no issue created */ }
@Test void staticAnalysisProblemsSkipContainerChecks() { /* examiner writes untagged test → checks runner never called for that attempt */ }
@Test void resumeAfterCrashDoesNotDuplicateIssue() {
  github.failNextComment();                         // crash after issue creation
  assertThatThrownBy(() -> sealer.seal(ID, clone));
  var r = (SealResult.Sealed) sealer.seal(ID, clone);
  assertThat(github.issues()).hasSize(1);
  assertThat(r.issueNumber()).isEqualTo(github.issues().getFirst().number());
}
@Test void wrongRepositoryRejected() { /* draft repo "jwcarman/other" → Rejected mentioning both names */ }
@Test void pinsRemoteDefaultBranchEvenWithLocalCommits() { /* local commit on clone; baseSha == origin/main, not HEAD */ }
```

(`FakeGitHub` gains `failNextComment()` alongside `failNextCreateIssue()`.)

- [ ] **Step 2: Run** `mvn -q test -Dtest=SealerTest` — Expected: compilation failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -q test -Dtest=SealerTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: sealer orchestration with crash-safe issue creation"`

---

### Task 12: CLI — `noir draft init` and `noir seal`

**Files:**
- Create: `src/main/java/org/jwcarman/noir/cli/{DraftCommand,SealCommand,Wiring}.java`
- Modify: `src/main/java/org/jwcarman/noir/cli/NoirCli.java` (register `draft`, `seal`)
- Test: `src/test/java/org/jwcarman/noir/cli/{DraftCommandTest,SealCommandTest}.java`
- Test: `src/test/java/org/jwcarman/noir/cli/SealEndToEndIT.java` (`@Tag("docker")`)

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `noir draft init --repo <owner/name> --title <title>` → creates `drafts/<new id>/spec.json` (schemaVersion 1, the id, repo, title, `intent` = `"TODO"`, one requirement `R1` with one example of `"TODO"` fields, quality bar from config, `apiStubs` empty) and an empty `stubs/` dir; prints the draft id and path. Exit 0.
  - `noir seal <draft-id> [--repo-dir <path>]` (default `.`): prints `Sealed <id> as <repo>#<n> (commitment <first 12 hex>)` and exits 0; on `Rejected` prints `Not sealed:` then each problem prefixed `- ` and exits 1; on `ToolMissingException`, `DockerException`, `GitHubException`, `GitException`, `IllegalStateException` (auth) prints the exception message only (no stack trace) and exits 2.
  - `final class Wiring`: builds the real object graph from `NoirHome` + `System.getenv()`; `NoirCli` uses it unless a test injects a `Function<NoirHome, Sealer>` via a package-private constructor.

- [ ] **Step 1: Write failing tests**

```java
@Test void draftInitCreatesSkeleton() { /* run("draft","init","--repo","jwcarman/demo","--title","Counter"); spec.json exists; SpecLint flags only baseSha/stubs, not schema */ }
@Test void sealPrintsResult() { /* injected Sealer returning Sealed("nr-…", 12, "abcdef0123456789…") → stdout "Sealed nr-… as jwcarman/demo#12 (commitment abcdef012345)", exit 0 */ }
@Test void sealRejectedListsProblems() { /* Rejected(["a","b"]) → "Not sealed:\n- a\n- b\n", exit 1 */ }
@Test void infraErrorsAreOneLine() {
  // injected Sealer throws new GitHubException("GitHub CLI is not authenticated; run: gh auth login")
  // → stderr exactly that line + "\n", no "at org.", exit 2
}
```

`SealEndToEndIT`: real `DockerCli`, real `ContainerSealCheckRunner`, real `GitCli` on a `GitFixtures` origin, `FakeGitHub`, and a `ScriptedAgent` that writes the `holdout-good` and `reference` fixtures → `noir seal` succeeds; work item `SEALED` in the store; commitment hash posted.

- [ ] **Step 2: Run** `mvn -q test -Dtest='DraftCommandTest,SealCommandTest'` — Expected: FAIL (unknown subcommands).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -q verify` — Expected: PASS. Then `mvn -q verify -Pdocker` — Expected: PASS (all docker-tagged tests).
- [ ] **Step 5: Commit** — `git commit -m "feat: noir draft init and noir seal commands"`

---

### Task 13: Acceptance — seal a real work item (requires the user)

This step creates a real GitHub issue and spends Claude usage. **Stop and ask the user** which repository to use (a scratch repo they own, single-module Maven, Java 21) and confirm before running.

- [ ] **Step 1:** With the user's repo cloned locally: `bin/noir draft init --repo <owner/name> --title "<small feature>"`; fill `spec.json` and `stubs/` with the user (one or two requirements, one small interface).
- [ ] **Step 2:** `bin/noir seal <id> --repo-dir <clone>` with `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN` set. Expected: `Sealed … as <repo>#<n>`.
- [ ] **Step 3:** Verify on GitHub: issue exists with `noir:ready`, rendered spec, and the commitment comment. Verify locally: `bin/noir status` shows the item `SEALED`; `events.jsonl` has `examiner_attempt` (with cost) and `sealed`.
- [ ] **Step 4:** Record the outcome (attempts, cost, any problems) in the ledger; no commit needed.
