package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.CriticalClaimPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyPreflightRegistryExecutionTest {
  private static final String PROBLEM = "problem-hash";
  private static final String STRATEGY = "strategy-id";
  private static final String CLAIM = "claim-id";
  private static final String PLAN_HASH = "plan-hash";

  @Test
  void recordsPlansReportsAndExactlyOnceExecutions() {
    StrategyPreflightRegistry registry = new StrategyPreflightRegistry();
    StrategyPreflightReport report = report("report-hash");
    StrategyPreflightPlan plan = plan(CLAIM);

    assertThat(registry.record(report)).isSameAs(report);
    assertThat(registry.record(report)).isSameAs(report);
    assertThat(registry.find(STRATEGY)).contains(report);
    assertThatThrownBy(() -> registry.record(report("other-report-hash")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(registry.recordPlan(plan)).isSameAs(plan);
    assertThat(registry.recordPlan(plan)).isSameAs(plan);
    assertThat(registry.plan(STRATEGY)).contains(plan);
    assertThatThrownBy(() -> registry.recordPlan(plan("other-claim")))
        .isInstanceOf(IllegalStateException.class);

    StrategyPreflightExecutionRecord started =
        registry.beginExecution(PROBLEM, STRATEGY, CLAIM, PLAN_HASH, 2);
    assertThat(registry.beginExecution(PROBLEM, STRATEGY, CLAIM, PLAN_HASH, 4))
        .isSameAs(started);
    assertThat(started.completed()).isFalse();
    assertThat(registry.execution(started.executionId())).contains(started);
    assertThat(registry.execution("missing")).isEmpty();

    CriticalClaimPreflightEvidence evidence = evidence("evidence-a");
    StrategyPreflightExecutionRecord completed =
        registry.completeExecution(started.executionId(), evidence, 3);
    assertThat(completed.completed()).isTrue();
    assertThat(completed.executionCount()).isEqualTo(1);
    assertThat(registry.executionCount()).isEqualTo(1);
    assertThat(registry.completeExecution(started.executionId(), evidence, 5))
        .isSameAs(completed);
    assertThatThrownBy(
            () ->
                registry.completeExecution(
                    started.executionId(), evidence("evidence-b"), 5))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> registry.completeExecution("missing", evidence, 3))
        .isInstanceOf(IllegalStateException.class);

    StrategyPreflightSnapshot snapshot = registry.snapshot();
    StrategyPreflightRegistry restored = StrategyPreflightRegistry.restore(snapshot);
    assertThat(restored.registryHash()).isEqualTo(registry.registryHash());
    assertThat(StrategyPreflightRegistry.restore(null).snapshot())
        .isEqualTo(StrategyPreflightSnapshot.empty());
    restored.mergeDurable(null);
    assertThat(restored.registryHash()).isEqualTo(registry.registryHash());
  }

  @Test
  void durableMergeUpgradesExecutionsWithoutAllowingPlanMutationOrDowngrade() {
    StrategyPreflightRegistry registry = new StrategyPreflightRegistry();
    StrategyPreflightPlan plan = plan(CLAIM);
    registry.recordPlan(plan);
    StrategyPreflightExecutionRecord started =
        registry.beginExecution(PROBLEM, STRATEGY, CLAIM, PLAN_HASH, 0);
    StrategyPreflightExecutionRecord completed =
        new StrategyPreflightExecutionRecord(
            started.executionId(),
            PROBLEM,
            STRATEGY,
            CLAIM,
            PLAN_HASH,
            "completed",
            evidence("durable"),
            0,
            1,
            1,
            2L);
    registry.mergeDurable(
        new StrategyPreflightSnapshot(
            StrategyPreflightSnapshot.CURRENT_SCHEMA_VERSION,
            Map.of(),
            Map.of(STRATEGY, plan),
            Map.of(completed.executionId(), completed),
            4L));
    assertThat(registry.execution(completed.executionId()))
        .contains(completed);

    registry.mergeDurable(
        new StrategyPreflightSnapshot(
            StrategyPreflightSnapshot.CURRENT_SCHEMA_VERSION,
            Map.of(),
            Map.of(),
            Map.of(started.executionId(), started),
            1L));
    assertThat(registry.execution(completed.executionId()))
        .contains(completed);

    assertThatThrownBy(
            () ->
                registry.mergeDurable(
                    new StrategyPreflightSnapshot(
                        StrategyPreflightSnapshot.CURRENT_SCHEMA_VERSION,
                        Map.of(),
                        Map.of(STRATEGY, plan("different-claim")),
                        Map.of(),
                        5L)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void executionRecordRejectsEveryInvalidFrontierShape() {
    CriticalClaimPreflightEvidence evidence = evidence("valid");
    assertThatThrownBy(
            () ->
                execution("unknown", null, 0, null, 1, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                execution("started", null, -1, null, 1, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                execution("completed", evidence, 2, 1, 1, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                execution("started", null, 0, null, 2, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                execution("started", null, 0, null, 1, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                execution("completed", null, 0, null, 1, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                execution("started", evidence, 0, 0, 1, 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static StrategyPreflightExecutionRecord execution(
      String state,
      CriticalClaimPreflightEvidence evidence,
      int startedRound,
      Integer completedRound,
      int executionCount,
      long version) {
    return new StrategyPreflightExecutionRecord(
        "execution-id",
        PROBLEM,
        STRATEGY,
        CLAIM,
        PLAN_HASH,
        state,
        evidence,
        startedRound,
        completedRound,
        executionCount,
        version);
  }

  private static StrategyPreflightPlan plan(String claimId) {
    return new StrategyPreflightPlan(
        PROBLEM,
        STRATEGY,
        List.of(new CriticalClaimPreflightPlan(claimId, "", List.of(), List.of())));
  }

  private static StrategyPreflightReport report(String hash) {
    return new StrategyPreflightReport(
        STRATEGY, PROBLEM, List.of(), false, false, 0.0d, Set.of(), hash);
  }

  private static CriticalClaimPreflightEvidence evidence(String detail) {
    return new CriticalClaimPreflightEvidence(
        CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE,
        "registered-computation",
        List.of("artifact://" + detail),
        detail);
  }
}
