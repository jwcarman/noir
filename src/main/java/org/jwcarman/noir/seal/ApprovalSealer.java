package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.noir.commit.Commitments;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.factory.Secrets;
import org.jwcarman.noir.git.GitPort;
import org.jwcarman.noir.git.RepoMirror;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.github.IssueSnapshot;
import org.jwcarman.noir.lifecycle.SealRejection;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.IssueSpec;
import org.jwcarman.noir.spec.IssueSpecException;
import org.jwcarman.noir.spec.RepoProblems;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.SpliceException;
import org.jwcarman.noir.spec.StubSplicer;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * The {@code SEALING} phase: seals the issue a human approved with {@code noir:ready}. Checks the
 * approval still covers the current text, parses the issue as a spec, pins the base commit,
 * examines a holdout suite and posts the commitment. Infrastructure exceptions propagate.
 */
public final class ApprovalSealer implements ApprovalSealing {
  /**
   * The GitHub edit time of the body being sealed, in {@code approved} and {@code sealed}: the
   * reconciler compares later edits against it (GitHub time against GitHub time).
   */
  static final String SEALED_EDITED_AT = "sealedEditedAt";

  static final List<String> SEAL_FILES =
      List.of(
          "spec.json",
          "stubs",
          "fragments",
          "base.bundle",
          "holdout",
          "reference",
          "commitment.json");

  private final Store store;
  private final GitHubPort gh;
  private final RepoMirror mirror;
  private final GitPort git;
  private final SuiteSealer suiteSealer;
  private final NoirConfig config;
  private final Clock clock;
  private final Set<String> secrets;

  public ApprovalSealer(
      Store store,
      GitHubPort gh,
      RepoMirror mirror,
      GitPort git,
      SuiteSealer suiteSealer,
      NoirConfig config,
      Clock clock,
      Set<String> secrets) {
    this.store = store;
    this.gh = gh;
    this.mirror = mirror;
    this.git = git;
    this.suiteSealer = suiteSealer;
    this.config = config;
    this.clock = clock;
    this.secrets = Set.copyOf(secrets);
  }

  @Override
  public SealOutcome seal(WorkItem item) {
    var itemDir = store.itemDir(item.id());
    var commitmentFile = itemDir.resolve("commitment.json");
    var snapshot = gh.issue(item.repo(), item.issueNumber());
    if (Files.isRegularFile(commitmentFile)) {
      // The suite is frozen once committed: finish publishing without re-checking or examining.
      return publish(item, commitmentFile);
    }

    var approval = snapshot.approval();
    if (approval.isEmpty() || snapshot.editedAt().isAfter(approval.get().at())) {
      return reject(
          item,
          itemDir,
          SealRejection.EDITED_AFTER_APPROVAL,
          ApprovalComments.editedAfterApproval(),
          1);
    }
    var bodySha = Commitments.sha256Hex(IssueSpec.normalize(snapshot.body()));
    recordApproval(item, snapshot, approval.get(), bodySha);

    IssueSpec parsed;
    List<String> problems;
    try {
      parsed = IssueSpec.parse(snapshot.title(), snapshot.body());
      problems = IssueSpec.lint(parsed);
    } catch (IssueSpecException e) {
      parsed = null;
      problems = e.problems();
    }
    if (!problems.isEmpty()) {
      return reject(
          item,
          itemDir,
          SealRejection.INVALID_SPEC,
          ApprovalComments.invalidSpec(problems),
          problems.size());
    }

    var branch = gh.defaultBranch(item.repo());
    var repoDir = mirror.sync(item.repo(), branch);
    var baseSha = git.revParse(repoDir, "origin/" + branch);

    var spliced = new ArrayList<String>();
    var baseSources = new LinkedHashMap<String, String>();
    var stubs = new ArrayList<StubSource>();
    var spliceProblems = new ArrayList<String>();
    for (var stub : parsed.stubs()) {
      if (!parsed.modifies().contains(stub.path())) {
        stubs.add(stub);
        continue;
      }
      var base = git.show(repoDir, baseSha, stub.path());
      if (base.isEmpty()) {
        spliceProblems.add(RepoProblems.modifyTargetMissing(stub.path()));
        continue;
      }
      try {
        stubs.add(
            new StubSource(
                stub.path(),
                StubSplicer.splice(stub.path(), base.get(), stub.content()).content()));
        spliced.add(stub.path());
        baseSources.put(stub.path(), base.get());
      } catch (SpliceException e) {
        spliceProblems.add(e.getMessage());
      }
    }
    for (var test : parsed.testsToUpdate()) {
      if (!git.exists(repoDir, baseSha, test)) {
        spliceProblems.add(RepoProblems.testMissing(test));
      }
    }
    if (!spliceProblems.isEmpty()) {
      return reject(
          item,
          itemDir,
          SealRejection.INVALID_SPEC,
          ApprovalComments.invalidSpec(spliceProblems),
          spliceProblems.size());
    }

    var existing = new ArrayList<String>();
    for (var stub : parsed.stubs()) {
      if (!parsed.modifies().contains(stub.path()) && git.exists(repoDir, baseSha, stub.path())) {
        existing.add(stub.path());
      }
    }
    if (!existing.isEmpty()) {
      return reject(
          item,
          itemDir,
          SealRejection.STUB_EXISTS,
          ApprovalComments.stubExists(existing),
          existing.size());
    }

    var spec = parsed.toSpec(item.id(), item.repo(), baseSha);
    deleteSealFiles(itemDir);
    SpecJson.write(spec, itemDir.resolve("spec.json"));
    writeFiles(itemDir, "stubs", stubs);
    writeFiles(
        itemDir,
        "fragments",
        parsed.stubs().stream().filter(s -> spliced.contains(s.path())).toList());
    var bundle = itemDir.resolve("base.bundle");
    git.bundle(repoDir, baseSha, bundle);

    var examination =
        suiteSealer.examine(spec, parsed.stubs(), stubs, baseSources, itemDir, bundle);
    if (examination.problems() != null && !examination.conflictingTests().isEmpty()) {
      return reject(
          item,
          itemDir,
          SealRejection.CONFLICTS_WITH_EXISTING_TESTS,
          ApprovalComments.conflictsWithExistingTests(examination.conflictingTests()),
          examination.problems().size());
    }
    if (examination.problems() != null) {
      return reject(
          item,
          itemDir,
          SealRejection.SUITE_TOO_WEAK,
          ApprovalComments.suiteTooWeak(
              config.examiner().attempts(), SealProblems.publicSummary(examination.problems())),
          examination.problems().size());
    }
    Commitments.write(
        Commitments.create(spec, itemDir, examination.model(), bodySha), commitmentFile);
    return publish(item, commitmentFile);
  }

  private void recordApproval(
      WorkItem item, IssueSnapshot snapshot, IssueSnapshot.Approval approval, String bodySha) {
    var data = new LinkedHashMap<String, Object>();
    putIfPresent(data, "approver", approval.actor());
    data.put("approvedAt", approval.at().toString());
    data.put("issueCreatedAt", snapshot.createdAt().toString());
    data.put("bodySha256", bodySha);
    putIfPresent(data, "title", snapshot.title());
    data.put(SEALED_EDITED_AT, snapshot.editedAt().toString());
    append(item, "approved", data);
  }

  /**
   * Posts the commitment comment (once) and records {@code commitment_posted} and {@code sealed}.
   */
  private SealOutcome publish(WorkItem item, Path commitmentFile) {
    var commitment = Commitments.read(commitmentFile);
    var sha = Commitments.sha256(commitment);
    var types = store.readEvents(item.id()).stream().map(Event::type).toList();
    if (!types.contains("commitment_posted")) {
      gh.comment(
          item.repo(),
          item.issueNumber(),
          Secrets.mask(
              ApprovalComments.sealed(sha, commitment.baseSha(), commitment.issueBodySha256()),
              secrets));
      append(item, "commitment_posted", Map.of("commitmentSha256", sha));
    }
    if (!types.contains("sealed")) {
      var data = new LinkedHashMap<String, Object>();
      data.put("issue", item.issueNumber());
      data.put("commitmentSha256", sha);
      data.put("baseSha", commitment.baseSha());
      data.put("bodySha256", commitment.issueBodySha256());
      sealedEditedAt(item, commitment.issueBodySha256())
          .ifPresent(at -> data.put(SEALED_EDITED_AT, at));
      append(item, "sealed", data);
    }
    return new SealOutcome.Passed(sha);
  }

  /**
   * The {@code sealedEditedAt} of the latest {@code approved} event for the committed body; empty
   * for an approval recorded before the key existed.
   */
  private Optional<String> sealedEditedAt(WorkItem item, String bodySha) {
    String found = null;
    for (var e : store.readEvents(item.id())) {
      if (e.type().equals("approved")
          && bodySha.equals(e.data().get("bodySha256"))
          && e.data().get(SEALED_EDITED_AT) instanceof String at) {
        found = at;
      }
    }
    return Optional.ofNullable(found);
  }

  private SealOutcome reject(
      WorkItem item, Path itemDir, SealRejection reason, String comment, int problems) {
    deleteSealFiles(itemDir);
    append(item, "seal_rejected", Map.of("reason", reason.eventValue(), "problems", problems));
    return new SealOutcome.Rejected(reason, Secrets.mask(comment, secrets));
  }

  private void append(WorkItem item, String type, Map<String, Object> data) {
    store.appendEvent(Event.of(clock, item.id(), type, item.attempt(), data));
  }

  private static void putIfPresent(Map<String, Object> data, String key, Object value) {
    if (value != null) {
      data.put(key, value);
    }
  }

  /** Writes each source at {@code <dir>/<path>}; writes nothing (no directory) when empty. */
  private static void writeFiles(Path itemDir, String dir, List<StubSource> stubs) {
    var root = itemDir.resolve(dir).normalize();
    try {
      for (var stub : stubs) {
        var file = root.resolve(stub.path()).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
          throw new IllegalArgumentException("stub path escapes " + dir + "/: " + stub.path());
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, stub.content());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteSealFiles(Path itemDir) {
    try {
      for (var name : SEAL_FILES) {
        var target = itemDir.resolve(name);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        try (var paths = Files.walk(target)) {
          for (var p : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(p);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
