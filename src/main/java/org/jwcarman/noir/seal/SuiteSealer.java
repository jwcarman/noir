package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jwcarman.noir.commit.SealedFileException;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.factory.Retry;
import org.jwcarman.noir.git.GitPort;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
import org.jwcarman.noir.measure.DiffLines;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecProblems;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;

/**
 * Runs the Examiner until its holdout suite passes the static and container seal checks, or the
 * attempts run out (spec §5.3). Writes {@code holdout/} and {@code reference/} into the item dir.
 *
 * <p>Infrastructure failures are retried here, per step: a failed container check re-runs only the
 * checks against the suite already written, and a failed Examiner container re-runs only that
 * attempt. So earlier, already-paid Examiner attempts are never repeated. When the retries run out
 * this throws {@link org.jwcarman.noir.factory.InfraFailure}, which the factory blocks on.
 */
public final class SuiteSealer {
  /** Prefix of the problem for a reference file outside the API stub paths. */
  static final String STRAY_REFERENCE = "reference file not at an API stub path: ";

  /** Where the repository inventory is listed, under the item dir. */
  static final String INVENTORY_DIR = "inventory";

  private static final String BASE_FILE = "base.java";
  private static final String REFERENCE_FILE = "reference.java";

  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private final Store store;
  private final Examiner examiner;
  private final SealCheckRunner checks;
  private final GitPort git;
  private final NoirConfig config;
  private final Clock clock;
  private final Retry retry;

  public SuiteSealer(
      Store store,
      Examiner examiner,
      SealCheckRunner checks,
      GitPort git,
      NoirConfig config,
      Clock clock,
      Retry retry) {
    this.store = store;
    this.examiner = examiner;
    this.checks = checks;
    this.git = git;
    this.config = config;
    this.clock = clock;
    this.retry = retry;
  }

  /**
   * Examiner outcome: the model that produced the sealed suite, or the last problems. {@code
   * conflictingTests} are the existing test classes (sorted) the reference broke, when those were
   * an attempt's only problems (sealing stops at that attempt); empty otherwise.
   */
  public record Examination(
      String model,
      List<String> problems,
      List<String> conflictingTests,
      SpecProblems specProblems) {
    public Examination {
      conflictingTests = List.copyOf(conflictingTests);
    }

    public Examination(String model, List<String> problems, List<String> conflictingTests) {
      this(model, problems, conflictingTests, null);
    }
  }

  /**
   * The repository inventory at the spec's base commit, listed into {@code itemDir/inventory/}:
   * {@code contract} is the issue view of the API stubs and {@code mainSources} the base commit's
   * {@code src/main/java} sources, keyed by path; {@code testsToUpdate} is the base content of the
   * spec's listed tests, keyed by path. A failed listing is retried as an infrastructure failure,
   * then thrown as {@link org.jwcarman.noir.factory.InfraFailure}.
   */
  public RepoInventory inventory(
      Spec spec,
      List<StubSource> contract,
      Map<String, String> mainSources,
      Map<String, String> testsToUpdate,
      Path itemDir,
      Path bundle) {
    var files =
        retry.call(() -> checks.inventory(bundle, spec.baseSha(), itemDir.resolve(INVENTORY_DIR)));
    return RepoInventory.of(files, mainSources, testsToUpdate, spec, contract);
  }

  /**
   * Examines the spec: {@code contract} is the issue view (fragments for modify targets) shown to
   * the Examiner, {@code stubs} the complete files, {@code baseSources} each modify target's
   * content at the base commit, keyed by path (empty for a spec that modifies nothing), and {@code
   * inventory} what the repository offers hidden tests.
   */
  public Examination examine(
      Spec spec,
      List<StubSource> contract,
      List<StubSource> stubs,
      Map<String, String> baseSources,
      Path itemDir,
      Path bundle,
      RepoInventory inventory) {
    var stubPackages = stubPackages(stubs);
    List<String> problems = List.of();
    for (int attempt = 1; attempt <= config.examiner().attempts(); attempt++) {
      var attemptDir = itemDir.resolve("examiner/attempt-" + attempt);
      ExaminerOutput out;
      try {
        var previous = problems;
        out =
            retry.call(() ->
                examiner.attempt(spec, contract, stubs, inventory, previous, attemptDir, itemDir));
        if (out.specProblems() instanceof SpecProblems.Valid v) {
          var data = new LinkedHashMap<String, Object>();
          data.put("attempt", attempt);
          data.put("ok", false);
          data.put("specProblems", v.report().problems().size());
          data.put("model", out.agent().model());
          data.put("costUsd", out.agent().costUsd());
          store.appendEvent(itemDir, Event.of(clock, spec.id(), "examiner_attempt", attempt, data));
          return new Examination(out.agent().model(), null, List.of(), v.report());
        }
        if (out.specProblems() instanceof SpecProblems.Invalid i) {
          store.appendEvent(
              itemDir,
              Event.of(
                  clock,
                  spec.id(),
                  "spec_problems_invalid",
                  attempt,
                  Map.of("reason", i.reason())));
        }
        problems =
            checkSuite(
                spec, baseSources, itemDir, bundle, stubPackages, inventory, out, attemptDir);
      } catch (SealedFileException e) {
        out = null;
        problems = List.of(e.getMessage());
      }
      var data = new LinkedHashMap<String, Object>();
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
      store.appendEvent(itemDir, Event.of(clock, spec.id(), "examiner_attempt", attempt, data));
      if (problems.isEmpty()) {
        return new Examination(out.agent().model(), null, List.of());
      }
      // The Examiner cannot change the repository's tests: a conflict with them is the spec's,
      // so another attempt cannot fix it.
      var conflicts = conflictingTests(problems);
      if (!conflicts.isEmpty()) {
        return new Examination(null, problems, conflicts);
      }
    }
    return new Examination(null, problems, List.of());
  }

  private List<String> checkSuite(
      Spec spec,
      Map<String, String> baseSources,
      Path itemDir,
      Path bundle,
      Set<String> stubPackages,
      RepoInventory inventory,
      ExaminerOutput out,
      Path attemptDir) {
    var problems = new ArrayList<>(strayReferenceFiles(spec, out.referenceDir()));
    var subpackageAllowed = new HashSet<>(stubPackages);
    subpackageAllowed.addAll(inventory.mainPackages());
    problems.addAll(
        HoldoutAnalyzer.analyze(
                spec, out.holdoutDir(), subpackageAllowed, inventory.dependencyPackages())
            .problems());
    if (!problems.isEmpty()) {
      return problems;
    }
    var changedLines =
        changedLinesByClass(baseSources, out.referenceDir(), attemptDir.resolve("changed-lines"));
    var inputs =
        SealInputs.of(spec, bundle, itemDir.resolve("stubs"), out.holdoutDir(), out.referenceDir());
    var artifacts = retry.call(() -> checks.run(inputs, attemptDir.resolve("checks")));
    return SealJudge.judge(artifacts, config.sealDiscrimination(), changedLines);
  }

  /**
   * A problem for each reference {@code .java} file whose path is neither an API stub nor one of
   * the spec's tests to update (the Examiner's updated versions of those).
   */
  static List<String> strayReferenceFiles(Spec spec, Path referenceDir) {
    var allowed = new HashSet<>(spec.apiStubs());
    allowed.addAll(spec.testsToUpdate());
    var problems = new ArrayList<String>();
    try (var paths = Files.walk(referenceDir)) {
      for (var p : paths.filter(Files::isRegularFile).sorted().toList()) {
        var rel = referenceDir.relativize(p).toString().replace('\\', '/');
        if (rel.endsWith(".java") && !allowed.contains(rel)) {
          problems.add(STRAY_REFERENCE + rel);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return problems;
  }

  /**
   * The lines the reference added or changed in each modify target, against its base content, keyed
   * by each top-level type the reference file declares. A target the reference does not contain is
   * left out (it is not mutated); one it left unchanged maps to no lines.
   */
  private Map<String, Set<Integer>> changedLinesByClass(
      Map<String, String> baseSources, Path referenceDir, Path workDir) {
    var byClass = new LinkedHashMap<String, Set<Integer>>();
    int n = 0;
    for (var entry : baseSources.entrySet()) {
      var reference = referenceDir.resolve(entry.getKey());
      if (!Files.isRegularFile(reference, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      var dir = workDir.resolve(String.valueOf(++n));
      try {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(BASE_FILE), entry.getValue());
        Files.copy(reference, dir.resolve(REFERENCE_FILE), StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      var diff = git.diffNoIndex(dir, BASE_FILE, REFERENCE_FILE);
      var lines = DiffLines.added(diff, Set.of(REFERENCE_FILE)).get(REFERENCE_FILE);
      for (var type : SealInputs.topLevelTypesOf(reference)) {
        byClass.put(type, lines);
      }
    }
    return byClass;
  }

  /** The classes named by check-4 problems, when every problem is one; empty otherwise. */
  static List<String> conflictingTests(List<String> problems) {
    var classes = new TreeSet<String>();
    for (var p : problems) {
      var cls = SealProblems.existingTestClass(p);
      if (cls.isEmpty()) {
        return List.of();
      }
      classes.add(cls.get());
    }
    return List.copyOf(classes);
  }

  public static Set<String> stubPackages(List<StubSource> stubs) {
    var packages = new LinkedHashSet<String>();
    for (var stub : stubs) {
      var m = PACKAGE.matcher(stub.content());
      if (m.find()) {
        packages.add(m.group(1));
      }
    }
    return packages;
  }
}
