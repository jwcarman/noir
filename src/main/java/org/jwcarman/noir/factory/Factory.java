package org.jwcarman.noir.factory;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.noir.agent.AgentResult;
import org.jwcarman.noir.build.BuildRunner;
import org.jwcarman.noir.build.CommitMessage;
import org.jwcarman.noir.commit.Commitments;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.deliver.PatchTooLargeException;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.feedback.FeedbackInput;
import org.jwcarman.noir.feedback.FeedbackRenderer;
import org.jwcarman.noir.feedback.RequirementResult;
import org.jwcarman.noir.grade.GradeJudge;
import org.jwcarman.noir.grade.GradeOutcome;
import org.jwcarman.noir.grade.GradeRunner;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
import org.jwcarman.noir.holdout.TagIndex;
import org.jwcarman.noir.lifecycle.Category;
import org.jwcarman.noir.lifecycle.Lifecycle;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.lifecycle.Transition;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.measure.DiffLines;
import org.jwcarman.noir.measure.MeasureArtifacts;
import org.jwcarman.noir.measure.MeasureJudge;
import org.jwcarman.noir.measure.MeasureOutcome;
import org.jwcarman.noir.measure.MeasureRunner;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.seal.ApprovalSealing;
import org.jwcarman.noir.seal.SealOutcome;
import org.jwcarman.noir.seal.SuiteSealer;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.QualityBar;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.store.AtomicFiles;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * Drives one work item through the {@link Lifecycle}: seal an approved issue, then build, measure,
 * grade, rework, and deliver (spec §4.5, §6–§8). Each {@link #advance} runs the current phase,
 * applies exactly one transition, performs its side effects, then persists {@code workitem.json}
 * and a {@code transition} event.
 *
 * <p>Infrastructure failures that outlast {@link Retry} block the item ({@code INFRASTRUCTURE}).
 * Feedback for the next attempt is written to {@code attempts/<n>/FEEDBACK.md}, outside the
 * directory mounted into the builder, and only ever from {@link FeedbackRenderer}: holdout test
 * names, sources, and compiler output over the holdout suite never reach it.
 *
 * <p>{@code SEALING} runs {@link ApprovalSealing} before any {@code spec.json} exists. A rejection
 * posts the sealer's comment, removes {@code noir:ready} and returns the item to {@code DRAFT}. Any
 * other failure of the sealer blocks the item ({@code INFRASTRUCTURE}); its message may name hidden
 * tests, so it is kept in the local event log only, and the issue gets one fixed {@link
 * BlockedNotice} per block.
 */
public final class Factory implements ItemDriver {
  private final Store store;
  private final Delivery delivery;
  private final ApprovalSealing approvalSealer;
  private final BuildRunner builder;
  private final MeasureRunner measurer;
  private final GradeRunner grader;
  private final Bundles bundles;
  private final NoirConfig config;
  private final String graderImageId;
  private final Clock clock;
  private final Retry retry;
  private final Set<String> secrets;

  /**
   * @param graderImageId the id of the grader image (from {@code Images.ensureGrader}), published
   *     as provenance in pull requests
   * @param secrets the credential values given to the builder (from {@code AgentAuth}); an attempt
   *     whose patch contains any of them is blocked ({@code SECRET_FOUND}) and never pushed
   */
  public Factory(
      Store store,
      Delivery delivery,
      ApprovalSealing approvalSealer,
      BuildRunner builder,
      MeasureRunner measurer,
      GradeRunner grader,
      Bundles bundles,
      NoirConfig config,
      String graderImageId,
      Clock clock,
      Retry retry,
      Set<String> secrets) {
    this.store = store;
    this.delivery = delivery;
    this.approvalSealer = approvalSealer;
    this.builder = builder;
    this.measurer = measurer;
    this.grader = grader;
    this.bundles = bundles;
    this.config = config;
    this.graderImageId = graderImageId;
    this.clock = clock;
    this.retry = retry;
    this.secrets =
        secrets.stream()
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * A phase's verdict, plus the feedback the next attempt gets if the lifecycle reworks, and the
   * issue comment a seal rejection posts.
   */
  private record Step(Trigger trigger, FeedbackInput feedback, String comment) {
    Step(Trigger trigger, FeedbackInput feedback) {
      this(trigger, feedback, null);
    }

    static Step of(Trigger trigger) {
      return new Step(trigger, null);
    }
  }

  /** What every phase of one {@link #advance} shares; {@code spec} is null while sealing. */
  private record Ctx(WorkItem item, Spec spec, Path itemDir, String branch) {
    AttemptPaths attempt() {
      return new AttemptPaths(itemDir, item.attempt());
    }
  }

  /**
   * Advances until the item is terminal, {@code BLOCKED} or {@code DRAFT}. {@code REWORK} picks up
   * the next attempt immediately.
   */
  public void drive(String itemId) {
    var item = store.readWorkItem(itemId);
    while (runnable(item.state())) {
      item = advance(itemId);
    }
  }

  /** Whether {@link #advance} has a phase to run: not terminal, blocked or waiting for a human. */
  static boolean runnable(State state) {
    return !state.terminal() && state != State.BLOCKED && state != State.DRAFT;
  }

  /** Performs exactly one transition and returns the new work item. */
  @Override
  public WorkItem advance(String itemId) {
    var item = store.readWorkItem(itemId);
    var from = item.state();
    if (!runnable(from)) {
      throw new IllegalStateException("Work item " + itemId + " is " + from + ": nothing to run");
    }
    var itemDir = store.itemDir(itemId);
    Ctx ctx;
    if (from == State.SEALING) {
      // spec.json is written by the sealer: it does not exist yet.
      ctx = new Ctx(item, null, itemDir, null);
    } else {
      var spec = SpecJson.read(itemDir.resolve("spec.json"));
      ctx = new Ctx(item, spec, itemDir, BranchNames.of(item.issueNumber(), spec.title()));
    }

    Step step;
    try {
      step = from == State.SEALING ? seal(ctx) : runPhase(ctx);
    } catch (InfraFailure e) {
      infraEvent(ctx, from.name(), e);
      step = Step.of(new Trigger.InfraBlocked());
    }
    var transition = Lifecycle.apply(item, step.trigger(), config.reworkRetries());
    try {
      perform(ctx, transition, step);
    } catch (InfraFailure e) {
      // Delivery or labelling kept failing. From an active phase the item blocks (resuming re-runs
      // the phase); otherwise nothing has changed and the failure propagates to the caller.
      if (!from.active() || step.trigger() instanceof Trigger.InfraBlocked) {
        throw e;
      }
      infraEvent(ctx, "delivery", e);
      step = Step.of(new Trigger.InfraBlocked());
      transition = Lifecycle.apply(item, step.trigger(), config.reworkRetries());
      perform(ctx, transition, step);
    } catch (OutboundSecret e) {
      // A credential in text about to be posted to GitHub: post nothing and block. Outbound text
      // is only produced from active phases, where SecretFound is always a valid trigger.
      event(itemId, "secret_found", item.attempt(), Map.of("where", e.where));
      step = Step.of(new Trigger.SecretFound());
      transition = Lifecycle.apply(item, step.trigger(), config.reworkRetries());
      perform(ctx, transition, step);
    }

    var next = transition.next();
    store.writeWorkItem(next);
    var data = new LinkedHashMap<String, Object>();
    data.put("from", from.name());
    data.put("to", next.state().name());
    data.put("trigger", step.trigger().getClass().getSimpleName());
    if (step.trigger() instanceof Trigger.SealRejected r) {
      data.put("reason", r.reason().eventValue());
    }
    transition
        .failure()
        .ifPresent(c -> {
          data.put("failure", c.name());
          data.put("category", c.name());
        });
    event(itemId, "transition", next.attempt(), data);
    if (from == State.SEALING
        && next.state() == State.BLOCKED
        && step.trigger() instanceof Trigger.InfraBlocked) {
      BlockedNotice.post(
          store,
          clock,
          next,
          text -> {
            guard("comment", text);
            retry.run(() -> delivery.comment(next, text));
          });
    }
    return next;
  }

  // ---- phases --------------------------------------------------------------------------------

  /**
   * Seals the approved issue. Infrastructure exceptions are retried; anything else the sealer
   * throws (an examiner that kept failing, an unexpected error) is an infrastructure failure too.
   */
  private Step seal(Ctx ctx) {
    SealOutcome outcome;
    try {
      outcome = retry.call(() -> approvalSealer.seal(ctx.item()));
    } catch (InfraFailure e) {
      throw e;
    } catch (RuntimeException e) {
      throw new InfraFailure(e);
    }
    return switch (outcome) {
      case SealOutcome.Passed p -> Step.of(new Trigger.SealPassed());
      case SealOutcome.Rejected r ->
          new Step(new Trigger.SealRejected(r.reason()), null, r.comment());
    };
  }

  private Step runPhase(Ctx ctx) {
    return switch (ctx.item().state()) {
      case SEALED, REWORK -> Step.of(new Trigger.Pickup());
      case BUILDING -> build(ctx);
      case MEASURING -> measure(ctx);
      case GRADING -> grade(ctx);
      default -> throw new IllegalStateException("No phase for " + ctx.item().state());
    };
  }

  private Step build(Ctx ctx) {
    int n = ctx.item().attempt();
    // The builder reads the issue's view (fragments for modify targets) in SPEC.md.
    var stubs = SpecRenderer.loadContract(ctx.spec(), ctx.itemDir());
    var input = inputBundle(ctx.itemDir(), n);
    var feedback = previousFeedback(ctx.itemDir(), n);
    var message = CommitMessage.of(ctx.item().issueNumber(), ctx.spec().title(), n);
    var outcome =
        retry.call(() -> {
          var o =
              builder.run(
                  ctx.spec(), stubs, input, ctx.branch(), message, feedback, ctx.attempt().build());
          if (consumedNothing(o.agent()) && !o.producedCommits()) {
            throw new DockerException(
                "builder agent failed before doing any work (exit "
                    + o.agent().exitCode()
                    + "): "
                    + CommandResult.tail(
                        Objects.requireNonNullElse(o.agent().resultText(), "").strip(), 5));
          }
          return o;
        });
    var agent = outcome.agent();
    var cost = agent.costUsd() == null ? BigDecimal.ZERO : agent.costUsd();
    var budget = config.budgets().costUsd();
    var data = new LinkedHashMap<String, Object>();
    data.put("producedCommits", outcome.producedCommits());
    data.put("model", orUnknown(agent.model()));
    data.put("costUsd", cost);
    data.put("turns", agent.turns());
    data.put("overBudget", budget != null && cost.compareTo(budget) > 0);
    data.put("imageId", orUnknown(agent.imageId()));
    data.put("agentError", agent.error());
    data.put("timedOut", agent.timedOut());
    event(ctx.item().id(), "build_attempt", n, data);
    boolean produced = outcome.producedCommits();
    return new Step(
        new Trigger.AgentExited(produced), produced ? null : new FeedbackInput.NoOutput());
  }

  /**
   * An agent that errored without timing out and without consuming anything (no cost, at most one
   * turn; also what a run with no result JSON and a non-zero exit parses to) never reached the
   * model: a bad or expired key, an API outage, a credit limit. That is infrastructure (spec §9.4),
   * retried and then blocked, never the builder's NO_OUTPUT.
   */
  static boolean consumedNothing(AgentResult agent) {
    var cost = agent.costUsd() == null ? BigDecimal.ZERO : agent.costUsd();
    return agent.error() && !agent.timedOut() && cost.signum() == 0 && agent.turns() <= 1;
  }

  /**
   * Attempt 1 starts from the sealed base bundle; later attempts from the newest earlier attempt
   * that left a bundle (an attempt whose post step failed leaves none).
   */
  private static Path inputBundle(Path itemDir, int attempt) {
    for (int k = attempt - 1; k >= 1; k--) {
      var bundle = new AttemptPaths(itemDir, k).bundle();
      if (Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
        return bundle;
      }
    }
    return itemDir.resolve("base.bundle");
  }

  private static Optional<String> previousFeedback(Path itemDir, int attempt) {
    if (attempt <= 1) {
      return Optional.empty();
    }
    var file = new AttemptPaths(itemDir, attempt - 1).feedback();
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(file, UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final String MAIN_JAVA = "src/main/java/";

  /**
   * The lines the attempt added or changed in each modify target, keyed by the class FQN its path
   * names ({@code src/main/java/a/b/C.java} becomes {@code a.b.C}). Targets outside {@code
   * src/main/java} or not ending in {@code .java} are left out.
   */
  static Map<String, Set<Integer>> changedLinesByClass(String patch, List<String> modifies) {
    var byClass = new LinkedHashMap<String, Set<Integer>>();
    DiffLines.added(patch, Set.copyOf(modifies))
        .forEach((path, lines) -> {
          if (path.startsWith(MAIN_JAVA) && path.endsWith(".java")) {
            var fqn =
                path.substring(MAIN_JAVA.length(), path.length() - ".java".length())
                    .replace('/', '.');
            byClass.put(fqn, lines);
          }
        });
    return byClass;
  }

  private Step measure(Ctx ctx) {
    var spec = ctx.spec();
    var paths = ctx.attempt();
    var bundle = paths.bundle();
    // Before anything runs or leaves the machine: a credential in the patch blocks the item.
    String patch;
    try {
      patch = retry.call(() -> bundles.patchSince(bundle, spec.baseSha(), ctx.branch()));
    } catch (PatchTooLargeException e) {
      // Too large to scan in full; retrying will not shrink it. Block for a human.
      throw new InfraFailure(e);
    }
    if (patch.isBlank()) {
      // The attempt produced commits, so its patch cannot be empty: lost output is never a pass.
      throw new InfraFailure(new IllegalStateException("empty patch for an attempt with commits"));
    }
    if (secrets.stream().anyMatch(patch::contains)) {
      event(ctx.item().id(), "secret_found", ctx.item().attempt(), Map.of());
      return Step.of(new Trigger.SecretFound());
    }
    var changes = retry.call(() -> bundles.fileChanges(bundle, spec.baseSha(), ctx.branch()));
    var violation = LockCheck.violation(spec, changes);
    if (violation.isPresent()) {
      event(
          ctx.item().id(),
          "measurement",
          ctx.item().attempt(),
          Map.of("passed", false, "category", Category.CONTRACT_VIOLATION.name()));
      return new Step(
          new Trigger.MeasurementFailed(Category.CONTRACT_VIOLATION),
          new FeedbackInput.ContractViolation(List.of(violation.get())));
    }
    var artifacts =
        retry.call(() ->
            measurer.run(
                spec, bundle, ctx.branch(), ctx.itemDir().resolve("stubs"), paths.measure()));
    MeasureOutcome outcome;
    try {
      outcome =
          MeasureJudge.judge(
              artifacts,
              bar(spec),
              artifacts.changedClasses(),
              changedLinesByClass(patch, spec.modifies()));
    } catch (IllegalStateException e) {
      throw new InfraFailure(e); // an incomplete measurement is noir's fault, not the builder's
    }
    var data = new LinkedHashMap<String, Object>();
    data.put("passed", outcome instanceof MeasureOutcome.Passed);
    if (outcome instanceof MeasureOutcome.Shortfall s) {
      data.put("category", s.input().category().name());
    }
    // Metrics are meaningful only when coverage and mutation were actually measured.
    boolean measured =
        outcome instanceof MeasureOutcome.Passed
            || (outcome instanceof MeasureOutcome.Shortfall s
                && s.input() instanceof FeedbackInput.QualityShortfall);
    if (measured) {
      var m = outcome.m();
      data.put("lineCoverage", m.lineCoverage());
      data.put("mutationScore", m.mutationScore());
      data.put("maxCrap", m.maxCrap());
      data.put("changedClasses", m.changedClasses());
    }
    event(ctx.item().id(), "measurement", ctx.item().attempt(), data);
    return switch (outcome) {
      case MeasureOutcome.Passed p -> Step.of(new Trigger.MeasurementPassed());
      case MeasureOutcome.Shortfall s ->
          new Step(new Trigger.MeasurementFailed(s.input().category()), s.input());
    };
  }

  private QualityBar bar(Spec spec) {
    return spec.qualityBar() != null ? spec.qualityBar() : config.qualityBar();
  }

  private Step grade(Ctx ctx) {
    var spec = ctx.spec();
    var paths = ctx.attempt();
    var measured = new MeasureArtifacts(paths.measure());
    var holdout = ctx.itemDir().resolve("holdout");
    var stubPackages = SuiteSealer.stubPackages(SpecRenderer.loadStubs(spec, ctx.itemDir()));
    var tags = TagIndex.of(HoldoutAnalyzer.analyze(spec, holdout, stubPackages));
    var requirementIds = spec.requirements().stream().map(Requirement::id).toList();
    var artifacts =
        retry.call(() ->
            grader.run(
                measured.exportClasses(),
                measured.exportDeps(),
                holdout,
                ItemId.holdoutPackage(spec.id()),
                paths.grade()));
    GradeOutcome outcome;
    try {
      outcome = GradeJudge.judge(artifacts, tags, requirementIds);
    } catch (IllegalStateException e) {
      throw new InfraFailure(e); // an incomplete grade run is noir's fault, not the builder's
    }
    var data = new LinkedHashMap<String, Object>();
    var step =
        switch (outcome) {
          case GradeOutcome.Passed p -> {
            data.put("result", "passed");
            data.put("results", resultMaps(p.results()));
            yield Step.of(new Trigger.GradePassed());
          }
          case GradeOutcome.Failed f -> {
            data.put("result", "failed");
            data.put("results", resultMaps(f.results()));
            yield new Step(
                new Trigger.GradeFailed(), new FeedbackInput.BehaviorFailure(f.results()));
          }
          case GradeOutcome.HoldoutDefect h -> {
            // The compiler output covers holdout sources: the local event log only, never feedback.
            data.put("result", "holdout_defect");
            data.put("detail", h.detail());
            yield Step.of(new Trigger.HoldoutDefect());
          }
        };
    event(ctx.item().id(), "grade", ctx.item().attempt(), data);
    return step;
  }

  // ---- side effects --------------------------------------------------------------------------

  private void perform(Ctx ctx, Transition transition, Step step) {
    var next = transition.next();
    for (var effect : transition.effects()) {
      switch (effect) {
        case START_ATTEMPT ->
            createDirectories(new AttemptPaths(ctx.itemDir(), next.attempt()).dir());
        case APPLY_LABEL -> retry.run(() -> delivery.label(next, next.state()));
        case WRITE_FEEDBACK -> writeFeedback(ctx, step);
        case OPEN_PR -> deliver(ctx, next, ctx.attempt(), false);
        case OPEN_DRAFT_PR_OR_COMMENT -> draftOrComment(ctx, next);
        // The runners already extracted and exported; containers are --rm and phase-scoped.
        case REJECT_APPROVAL -> rejectApproval(next, step);
        case EXTRACT_BUNDLE, EXPORT_ARTIFACTS, STOP_CONTAINERS -> {}
      }
    }
  }

  private void rejectApproval(WorkItem next, Step step) {
    if (step.comment() == null) {
      throw new IllegalStateException("No rejection comment for " + step.trigger());
    }
    guard("comment", step.comment());
    // Separate retries: a failing label removal must not post the comment again.
    retry.run(() -> delivery.comment(next, step.comment()));
    retry.run(() -> delivery.removeReady(next));
  }

  private static void writeFeedback(Ctx ctx, Step step) {
    if (step.feedback() == null) {
      throw new IllegalStateException("No feedback for " + step.trigger());
    }
    var text = FeedbackRenderer.render(ctx.item().attempt(), step.feedback());
    var file = ctx.attempt().feedback();
    createDirectories(file.getParent());
    AtomicFiles.write(file, text.getBytes(UTF_8));
  }

  private void deliver(Ctx ctx, WorkItem next, AttemptPaths attempt, boolean draft) {
    var body = body(ctx, next, attempt.attempt(), draft);
    guard("pull_request", ctx.spec().title(), body);
    int pr = retry.call(() -> delivery.openPullRequest(next, ctx.spec(), attempt, body, draft));
    var data = new LinkedHashMap<String, Object>();
    data.put("pullRequest", pr);
    data.put("draft", draft);
    data.put("deliveredAttempt", attempt.attempt());
    event(next.id(), "delivered", next.attempt(), data);
  }

  /**
   * A draft PR from the newest attempt that produced commits and whose bundle has commits beyond
   * the base; with none, an issue comment carrying the per-requirement results.
   */
  private void draftOrComment(Ctx ctx, WorkItem next) {
    var events = store.readEvents(next.id());
    var producing =
        events.stream()
            .filter(e -> e.type().equals("build_attempt"))
            .filter(e -> Boolean.TRUE.equals(e.data().get("producedCommits")))
            .map(Event::attempt)
            .distinct()
            .sorted(Comparator.reverseOrder())
            .toList();
    for (int k : producing) {
      var attempt = new AttemptPaths(ctx.itemDir(), k);
      var bundle = attempt.bundle();
      if (Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)
          && !retry
              .call(() -> bundles.commitsSince(bundle, ctx.spec().baseSha(), ctx.branch()))
              .isEmpty()) {
        deliver(ctx, next, attempt, true);
        return;
      }
    }
    var comment = PullRequestBody.failureComment(next.attempt(), latestResults(events, null));
    guard("comment", comment);
    retry.run(() -> delivery.comment(next, comment));
    event(next.id(), "delivered", next.attempt(), Map.of("comment", true));
  }

  /** The sum of {@code costUsd} over every event of {@code type}; null when none recorded one. */
  private static BigDecimal totalCost(List<Event> events, String type) {
    BigDecimal total = null;
    for (var e : events) {
      if (e.type().equals(type) && e.data().get("costUsd") instanceof Number n) {
        var c = new BigDecimal(n.toString());
        total = total == null ? c : total.add(c);
      }
    }
    return total;
  }

  private String body(Ctx ctx, WorkItem next, int attempt, boolean draft) {
    var events = store.readEvents(next.id());
    var commitment = Commitments.read(ctx.itemDir().resolve("commitment.json"));
    var built = latest(events, "build_attempt", attempt);
    var provenance =
        new PullRequestBody.Provenance(
            ctx.spec().baseSha(),
            next.attempt(),
            Commitments.sha256(commitment),
            built.map(e -> str(e.data().get("model"))).orElse(null),
            commitment.examinerModel(),
            built.map(e -> str(e.data().get("imageId"))).orElse(null),
            graderImageId,
            totalCost(events, "build_attempt"),
            totalCost(events, "examiner_attempt"));
    var metrics =
        latest(events, "measurement", attempt)
            .filter(e -> e.data().containsKey("lineCoverage"))
            .map(e ->
                new PullRequestBody.Metrics(
                    num(e.data().get("lineCoverage")),
                    num(e.data().get("mutationScore")),
                    num(e.data().get("maxCrap"))));
    return PullRequestBody.render(
        next.issueNumber(), metrics, latestResults(events, attempt), provenance, draft);
  }

  /** Text bound for GitHub carried a credential; nothing has been posted. */
  private static final class OutboundSecret extends RuntimeException {
    final String where;

    OutboundSecret(String where) {
      super("secret in outbound " + where);
      this.where = where;
    }
  }

  /** Refuses outbound GitHub text (PR title and body, comments) that contains any secret. */
  private void guard(String where, String... texts) {
    for (var text : texts) {
      if (text != null && secrets.stream().anyMatch(text::contains)) {
        throw new OutboundSecret(where);
      }
    }
  }

  // ---- events --------------------------------------------------------------------------------

  private void event(String itemId, String type, int attempt, Map<String, Object> data) {
    store.appendEvent(Event.of(clock, itemId, type, attempt, data));
  }

  private void infraEvent(Ctx ctx, String phase, InfraFailure e) {
    var cause = e.getCause() == null ? e : e.getCause();
    var data = new LinkedHashMap<String, Object>();
    data.put("phase", phase);
    data.put("exception", cause.getClass().getSimpleName());
    // Local only, and redacted: a sealer's message may quote examiner output.
    data.put("error", Secrets.redact(Objects.requireNonNullElse(cause.getMessage(), ""), secrets));
    event(ctx.item().id(), "infra_failure", ctx.item().attempt(), data);
  }

  private static Optional<Event> latest(List<Event> events, String type, int attempt) {
    Event found = null;
    for (var e : events) {
      if (e.type().equals(type) && e.attempt() == attempt) {
        found = e;
      }
    }
    return Optional.ofNullable(found);
  }

  /** Per-requirement results of the newest grade (of {@code attempt}, or of any when null). */
  private static List<RequirementResult> latestResults(List<Event> events, Integer attempt) {
    List<RequirementResult> results = List.of();
    for (var e : events) {
      if (e.type().equals("grade")
          && (attempt == null || e.attempt() == attempt)
          && e.data().get("results") instanceof List<?> list) {
        var parsed = new ArrayList<RequirementResult>();
        for (var o : list) {
          if (o instanceof Map<?, ?> m) {
            parsed.add(
                new RequirementResult(
                    str(m.get("requirement")),
                    (int) num(m.get("passed")),
                    (int) num(m.get("total"))));
          }
        }
        results = parsed;
      }
    }
    return results;
  }

  private static List<Map<String, Object>> resultMaps(List<RequirementResult> results) {
    return results.stream()
        .map(r ->
            Map.<String, Object>of(
                "requirement", r.requirementId(), "passed", r.passed(), "total", r.total()))
        .toList();
  }

  private static String orUnknown(String s) {
    return s == null ? "unknown" : s;
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private static double num(Object o) {
    return o instanceof Number n ? n.doubleValue() : 0;
  }

  private static void createDirectories(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
