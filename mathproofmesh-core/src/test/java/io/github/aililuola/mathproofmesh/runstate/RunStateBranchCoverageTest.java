package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RunStateBranchCoverageTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void executionTerminalityAndEveryProgressSourceAreExplicit() {
    EnumSet<RunExecutionStatus> terminalExecutions =
        EnumSet.of(
            RunExecutionStatus.SUCCEEDED,
            RunExecutionStatus.FAILED,
            RunExecutionStatus.INTERRUPTED,
            RunExecutionStatus.CANCELLED);
    for (RunExecutionStatus status : RunExecutionStatus.values()) {
      assertThat(status.terminal()).isEqualTo(terminalExecutions.contains(status));
    }

    EnumSet<RunExecutionAttemptStatus> terminalAttempts =
        EnumSet.of(
            RunExecutionAttemptStatus.SUCCEEDED,
            RunExecutionAttemptStatus.FAILED,
            RunExecutionAttemptStatus.INTERRUPTED,
            RunExecutionAttemptStatus.CANCELLED);
    for (RunExecutionAttemptStatus status : RunExecutionAttemptStatus.values()) {
      assertThat(status.terminal()).isEqualTo(terminalAttempts.contains(status));
    }

    assertThat(progress(-1).anyProgress()).isFalse();
    for (int source = 0; source < 10; source++) {
      assertThat(progress(source).anyProgress()).as("progress source %s", source).isTrue();
    }
    assertThatThrownBy(
            () ->
                new RunMathematicalProgressSnapshot(
                    -1, 0, 0, false, false, false, false, false, false, false, false, false, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RunMathematicalProgressSnapshot(
                    0, -1, 0, false, false, false, false, false, false, false, false, false, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RunMathematicalProgressSnapshot(
                    0, 0, -1, false, false, false, false, false, false, false, false, false, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void executionAttemptLedgerCoversEveryLegalTerminalAndRestoreBoundary() {
    RunExecutionAttemptLedger ledger = new RunExecutionAttemptLedger();
    assertThatThrownBy(ledger::latest).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ledger.require("missing"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ledger.create("run", null)).isInstanceOf(NullPointerException.class);

    var first = ledger.create("run", NOW);
    assertThat(ledger.transition(first.attemptId(), RunExecutionAttemptStatus.QUEUED, "", NOW))
        .isNotNull();
    ledger.transition(first.attemptId(), RunExecutionAttemptStatus.RUNNING, "", NOW);
    ledger.transition(first.attemptId(), RunExecutionAttemptStatus.SUCCEEDED, "", NOW);
    assertThat(ledger.latest().status()).isEqualTo(RunExecutionAttemptStatus.SUCCEEDED);
    assertThatThrownBy(
            () -> ledger.transition(first.attemptId(), RunExecutionAttemptStatus.RUNNING, "", NOW))
        .isInstanceOf(IllegalStateException.class);

    for (RunExecutionAttemptStatus terminal :
        List.of(
            RunExecutionAttemptStatus.FAILED,
            RunExecutionAttemptStatus.INTERRUPTED,
            RunExecutionAttemptStatus.CANCELLED)) {
      var attempt = ledger.create("run", NOW.plusSeconds(ledger.snapshot().attempts().size()));
      if (terminal == RunExecutionAttemptStatus.CANCELLED) {
        ledger.transition(attempt.attemptId(), terminal, "cancelled", NOW.plusSeconds(10));
      } else {
        ledger.transition(attempt.attemptId(), RunExecutionAttemptStatus.RUNNING, "", NOW.plusSeconds(10));
        ledger.transition(attempt.attemptId(), terminal, "failure", NOW.plusSeconds(11));
      }
    }
    var queued = ledger.create("run", NOW.plusSeconds(20));
    ledger.transition(queued.attemptId(), RunExecutionAttemptStatus.RUNNING, "", NOW.plusSeconds(21));
    assertThatThrownBy(
            () -> ledger.transition(queued.attemptId(), RunExecutionAttemptStatus.QUEUED, "", NOW))
        .isInstanceOf(IllegalStateException.class);

    RunExecutionAttemptSnapshot snapshot = ledger.snapshot();
    RunExecutionAttemptLedger restored = new RunExecutionAttemptLedger();
    restored.restore(snapshot);
    assertThat(restored.latest()).isEqualTo(snapshot.attempts().getLast());
    assertThatThrownBy(() -> restored.restore(null)).isInstanceOf(NullPointerException.class);
    RunExecutionAttemptRecord duplicate = snapshot.attempts().getFirst();
    assertThatThrownBy(
            () ->
                restored.restore(
                    new RunExecutionAttemptSnapshot(List.of(duplicate, duplicate), null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void usageValueObjectsRejectEveryInvalidCounterShape() {
    assertThatThrownBy(
            () -> new ProviderCallUsageEvidence("request", -1, 0, BigDecimal.ZERO, 0, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ProviderCallUsageEvidence("request", 0, -1, BigDecimal.ZERO, 0, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ProviderCallUsageEvidence("request", 0, 0, BigDecimal.valueOf(-1), 0, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ProviderCallUsageEvidence("request", 0, 0, BigDecimal.ZERO, Double.NaN, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ProviderCallUsageEvidence("request", 0, 0, BigDecimal.ZERO, -1, ""))
        .isInstanceOf(IllegalArgumentException.class);

    assertInvalidUsage(-1, 0, 0, 0, BigDecimal.ZERO, 0);
    assertInvalidUsage(0, -1, 0, -1, BigDecimal.ZERO, 0);
    assertInvalidUsage(0, 0, -1, -1, BigDecimal.ZERO, 0);
    assertInvalidUsage(0, 1, 1, 3, BigDecimal.ZERO, 0);
    assertInvalidUsage(0, 0, 0, 0, BigDecimal.valueOf(-1), 0);
    assertInvalidUsage(0, 0, 0, 0, BigDecimal.ZERO, Double.POSITIVE_INFINITY);
    assertInvalidUsage(0, 0, 0, 0, BigDecimal.ZERO, -1);
    assertThat(RunUsageSnapshot.empty().identityPayload()).containsEntry("totalTokens", 0L);
  }

  @Test
  void usageReconciliationCoversAggregateCallConflictAndMonotonicBranches() {
    RunUsageReconciler reconciler = new RunUsageReconciler();
    assertThat(reconciler.reconcile(null, null).status())
        .isEqualTo(RunUsageStatus.NOT_RECORDED);

    RunUsageSnapshot aggregate =
        RunUsageSnapshot.of(1, 2, 3, BigDecimal.ONE, 4, "requests", "artifact");
    assertThat(
            reconciler
                .reconcile(
                    List.of(
                        RunUsageEvidence.aggregate(
                            RunUsageEvidenceSource.RESULT_PROJECTION, aggregate, "result")),
                    null)
                .status())
        .isEqualTo(RunUsageStatus.PARTIAL_RECORDED);
    assertThat(
            reconciler
                .reconcile(
                    List.of(
                        RunUsageEvidence.aggregate(
                            RunUsageEvidenceSource.SEMANTIC_CHECKPOINT, aggregate, "checkpoint")),
                    null)
                .status())
        .isEqualTo(RunUsageStatus.RECORDED);

    ProviderCallUsageEvidence call = call("request", 2, 3, BigDecimal.ONE, 4, "artifact-a");
    assertThat(
            reconciler
                .reconcile(
                    List.of(RunUsageEvidence.providerCalls(List.of(call, call), "calls")), null)
                .usage()
                .providerCalls())
        .isEqualTo(1);
    for (ProviderCallUsageEvidence conflicting :
        List.of(
            call("request", 9, 3, BigDecimal.ONE, 4, "artifact-b"),
            call("request", 2, 9, BigDecimal.ONE, 4, "artifact-b"),
            call("request", 2, 3, BigDecimal.TEN, 4, "artifact-b"),
            call("request", 2, 3, BigDecimal.ONE, 9, "artifact-b"))) {
      assertThat(
              reconciler
                  .reconcile(
                      List.of(
                          RunUsageEvidence.providerCalls(
                              List.of(call, conflicting), "conflict")),
                      null)
                  .status())
          .isEqualTo(RunUsageStatus.CONFLICT);
    }

    RunUsageSnapshot prior =
        RunUsageSnapshot.of(2, 10, 20, BigDecimal.TEN, 30, "prior", "prior");
    assertThat(
            reconciler
                .reconcile(
                    List.of(
                        RunUsageEvidence.aggregate(
                            RunUsageEvidenceSource.SEMANTIC_CHECKPOINT,
                            RunUsageSnapshot.of(1, 10, 20, BigDecimal.TEN, 30, "next", "next"),
                            "calls")),
                    prior)
                .status())
        .isEqualTo(RunUsageStatus.CONFLICT);
    assertThat(
            reconciler
                .reconcile(
                    List.of(
                        RunUsageEvidence.aggregate(
                            RunUsageEvidenceSource.SEMANTIC_CHECKPOINT,
                            RunUsageSnapshot.of(2, 9, 20, BigDecimal.TEN, 30, "next", "next"),
                            "input")),
                    prior)
                .usage())
        .isEqualTo(prior);
  }

  @Test
  void snapshotsApplyDefaultsDefensiveCopiesAndHashVerification() {
    List<String> refs = new ArrayList<>(List.of("evidence"));
    RunStateConflict conflict = new RunStateConflict("CODE", "detail", refs);
    refs.add("late");
    assertThat(conflict.evidenceRefs()).containsExactly("evidence");

    RunProjectionSnapshot projection =
        new RunProjectionSnapshot(null, null, null, null, null, null, null, null, 0, null, null);
    assertThat(projection.reportStatus()).isEqualTo(RunReportStatus.ABSENT);
    assertThat(projection.projectionErrors()).isEmpty();
    assertThatThrownBy(
            () ->
                new RunProjectionSnapshot(
                    "", null, "", "", "", "", "", "", -1, List.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RunProjectionSnapshot(
                    "", null, "", "", "", "", "", "", 0, List.of(), "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(RunExecutionAttemptSnapshot.empty().attempts()).isEmpty();
    assertThat(RunStateTransitionSnapshot.empty().transitions()).isEmpty();
    assertThat(RunStateAnchor.empty().authoritySequence()).isZero();
    assertThatThrownBy(() -> new RunStateAnchor(-1, "", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RunMathematicalProgressSnapshot progress(int source) {
    return new RunMathematicalProgressSnapshot(
        source == 3 ? 1 : 0,
        source == 4 ? 1 : 0,
        source == 5 ? 1 : 0,
        source == 0,
        source == 1,
        source == 2,
        source == 6,
        source == 7,
        source == 8,
        source == 9,
        false,
        false,
        true);
  }

  private static ProviderCallUsageEvidence call(
      String request, long input, long output, BigDecimal cost, double latency, String artifact) {
    return new ProviderCallUsageEvidence(request, input, output, cost, latency, artifact);
  }

  private static void assertInvalidUsage(
      long calls,
      long input,
      long output,
      long total,
      BigDecimal cost,
      double latency) {
    assertThatThrownBy(
            () -> new RunUsageSnapshot(calls, input, output, total, cost, latency, "", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
