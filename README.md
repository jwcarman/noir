# noir

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A **dark software factory**: an experiment in how far autonomous software
delivery can go. You write a spec; noir turns it into a pull request with no
human in the loop between "sealed" and "PR open".

> **Status:** experimental (v0.1). Java/Maven targets only. Expect sharp edges.

## How it works

1. **Spec.** A work item is a structured spec (`spec.json`): requirements
   (R1, R2, …) and the public API stubs the implementation must provide.
2. **Seal.** `noir seal` asks a separate *Examiner* agent to write a
   **holdout acceptance suite** and a reference implementation. noir checks
   that the tests fail on the stubs, pass on the reference (3×), and are
   strong enough (PIT mutation score ≥ 0.70). The holdout suite is stored
   locally and never shown to the builder; a SHA-256 commitment to it is
   posted on the GitHub issue that carries the visible spec.
3. **Build.** `noir run` picks up sealed issues and runs Claude Code
   headlessly in a locked-down Docker container against the spec.
4. **Measure.** In fresh containers noir measures line coverage (JaCoCo),
   mutation score (PIT), CRAP score, and API conformance of the changed
   classes. These guide rework; they are not the gate.
5. **Grade.** The sealed holdout suite runs in a grader with no network
   under a Java Security Manager. Failures go back to the builder as
   per-requirement pass counts only — never test code — so the agent can't
   overfit to the test.
6. **Deliver.** On a pass noir pushes a branch and opens a PR with the
   metrics, holdout results, and provenance (base SHA, commitment, models,
   image digests). When attempts run out it opens a draft PR instead.

Work items move through `SEALED → BUILDING → MEASURING → GRADING →
PR_OPEN`, with `REWORK`, `FAILED`, `BLOCKED`, and `CANCELLED` along the way,
mirrored as `noir:*` labels on the issue.

## Requirements

- Java 21 and Maven (to build noir)
- Docker (Docker Desktop is fine; give it ≥ 4 GB per container)
- [`gh`](https://cli.github.com/) authenticated with `repo` scope
- An Anthropic credential: `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN`

Target repositories must be single-module Maven projects on Java 21.

## Quick start

```bash
mvn -B package -DskipTests

# ~/.noir/config.yaml
cat > ~/.noir/config.yaml <<'EOF'
repos:
  - you/your-repo
EOF

bin/noir skill install     # installs /noir-spec into ~/.claude/skills
```

Then open a Claude Code session inside a clean local clone of the target repo
and run `/noir-spec`. It reads the repo, works out the API stubs and
numbered, testable requirements with you one question at a time, writes the
draft under `~/.noir/store/drafts/` (never into your working tree), shows you
the exact issue text for approval, and seals it. Then build it from a
terminal where your credential is exported:

```bash
bin/noir run --once        # or `bin/noir run` to keep polling
bin/noir status
```

**Without the skill:** `bin/noir draft init --repo you/your-repo --title "Add
a Counter"`, fill in the printed draft's `spec.json` and `stubs/` by hand,
check it with `bin/noir draft check <draft-id>`, preview it with
`bin/noir draft show <draft-id>`, then
`bin/noir seal <draft-id> --repo-dir path/to/local/clone`.

Credentials are passed to containers through a private env file, never on
the command line, and every outbound GitHub text is checked for them.

## Building and testing

```bash
mvn -B verify              # unit tests
mvn -B -Pdocker verify     # plus Docker integration tests
```

Formatting is enforced with Spotless (google-java-format).

## Design

See [`docs/superpowers/specs/2026-11-03-noir-v1-design.md`](docs/superpowers/specs/2026-11-03-noir-v1-design.md).

## License

[Apache License 2.0](LICENSE)
