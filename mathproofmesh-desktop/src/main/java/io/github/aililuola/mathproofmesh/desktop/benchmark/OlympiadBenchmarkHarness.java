package io.github.aililuola.mathproofmesh.desktop.benchmark;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Sequential, cold-start benchmark orchestrator with hard-gate stop behavior. */
public final class OlympiadBenchmarkHarness {
  private static final DateTimeFormatter RUN_STAMP =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private final Path benchmarkRoot;
  private final Path outputRoot;
  private final OlympiadProblemCatalog problems;
  private final OlympiadSecretRedactor redactor;
  private final Clock clock;
  private final OlympiadGitExecutionState gitExecutionState;

  public OlympiadBenchmarkHarness(
      Path benchmarkRoot, Path outputRoot, OlympiadSecretRedactor redactor) {
    this(benchmarkRoot, outputRoot, redactor, Clock.systemUTC());
  }

  OlympiadBenchmarkHarness(
      Path benchmarkRoot, Path outputRoot, OlympiadSecretRedactor redactor, Clock clock) {
    this(
        benchmarkRoot,
        outputRoot,
        redactor,
        clock,
        OlympiadGitExecutionState.capture(projectRoot(benchmarkRoot)));
  }

  OlympiadBenchmarkHarness(
      Path benchmarkRoot,
      Path outputRoot,
      OlympiadSecretRedactor redactor,
      Clock clock,
      OlympiadGitExecutionState gitExecutionState) {
    this.benchmarkRoot = normalize(benchmarkRoot, "benchmarkRoot");
    this.outputRoot = normalize(outputRoot, "outputRoot");
    this.problems = new OlympiadProblemCatalog(this.benchmarkRoot);
    this.redactor = Objects.requireNonNull(redactor, "redactor");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.gitExecutionState = Objects.requireNonNull(gitExecutionState, "gitExecutionState");
  }

  public HarnessResult execute(List<OlympiadBenchmarkPlan.RunSpec> schedule, RunExecutor executor) {
    Objects.requireNonNull(schedule, "schedule");
    Objects.requireNonNull(executor, "executor");
    if (schedule.isEmpty()) {
      throw new IllegalArgumentException("benchmark schedule must not be empty");
    }
    try {
      Files.createDirectories(outputRoot.resolve("runs"));
      Files.createDirectories(outputRoot.resolve("work"));
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark output directories could not be created", exception);
    }
    List<CompletedRun> completed = new ArrayList<>();
    int hardViolations = 0;
    for (OlympiadBenchmarkPlan.RunSpec spec : schedule) {
      OlympiadProblemCatalog.ProblemPrompt problem = problems.load(spec.problemId());
      String runId = runId(spec);
      Path workDirectory = outputRoot.resolve("work").resolve(runId).normalize();
      Path bundleDirectory =
          outputRoot
              .resolve("runs")
              .resolve(spec.problemId())
              .resolve(spec.trialId())
              .resolve(runId)
              .normalize();
      requireFreshNamespace(workDirectory, bundleDirectory);
      RunRequest request =
          new RunRequest(spec, runId, problem, workDirectory, Instant.now(clock));
      RunOutcome outcome = Objects.requireNonNull(executor.execute(request), "run outcome");
      validateOutcomeBinding(request, outcome);
      OlympiadEvidenceBundleWriter.write(
          benchmarkRoot, bundleDirectory, request, outcome, gitExecutionState, redactor);
      OlympiadEvidenceBundleValidator.Validation validation =
          OlympiadEvidenceBundleValidator.validate(
              bundleDirectory, request, gitExecutionState, redactor);
      if (!validation.passed()) {
        hardViolations = Math.addExact(hardViolations, validation.failures());
      }
      hardViolations = Math.addExact(hardViolations, outcome.hardViolationCount());
      completed.add(
          new CompletedRun(
              spec,
              runId,
              bundleDirectory,
              outcome.finalStatus(),
              outcome.usage(),
              validation));
      if (hardViolations > 0) {
        break;
      }
    }
    OlympiadAggregateWriter.write(outputRoot.resolve("aggregate"), schedule, completed);
    return new HarnessResult(schedule.size(), List.copyOf(completed), hardViolations);
  }

  private String runId(OlympiadBenchmarkPlan.RunSpec spec) {
    return spec.problemId().toLowerCase(java.util.Locale.ROOT)
        + "-"
        + spec.trialId().toLowerCase(java.util.Locale.ROOT)
        + "-"
        + RUN_STAMP.format(Instant.now(clock))
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  private static void requireFreshNamespace(Path workDirectory, Path bundleDirectory) {
    if (Files.exists(workDirectory) || Files.exists(bundleDirectory)) {
      throw new IllegalStateException("benchmark run namespace is not a cold start");
    }
    try {
      Files.createDirectories(workDirectory);
      Files.createDirectories(bundleDirectory);
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark run namespace could not be created", exception);
    }
  }

  private static void validateOutcomeBinding(RunRequest request, RunOutcome outcome) {
    if (!request.runId().equals(outcome.runId())) {
      throw new IllegalStateException("benchmark outcome is bound to a different run");
    }
    if (!request.problem().sha256().equals(outcome.problemPromptSha256())) {
      throw new IllegalStateException("benchmark outcome is bound to a different problem prompt");
    }
    if (!outcome.rootGoalHashInitial().equals(outcome.rootGoalHashFinal())) {
      throw new IllegalStateException("benchmark root goal hash drifted");
    }
  }

  private static Path normalize(Path path, String field) {
    return Objects.requireNonNull(path, field).toAbsolutePath().normalize();
  }

  private static Path projectRoot(Path benchmarkRoot) {
    Path benchmarkDirectory =
        Objects.requireNonNull(normalize(benchmarkRoot, "benchmarkRoot").getParent(),
            "benchmarkRoot parent");
    return Objects.requireNonNull(benchmarkDirectory.getParent(), "projectRoot");
  }

  @FunctionalInterface
  public interface RunExecutor {
    RunOutcome execute(RunRequest request);
  }

  public record RunRequest(
      OlympiadBenchmarkPlan.RunSpec spec,
      String runId,
      OlympiadProblemCatalog.ProblemPrompt problem,
      Path workDirectory,
      Instant startedAt) {
    public RunRequest {
      spec = Objects.requireNonNull(spec, "spec");
      runId = require(runId, "runId");
      problem = Objects.requireNonNull(problem, "problem");
      workDirectory = normalize(workDirectory, "workDirectory");
      startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }
  }

  public record RunOutcome(
      String runId,
      String problemPromptSha256,
      String rootGoalHashInitial,
      String rootGoalHashFinal,
      String configHash,
      String pricingHash,
      String modelId,
      String providerId,
      FinalStatus finalStatus,
      String stopReason,
      String finalProof,
      Usage usage,
      Map<String, Object> evidenceDocuments,
      Map<String, IssueObservation> issueObservations,
      RecoveryEvidence recoveryEvidence,
      Instant endedAt) {
    public RunOutcome {
      runId = require(runId, "runId");
      problemPromptSha256 = hash(problemPromptSha256, "problemPromptSha256");
      rootGoalHashInitial = require(rootGoalHashInitial, "rootGoalHashInitial");
      rootGoalHashFinal = require(rootGoalHashFinal, "rootGoalHashFinal");
      configHash = require(configHash, "configHash");
      pricingHash = require(pricingHash, "pricingHash");
      modelId = require(modelId, "modelId");
      providerId = require(providerId, "providerId");
      finalStatus = Objects.requireNonNull(finalStatus, "finalStatus");
      stopReason = stopReason == null ? "" : stopReason.strip();
      finalProof = finalProof == null ? "" : finalProof;
      usage = Objects.requireNonNull(usage, "usage");
      evidenceDocuments = Map.copyOf(Objects.requireNonNull(evidenceDocuments, "evidenceDocuments"));
      issueObservations =
          Map.copyOf(Objects.requireNonNull(issueObservations, "issueObservations"));
      for (int issue = 1; issue <= 13; issue++) {
        if (!issueObservations.containsKey("issue_%03d".formatted(issue))) {
          throw new IllegalArgumentException("all issue observations are required");
        }
      }
      recoveryEvidence = Objects.requireNonNull(recoveryEvidence, "recoveryEvidence");
      endedAt = Objects.requireNonNull(endedAt, "endedAt");
    }

    @Override
    public Map<String, Object> evidenceDocuments() {
      return Map.copyOf(evidenceDocuments);
    }

    @Override
    public Map<String, IssueObservation> issueObservations() {
      return Map.copyOf(issueObservations);
    }

    public int hardViolationCount() {
      return issueObservations.values().stream()
          .mapToInt(IssueObservation::violations)
          .sum();
    }
  }

  public record IssueObservation(int violations, List<String> evidenceRefs) {
    public IssueObservation {
      if (violations < 0) {
        throw new IllegalArgumentException("issue violations must not be negative");
      }
      evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }

    @Override
    public List<String> evidenceRefs() {
      return List.copyOf(evidenceRefs);
    }
  }

  public record RecoveryEvidence(
      int providerCallReplays,
      int taskLosses,
      int stateDrifts,
      String beforeHash,
      String afterHash) {
    public RecoveryEvidence {
      if (providerCallReplays < 0 || taskLosses < 0 || stateDrifts < 0) {
        throw new IllegalArgumentException("recovery counters must not be negative");
      }
      beforeHash = require(beforeHash, "beforeHash");
      afterHash = require(afterHash, "afterHash");
    }
  }

  public record Usage(
      long calls,
      long inputTokens,
      long outputTokens,
      BigDecimal costUsd,
      long latencyMillis) {
    public Usage {
      if (calls < 0L || inputTokens < 0L || outputTokens < 0L || latencyMillis < 0L) {
        throw new IllegalArgumentException("usage counters must not be negative");
      }
      costUsd = Objects.requireNonNull(costUsd, "costUsd");
      if (costUsd.signum() < 0) {
        throw new IllegalArgumentException("usage cost must not be negative");
      }
    }

    public long totalTokens() {
      return Math.addExact(inputTokens, outputTokens);
    }
  }

  public enum FinalStatus {
    COMPLETE,
    INCOMPLETE,
    REFUTED,
    UNCERTAIN,
    INVALID
  }

  public record CompletedRun(
      OlympiadBenchmarkPlan.RunSpec spec,
      String runId,
      Path bundleDirectory,
      FinalStatus finalStatus,
      Usage usage,
      OlympiadEvidenceBundleValidator.Validation validation) {}

  public record HarnessResult(int plannedRuns, List<CompletedRun> completedRuns, int hardViolations) {
    public HarnessResult {
      if (plannedRuns < 1 || hardViolations < 0) {
        throw new IllegalArgumentException("invalid harness result");
      }
      completedRuns = List.copyOf(Objects.requireNonNull(completedRuns, "completedRuns"));
    }

    @Override
    public List<CompletedRun> completedRuns() {
      return List.copyOf(completedRuns);
    }

    public boolean passed() {
      return completedRuns.size() == plannedRuns && hardViolations == 0;
    }
  }

  static String json(Object value) {
    return ContractObjectMapper.write(value);
  }

  static Map<String, Object> missing(String reason) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("status", "MISSING");
    value.put("reason", require(reason, "reason"));
    return Map.copyOf(value);
  }

  private static String hash(String value, String field) {
    String normalized = require(value, field);
    if (!normalized.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a SHA-256 hash");
    }
    return normalized;
  }

  private static String require(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }
}
