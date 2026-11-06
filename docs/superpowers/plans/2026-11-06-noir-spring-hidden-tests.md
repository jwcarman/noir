# Spring Hidden Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let hidden tests use the repository's own test libraries (Spring's test slices included), drop the grader's Security Manager, allow abstract members in fragments, let the Examiner update listed test helpers, and show it the repository's API.

**Architecture:** One new seal step, a per-item *repository inventory* (dependency jars and their packages, from a container with network; main packages, the `@SpringBootApplication` class and the Repository API text, from the base source on the host), feeds the holdout analyzer and the Examiner prompt. The seal check, measurement and grader all put the repository's runtime and test-scope jars, minus JUnit's own, on the hidden-test classpath; the grader runs without a Security Manager. The splicer accepts body-less members; the seal check overlays the Examiner's versions of listed test files before running the existing tests.

**Tech Stack:** Java 21, JavaParser, bash (`seal-check.sh`, `measure.sh`, `grade.sh`), Docker, Maven dependency plugin 3.11.0.

**Spec:** `docs/superpowers/specs/2026-11-06-noir-spring-hidden-tests-design.md`

## Global Constraints

- JUnit's own jars are never exported or put on a hidden-test classpath from the repository: file names matching `junit-*.jar`, `opentest4j-*.jar`, `apiguardian-api-*.jar`. noir's launcher supplies JUnit.
- Denied packages stay exactly `java.net`, `java.nio.channels`, `java.util.concurrent`, `java.lang.reflect` (and subpackages), and `HoldoutAnalyzer.deniedPackages()` stays the single source the prompt lists.
- New analyzer rule, exact problem text: `<file>: a real server is not allowed: webEnvironment = <VALUE>` for `RANDOM_PORT` or `DEFINED_PORT`.
- Repository API section cap: 60,000 characters; types named in the spec (contract, requirements, constraints) first; a type that does not fit is left out whole, never cut.
- The builder never receives the reference, the holdout suite or the Examiner's updated test helpers.
- Repositories without Spring behave as today apart from the classpath rule (their test-scope jars minus JUnit are usually empty).
- Commitment format, `spec.json` and the issue format do not change.
- Docker integration tests: run with `mvn -B test -Pdocker -Dtest=<Class>`; never `mvn package`/`verify` while a server runs from this checkout.

## Review Focus

1. A repository whose test scope has no Spring (noir-test) gets an allowed-package set of just its own main packages, stubs, JDK and JUnit — `RepoInventoryTest.plainRepoAddsNoLibraryPackages`.
2. A dependency jar that also contains `org.junit.*` classes (a shaded jar) must not make `org.junit.platform` imports allowed beyond what JUnit already allows — `HoldoutAnalyzerTest.dependencyPackagesNeverWidenJunitPlatform`.
3. The Examiner writing a listed **test class** (not a helper) still never gets that class run in check 4 — `ContainerSealCheckRunnerIT.updatedListedTestClassIsStillSkipped`.
4. An abstract fragment member targeting a concrete class is rejected with a clear message — `StubSplicerTest.abstractMemberInConcreteClassRejected`.
5. A Repository API larger than the cap keeps every spec-named type and drops others whole — `RepositoryApiTest.capKeepsSpecNamedTypesWhole`.

---

### Task 1: Grader without the Security Manager

**Files:**
- Modify: `src/main/resources/docker/grader/grade.sh`, `src/main/resources/docker/grader/Dockerfile` (drop the `grader.policy` COPY), `src/main/java/org/jwcarman/noir/docker/Images.java` (`GRADER_FILES`), `README.md` (step 6 of "How it works", and any Security Manager mention)
- Delete: `src/main/resources/docker/grader/grader.policy`
- Test: `src/test/java/org/jwcarman/noir/grade/ContainerGradeRunnerIT.java`, `src/test/java/org/jwcarman/noir/docker/ImagesTest.java`

- [ ] **Step 1:** In `ContainerGradeRunnerIT`, delete the tests whose only purpose is a Security Manager guarantee (builder code halting the JVM, writing `/work/out`, shutdown hooks, replacing the security manager); keep every grading-outcome test. Add `gradesWithoutASecurityManager`: a builder fixture whose class reads `System.getSecurityManager()` and a holdout test asserting it is `null`; expect the test to pass.
- [ ] **Step 2:** Run `mvn -B test -Pdocker -Dtest=ContainerGradeRunnerIT`. Expected: `gradesWithoutASecurityManager` FAILS (a manager is installed).
- [ ] **Step 3:** In `holdout_run`, remove `-Djava.security.manager=default -Djava.security.policy==$POLICY` and the `POLICY` variable; update the header comment to say the container is the sandbox. Remove the policy from the Dockerfile, `Images.GRADER_FILES` and the repo. README step 6: "The sealed holdout suite runs in a grader container with no network and read-only mounts." plus one sentence that noir does not sandbox the test JVM itself.
- [ ] **Step 4:** Run `mvn -B test -Pdocker -Dtest='ContainerGradeRunnerIT,ImagesTest'`. Expected: PASS.
- [ ] **Step 5:** Commit `feat: grader runs hidden tests without a Security Manager`.

### Task 2: Test-scope dependencies on the hidden-test classpath

**Files:**
- Modify: `src/main/resources/docker/builder/seal-check.sh` (`stub_build`: also copy test scope; drop JUnit jars), `src/main/resources/docker/builder/measure.sh` (`export_artifacts`: export runtime and test scope into `export/deps`, JUnit jars dropped)
- Test: `src/test/java/org/jwcarman/noir/seal/ContainerSealCheckRunnerIT.java`, `src/test/java/org/jwcarman/noir/measure/ContainerMeasureRunnerIT.java`

**Interfaces:**
- Produces: `$OUT/deps` (seal) and `export/deps` (measure) hold the runtime and test-scope jars, de-duplicated by file name, with no `junit-*`, `opentest4j-*`, `apiguardian-api-*`. The grader's `/work/deps` mount is `export/deps`, unchanged.

- [ ] **Step 1: Write the failing tests:** `ContainerSealCheckRunnerIT.depsIncludeTestScopeWithoutJunit` (fixture pom adds `org.assertj:assertj-core` test scope; after a seal run, `deps/` contains an `assertj-core-*.jar` and no `junit-*`); `ContainerMeasureRunnerIT.exportIncludesTestScopeWithoutJunit` (same assertion on `export/deps`).
- [ ] **Step 2:** Run both with `-Pdocker`. Expected: FAIL (no assertj jar).
- [ ] **Step 3:** In both scripts add `copy-dependencies -DincludeScope=test` into the same directory, then delete the JUnit file patterns from it. Keep measure's separate `test-deps` (PIT) as is.
- [ ] **Step 4:** Run `mvn -B test -Pdocker -Dtest='ContainerSealCheckRunnerIT,ContainerMeasureRunnerIT,ContainerGradeRunnerIT'`. Expected: PASS.
- [ ] **Step 5:** Commit `feat: hidden tests see the repository's test-scope dependencies`.

### Task 3: Repository inventory

**Files:**
- Create: `src/main/java/org/jwcarman/noir/seal/RepoInventory.java`, `src/main/java/org/jwcarman/noir/seal/RepositoryApi.java`
- Modify: `src/main/resources/docker/builder/seal-check.sh` (new phase `inventory`), `src/main/java/org/jwcarman/noir/seal/SealCheckRunner.java`, `ContainerSealCheckRunner.java`, `ApprovalSealer.java` (run once per seal, before the Examiner; store in `itemDir/inventory/`), `SuiteSealer.java` (pass it on)
- Test: `RepoInventoryTest.java`, `RepositoryApiTest.java` (unit, under `src/test/java/org/jwcarman/noir/seal/`), `ContainerSealCheckRunnerIT.inventoryListsLibrariesAndPackages`

**Interfaces:**
- Produces:
  - `record RepoInventory(List<String> testLibraries, Set<String> dependencyPackages, Set<String> mainPackages, Optional<String> applicationClass, String repositoryApi)`; `testLibraries` are `group:artifact:version` (JUnit's own excluded), sorted.
  - `SealCheckRunner.inventory(Path bundle, String baseSha, Path outDir) -> RepoInventoryFiles` reading `inventory/libraries.txt` and `inventory/packages.txt` written by `seal-check.sh inventory` (network on): clone, `dependency:list` for runtime and test scope, and the package of every `.class` entry in those jars (minus JUnit's jars).
  - `RepoInventory.of(RepoInventoryFiles files, Map<String, String> mainSources, Spec spec)` where `mainSources` are the base commit's `src/main/java/**/*.java` (path → content, via `GitPort`); `mainPackages` from their `package` lines; `applicationClass` is the fully qualified name of the one top-level class annotated `@SpringBootApplication` (empty if none or several); `repositoryApi = RepositoryApi.render(mainSources, spec, 60_000)`.
  - `RepositoryApi.render(Map<String,String> sources, Spec spec, int cap) -> String`: each type's declaration with Javadoc, fields' and methods' declarations without bodies (JavaParser: replace bodies, drop private members), one fenced block per file headed `### <path>`; spec-named simple type names first, then by path; files that do not fit the cap are left out whole, and a final line `(… N more files omitted)` when any are.
- [ ] **Step 1: Write the failing tests:** `RepositoryApiTest.stripsBodiesAndPrivateMembers`, `RepositoryApiTest.capKeepsSpecNamedTypesWhole`, `RepoInventoryTest.findsTheSpringBootApplication`, `RepoInventoryTest.plainRepoAddsNoLibraryPackages`, `RepoInventoryTest.twoApplicationsMeanNone`.
- [ ] **Step 2:** Run `mvn -B test -Dtest='RepositoryApiTest,RepoInventoryTest'`. Expected: compilation failure.
- [ ] **Step 3:** Implement the Produces types; the IT `inventoryListsLibrariesAndPackages` (fixture pom with assertj test scope → `libraries.txt` has `org.assertj:assertj-core:<v>`, `packages.txt` has `org.assertj.core.api`, neither has JUnit).
- [ ] **Step 4:** Run unit tests and `mvn -B test -Pdocker -Dtest=ContainerSealCheckRunnerIT`. Expected: PASS.
- [ ] **Step 5:** Commit `feat: seal builds a repository inventory before the Examiner runs`.

### Task 4: Holdout analyzer rules

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/holdout/HoldoutAnalyzer.java`, callers in `SuiteSealer.java` (inventory packages + stub packages), `Factory.java` and `audit/HoldoutCatalog.java` (tags only: keep passing what they pass)
- Test: `src/test/java/org/jwcarman/noir/holdout/HoldoutAnalyzerTest.java`

**Interfaces:**
- Consumes: `RepoInventory.dependencyPackages()`, `mainPackages()` (Task 3).
- Produces: `HoldoutAnalyzer.analyze(Spec spec, Path holdoutDir, Set<String> allowedPackages)`; an import is allowed when not under a denied prefix and under `java`, `org.junit.jupiter`, or an allowed package (exact package match, plus subpackages for stub and main packages only).

- [ ] **Step 1: Write the failing tests:** `springImportsAllowedWhenInTheInventory` (`org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest` allowed with that package in the set); `deniedPackagesStayDenied` (`java.net.URI` denied even if listed); `dependencyPackagesNeverWidenJunitPlatform`; `realServerRejected` for `RANDOM_PORT` and `DEFINED_PORT` (exact text from Global Constraints); `mockMvcAllowed`.
- [ ] **Step 2:** Run `mvn -B test -Dtest=HoldoutAnalyzerTest`. Expected: the new tests FAIL.
- [ ] **Step 3:** Implement; the server rule finds `webEnvironment` in any `@SpringBootTest` annotation (as `WebEnvironment.X` or `SpringBootTest.WebEnvironment.X`).
- [ ] **Step 4:** Run `mvn -B test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat: hidden tests may import the repository's libraries and classes; no real servers`.

### Task 5: Examiner prompt

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/seal/ExaminerPrompt.java`, `src/main/resources/prompts/examiner-system.md`, `Examiner.java`/`SuiteSealer.java` (pass the inventory)
- Test: `src/test/java/org/jwcarman/noir/seal/ExaminerPromptTest.java`

**Interfaces:**
- Consumes: `RepoInventory` (Task 3).
- Produces: `ExaminerPrompt.user(Spec, List<StubSource> contract, List<StubSource> stubs, RepoInventory inventory, List<String> previousProblems)` adding, after `## Current source`: `## Test libraries` (bullets of `testLibraries`, or `JUnit and the JDK only`), `## Application class` (only when present: the class and the rule to name it in `@SpringBootTest(classes = …)` or `@ContextConfiguration(classes = …)`), `## Repository API` (`inventory.repositoryApi()`).

- [ ] **Step 1: Write the failing tests:** `userPromptListsTestLibraries`, `userPromptNamesTheApplicationClass`, `userPromptOmitsApplicationClassWithoutSpringBoot`, `userPromptCarriesRepositoryApi`, `systemPromptStatesSpringRules` (contains "Prefer test slices", "a real server", "update a listed test helper"), and update `systemPromptStatesRules` for the new dependency rule ("the libraries listed under Test libraries").
- [ ] **Step 2:** Run `mvn -B test -Dtest=ExaminerPromptTest`. Expected: FAIL.
- [ ] **Step 3:** Implement. System prompt: the Dependencies rule becomes "JUnit Jupiter, the JDK, the libraries under Test libraries, and the repository's own classes"; a "Spring" paragraph (name the application class, prefer slices, MockMvc not a real server, `@MockitoBean` for collaborators); a "Tests to update" paragraph (write the updated version of a listed helper the change makes stale, at its path under `/work/out/reference/`; change only what the spec requires; never write other test files).
- [ ] **Step 4:** Run `mvn -B test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat: Examiner sees test libraries, the application class and the repository API`.

### Task 6: Abstract members in fragments

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/spec/StubSplicer.java`
- Test: `src/test/java/org/jwcarman/noir/spec/StubSplicerTest.java`

- [ ] **Step 1: Write the failing tests:** `abstractInterfaceMethodIsAdded` (interface base, fragment `Optional<Bookmark> findByTag(String tag);` → appended as declared, listed in `added`); `abstractMethodInAbstractClassIsAdded`; `changedAbstractMethodKeepsDeclarationTakesJavadoc`; `abstractVersusConcreteMismatchRejected` (message contains `is abstract in the fragment but not in the file` or the reverse); `abstractMemberInConcreteClassRejected` (message contains `only interfaces and abstract classes can declare a method without a body`).
- [ ] **Step 2:** Run `mvn -B test -Dtest=StubSplicerTest`. Expected: FAIL.
- [ ] **Step 3:** Implement: a body-less `MethodDeclaration` is allowed when the fragment type is an interface (and the method is not `default`, `static` or `private`) or an abstract class with the method `abstract`; otherwise the existing "not exactly the UOE throw" rule applies.
- [ ] **Step 4:** Run `mvn -B test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat: fragments may add and change abstract methods`.

### Task 7: The Examiner's updated test helpers

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/seal/SuiteSealer.java` (`strayReferenceFiles`: allow listed test paths), `src/main/resources/docker/builder/seal-check.sh` (`restore_tests` then copy listed test files from `/work/reference` into the repo before `test_deps` and `existing_tests`; never any other path)
- Test: `SuiteSealerTest.java`, `ContainerSealCheckRunnerIT.java`

- [ ] **Step 1: Write the failing tests:** `SuiteSealerTest.referenceMayWriteListedTestPaths` and `referenceMayNotWriteOtherTestPaths`; `ContainerSealCheckRunnerIT.updatedHelperIsUsedByExistingTests` (base fake lacks a method the reference adds abstractly; listed helper updated by the reference → existing tests compile and pass); `updatedListedTestClassIsStillSkipped`.
- [ ] **Step 2:** Run unit and `-Pdocker` tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `mvn -B test` and `mvn -B test -Pdocker -Dtest=ContainerSealCheckRunnerIT`. Expected: PASS.
- [ ] **Step 5:** Commit `feat: the Examiner may update listed test helpers for the existing-test check`.

### Task 8: Skill and docs

**Files:**
- Modify: `src/main/resources/skills/noir-spec/SKILL.md`, `README.md`, `docs/superpowers/specs/2026-11-05-noir-modify-existing-code-design.md` (one "Superseded by" line in §4 and §5 where Phase 2 changes the rules)
- Test: `src/test/java/org/jwcarman/noir/cli/SkillCommandTest.java` (existing)

- [ ] **Step 1:** Skill section 4d: remove the `default`-method advice; add: "When the spec adds an interface method, grep `src/test/java` for classes implementing the interface and suggest listing them under Tests to update; the class implementing it in `src/main/java` (for example a JPA adapter) must be a modify target." Section on requirements: hidden tests may use the repository's test libraries (for example `@DataJpaTest`, MockMvc).
- [ ] **Step 2:** README: hidden tests may use the repository's test-scope libraries; no Security Manager.
- [ ] **Step 3:** Run `mvn -B test`. Expected: PASS.
- [ ] **Step 4:** Commit `docs: skill and README for Spring hidden tests`.

### Task 9: Acceptance (needs the user)

- [ ] **Step 1:** Run `mvn -B verify -Pdocker` with the server stopped; ask the user to stop it first. Expected: all tests pass. Then `bin/noir skill install` and ask the user to start the server.
- [ ] **Step 2:** On `jwcarman/noir-bookmarks`, one at a time, wait for each outcome:
  1. abstract `BookmarkRepository.findByTag(String tag)` (fragment without a body), implemented in `JpaBookmarkRepository` (modify target) and the in-memory fake (Tests to update), used by a new `BookmarkService.listByTag(String tag)`;
  2. `BookmarkPage search(String text, Set<String> tags, int page, int size)` with a JPA query;
  3. `PATCH /bookmarks/{id}` updating title and tags (MockMvc).
- [ ] **Step 3:** One regression spec on `jwcarman/noir-test` (new file, plain JUnit).
- [ ] **Step 4:** Append results (attempts, seal time, cost, hidden-test kinds used) to `docs/findings/2026-11-06-spring-phase-1.md` under "Phase 2 acceptance"; commit.
