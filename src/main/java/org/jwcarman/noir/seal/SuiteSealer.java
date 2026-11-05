package org.jwcarman.noir.seal;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jwcarman.noir.commit.SealedFileException;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;

/**
 * Runs the Examiner until its holdout suite passes the static and container seal checks, or the
 * attempts run out (spec §5.3). Writes {@code holdout/} and {@code reference/} into the item dir.
 */
public final class SuiteSealer {
  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private final Store store;
  private final Examiner examiner;
  private final SealCheckRunner checks;
  private final NoirConfig config;
  private final Clock clock;

  public SuiteSealer(
      Store store, Examiner examiner, SealCheckRunner checks, NoirConfig config, Clock clock) {
    this.store = store;
    this.examiner = examiner;
    this.checks = checks;
    this.config = config;
    this.clock = clock;
  }

  /** Examiner outcome: the model that produced the sealed suite, or the last problems. */
  public record Examination(String model, List<String> problems) {}

  public Examination examine(Spec spec, List<StubSource> stubs, Path itemDir, Path bundle) {
    var stubPackages = stubPackages(stubs);
    List<String> problems = List.of();
    for (int attempt = 1; attempt <= config.examiner().attempts(); attempt++) {
      var attemptDir = itemDir.resolve("examiner/attempt-" + attempt);
      ExaminerOutput out;
      try {
        out = examiner.attempt(spec, stubs, problems, attemptDir, itemDir);
        problems = checkSuite(spec, itemDir, bundle, stubPackages, out, attemptDir);
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
        return new Examination(out.agent().model(), null);
      }
    }
    return new Examination(null, problems);
  }

  private List<String> checkSuite(
      Spec spec,
      Path itemDir,
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
        SealInputs.of(spec, bundle, itemDir.resolve("stubs"), out.holdoutDir(), out.referenceDir());
    var artifacts = checks.run(inputs, attemptDir.resolve("checks"));
    return SealJudge.judge(artifacts, config.sealDiscrimination());
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
