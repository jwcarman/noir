---
name: noir-spec
description: Use when the user wants noir (the dark software factory) to build something in the current repository. Co-writes a testable noir spec with the user (intent, API stubs, numbered requirements with concrete examples) in a noir draft, checks it, and seals it into a GitHub issue that noir then builds with no human in the loop.
---

# /noir-spec: write and seal a noir spec

noir turns a **sealed spec** into a pull request with no human in the loop. This skill is the
only interactive step, so the spec you write together is everything noir will ever know about
the work:

- A **builder** agent implements it from the rendered spec and the API stubs.
- A separate **Examiner** agent, which sees *only* the rendered spec and stubs (never this
  conversation, never the repository's code), writes hidden **holdout tests** that decide whether
  the build is accepted.
- **Fairness rule:** the Examiner may test only behavior the spec states. Anything that matters
  and is not written down as a requirement, an example, or stub Javadoc will not be tested, and
  the builder is free to do it differently. Anything vague will be tested by guesswork.

Your job is to get the user's intent into a spec that is complete, precise, and testable.

## Ground rules

- **Never write into the repository working tree.** Everything you produce goes into the noir
  draft directory (`~/.noir/store/drafts/<id>/`): its `spec.json` and the stub sources under its
  `stubs/` directory. Read the repository freely; do not create, edit, or delete files in it, and
  do not commit, branch, or push.
- **Never look at holdout tests.** The holdout tests are hidden from you as much as from the
  builder. Never run `noir audit <id>`, never read anything under
  `~/.noir/store/<id>/holdout` or `~/.noir/store/<id>/reference`, and do not look at holdout
  tests in any other way (for example in issue comments or logs). `noir audit <id>` is for the
  human only.
- **Never ask the user for credentials.** Do not ask them to paste an API key or token into the
  conversation, and never print one. `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN` stays
  exported in the user's own terminal.
- **Ask one question at a time.** Wait for the answer before moving on. Offer a concrete proposal
  the user can accept or correct rather than an open-ended question whenever you can.
- **Resolve the launcher first.** If `command -v noir` finds it, use `noir`. Otherwise ask the
  user for the path to `bin/noir` in their noir checkout (it runs `target/noir.jar`, built with
  `mvn -B package -DskipTests` in that checkout) and use that path wherever this skill says `noir`.

## 1. Preconditions: stop if any fails

Run these checks from the repository root and tell the user plainly which one failed and why
noir cannot proceed.

1. **A clean local clone of the target repo.** `git rev-parse --show-toplevel` succeeds and
   `git status --porcelain` prints nothing. Derive `<owner/name>` from `git remote get-url origin`
   (it must be a GitHub remote).
2. **On the default branch, up to date with origin.** noir seals against the **default branch on
   origin**, not your local checkout. In the repository, find the default branch's name with
   `gh repo view --json defaultBranchRef -q .defaultBranchRef.name` (it prints a bare name such
   as `main`). Check that `git branch --show-current` prints that name. Then run
   `git fetch origin` and check that `git rev-parse HEAD` equals `git rev-parse origin/<name>`.
   If the user is on another branch, or HEAD is behind or ahead of origin, stop and say so: you
   would be reading code the build will never see. Do not switch branches or pull for them.
3. **noir knows the repo.** `~/.noir/config.yaml` exists and its `repos:` list contains
   `<owner/name>`. If not, stop and ask the user to add it; `noir run` ignores repos that are not
   listed.
4. **noir's build assumptions hold** (noir refuses to seal otherwise):
   - a single-module Maven project: `pom.xml` at the root and no `<modules>` in it;
   - Java 21: `maven.compiler.release` is `21` (check the `pom.xml` properties or compiler plugin
     configuration, or run `mvn -q help:evaluate -Dexpression=maven.compiler.release -DforceStdout`).

   Gradle, multi-module builds, and other Java versions are out of scope: say so and stop.

## 2. Start the draft

As soon as every precondition passes, before reading the repository or asking anything else,
create the draft. noir measures the human time a spec takes from this moment to the seal, so
start it now rather than after the conversation:

```
noir draft init --repo <owner/name> --title "<title>"
```

Use a short provisional title taken from the user's first description of the work (if they
have not described it yet, ask for one sentence first). The title can be changed later by
editing `title` in the draft's `spec.json`. The command prints `Created draft <id>` followed by
the draft directory. Remember both: everything you produce goes in that directory and nowhere
else.

## 3. Read the repository

Before asking anything substantive, build a picture of the code the work will live in:

- the packages under `src/main/java` and the types near where the new work belongs;
- existing public APIs, naming, how errors are reported (which exception types), nullability
  conventions, and whether types are classes, records, interfaces, or builders;
- the test style under `src/test/java` (for your own understanding: the holdout tests are
  JUnit Jupiter only and do not use the repository's test dependencies);
- dependencies in `pom.xml`, and formatting plugins (such as Spotless or Checkstyle) that run
  during `mvn compile`; the stubs must pass them.

Keep this brief. Summarize back only what is relevant to the user's request.

## 4. The conversation

Work through these in order, one question at a time.

### 4a. Intent

Ask what they want built and why. Restate it as a short `intent` paragraph (the problem and the
outcome, not the implementation) and a `title` (an imperative phrase such as
"Add a bounded LRU cache"); it replaces the provisional title. Get agreement before going
further. If the request is really
several independent pieces of work, propose splitting it into separate specs.

### 4b. API surface

Propose the public types and method signatures, following the repository's conventions. These
become the **API stubs**. Design them so the hidden tests can exercise the behavior:

- **Holdout tests may import only `java.*`, JUnit Jupiter, and the stubs' packages.** Every type
  a test needs to construct, call, or catch must be a JDK type or a type declared in a stub (or
  already living in a stub's package). Do not expose repository types from other packages in
  the API surface.
- **The Examiner never sees the repository.** If a signature mentions an existing type, the
  Examiner knows only what the spec says about it. Prefer new types plus JDK types.
- **Prefer new files.** A stub is copied over the repository at its path, so a stub for an
  existing file replaces that whole file. If the work truly must extend an existing class, the
  stub has to be that file's complete current content plus the new members, and only the new
  members' bodies throw. Discuss this with the user before choosing it.
- **Behavior lives in methods that throw in the stub.** Every holdout test must fail against the
  stubs, so behavior cannot hide in code the stub already implements (record accessors, enum
  constants, constants, default methods with real bodies). Constructors that validate their
  arguments throw in the stub too.
- **Keep it deterministic.** Holdout tests cannot read the clock, use unseeded randomness, start
  threads, use the network, or touch files outside a temporary directory, and each test has a
  10 second timeout by default. If behavior depends on time or randomness, make it injectable (take a
  `java.time.Clock`, a seeded `java.util.random.RandomGenerator`, or a `Path`) so it can be
  tested. Concurrency guarantees cannot be tested by the holdout suite; record them as
  constraints if they matter.

### 4c. Requirements and examples

Agree on numbered requirements `R1`, `R2`, ... in order. Each requirement:

- states **one observable behavior** in terms of the stub API ("`get(key)` returns the value
  most recently stored under `key`"), not implementation details;
- has **at least one concrete example**, written as `given` / `when` / `then` with real values
  and Java expressions, for example given "a `Counter` created with `new Counter(5)`", when
  "`increment()` is called twice", then "`value()` returns `7`". Add examples for boundaries
  and error cases (empty, zero, maximum, duplicate, missing).

**Push back on untestable language.** Do not accept "handles errors gracefully", "is fast",
"is robust", "works correctly", "validates input", "etc.", or "as appropriate". Turn each into a
checkable statement and propose the replacement, for example "handles errors gracefully" becomes
"throws `IllegalArgumentException` when `key` is blank".

**Decide the edges explicitly.** For each method ask what happens with `null`, empty, blank,
negative, out of range, duplicate, and missing inputs where they apply. The Examiner tests `null`
handling, exception types, exception messages, ordering, mutability of returned collections, and
`equals`/`hashCode`/`toString` **only if the spec states them**. If the user cares, write it
down: state the exact exception type, and the exact message only if it matters. If the user does
not care, leave it unstated so the builder has freedom.

Also ask about **constraints**: rules the builder must respect that are not behavioral tests
(for example "no new dependencies", "keep the public API of `Foo` unchanged", "use only the JDK").
Leave the `qualityBar` noir generated unless the user asks to change it.

Before writing files, read the whole spec back to the user in plain language and resolve any
contradictions or gaps.

## 5. Write the draft

Fill in the draft created in step 2. Everything below goes in its directory and nowhere else.

**`spec.json`**: edit the generated skeleton and replace every `TODO`; set `title` to the agreed
title. Keep `schemaVersion`,
`id`, `repo`, and `qualityBar` as generated, leave `baseSha` as `null` (sealing fills it in
from origin), and add no other fields. The shape:

```json
{
  "schemaVersion": 1,
  "id": "<id>",
  "repo": "<owner/name>",
  "baseSha": null,
  "title": "Add a bounded LRU cache",
  "intent": "One paragraph: the problem and the outcome.",
  "requirements": [
    {
      "id": "R1",
      "statement": "One observable behavior of the stub API.",
      "examples": [
        { "given": "...", "when": "...", "then": "..." }
      ]
    }
  ],
  "constraints": ["Use only the JDK; add no dependencies."],
  "qualityBar": { "lineCoverage": 0.8, "mutationScore": 0.7, "maxCrap": 30 },
  "apiStubs": ["src/main/java/com/example/cache/LruCache.java"]
}
```

**Stubs**: each `apiStubs` entry is a repository-relative path under `src/main/java/`, and its
file lives at `<draft directory>/stubs/` followed by that same path, for example
`<draft directory>/stubs/src/main/java/com/example/cache/LruCache.java`. Every stub:

- declares the package that matches its directory, compiles against the repository, and follows
  the repository's formatting rules;
- contains every public type, constructor, and method of the agreed API with exact signatures;
- has method and constructor bodies that do only `throw new UnsupportedOperationException();`
  (with fields only as needed for it to compile);
- carries Javadoc on every public type and member stating the behavior, consistent with the
  requirements: parameters, return values, `@throws` with exact exception types, and `null`
  handling where decided. Javadoc is part of the spec; the Examiner may test what it states.

## 6. Check and approve

Run the mechanical check and fix every problem it reports, repeating until it prints
`Draft <id> OK`:

```
noir draft check <id>
```

Optionally, confirm the stubs compile without touching the working tree: create a temporary
directory outside it and keep its path (`tmp=$(mktemp -d)`), clone the repository into it
(`git clone -q . "$tmp/repo"`), copy the draft's `stubs/` contents over that clone
(`cp -R <draft directory>/stubs/. "$tmp/repo/"`), run `mvn -B -q -DskipTests compile` in
`"$tmp/repo"`, then delete the temporary directory (`rm -rf "$tmp"`). Sealing does the same compile in Docker, but this catches errors sooner.

Then render the exact GitHub issue text:

```
noir draft show <id>
```

Show the user that output in full and ask them to approve **that exact text**. Make any changes
they ask for, rerun `noir draft check <id>` and `noir draft show <id>`, and ask again. Do not
seal without explicit approval of the current text.

## 7. Seal

Sealing needs Docker running, `gh` authenticated with `repo` scope, and `ANTHROPIC_API_KEY` or
`CLAUDE_CODE_OAUTH_TOKEN` exported in the environment that runs noir. Check for the credential
without printing it: `[ -n "${ANTHROPIC_API_KEY:-}${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && echo present`.
If it is absent, do not ask for its value: tell the user to run the seal command below in their
own terminal, where it is exported, and share the output.

From the repository root:

```
noir seal <id> --repo-dir .
```

Sealing runs the Examiner (up to 3 attempts) and the seal checks in Docker. It often takes longer
than a foreground command is allowed to run (10 minutes), so **run it as a background command and
wait for it to exit** before reading its output. Never start a second `noir seal <id>` while one
is still running. If a seal is interrupted, rerun the same command only after the first process
has exited. That is safe: if the issue was already created it only finishes publishing,
otherwise it examines again. If you cannot run it in the
background, hand the command to the user to run in their own terminal and ask them to share the
output. Outcomes:

- `Sealed <id> as <owner/name>#<n> (commitment <hash>)`: report the issue number and the
  commitment to the user.
- `Not sealed:` followed by problems: explain them. If they point at an ambiguous, contradictory,
  or untestable spec, revise the draft with the user, go back through step 6 (check, show,
  approve), and seal again.
- Any other error (exit code 2): show the one-line message; it usually names a missing tool,
  Docker, `gh`, or credential problem for the user to fix.

After sealing, tell the user how noir builds it, run in their own terminal with the credential
exported:

```
noir run --once
```

and that `noir status` shows progress (`noir run` keeps polling instead of stopping after one
pass). Once the item has been built, `noir audit <id>` lets them review the hidden holdout tests
in their own terminal (you never run it).
Drafts can be listed at any time with `noir draft list`.
