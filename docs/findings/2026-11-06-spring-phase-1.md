# Spring Phase 1 findings (noir-bookmarks)

Date: 2026-11-06
Design: `docs/superpowers/specs/2026-11-06-noir-bookmarks-design.md`
Target repo: https://github.com/jwcarman/noir-bookmarks (Spring Boot 3.5.16, Java 21, H2)

## Summary

noir builds Spring code it can test with plain JUnit: a new domain class, a rule inside a record,
a service change. Every PR passed on its first build attempt, and the seed's Spring tests
(`@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`) ran offline at seal time without trouble.

But noir cannot yet make an ordinary layered change. A spec that touches a JPA adapter cannot seal
(F10), a fragment cannot add an abstract interface method (F5), and a changed test helper shows up
as a false conflict (F9). Spec 3 only sealed after it was rewritten around those limits (a paging
`default` method instead of a repository query), and it cost $3.38 instead of about $0.45.

The root cause is one rule: hidden tests may use only JUnit and the JDK. That rule kept the
anti-cheating guarantees simple for standalone classes; in a layered Spring app it makes the
adapter and web layers unreachable. Phase 2 has to change it.

Specs 4 (search) and 5 (`PATCH`) were not run: both need the JPA adapter or the web layer and
would hit F10 and F5 again.

## Specs

| Spec | Kind | Outcome | Seal | Build | Cost | Notes |
|---|---|---|---|---|---|---|
| 1 `UrlNormalizer` | new file | PR #2 merged, first attempt | 1m15s (checks 13s) | build 1m14s, measure 20s | $0.47 | 256 artifacts downloaded at seal, 412 at measure |
| 2 tag rules | modify a record's compact constructor | PR #4 merged, first attempt | 1m13s (checks 19s) | ~1m; measure blocked once (F4) | $0.37 | needed the splicer fix F2; 74 existing Spring tests ran offline at seal |
| 3 duplicate URL | modify an interface, a service; new exception | PR #6 merged, first build attempt | 4 seal rounds, 9 Examiner attempts | measure blocked once (F4) | $3.38 (Examiner $3.09) | needed fix F7; workarounds for F5, F9, F10; F6 twice |

## Findings

Each finding is tagged: **fixed** (in noir, test-first), **Phase 2** (needs design), or **small**.

### F10. Seal-time mutation score counts code the hidden tests cannot reach — Phase 2 (blocker)

Seal check 3 scores PIT mutants on the changed lines of every modify target. A JPA adapter can
only be exercised through Spring, which hidden tests cannot start. In spec 3,
`JpaBookmarkRepository.findByUrl`'s three changed-line mutants survived in every attempt: 4 of 7
killed, 0.57 < 0.70, rejected `suite_too_weak`. Any spec that touches an adapter cannot seal.

Proposed: let hidden tests use Spring's test support per repository (below), or exempt modify
targets the hidden tests cannot reach from check 3 and rely on the repository's own tests, which
already run at measurement.

### F5. A fragment cannot add an abstract method — Phase 2

Every fragment member must have the body `throw new UnsupportedOperationException();`, so an
abstract interface method cannot be declared. Adding a method to a repository or port interface is
the most common Spring change. Workaround used: a `default` method. Proposed: allow body-less
members where the target type allows them; the seal must then compile the fakes that implement
the interface (see F9).

### F9. A listed test helper is stale at seal time — Phase 2

Check 4 runs the existing tests against the reference with the base versions of the files under
`## Tests to update`. When a listed file is a helper, such as an in-memory fake, the tests that use
it fail against the reference (`findByUrl` not implemented) although the builder will update the
helper. Spec 3 was rejected `conflicts_with_existing_tests` for `BookmarkServiceTest`. Workaround:
list the dependent test too. Proposed: let the Examiner supply updated versions of listed test
files, compiled for check 4 only, or skip tests that depend on listed helpers.

### F6. The Examiner sees only the modify targets — Phase 2

The Examiner sees the spec and the current source of the modify targets, not other classes the
spec names. In spec 3 its reference used `UrlNormalizer` without importing it (twice); feedback
fixed it each time, at the cost of an attempt. Workaround: name the class and signature under
`## Constraints`. Proposed: show the Examiner the signatures of repository types the spec
references.

### F1. Every container downloads Spring Boot's dependencies — Phase 2

The builder image caches only noir's own toolchain. Each run downloads the Spring Boot tree:
256 artifacts at seal, 30 more for the test dependencies, 412 at measure. It is fast on this
network (seconds), but at volume it is the Maven Central 429 problem seen earlier. Proposed: a
shared read-only cache or a local Maven proxy; the builder container runs untrusted code, so a
writable shared cache is not safe.

### F7. Listed test files were deleted before check 4 — fixed (`a3b52fb`)

The seal check deleted every file under `## Tests to update`. A listed helper used by other tests
broke their compilation, so the existing tests could not run in any attempt and spec 3 was rejected
`suite_too_weak`. Listed files now stay compiled and are excluded from the surefire run.

### F2. The splicer rejected record compact constructors — fixed (`a6690c2`)

Plan 6 allows constructors in fragments, but a record's compact constructor was rejected. It is
now a changed member when the record has one (body kept, Javadoc taken) and an added one otherwise.

### F4. Measurement export failed on macOS file sharing — fixed (`1c480e8`)

`cp` into the bind mount intermittently failed to set permissions ("Operation not permitted") on
jars it had copied in full, blocking the item as an infrastructure failure: 2 of 3 Spring
measurements. Export now writes files with `cat`. Not reproducible on demand; covered by
`ContainerMeasureRunnerIT`.

### F8. Unfixable seal problems were opaque — fixed (`ea1774b`), partly

A rejection comment said only "other check failures", and the attempt directories are reused on
a reseal, so the cause was lost. The `seal_rejected` event now keeps the full problems. Still
open: problems the Examiner cannot fix (existing tests that cannot run) still use all attempts.

### F0, F3 and operator visibility — fixed

- `noir spec check --repo` failed without `origin/HEAD`, which `gh repo create --source` does not
  set; it now falls back to `origin/main` or `origin/master` (`89da800`).
- `mvn verify` in the noir repo replaced the jar under a running server; `bin/noir` now runs a
  private copy (`5730dc9`).
- The server was silent while working on a resumed item; it now prints
  `<id> <repo>#<n>: working (<STATE>)` when it picks an item up (`88eb482`).

## What worked

- Builds: every PR passed the hidden tests on the first build attempt, at 94–100% mutation score.
- Check 4 ran the seed's `@SpringBootTest`, `@DataJpaTest` and `@WebMvcTest` offline in the seal
  container, so running Spring tests in noir's containers is not the obstacle.
- The lock rule held: builders changed only the files each spec allowed.

## Phase 2 agenda

1. **Spike first:** start a Spring Boot test context (`@DataJpaTest`, MockMvc) inside the grader,
   under its Security Manager policy and with no network. If it cannot be made to work, decide
   whether Spring repositories drop the Security Manager and rely on the container sandbox.
2. Per-repository allowed test libraries for hidden tests (spring-boot-test, AssertJ, Mockito),
   with the analyzer's rules adjusted to match.
3. F10: scope seal check 3 to what the hidden tests can reach.
4. F5 and F9: abstract members in fragments, and Examiner-supplied versions of listed test helpers.
5. F6: show referenced type signatures to the Examiner.
6. F1: a shared read-only dependency cache or a Maven proxy.

## Grader spike (2026-11-06)

Question: can hidden tests that start a Spring Boot test context run in the grader, under its
Security Manager policy, with no network?

Probe: noir-bookmarks' compiled classes and all its dependencies (test scope included), three
throwaway hidden tests (plain JUnit, `@DataJpaTest`, `@WebMvcTest` with MockMvc and
`@MockitoBean`), run with the real `noir-grader` image and `docker run` flags.

Results:
1. The forbidden-file scan rejected the run first: test-scope dependencies include JUnit's own
   jars, whose service registrations it treats as an attempt to take over the launcher. Hidden-test
   libraries need a trusted path of their own.
2. With the current policy, both Spring tests failed at once: Spring in `/work/deps` lacks
   `accessDeclaredMembers`.
3. Trusting `/work/deps` fully made `@WebMvcTest` pass. `@DataJpaTest` still failed: Spring's
   CGLIB proxy of the builder's `@Transactional` class is defined in the builder's protection
   domain and reflects on itself.
4. Adding `accessDeclaredMembers` for the builder's classes made all three pass (`@DataJpaTest`
   0.13 s, MockMvc 0.10 s).
5. Replacing full trust for `/work/deps` with only what Spring asks for needed 24 grants and was
   not done: writing system properties, reading environment variables, reading `application.*`
   from the working directory, adding shutdown hooks. Full trust, on the other hand, lets Spring,
   steered by builder-controlled configuration such as a new `application.yaml`, read files the
   builder's code may not.
6. Hidden tests live outside the application's package, so they must name the configuration class
   (`@ContextConfiguration(classes = ...)`).

Decision (agreed): drop the Security Manager from the grader. It is deprecated since Java 17 and
permanently disabled in Java 24, and a Spring-compatible policy would be long and weak. noir's
protection rests on the builder never seeing the sealed tests, the commitment, the grader
container (no network, read-only mounts) and the forbidden-file scan. Lost: protection against
builder code tampering with the test JVM at run time.

## Phase 2 acceptance (2026-11-06)

Branch `plan-7-spring-hidden-tests`, server with the Maven mirror configured.

| Spec | Hidden tests | Seal | Build | Cost | Notes |
|---|---|---|---|---|---|
| noir-bookmarks #7 `findByTag` (abstract repository method, Spring Data query, two fakes listed) | `@DataJpaTest`, service | attempt 1 after a fix | attempt 1, all passed | $1.56 (incl. $0.84 before the fix) | found: the Examiner never saw listed test files; fixed in `f3b2750` (they now appear under Current source) |
| noir-bookmarks #9 search (two abstract methods, JPQL with LIKE escaping, paging) | `@DataJpaTest`, service | attempt 2 (attempt 1: a hidden test disagreed with its own reference) | attempt 1, all passed | $1.67 | |
| noir-bookmarks #12 `PUT /bookmarks/{id}` (replaced a PATCH spec at the user's request) | `@WebMvcTest` with MockMvc, service | attempt 1 | attempt 1, all passed | $0.84 | |
| noir-test #53 `Base62` (plain JUnit regression) | JUnit | attempt 1 | attempt 1, all passed | $0.33 | inventory lists only JUnit 6's `jspecify` |

The layered changes that could not seal in Phase 1 (an abstract repository method, a JPA adapter change, a changed test fake, a web endpoint) now seal and build without shaping the code around noir.
