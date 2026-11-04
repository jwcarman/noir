package org.jwcarman.noir.seal;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jwcarman.noir.commit.Commitments;
import org.jwcarman.noir.commit.SealedFileException;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.git.GitPort;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
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
  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private final Store store;
  private final GitPort git;
  private final GitHubPort github;
  private final Examiner examiner;
  private final SealCheckRunner checks;
  private final NoirConfig config;
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
    this.examiner = examiner;
    this.checks = checks;
    this.config = config;
    this.clock = clock;
  }

  public SealResult seal(String draftId, Path repoDir) {
    var draft = store.draftDir(draftId);
    var specFile = draft.resolve("spec.json");
    if (!Files.isRegularFile(specFile)) {
      throw new SealException("No draft " + draftId);
    }
    var spec = SpecJson.read(specFile);
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
    var model = examine(spec, stubs, draft, bundle);
    if (model.problems() != null) {
      return new SealResult.Rejected(model.problems());
    }

    var commitment = Commitments.create(spec, draft, model.name());
    Commitments.write(commitment, draft.resolve("commitment.json"));

    github.ensureLabels(spec.repo());
    int n =
        github.createIssue(
            spec.repo(), spec.title(), SpecRenderer.issueBody(spec, stubs), List.of("noir:ready"));
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

  /** Examiner outcome: the model that produced the sealed suite, or the last problems. */
  private record Examination(String name, List<String> problems) {}

  private Examination examine(Spec spec, List<StubSource> stubs, Path draft, Path bundle) {
    var stubPackages = stubPackages(stubs);
    List<String> problems = List.of();
    for (int attempt = 1; attempt <= config.examiner().attempts(); attempt++) {
      var attemptDir = draft.resolve("examiner/attempt-" + attempt);
      ExaminerOutput out;
      try {
        out = examiner.attempt(spec, stubs, problems, attemptDir, draft);
        problems = checkSuite(spec, draft, bundle, stubPackages, out, attemptDir);
      } catch (SealedFileException e) {
        out = null;
        problems = List.of(e.getMessage());
      }
      var data = new java.util.LinkedHashMap<String, Object>();
      data.put("attempt", attempt);
      data.put("ok", problems.isEmpty());
      data.put("problems", problems.size());
      if (out != null) {
        data.put("model", out.agent().model());
        data.put("imageId", out.agent().imageId());
        data.put("costUsd", out.agent().costUsd());
        data.put("turns", out.agent().turns());
        data.put("agentError", out.agent().error());
      }
      store.appendEvent(draft, Event.of(clock, spec.id(), "examiner_attempt", attempt, data));
      if (problems.isEmpty()) {
        return new Examination(out.agent().model(), null);
      }
    }
    return new Examination(null, problems);
  }

  private List<String> checkSuite(
      Spec spec,
      Path draft,
      Path bundle,
      Set<String> stubPackages,
      ExaminerOutput out,
      Path attemptDir) {
    var problems =
        new ArrayList<>(HoldoutAnalyzer.analyze(spec, out.holdoutDir(), stubPackages).problems());
    if (!problems.isEmpty()) {
      return problems;
    }
    var inputs =
        SealInputs.of(spec, bundle, draft.resolve("stubs"), out.holdoutDir(), out.referenceDir());
    var artifacts = checks.run(inputs, attemptDir.resolve("checks"));
    return SealJudge.judge(artifacts, config.sealDiscrimination());
  }

  static Set<String> stubPackages(List<StubSource> stubs) {
    var packages = new LinkedHashSet<String>();
    for (var stub : stubs) {
      var m = PACKAGE.matcher(stub.content());
      if (m.find()) {
        packages.add(m.group(1));
      }
    }
    return packages;
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
