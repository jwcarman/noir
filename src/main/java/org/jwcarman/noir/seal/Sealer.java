package org.jwcarman.noir.seal;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jwcarman.noir.commit.Commitments;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.git.GitPort;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.json.Json;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.SpecLint;
import org.jwcarman.noir.store.AtomicFiles;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/** Turns a draft into a sealed work item (spec §5). */
public final class Sealer {
  private final Store store;
  private final GitPort git;
  private final GitHubPort github;
  private final SuiteSealer suiteSealer;
  private final Clock clock;

  public Sealer(
      Store store,
      GitPort git,
      GitHubPort github,
      Examiner examiner,
      SealCheckRunner checks,
      NoirConfig config,
      Clock clock) {
    this.store = store;
    this.git = git;
    this.github = github;
    this.suiteSealer = new SuiteSealer(store, examiner, checks, config, clock);
    this.clock = clock;
  }

  public SealResult seal(String draftId, Path repoDir) {
    var draft = store.draftDir(draftId);
    var specFile = draft.resolve("spec.json");
    if (!Files.isRegularFile(specFile)) {
      throw new SealException("No draft " + draftId);
    }
    var spec = SpecJson.read(specFile);
    if (store.readEvents(draft).stream().noneMatch(e -> e.type().equals("seal_requested"))) {
      store.appendEvent(draft, Event.of(clock, spec.id(), "seal_requested", 0, Map.of()));
    }
    var progress = readProgress(draft);
    if (progress.issueNumber() != null) {
      // The issue exists, so the suite is frozen: finish publishing without re-examining.
      return publish(spec, draft, progress);
    }

    var origin = git.originRepo(repoDir);
    if (!origin.equals(spec.repo())) {
      return new SealResult.Rejected(
          List.of(
              "repository at "
                  + repoDir
                  + " is "
                  + origin
                  + ", but the draft is for "
                  + spec.repo()));
    }

    var branch = github.defaultBranch(spec.repo());
    git.fetch(repoDir, branch);
    spec = spec.withBaseSha(git.revParse(repoDir, "origin/" + branch));
    SpecJson.write(spec, specFile);

    var lint = SpecLint.lint(spec);
    if (!lint.isEmpty()) {
      return new SealResult.Rejected(lint);
    }

    var bundle = draft.resolve("base.bundle");
    git.bundle(repoDir, spec.baseSha(), bundle);

    var stubs = SpecRenderer.loadStubs(spec, draft);
    var model = suiteSealer.examine(spec, stubs, draft, bundle);
    if (model.problems() != null) {
      return new SealResult.Rejected(model.problems());
    }

    var issueBody = SpecRenderer.issueBody(spec, stubs);
    var commitment =
        Commitments.create(spec, draft, model.model(), Commitments.sha256Hex(issueBody));
    Commitments.write(commitment, draft.resolve("commitment.json"));

    github.ensureLabels(spec.repo());
    int n = github.createIssue(spec.repo(), spec.title(), issueBody, List.of("noir:ready"));
    var created = new SealProgress(n, false);
    writeProgress(draft, created);
    return publish(spec, draft, created);
  }

  /** Posts the commitment (if not yet posted) and moves the draft into the store. */
  private SealResult publish(Spec spec, Path draft, SealProgress progress) {
    var hash = Commitments.sha256(Commitments.read(draft.resolve("commitment.json")));
    int issue = progress.issueNumber();
    if (!progress.commented()) {
      github.comment(spec.repo(), issue, commitmentComment(hash));
      writeProgress(draft, new SealProgress(issue, true));
    }

    var item = WorkItem.sealed(spec.id(), spec.repo(), issue);
    AtomicFiles.write(draft.resolve("workitem.json"), workItemJson(item));
    store.appendEvent(
        draft,
        Event.of(
            clock,
            spec.id(),
            "sealed",
            0,
            Map.of("issue", issue, "commitmentSha256", hash, "baseSha", spec.baseSha())));
    moveIntoStore(draft, store.itemDir(spec.id()));
    return new SealResult.Sealed(spec.id(), spec.repo(), issue, hash);
  }

  public static Set<String> stubPackages(List<StubSource> stubs) {
    return SuiteSealer.stubPackages(stubs);
  }

  static String commitmentComment(String hash) {
    return "noir commitment `"
        + hash
        + "` (commitmentVersion "
        + Commitments.VERSION
        + "). The holdout suite for this work item is sealed; this hash lets anyone verify it"
        + " was not changed after the build.";
  }

  private static SealProgress readProgress(Path draft) {
    var file = draft.resolve("seal.json");
    if (!Files.isRegularFile(file)) {
      return SealProgress.NONE;
    }
    try {
      return Json.mapper().readValue(file.toFile(), SealProgress.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeProgress(Path draft, SealProgress progress) {
    try {
      AtomicFiles.write(draft.resolve("seal.json"), Json.mapper().writeValueAsBytes(progress));
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] workItemJson(WorkItem item) {
    try {
      return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(item);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void moveIntoStore(Path draft, Path itemDir) {
    try {
      Files.createDirectories(itemDir.getParent());
      Files.move(draft, itemDir, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot move " + draft + " to " + itemDir, e);
    }
  }
}
