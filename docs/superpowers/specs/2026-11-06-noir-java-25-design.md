# noir: Java 25 (design)

Date: 2026-11-06
Status: draft for review
Builds on: `2026-11-06-noir-spring-hidden-tests-design.md` (Phase 2)

## 1. Problem

noir's builder and grader images ship JDK 21, and the seal check rejects any repository whose
`maven.compiler.release` is not `21`. Java 25 is the current LTS and most of the operator's
projects use it, so noir rejects them before anything runs.

**Goal.** Repositories on Java 25 seal, build, measure (JaCoCo, PIT) and grade. Repositories on
Java 21 keep working.

## 2. Decision

One image pair on **JDK 25** for every repository. Maven compiles with the repository's own
`maven.compiler.release`, so older targets build as before; their tests run on the Java 25
runtime. The alternative, one image per release, was rejected as more images to build and keep in
step for no practical gain.

## 3. Changes

**Images**
- Builder: `FROM maven:3.10.0-eclipse-temurin-25-noble` (was `…-temurin-21-noble`).
- Grader: `FROM eclipse-temurin:25-jdk-noble` (was `21-jdk-noble`).
- Toolchain: PIT `pitest-command-line` 1.30.2 (was 1.30.1); JaCoCo 0.8.16, `pitest-junit5-plugin`
  1.2.3 and the JUnit console launcher 6.1.3 unchanged.
- The image's warm-up project compiles with release 25.

**Seal check.** `maven.compiler.release` must be an integer from **17 to 25** inclusive. An unset
value or one outside the range is rejected with
`maven.compiler.release must be from 17 to 25 (found <value>|unset)`, replacing
`maven.compiler.release must be 21 (found …)`. `SealProblems` passes the new text through to the
issue comment as it does the old one.

**Docs.** README requirements, the `/noir-spec` skill's assumptions, and any other "Java 21"
statement about target repositories.

No change to the analyzer, the Examiner, the grader script, the commitment, `spec.json` or the
issue format.

## 4. Testing

Unit:
- releases `17`, `21` and `25` accepted; `11`, `26`, `abc` and unset rejected with the exact text;
- the issue comment carries the new text.

Docker integration:
- a release-25 fixture repository whose code uses a Java 25 feature (for example an unnamed
  variable `_` or a `import module` declaration) seals, builds, measures (JaCoCo coverage and PIT
  mutants present) and grades;
- every existing release-21 fixture still passes.

Acceptance:
- bump `jwcarman/noir-test` to `maven.compiler.release` 25 (a human commit; noir cannot change the
  pom), then run one new-file spec and one modify spec there;
- one regression spec on `jwcarman/noir-bookmarks`, which stays on release 21.
