# noir-bookmarks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `jwcarman/noir-bookmarks`, a realistic Spring Boot bookmarks API, and run the Phase 1 noir experiment against it, ending in a findings write-up.

**Architecture:** One Maven module, package `org.jwcarman.bookmarks`, layered `domain` → `service` → `web`, with `persistence` as a JPA adapter behind the small `BookmarkRepository` interface so services are testable with plain JUnit. Tasks 1–4 write the seed by hand; Task 5 publishes it and registers it with the noir server; Task 6 runs the noir specs; Task 7 writes the findings.

**Tech Stack:** Java 21, Spring Boot 3.5 (web, validation, data-jpa, test), H2, JUnit 5, AssertJ (seed tests only).

**Spec:** `docs/superpowers/specs/2026-11-06-noir-bookmarks-design.md`

## Global Constraints

- Repo `jwcarman/noir-bookmarks`, public, default branch `main`; local clone `~/IdeaProjects/noir-bookmarks`.
- Spring Boot parent: the latest `3.5.x` release (read it from `https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml`); Java 21 (`<java.version>21</java.version>`).
- Dependencies exactly: `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-data-jpa`, `com.h2database:h2` (runtime), `spring-boot-starter-test` (test). Nothing else.
- No Docker or external database to build or test: `mvn -B verify` passes on a clean machine.
- JPA types appear only in `org.jwcarman.bookmarks.persistence`.
- Services depend on `BookmarkRepository` and `java.time.Clock`, never on Spring Data.
- Phase 1 makes no design changes to noir; a small blocking noir bug may be fixed test-first and recorded in the findings.
- Creating the GitHub repo, editing `~/noir-server/config.yaml` and restarting the server need the user (Task 5).

## Review Focus

1. Paging past the end (`page` beyond the last page) returns an empty list with the right `total`, not an error — `BookmarkServiceTest.listPastTheEndIsEmpty`.
2. `size` of 0 or above 100, or a negative `page`, is a 400 problem detail from the web layer, not a 500 — `BookmarkControllerTest.badPagingIsAProblem`.
3. Tags come back in the order they were saved, after a JPA round trip — `JpaBookmarkRepositoryTest.tagsKeepTheirOrder`.
4. Deleting a missing id is a 404 problem detail, not a 204 — `BookmarkControllerTest.deleteMissingIs404`.
5. Bookmarks created in the same instant list deterministically (newest first, then higher id first) — `BookmarkServiceTest.listOrdersByCreatedAtThenId`.

---

### Task 1: Project skeleton

**Files:**
- Create in `~/IdeaProjects/noir-bookmarks`: `pom.xml`, `.gitignore` (`target/`, `.idea/`, `*.iml`), `README.md` (one paragraph: what the app is and that it is a noir target repo), `src/main/java/org/jwcarman/bookmarks/BookmarksApplication.java`, `src/main/java/org/jwcarman/bookmarks/ClockConfig.java`, `src/main/resources/application.properties`
- Test: `src/test/java/org/jwcarman/bookmarks/BookmarksApplicationTest.java`

**Interfaces:**
- Produces: a `java.time.Clock` bean (`Clock.systemUTC()`) from `ClockConfig`.

- [ ] **Step 1:** `git init -b main ~/IdeaProjects/noir-bookmarks`; write `pom.xml` with the parent and dependencies from Global Constraints, and `spring-boot-maven-plugin`.
- [ ] **Step 2: Write the failing test** `BookmarksApplicationTest.contextLoads` (`@SpringBootTest`), asserting the context has exactly one `Clock` bean.
- [ ] **Step 3:** Run `mvn -B test`. Expected: FAIL (no application class).
- [ ] **Step 4:** Write `BookmarksApplication` (`@SpringBootApplication`) and `ClockConfig`. `application.properties`: `spring.application.name=noir-bookmarks`, `spring.jpa.open-in-view=false`, `spring.mvc.problemdetails.enabled=true`.
- [ ] **Step 5:** Run `mvn -B verify`. Expected: BUILD SUCCESS.
- [ ] **Step 6:** Commit `chore: Spring Boot skeleton`.

### Task 2: Domain and service

**Files:**
- Create: `src/main/java/org/jwcarman/bookmarks/domain/Bookmark.java`, `domain/BookmarkRepository.java`, `service/BookmarkService.java`, `service/BookmarkPage.java`, `service/BookmarkNotFoundException.java`
- Test: `src/test/java/org/jwcarman/bookmarks/service/BookmarkServiceTest.java`, `src/test/java/org/jwcarman/bookmarks/service/InMemoryBookmarkRepository.java`

**Interfaces:**
- Produces:
  - `record Bookmark(Long id, String url, String title, List<String> tags, Instant createdAt)`: `url`, `title`, `tags`, `createdAt` non-null (`NullPointerException`); `tags` copied with `List.copyOf`; `id` is `null` until saved; `Bookmark withId(long id)`.
  - `interface BookmarkRepository`: `Bookmark save(Bookmark b)` (assigns an id when `b.id()` is null, returns the saved bookmark); `Optional<Bookmark> findById(long id)`; `boolean deleteById(long id)` (false if absent); `List<Bookmark> findAll(int page, int size)` ordered by `createdAt` descending then `id` descending; `long count()`.
  - `record BookmarkPage(List<Bookmark> items, int page, int size, long total)`.
  - `class BookmarkNotFoundException extends RuntimeException` with `long id()`; message `Bookmark <id> not found`.
  - `class BookmarkService(BookmarkRepository repository, Clock clock)` (a Spring `@Service`): `Bookmark create(String url, String title, List<String> tags)` stamps `Instant.now(clock)`; `Bookmark get(long id)`; `void delete(long id)` (both throw `BookmarkNotFoundException`); `BookmarkPage list(int page, int size)`, throwing `IllegalArgumentException` unless `page >= 0` and `1 <= size <= 100`.
  - `InMemoryBookmarkRepository` (test source): implements `BookmarkRepository` with a `TreeMap`, ids from 1.

- [ ] **Step 1: Write the failing tests** in `BookmarkServiceTest` (fixed `Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), UTC)`):
  - `createStampsTimeAndAssignsId`: `create("https://a.example", "A", List.of("x"))` has id `1L` and `createdAt` `2026-11-06T12:00:00Z`.
  - `getMissingThrows`: `get(99)` throws `BookmarkNotFoundException` with `id() == 99`.
  - `deleteRemovesAndMissingThrows`.
  - `listPagesNewestFirst`: three bookmarks created at 12:00, 12:01, 12:02; `list(0, 2)` items are the 12:02 and 12:01 ones, `total` 3; `list(1, 2)` is the 12:00 one.
  - `listPastTheEndIsEmpty`: `list(5, 10)` with 3 saved → empty items, `total` 3.
  - `listOrdersByCreatedAtThenId`: two bookmarks at the same instant → higher id first.
  - `listRejectsBadPaging`: `(-1, 10)`, `(0, 0)`, `(0, 101)` throw `IllegalArgumentException`.
- [ ] **Step 2:** Run `mvn -B test -Dtest=BookmarkServiceTest`. Expected: compilation failure.
- [ ] **Step 3:** Implement the Produces types above.
- [ ] **Step 4:** Run `mvn -B verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat: bookmark domain and service`.

### Task 3: JPA adapter

**Files:**
- Create: `src/main/java/org/jwcarman/bookmarks/persistence/BookmarkEntity.java`, `persistence/SpringDataBookmarks.java`, `persistence/JpaBookmarkRepository.java`
- Test: `src/test/java/org/jwcarman/bookmarks/persistence/JpaBookmarkRepositoryTest.java`

**Interfaces:**
- Consumes: `Bookmark`, `BookmarkRepository` (Task 2).
- Produces: `JpaBookmarkRepository implements BookmarkRepository` (`@Repository`), the only `BookmarkRepository` bean. `BookmarkEntity`: table `bookmark`, generated `Long id`, `url` (length 2048), `title` (length 200), `tags` as `@ElementCollection` with `@OrderColumn` (table `bookmark_tag`), `createdAt` `Instant`.

- [ ] **Step 1: Write the failing tests** (`@DataJpaTest` + `@Import(JpaBookmarkRepository.class)`):
  - `saveAssignsIdAndFindReturnsIt`.
  - `tagsKeepTheirOrder`: tags `List.of("b", "a", "c")` come back in that order from `findById`.
  - `deleteByIdReportsWhetherItExisted`.
  - `findAllPagesNewestFirstThenHigherId` and `count`.
- [ ] **Step 2:** Run `mvn -B test -Dtest=JpaBookmarkRepositoryTest`. Expected: compilation failure.
- [ ] **Step 3:** Implement; paging via `PageRequest.of(page, size, Sort.by(DESC, "createdAt", "id"))`.
- [ ] **Step 4:** Run `mvn -B verify`. Expected: BUILD SUCCESS, including `BookmarksApplicationTest`.
- [ ] **Step 5:** Commit `feat: JPA bookmark repository`.

### Task 4: Web layer

**Files:**
- Create: `src/main/java/org/jwcarman/bookmarks/web/BookmarkController.java`, `web/CreateBookmarkRequest.java`, `web/BookmarkResponse.java`, `web/BookmarkPageResponse.java`, `web/ProblemHandler.java`
- Test: `src/test/java/org/jwcarman/bookmarks/web/BookmarkControllerTest.java`

**Interfaces:**
- Consumes: `BookmarkService`, `BookmarkPage`, `BookmarkNotFoundException` (Task 2).
- Produces:
  - `record CreateBookmarkRequest(@NotBlank @Size(max = 2048) String url, @NotBlank @Size(max = 200) String title, List<@NotBlank String> tags)`; `null` tags mean an empty list.
  - `record BookmarkResponse(long id, String url, String title, List<String> tags, Instant createdAt)`.
  - `record BookmarkPageResponse(List<BookmarkResponse> items, int page, int size, long total)`.
  - `POST /bookmarks` → 201, `Location: /bookmarks/{id}`, body `BookmarkResponse`; `GET /bookmarks?page=0&size=20` (defaults) → 200 `BookmarkPageResponse`; `GET /bookmarks/{id}` → 200 or 404; `DELETE /bookmarks/{id}` → 204 or 404.
  - `ProblemHandler` (`@RestControllerAdvice`): `BookmarkNotFoundException` → 404 `ProblemDetail` with title `Bookmark not found` and detail the exception message; `IllegalArgumentException` → 400 with title `Bad request`. Bean Validation failures keep Spring's built-in 400 problem detail.

- [ ] **Step 1: Write the failing tests** (`@WebMvcTest(BookmarkController.class)`, `@MockitoBean BookmarkService`):
  - `createReturns201WithLocation`.
  - `createWithBlankUrlIs400`.
  - `getMissingIs404Problem`: content type `application/problem+json`, `$.title` `Bookmark not found`.
  - `deleteMissingIs404`.
  - `listUsesDefaults`: `GET /bookmarks` calls `list(0, 20)`.
  - `badPagingIsAProblem`: service throws `IllegalArgumentException` → 400 problem.
- [ ] **Step 2:** Run `mvn -B test -Dtest=BookmarkControllerTest`. Expected: compilation failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `mvn -B verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat: bookmarks REST API`.

### Task 5: Publish and register (needs the user)

- [ ] **Step 1:** Ask the user before creating the repo. Then `gh repo create jwcarman/noir-bookmarks --public --source ~/IdeaProjects/noir-bookmarks --push`.
- [ ] **Step 2:** Ask the user to add `- jwcarman/noir-bookmarks` under `repos:` in `~/noir-server/config.yaml` and restart `bin/noir --home ~/noir-server run`; confirm the startup line lists both repos.
- [ ] **Step 3:** Confirm the server created the `noir:*` labels on the new repo (`gh label list --repo jwcarman/noir-bookmarks`).

### Task 6: Phase 1 specs

Write each spec as `/noir-spec` would (read targets from `origin/main`; grep `src/test/java` for references before choosing `## Tests to update`), check it with `noir spec check <file> --title <t> --repo ~/IdeaProjects/noir-bookmarks`, publish with `noir:draft`, add `noir:ready`, and wait for the outcome before the next. Review and merge each passing PR.

- [ ] **Spec 1, domain, new file:** `domain/UrlNormalizer.java`, `static String normalize(String url)`: lowercase scheme and host, drop the default port (`:80` for http, `:443` for https), drop the fragment, remove a trailing `/` from a non-root path, keep the query as is; `IllegalArgumentException` for anything that is not an absolute `http` or `https` URL.
- [ ] **Spec 2, domain, modify `Bookmark`:** the canonical constructor trims and lowercases tags, drops blank and duplicate tags (first occurrence wins), and rejects more than 10 tags with `IllegalArgumentException`.
- [ ] **Spec 3, service, modify `BookmarkService.create`:** throws a new `DuplicateBookmarkException` (new file) when a bookmark with the same URL exists. Needs `Optional<Bookmark> findByUrl(String url)` on `BookmarkRepository`; record what happens to `JpaBookmarkRepository` and `InMemoryBookmarkRepository` (which must change and are outside the hidden tests' reach).
- [ ] **Spec 4, service + repository:** `BookmarkPage search(String text, Set<String> tags, int page, int size)` on `BookmarkService`: `text` matches title or URL, case-insensitive; every given tag must be present.
- [ ] **Spec 5, stretch, web:** `PATCH /bookmarks/{id}` updating title and tags. Expected to fail or be rejected; record why.
- [ ] For every spec, record in a scratch table: outcome, attempts, seal time, build wall time, cost (from the PR or `events.jsonl`), `Downloaded from` counts in the seal and measure logs under `~/noir-server/store/<item>/`, the failing step and its message, and anything odd in the PR.

### Task 7: Findings

**Files:**
- Create (noir repo): `docs/findings/2026-11-06-spring-phase-1.md`

- [ ] **Step 1:** Write one row per spec with the Task 6 measurements, then one section per problem: what happened, evidence (paths, log excerpts, event data), and a proposed fix tagged for the Phase 2 design (holdout test libraries, grader under Spring, dependency caching, existing-test run time, or other).
- [ ] **Step 2:** Commit `docs: Spring Phase 1 findings` in the noir repo (do not push without asking).
