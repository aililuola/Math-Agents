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

    StrategyPreflightExecutionRecord newerButWeaker =
        new StrategyPreflightExecutionRecord(
            started.executionId(),
            PROBLEM,
            STRATEGY,
            CLAIM,
            PLAN_HASH,
            started.actionKey(),
            started.typedInputHash(),
            "",
            "",
            StrategyPreflightExecutionStatus.RUNNING,
            null,
            0,
            null,
            null,
            1,
            99L);
    registry.mergeDurable(
        new StrategyPreflightSnapshot(
            StrategyPreflightSnapshot.CURRENT_SCHEMA_VERSION,
            Map.of(),
            Map.of(),
            Map.of(newerButWeaker.executionId(), newerButWeaker),
            99L));
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

  @Test
  void typedFrontierTransitionsAreIdempotentBoundAndMonotonic() {
    StrategyPreflightRegistry registry = new StrategyPreflightRegistry();
    StrategyPreflightExecutionRecord reserved =
        registry.reserveExecution(
            PROBLEM, STRATEGY, CLAIM, PLAN_HASH, "bounded-search", "input-hash", 2);
    assertThat(reserved.status()).isEqualTo(StrategyPreflightExecutionStatus.RESERVED);
    assertThat(reserved.executionCount()).isZero();
    assertThat(
            registry.reserveExecution(
                PROBLEM, STRATEGY, CLAIM, PLAN_HASH, "bounded-search", "input-hash", 9))
        .isSameAs(reserved);
    assertThatThrownBy(
            () ->
                registry.reserveExecution(
                    PROBLEM, STRATEGY, CLAIM, PLAN_HASH, "other-action", "input-hash", 2))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                registry.reserveExecution(
                    PROBLEM, STRATEGY, CLAIM, PLAN_HASH, "bounded-search", "other-input", 2))
        .isInstanceOf(IllegalStateException.class);

    CriticalClaimPreflightEvidence evidence = evidence("durable");
    String replayHash = StrategySemanticNormalizer.hash(evidence);
    assertThatThrownBy(
            () ->
                registry.recordDurableResult(
                    reserved.executionId(), evidence, "artifact://durable", replayHash, 2))
        .isInstanceOf(IllegalStateException.class);
    StrategyPreflightExecutionRecord running = registry.startExecution(reserved.executionId());
    assertThat(registry.startExecution(reserved.executionId())).isSameAs(running);
    StrategyPreflightExecutionRecord durable =
        registry.recordDurableResult(
            reserved.executionId(), evidence, "artifact://durable", replayHash, 3);
    assertThat(
            registry.recordDurableResult(
                reserved.executionId(), evidence, "artifact://durable", replayHash, 4))
        .isSameAs(durable);
    assertThatThrownBy(
            () ->
                registry.recordDurableResult(
                    reserved.executionId(), evidence, "artifact://changed", replayHash, 4))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                registry.recordDurableResult(
                    reserved.executionId(), evidence, "artifact://durable", "changed-replay", 4))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> registry.abortExecution(reserved.executionId()))
        .isInstanceOf(IllegalStateException.class);

    StrategyPreflightExecutionRecord completed =
        registry.completeExecution(reserved.executionId(), evidence, 4);
    assertThat(registry.completeExecution(reserved.executionId(), evidence, 8))
        .isSameAs(completed);
    assertThatThrownBy(() -> registry.startExecution(reserved.executionId()))
        .isInstanceOf(IllegalStateException.class);

    StrategyPreflightRegistry abortedRegistry = new StrategyPreflightRegistry();
    StrategyPreflightExecutionRecord abortable =
        abortedRegistry.reserveExecution(
            PROBLEM, "aborted-strategy", CLAIM, PLAN_HASH, "action", "input", 0);
    StrategyPreflightExecutionRecord aborted =
        abortedRegistry.abortExecution(abortable.executionId());
    assertThat(aborted.status()).isEqualTo(StrategyPreflightExecutionStatus.ABORTED);
    assertThat(aborted.executionCount()).isZero();
    assertThat(abortedRegistry.abortExecution(abortable.executionId())).isSameAs(aborted);
    assertThatThrownBy(() -> abortedRegistry.startExecution(abortable.executionId()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> abortedRegistry.completeExecution(abortable.executionId(), evidence, 1))
        .isInstanceOf(IllegalStateException.class);

    StrategyPreflightRegistry runningAbortRegistry = new StrategyPreflightRegistry();
    StrategyPreflightExecutionRecord runningAbort =
        runningAbortRegistry.startExecution(
            runningAbortRegistry
                .reserveExecution(
                    PROBLEM, "running-abort", CLAIM, PLAN_HASH, "action", "input", 0)
                .executionId());
    assertThat(runningAbortRegistry.abortExecution(runningAbort.executionId()).executionCount())
        .isEqualTo(1);
  }

  @Test
  void typedExecutionRecordValidatesEveryDurableFrontierShape() {
    StrategyPreflightExecutionRecord reserved =
        fullExecution(
            StrategyPreflightExecutionStatus.RESERVED,
            null,
            null,
            null,
            null,
            null,
            0,
            1L);
    assertThat(reserved.actionKey()).startsWith("legacy-preflight-action:");
    assertThat(reserved.typedInputHash()).isEqualTo(PLAN_HASH);
    assertThat(reserved.resultArtifactRef()).isEmpty();
    assertThat(reserved.replayHash()).isEmpty();

    CriticalClaimPreflightEvidence emptyRefs =
        new CriticalClaimPreflightEvidence(
            CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE,
            "registered-computation",
            List.of(),
            "empty refs remain replayable");
    StrategyPreflightExecutionRecord completed =
        fullExecution(
            StrategyPreflightExecutionStatus.COMPLETED,
            emptyRefs,
            "",
            "",
            2,
            3,
            1,
            1L);
    assertThat(completed.resultArtifactRef()).isEqualTo("legacy:execution-id");
    assertThat(completed.replayHash()).isNotBlank();
    assertThat(
            fullExecution(
                    StrategyPreflightExecutionStatus.RESULT_DURABLE,
                    evidence("result"),
                    "artifact://result",
                    "replay",
                    2,
                    null,
                    1,
                    1L)
                .resultDurable())
        .isTrue();
    assertThat(
            fullExecution(
                    StrategyPreflightExecutionStatus.ABORTED,
                    null,
                    "",
                    "",
                    null,
                    null,
                    0,
                    1L)
                .resultDurable())
        .isFalse();

    assertThatThrownBy(
            () ->
                fullExecution(
                    StrategyPreflightExecutionStatus.RUNNING,
                    null,
                    "",
                    "",
                    -1,
                    null,
                    1,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fullExecution(
                    StrategyPreflightExecutionStatus.COMPLETED,
                    evidence("early-completion"),
                    "artifact://early",
                    "replay",
                    2,
                    1,
                    1,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fullExecution(
                    StrategyPreflightExecutionStatus.RESULT_DURABLE,
                    evidence("missing-artifact"),
                    "",
                    "replay",
                    2,
                    null,
                    1,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fullExecution(
                    StrategyPreflightExecutionStatus.RESULT_DURABLE,
                    evidence("missing-replay"),
                    "artifact://result",
                    "",
                    2,
                    null,
                    1,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fullExecution(
                    StrategyPreflightExecutionStatus.RESERVED,
                    null,
                    "",
                    "",
                    null,
                    null,
                    1,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fullExecution(
                    StrategyPreflightExecutionStatus.RUNNING,
                    null,
                    "",
                    "",
                    null,
                    null,
                    0,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fullExecution(
                    StrategyPreflightExecutionStatus.RUNNING,
                    null,
                    "",
                    "",
                    null,
                    2,
                    1,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fullExecution(
                    StrategyPreflightExecutionStatus.RUNNING,
                    evidence("non-durable"),
                    "",
                    "",
                    null,
                    null,
                    1,
                    1L))
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

  private static StrategyPreflightExecutionRecord fullExecution(
      StrategyPreflightExecutionStatus status,
      CriticalClaimPreflightEvidence evidence,
      String artifact,
      String replay,
      Integer resultRound,
      Integer completedRound,
      int executionCount,
      long version) {
    return new StrategyPreflightExecutionRecord(
        "execution-id",
        PROBLEM,
        STRATEGY,
        CLAIM,
        PLAN_HASH,
        null,
        null,
        artifact,
        replay,
        status,
        evidence,
        0,
        resultRound,
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
