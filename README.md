# noir

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A **dark software factory**: an experiment in how far autonomous software
delivery can go. You write a spec and approve it; noir turns it into a pull
request with no human in the loop between "approved" and "PR open".

> **Status:** experimental (v0.1). Java/Maven targets only. Expect sharp edges.

noir is split in two. The **server** runs `noir run`: it watches your
configured repos, and when a human adds the `noir:ready` label to an issue it
seals and builds the spec in that issue. The **desktop** only writes specs
(with the `/noir-spec` skill) and publishes them as draft issues.

## How it works

<p align="center"><img src="docs/how-it-works.svg" alt="noir pipeline: you write and approve a spec; the server seals hidden tests, builds, measures and grades with no human in the loop, then opens a pull request" width="100%"></p>

1. **Spec.** A work item is a GitHub issue whose body is the spec: `## Intent`,
   `## API contract` (the public API stubs), numbered `## Requirements`
   (R1, R2, … each with given/when/then examples), `## Constraints` and a
   `## Quality bar`. The issue title is the spec title. Drafts carry the
   `noir:draft` label. A spec can also **modify existing Java files**: an
   `### <path> (modify)` entry under `## API contract` holds a fragment with
   only the new and changed members (bodies throw
   `UnsupportedOperationException`), which noir splices into the file on the
   default branch. An optional `## Tests to update` section lists existing
   tests the build may change. **Lock rule:** for every spec, a build may
   change only the files the spec lists (modify targets and tests to update)
   plus new files; any other change (including deleting or renaming an allowed
   file) is a violation and sends the attempt back as a contract-violation
   rework. In modified files only the changed lines are measured. If the
   change breaks existing tests that are not listed, sealing is rejected with
   `conflicts_with_existing_tests`; list those tests under
   `## Tests to update` and add `noir:ready` again. Check a spec against
   a local clone with `noir spec check <file> --title <t> --repo .`.
2. **Approve.** A human reviews the issue and adds `noir:ready`. Only humans
   add that label; it is the approval.
3. **Seal.** The server asks a separate *Examiner* agent to write a
   **holdout acceptance suite** and a reference implementation. noir checks
   that the tests fail on the stubs, pass on the reference (3×), and are
   strong enough (PIT mutation score ≥ 0.70). The holdout suite is stored on
   the server and never shown to the builder; a SHA-256 commitment to it is
   posted on the issue. If a seal is rejected, noir comments on the issue,
   removes `noir:ready` and labels it `noir:draft`; revise the spec and add
   `noir:ready` again. The approval locks the text: an issue whose title or
   body was edited after `noir:ready` was added is rejected the same way, and
   edits after sealing never affect the build (noir comments once to say so).
4. **Build.** The server runs Claude Code headlessly in a locked-down Docker
   container against the spec.
5. **Measure.** In fresh containers noir measures line coverage (JaCoCo),
   mutation score (PIT), CRAP score, and API conformance of the changed
   classes. These guide rework; they are not the gate.
6. **Grade.** The sealed holdout suite runs in a grader with no network
   under a Java Security Manager. Failures go back to the builder as
   per-requirement pass counts only — never test code — so the agent can't
   overfit to the test.
7. **Deliver.** On a pass noir pushes a branch and opens a PR with the
   metrics, holdout results, provenance (base SHA, commitment, models, image
   digests) and cost (builder and Examiner, as reported by Claude Code). Each attempt is one commit titled after the issue, e.g.
   `Add a Counter (#12)` (`…, attempt 2` for rework). When attempts run out it
   opens a draft PR instead.

Work items move through `DRAFT → SEALING → SEALED → BUILDING → MEASURING →
GRADING → PR_OPEN`, with `REWORK`, `FAILED`, `BLOCKED`, and `CANCELLED` along
the way, mirrored as `noir:*` labels on the issue (`noir:draft`, `noir:ready`,
`noir:sealing`, `noir:building`, `noir:pr-open`, `noir:failed`,
`noir:blocked`).

## Requirements

- Java 21 and Maven (to build noir)
- [`gh`](https://cli.github.com/) authenticated with `repo` scope

The **server** additionally needs Docker (Docker Desktop is fine; give it
≥ 4 GB per container) and an Anthropic credential: `ANTHROPIC_API_KEY` or
`CLAUDE_CODE_OAUTH_TOKEN`. The **desktop** needs neither Docker nor a
credential.

Target repositories must be single-module Maven projects on Java 21.

## Quick start

### Server

```bash
mvn -B package -DskipTests

# ~/.noir/config.yaml
mkdir -p ~/.noir
cat > ~/.noir/config.yaml <<'EOF'
repos:
  - you/your-repo
EOF

# with Docker running and your credential exported
bin/noir run               # keeps polling; `bin/noir run --once` for one pass
bin/noir status
```

To keep the server's state apart from a desktop's `~/.noir`, give every command
the same `--home`, e.g. `bin/noir --home ~/noir-server run` with
`~/noir-server/config.yaml`. Restart `noir run` after rebuilding noir: the
running server keeps the old code.

Credentials are passed to containers through a private env file, never on the
command line, and every outbound GitHub text is checked for them.

`bin/noir status` lists every work item with its id (`nr-…`). A `BLOCKED`
item needs the operator: once the cause is fixed (Docker stopped, GitHub or
the API unreachable, …), `bin/noir resume <id>` re-runs the phase it blocked
in. An item that blocked while sealing says so in a comment on its issue,
naming that command. `bin/noir cancel <id>` stops an item for good. It refuses
while `noir run` is active; close the issue instead and the next pass cancels
the item.

### Desktop

```bash
mvn -B package -DskipTests
bin/noir skill install     # installs /noir-spec into ~/.claude/skills
```

A noir server must already be watching the repo: it creates the `noir:*` labels
that `/noir-spec` checks for, and otherwise the skill stops with "No noir
server watches".

Open a Claude Code session inside a clone of the target repo and run
`/noir-spec`. It reads the repo, works out the API stubs and numbered,
testable requirements with you one question at a time (drafting in a
temporary directory, never in your working tree), checks the spec, and
publishes it as an issue labelled `noir:draft`. Review the issue on GitHub and
add the `noir:ready` label to approve it; the server takes it from there. To
revise a draft, run `/noir-spec #N`.

**Without the skill:**

```bash
bin/noir spec template --title "Add a Counter" > body.md
# edit body.md
bin/noir spec check body.md --title "Add a Counter"
gh issue create --repo you/your-repo --title "Add a Counter" \
  --body-file body.md --label noir:draft
```

Then add `noir:ready` on GitHub.

## Building and testing

```bash
mvn -B verify              # unit tests
mvn -B -Pdocker verify     # plus Docker integration tests
```

Formatting is enforced with Spotless (google-java-format).

## Design

- [`docs/superpowers/specs/2026-11-03-noir-v1-design.md`](docs/superpowers/specs/2026-11-03-noir-v1-design.md):
  the v1 design.
- [`docs/superpowers/specs/2026-11-05-noir-approval-flow-design.md`](docs/superpowers/specs/2026-11-05-noir-approval-flow-design.md):
  approval with `noir:ready` and sealing on the server, which replace the
  v1 desktop seal flow.
- [`docs/superpowers/specs/2026-11-05-noir-modify-existing-code-design.md`](docs/superpowers/specs/2026-11-05-noir-modify-existing-code-design.md):
  modifying existing Java files (Plan 6).

## License

[Apache License 2.0](LICENSE)
