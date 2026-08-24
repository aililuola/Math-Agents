package io.github.aililuola.mathproofmesh.desktop.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable 34-run schedule for the five-key olympiad benchmark. */
public final class OlympiadBenchmarkPlan {
  public static final String BENCHMARK_ID = "olympiad-5key-v1";
  public static final String BASELINE_COMMIT =
      "ea94a34041fd32a4f94ecb1a3532ddc314430a47";
  public static final String BRANCH = "validation/014-five-key-math-olympiad-benchmark";
  public static final List<String> KEY_LABELS =
      List.of("KEY_A", "KEY_B", "KEY_C", "KEY_D", "KEY_E");

  private static final Set<Integer> REPLICATION_PROBLEMS = Set.of(9, 12, 13, 15, 16);
  private static final Set<Integer> RECOVERY_PROBLEMS = Set.of(11, 17, 19, 20);

  private OlympiadBenchmarkPlan() {}

  public static List<RunSpec> fullSchedule() {
    List<RunSpec> schedule = new ArrayList<>();
    for (int problem = 1; problem <= 20; problem++) {
      schedule.add(run(problem, "T1", RunKind.STANDARD, 0, RecoveryBoundary.NONE));
    }
    for (int problem : List.of(9, 12, 13, 15, 16)) {
      schedule.add(run(problem, "T2", RunKind.REPLICATION, 1, RecoveryBoundary.NONE));
      schedule.add(run(problem, "T3", RunKind.REPLICATION, 2, RecoveryBoundary.NONE));
    }
    schedule.add(
        run(
            11,
            "R1",
            RunKind.CONTROLLED_RECOVERY,
            0,
            RecoveryBoundary.AFTER_FIRST_RESULT_DURABLE));
    schedule.add(
        run(
            17,
            "R1",
            RunKind.CONTROLLED_RECOVERY,
            0,
            RecoveryBoundary.AFTER_ALL_SETTLED_BEFORE_STABLE_MERGE));
    schedule.add(
        run(
            19,
            "R1",
            RunKind.CONTROLLED_RECOVERY,
            0,
            RecoveryBoundary.AFTER_MERGE_PREPARED_BEFORE_AUTHORITY_COMMIT));
    schedule.add(
        run(
            20,
            "R1",
            RunKind.CONTROLLED_RECOVERY,
            0,
            RecoveryBoundary.AFTER_V22_ATOMIC_CHECKPOINT));
    validateSchedule(schedule);
    return List.copyOf(schedule);
  }

  public static String keyEnvironmentName(String keyLabel) {
    int index = KEY_LABELS.indexOf(require(keyLabel, "keyLabel"));
    if (index < 0) {
      throw new IllegalArgumentException("unknown benchmark key label");
    }
    return "DEEPSEEK_API_KEY_" + (char) ('A' + index);
  }

  public static Tier tier(String problemId) {
    int number = problemNumber(problemId);
    if (number <= 5) {
      return Tier.SMOKE;
    }
    if (number <= 10) {
      return Tier.CORE;
    }
    if (number <= 15) {
      return Tier.ADVANCED;
    }
    return Tier.STRESS;
  }

  public static int problemNumber(String problemId) {
    String normalized = require(problemId, "problemId");
    if (!normalized.matches("P(?:0[1-9]|1[0-9]|20)")) {
      throw new IllegalArgumentException("problemId must be P01 through P20");
    }
    return Integer.parseInt(normalized.substring(1));
  }

  private static RunSpec run(
      int problem,
      String trial,
      RunKind kind,
      int coordinationRotation,
      RecoveryBoundary recoveryBoundary) {
    String problemId = "P%02d".formatted(problem);
    int baseKey = (problem - 1) % KEY_LABELS.size();
    String coordination = KEY_LABELS.get((baseKey + coordinationRotation) % KEY_LABELS.size());
    List<String> research = KEY_LABELS.stream().filter(key -> !key.equals(coordination)).toList();
    return new RunSpec(
        problemId,
        trial,
        tier(problemId),
        kind,
        coordination,
        research,
        recoveryBoundary);
  }

  private static void validateSchedule(List<RunSpec> schedule) {
    if (schedule.size() != 34) {
      throw new IllegalStateException("benchmark schedule must contain exactly 34 runs");
    }
    Set<String> identities = new LinkedHashSet<>();
    for (RunSpec spec : schedule) {
      if (!identities.add(spec.identity())) {
        throw new IllegalStateException("duplicate benchmark run identity");
      }
    }
    long standard = schedule.stream().filter(run -> run.kind() == RunKind.STANDARD).count();
    long replication =
        schedule.stream().filter(run -> run.kind() == RunKind.REPLICATION).count();
    long recovery =
        schedule.stream().filter(run -> run.kind() == RunKind.CONTROLLED_RECOVERY).count();
    if (standard != 20 || replication != 10 || recovery != 4) {
      throw new IllegalStateException("benchmark suite counts do not match the frozen protocol");
    }
    for (int problem = 1; problem <= 20; problem++) {
      int expected = 1 + (REPLICATION_PROBLEMS.contains(problem) ? 2 : 0)
          + (RECOVERY_PROBLEMS.contains(problem) ? 1 : 0);
      String problemId = "P%02d".formatted(problem);
      long actual = schedule.stream().filter(run -> run.problemId().equals(problemId)).count();
      if (actual != expected) {
        throw new IllegalStateException("unexpected trial count for " + problemId);
      }
    }
  }

  private static String require(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }

  public enum RunKind {
    STANDARD,
    REPLICATION,
    CONTROLLED_RECOVERY
  }

  public enum RecoveryBoundary {
    NONE,
    AFTER_FIRST_RESULT_DURABLE,
    AFTER_ALL_SETTLED_BEFORE_STABLE_MERGE,
    AFTER_MERGE_PREPARED_BEFORE_AUTHORITY_COMMIT,
    AFTER_V22_ATOMIC_CHECKPOINT
  }

  public enum Tier {
    SMOKE(24, 6, 1_152_000),
    CORE(40, 8, 1_920_000),
    ADVANCED(64, 12, 3_072_000),
    STRESS(96, 16, 4_608_000);

    private final int maximumCalls;
    private final int maximumRounds;
    private final int maximumTokens;

    Tier(int maximumCalls, int maximumRounds, int maximumTokens) {
      this.maximumCalls = maximumCalls;
      this.maximumRounds = maximumRounds;
      this.maximumTokens = maximumTokens;
    }

    public int maximumCalls() {
      return maximumCalls;
    }

    public int maximumRounds() {
      return maximumRounds;
    }

    public int maximumTokens() {
      return maximumTokens;
    }
  }

  public record RunSpec(
      String problemId,
      String trialId,
      Tier tier,
      RunKind kind,
      String coordinationKeyLabel,
      List<String> researchKeyLabels,
      RecoveryBoundary recoveryBoundary) {
    public RunSpec {
      problemId = require(problemId, "problemId");
      problemNumber(problemId);
      trialId = require(trialId, "trialId");
      tier = Objects.requireNonNull(tier, "tier");
      kind = Objects.requireNonNull(kind, "kind");
      coordinationKeyLabel = require(coordinationKeyLabel, "coordinationKeyLabel");
      if (!KEY_LABELS.contains(coordinationKeyLabel)) {
        throw new IllegalArgumentException("unknown coordination key label");
      }
      researchKeyLabels = List.copyOf(Objects.requireNonNull(researchKeyLabels, "researchKeyLabels"));
      Set<String> expectedResearchKeys = new LinkedHashSet<>(KEY_LABELS);
      expectedResearchKeys.remove(coordinationKeyLabel);
      if (researchKeyLabels.size() != 4
          || researchKeyLabels.contains(coordinationKeyLabel)
          || !new LinkedHashSet<>(researchKeyLabels).equals(expectedResearchKeys)) {
        throw new IllegalArgumentException("research key labels must be the other four keys");
      }
      recoveryBoundary = Objects.requireNonNull(recoveryBoundary, "recoveryBoundary");
      if ((kind == RunKind.CONTROLLED_RECOVERY) != (recoveryBoundary != RecoveryBoundary.NONE)) {
        throw new IllegalArgumentException("recovery runs require exactly one recovery boundary");
      }
    }

    @Override
    public List<String> researchKeyLabels() {
      return List.copyOf(researchKeyLabels);
    }

    public String identity() {
      return problemId + "/" + trialId;
    }

    public boolean coldStart() {
      return true;
    }
  }
}
