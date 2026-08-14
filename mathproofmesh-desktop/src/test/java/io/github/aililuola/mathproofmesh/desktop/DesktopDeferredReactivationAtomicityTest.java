package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionStatus;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopDeferredReactivationAtomicityTest {
  @Test
  void eachInjectedFailureRollsBackGraphLedgerLeaseAndPendingTask(@TempDir Path directory)
      throws Exception {
    int graphPartial = 0;
    int ledgerPartial = 0;
    int leaseLeaks = 0;
    int pendingLeaks = 0;
    int duplicateRetryTasks = 0;
    for (DeferredReactivationFailurePoint point :
        new DeferredReactivationFailurePoint[] {
          DeferredReactivationFailurePoint.AFTER_GRAPH_TRANSITION,
          DeferredReactivationFailurePoint.AFTER_TASK_LEASE,
          DeferredReactivationFailurePoint.AFTER_PENDING_TASK
        }) {
      try (var harness =
          DesktopResearchCheckpointBlackBoxHarness.open(
              directory.resolve(point.name().toLowerCase(java.util.Locale.ROOT)),
              "issue-005-reactivation-atomicity-" + point.name().toLowerCase(java.util.Locale.ROOT),
              DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
              "unused")) {
        harness.prepareProductionRoute();
        DesktopDeferredReactivationTestSupport.fillRouteCapacity(harness, "atomic-" + point);
        var obligation =
            DesktopDeferredReactivationTestSupport.addControlledTarget(
                harness,
                "atomic-deferred-" + point,
                "atomic-family-" + point,
                FocusedRecoveryActionType.NEW_STRATEGY,
                0);
        var record =
            DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().getFirst();
        String deferredCanonical =
            DesktopProofGraphIssue005BlackBoxSupport.graph(harness)
                .canonicalTargetForObligation(obligation.obligationId())
                .orElseThrow()
                .canonicalTargetId();
        for (int release = 0;
            release < 3
                && DesktopProofGraphIssue005BlackBoxSupport.graph(harness)
                        .activeCanonicalTargetCount("route-1")
                    >= 8;
            release++) {
          DesktopProofGraphIssue005BlackBoxSupport.closeFirstActiveCanonicalTargetExcept(
              harness, deferredCanonical);
        }
        DesktopProofGraphIssue005BlackBoxSupport.setRound(harness, 1);
        String graphHash = DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness);
        String ledgerHash = DesktopProofGraphIssue005BlackBoxSupport.deferredHash(harness);
        int leases = DesktopProofGraphIssue005BlackBoxSupport.canonicalTaskLeaseCount(harness);
        int pending = DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness);
        assertThat(
                DesktopProofGraphIssue005BlackBoxSupport.graph(harness)
                    .activeCanonicalTargetCount("route-1"))
            .isLessThan(8);

        DesktopProofGraphIssue005BlackBoxSupport.injectDeferredReactivationFailure(harness, point);
        assertThatThrownBy(
                () ->
                    DesktopProofGraphIssue005BlackBoxSupport.reconsiderDeferredExpansions(harness))
            .hasRootCauseInstanceOf(IllegalStateException.class);

        var rolledBackGraph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
        var rolledBackTarget =
            rolledBackGraph.canonicalTargetForObligation(obligation.obligationId()).orElseThrow();
        var rolledBackRecord =
            DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
                .filter(item -> item.deferredId().equals(record.deferredId()))
                .findFirst()
                .orElseThrow();
        graphPartial +=
            rolledBackTarget.schedulingState()
                    == CanonicalObligationSchedulingState.DEFERRED_CAPACITY
                ? 0
                : 1;
        ledgerPartial += rolledBackRecord.status() == DeferredExpansionStatus.DEFERRED ? 0 : 1;
        leaseLeaks +=
            DesktopProofGraphIssue005BlackBoxSupport.canonicalTaskLeaseCount(harness) == leases
                ? 0
                : 1;
        pendingLeaks +=
            DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness) == pending ? 0 : 1;
        assertThat(DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness))
            .isEqualTo(graphHash);
        assertThat(DesktopProofGraphIssue005BlackBoxSupport.deferredHash(harness))
            .isEqualTo(ledgerHash);

        DesktopProofGraphIssue005BlackBoxSupport.injectDeferredReactivationFailure(
            harness, DeferredReactivationFailurePoint.NONE);
        assertThat(
                DesktopProofGraphIssue005BlackBoxSupport.reconsiderDeferredExpansions(harness))
            .isEqualTo(1);
        long retryTasks =
            DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
                .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
                .filter(task -> task.source().equals("deferred-reactivation:" + record.deferredId()))
                .count();
        duplicateRetryTasks += Math.max(0, (int) retryTasks - 1);
      }
    }

    assertThat(graphPartial).isZero();
    assertThat(ledgerPartial).isZero();
    assertThat(leaseLeaks).isZero();
    assertThat(pendingLeaks).isZero();
    assertThat(duplicateRetryTasks).isZero();
    System.out.println("DEFERRED REACTIVATION ATOMICITY DIAGNOSTIC");
    print("GRAPH_PARTIAL_REACTIVATIONS", graphPartial);
    print("LEDGER_PARTIAL_REACTIVATIONS", ledgerPartial);
    print("TASK_LEASE_LEAKS", leaseLeaks);
    print("PENDING_TASK_LEAKS", pendingLeaks);
    print("DUPLICATE_RETRY_TASKS", duplicateRetryTasks);
    System.out.println("RESULT=PASS");
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
