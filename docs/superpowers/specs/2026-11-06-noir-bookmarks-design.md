# noir-bookmarks: a Spring Boot target repo (design)

Date: 2026-11-06
Status: draft for review
Builds on: `2026-11-05-noir-modify-existing-code-design.md` (Plan 6)

## 1. Purpose

noir has only been exercised on `jwcarman/noir-test`, a repo of small JDK-only classes. Real Java
work is mostly Spring Boot services. This project creates a realistic Spring Boot API as a second
target repo and runs noir specs against it, to learn:

- whether noir builds domain and service specs in a Spring codebase as reliably as in noir-test;
- what noir must change before web-layer and persistence specs can work (test libraries the
  Examiner may use, how the grader runs Spring, dependency caching).

The app is a test fixture, not a product. It should look like real code: layers, validation,
persistence, hand-written tests.

Success:
- Phase 1 specs on the domain and service layers seal and build, or fail for reasons we can name.
- A findings write-up lists each problem with evidence, ready to become the Phase 2 design.

## 2. The repo

- `jwcarman/noir-bookmarks`, public, default branch `main`.
- Spring Boot 3.5, Java 21, one Maven module.
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-validation`,
  `spring-boot-starter-data-jpa`, `com.h2database:h2` (runtime), `spring-boot-starter-test` (test).
  No Docker or external database is needed to build or test.

## 3. Seed code (written by hand)

noir cannot create `pom.xml` or change non-Java files, so the seed is written by hand. Package
`org.jwcarman.bookmarks`:

| Package | Contents |
|---|---|
| `domain` | `Bookmark` record (`id`, `url`, `title`, `tags`, `createdAt`); `BookmarkRepository`, a small interface (`save`, `findById`, `deleteById`, `findAll(int page, int size)`, `count`) |
| `persistence` | `BookmarkEntity` (JPA), `SpringDataBookmarks` (`JpaRepository`), `JpaBookmarkRepository` implementing `BookmarkRepository`. The only place JPA appears. |
| `service` | `BookmarkService`: `create`, `get`, `delete`, `list(page, size)`. Takes `BookmarkRepository` and a `java.time.Clock`. Throws `BookmarkNotFoundException`. |
| `web` | `BookmarkController` (`POST/GET/DELETE /bookmarks`, `GET /bookmarks/{id}`), request and response DTOs with Bean Validation, a `@RestControllerAdvice` that returns RFC 7807 problem details for 404 and 400 |
| root | `BookmarksApplication`, a `Clock` bean |

Seed tests, which are also the existing tests noir must keep passing:
- `BookmarkServiceTest`: plain JUnit with an in-memory fake `BookmarkRepository` and a fixed `Clock`.
- `JpaBookmarkRepositoryTest`: `@DataJpaTest` on H2.
- `BookmarkControllerTest`: `@WebMvcTest` with a mocked service.

Deliberately left for specs: URL normalization, tag rules, duplicate-URL rejection, search by text
and tags, sorting, update/patch, import/export.

## 4. Phase 1 experiment (today's noir, no noir changes)

Add `jwcarman/noir-bookmarks` to `~/noir-server/config.yaml` (server restart). Publish and approve
specs one at a time, simplest first:

1. **Domain, new file:** `UrlNormalizer` (no Spring).
2. **Domain, modify:** tag rules on `Bookmark` (lowercase, trimmed, no duplicates, at most 10).
3. **Service, modify:** `BookmarkService.create` rejects a URL that is already saved; the existing
   service tests must keep passing.
4. **Service + repository:** search by text and tags. Needs a new `BookmarkRepository` method and
   a JPA adapter change that hidden tests cannot reach.
5. **Stretch, web:** a controller spec. Expected to fail or be rejected; the point is to record
   why.

For each spec, record: outcome, attempts, seal time, build wall time, cost, Maven downloads per
container, the failing seal or measure step and its message, and anything odd in the PR.

## 5. Deliverable

`docs/findings/2026-11-06-spring-phase-1.md` in the noir repo: one row per spec with the
measurements above, then each problem found with evidence and a proposed fix. It feeds the
Phase 2 design (allowed holdout test libraries, grader under Spring, dependency caching,
existing-test run time), which is a separate spec.

## 6. Out of scope

- Design changes to noir. Phase 1 observes; Phase 2 designs. A small bug that blocks Phase 1 may
  be fixed test-first and recorded in the findings.
- Multi-module builds, Testcontainers, a real database.
- Security, authentication, deployment.
