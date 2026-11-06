# noir: hidden tests for Spring code (design)

Date: 2026-11-06
Status: draft for review
Builds on: `2026-11-05-noir-modify-existing-code-design.md` (Plan 6)
Evidence: `docs/findings/2026-11-06-spring-phase-1.md` (Phase 1 and the grader spike)

## 1. Problem

Hidden tests may use only JUnit and the JDK. In a layered Spring app that makes the persistence
and web layers unreachable. Phase 1 on `jwcarman/noir-bookmarks` found:

- A spec that changes a JPA adapter cannot seal: its changed lines hold mutants no hidden test can
  reach (F10).
- A fragment cannot add an abstract interface method (F5).
- A listed test helper, such as an in-memory fake, is stale at seal time, so tests that use it
  fail against the reference (F9).
- The Examiner sees only the modify targets, so its reference misses classes the spec names (F6).

**Goal.** An ordinary layered Spring change (a repository method, its JPA implementation, the
service and controller that use it, and the fakes in the tests) can be specified, sealed and
built without shaping the code around noir. Repositories without Spring work as today.

**Decided in the spike.** The grader drops the Java Security Manager (§3).

**Out of scope:** a shared dependency cache (F1); exempting unreachable code from seal check 3
(F10's remainder, only if still needed); multi-module builds.

## 2. Policy

| Question | Decision |
|---|---|
| Libraries hidden tests may use | JUnit, the JDK (minus denied packages), and the repository's own test-scope and runtime dependencies, resolved from the locked `pom.xml` |
| Classes hidden tests may use | Every package under `src/main/java` at the base commit, plus the stub packages |
| Stale test helpers | The Examiner writes updated versions of listed test files; the existing-test check uses them; the builder never sees them |
| Abstract members | Allowed in fragments where the target type allows them |
| Runtime protection in the grader | The container (no network, read-only mounts, non-root, limits) and the forbidden-file scan; no Security Manager |

## 3. Grader and classpaths

- `grade.sh` runs the JUnit console launcher without `-Djava.security.manager` and without a
  policy. `grader.policy` is deleted. The container, its mounts and limits, the forbidden-file
  scan, extension auto-detection off and listener loading off are unchanged.
- Measurement exports, besides the runtime dependencies, the test-scope dependencies resolved from
  the locked pom. JUnit's own jars (`junit-*`, `opentest4j`, `apiguardian`) are dropped from both
  exports; noir's launcher supplies JUnit. The grader puts the builder's classes and both
  exported sets on the hidden-test classpath.
- The seal check builds the hidden-test classpath the same way (stubs or reference, runtime and
  test-scope dependencies, noir's launcher), so a suite proven at seal time is graded on the same
  classpath.

Lost with the Security Manager: protection against builder code tampering with the test JVM at
run time (halting it, writing `/work/out`, adding shutdown hooks). The README states this.

## 4. Hidden-test rules and the Examiner

**Allowed imports** (the holdout analyzer):
- the JDK, except the denied packages (`java.net`, `java.lang.reflect`, `java.util.concurrent`,
  `java.nio.channels` and subpackages);
- JUnit;
- every package in the repository's test-scope and runtime dependency jars, listed by the seal
  check's prepare phase;
- every package under `src/main/java` at the base commit, and the stub packages.

**Determinism rules** are unchanged (no threads, clock reads, unseeded randomness, files outside a
`@TempDir`). New rule: no real server. `@SpringBootTest` with `webEnvironment` `RANDOM_PORT` or
`DEFINED_PORT` is rejected; loopback works in a container without a network, so the rule is
needed.

**The Examiner's prompt** gains:
- the repository's test libraries (artifact and version);
- the `@SpringBootApplication` class at the base commit, if there is one, and the rule to name it
  in `@SpringBootTest(classes = …)` or `@ContextConfiguration(classes = …)`, because hidden tests
  live outside the application's package; prefer test slices (`@DataJpaTest`, `@WebMvcTest`);
- **Repository API**: the public declarations and Javadoc, without method bodies, of the classes
  under `src/main/java` at the base commit. Types named in the spec come first; the section is
  capped at 60 KB.

## 5. Fragments and test helpers

**Abstract members.** A fragment member may have no body where the target type allows it: an
interface method that is not `default`, `private` or `static`, or an `abstract` method in an
abstract class. An added one is appended as declared. A changed one must match the base in being
abstract or not; switching is a signature change and stays out of scope. Every other fragment rule
is unchanged.

**Updated helpers.** The Examiner may write updated versions of files listed under
`## Tests to update`, at the same paths under `/work/out/reference/`. Any other test path it
writes is rejected, as today. Seal check 4 compiles the existing tests with those versions (a
listed file it did not update keeps its base version) and still does not run the test classes in
listed files. The updated helpers are stored with the reference and never shown to the builder.
The prompt tells the Examiner to update a listed helper the change makes stale, changing only what
the spec requires.

**The `/noir-spec` skill** drops the `default`-method workaround. When a spec adds an interface
method, it finds the implementations in `src/test/java` and suggests listing them under
`## Tests to update`, and it reminds the author that the adapter implementing the method must be a
modify target.

## 6. Migration

- No change to the commitment, `spec.json` or the issue format.
- Sealed items grade as before, without the Security Manager.
- The grader policy and the integration tests that proved its guarantees are removed.
- Seal check 3 is unchanged.

## 7. Testing

Unit:
- holdout analyzer: dependency and repository packages allowed, denied packages still denied,
  `RANDOM_PORT` and `DEFINED_PORT` rejected;
- splicer: abstract members added and changed, abstract versus concrete mismatch rejected;
- Examiner prompt: test libraries, application class, Repository API ordering and cap;
- seal: the reference may write listed test paths only.

Docker integration:
- the grader runs a Spring-style hidden test without the Security Manager, and a plain JUnit one;
- seal check 4 uses the Examiner's updated helper;
- the exported dependencies contain test scope and no JUnit jars.

Acceptance on `jwcarman/noir-bookmarks`:
1. A new abstract `BookmarkRepository.findByTag(String tag)`, implemented in
   `JpaBookmarkRepository` (a Spring Data query) and in the in-memory fake, used by a new
   `BookmarkService.listByTag`. (`findByUrl` stays a `default` method: making it abstract would be a
   signature change.)
2. Search by text and tags with a JPA query, hidden tests using `@DataJpaTest`.
3. `PATCH /bookmarks/{id}`, hidden tests using MockMvc.
4. A regression spec on `jwcarman/noir-test`.
