# Hidden-test efficacy (2026-11-07)

Question: do noir's hidden (holdout) tests catch defects the builder's own tests miss?

## Method

For 24 merged `jwcarman/noir-test` items, at each item's PR merge commit, PIT (noir's own toolchain,
in the builder image) mutated the classes the spec touched and was run twice:

- **repo tests**: every test in the repository at that commit (the builder's tests plus earlier
  ones), i.e. what the project has without the hidden suite;
- **hidden suite**: only that item's sealed holdout tests from noir's store.

A mutant killed by the hidden suite but not by the repo tests is a defect the hidden-test gate
would catch that the builder's tests would not. Items #56 and #57 were skipped (local clone lacked
their merge commits). Throwaway scripts; not committed.

## Results

```
 item mutants  repo hidden hidden-only repo-only neither
    5       7     7      7           0         0       0
    7      12    12     12           0         0       0
    8      41    41     41           0         0       0
    9      13    13     13           0         0       0
   10      41    41     40           0         1       0
   11      33    31     32           1         0       1
        hidden-only: IntervalSet.remove line 60 VoidMethodCallMutator
   12      29    26     26           0         0       3
   19      15    14     13           0         1       1
   21      13    13      3           0        10       0
   24      17    17     14           0         3       0
   26      42    40     19           0        21       2
   27      46    46     24           0        22       0
   28       6     6      6           0         0       0
   33      41    41     37           0         4       0
   34      18    18     18           0         0       0
   36       6     6      5           0         1       0
   37      26    24     24           0         0       2
   38      16    13     13           0         0       3
   40      15    14     12           0         2       1
   41      37    34     20           0        14       3
   42      16    16     16           0         0       0
   49      49    49     14           0        35       0
   50      34    34     31           0         3       0
   53      21    21     21           0         0       0
TOTAL {'m': 594, 'repo': 577, 'hid': 461, 'hidonly': 1, 'repoonly': 117, 'neither': 16}
```

- 594 mutants; repo tests killed 577, the hidden suites 461.
- **Hidden-only kills: 1 of 594 (0.17%)** — `IntervalSet.remove`, a removed void call.
- Repo-only kills: 117, mostly pre-existing code in modify targets the hidden suite does not aim at.
- Across all 26 earlier builds, grading against the hidden suite never failed.

## Why

noir's measurement gate already forces the builder's own tests to be strong (mutation score ≥ 70%,
line coverage ≥ 80% on changed code). With a strong builder model and specs full of concrete
examples, the builder's tests cover what the hidden suite covers. The hidden suite costs roughly
half of every item (the Examiner, ~$0.20–0.30) and about a minute of sealing.

## Recommendation

Make hidden tests optional and off by default (`hiddenTests: on|off`, per server or per repo);
keep sealing of the spec text and the measurement gate as the quality bar. Turn them on where they
plausibly pay: a cheaper builder model, or a repository that wants the guarantee that grading is
independent of the builder. A weaker-builder experiment (cheaper model, same sealed suites) would
measure that case; run it only if a cheaper builder is planned.

Not run: hand-planted bugs. PIT already plants hundreds of small realistic faults, and the result
is too lopsided for a handful more to change it.
