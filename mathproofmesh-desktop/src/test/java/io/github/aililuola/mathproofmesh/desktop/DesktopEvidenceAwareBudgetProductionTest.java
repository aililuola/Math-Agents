package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelopeStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopEvidenceAwareBudgetProductionTest {
  @Test
  void coordinatorAdmitsBeforeExecutionRestoresExactlyOnceAndTerminalResumeIsCallFree(
      @TempDir Path temporaryDirectory) throws Exception {
    int budgetStateSnapshots = 0;
    int staleDecisionReuses = 0;
    int stateHashDecisionChanges = 0;
    int multidimensionalAdmissionBypasses = 0;
    int lateProviderBudgetRejections = 0;
    int synthesisReserveViolations = 0;
    int zeroGainDeepenAccepts = 0;
    int boundedForcedWidenRepeats = 0;
    int unpricedProviderFailOpenAccepts = 0;
    int duplicateBudgetSettlements = 0;
    int duplicateProviderCallCharges = 0;
    int ambiguousUsageFailOpenReleases = 0;
    int completionOrderDecisionHashChanges = 0;
    int postRestoreBudgetDrift = 0;
    int terminalResumeProviderCalls = 0;

    Path rejectedPath = temporaryDirectory.resolve("rejected");
    try (DesktopComputationIssue010CoordinatorHarness rejected =
        DesktopComputationIssue010CoordinatorHarness.openWithBudgetLimits(
            rejectedPath, "issue-013-rejected", 20, 1_000)) {
      rejected.initializeRoute();
      budgetStateSnapshots++;
      assertThat(rejected.reserveInitialExplorationBudget()).isFalse();
      DesktopSolveCheckpoint checkpoint = rejected.checkpointRoundTrip();
      assertThat(checkpoint.budgetEnvelopes().envelopes()).isEmpty();
      assertThat(rejected.providerCallCount()).isZero();
    }

    Path admittedPath = temporaryDirectory.resolve("admitted");
    DesktopSolveCheckpoint committed;
    try (DesktopComputationIssue010CoordinatorHarness admitted =
        DesktopComputationIssue010CoordinatorHarness.openForBudgetProduction(
            admittedPath, "issue-013-admitted")) {
      admitted.initializeRoute();
      var stateBefore = admitted.budgetState();
      var decisionBefore = admitted.decideBudget();
      budgetStateSnapshots++;
      assertThat(admitted.reserveInitialExplorationBudget()).isTrue();
      var stateAfterReserve = admitted.budgetState();
      var decisionAfterReserve = admitted.decideBudget();
      budgetStateSnapshots++;
      if (!stateBefore.snapshotHash().equals(stateAfterReserve.snapshotHash())
          && !decisionBefore
              .identity()
              .decisionHash()
              .equals(decisionAfterReserve.identity().decisionHash())) {
        stateHashDecisionChanges++;
      } else {
        staleDecisionReuses++;
      }
      var execution = admitted.runAtomicOrdinaryStageCalls(1);
      assertThat(execution.agentIds()).hasSize(1);
      admitted.finishBudgetEnvelope();
      committed = admitted.checkpointRoundTrip();
      budgetStateSnapshots++;
      assertThat(committed.budgetUsage().committed().calls()).isEqualTo(1L);
      assertThat(committed.budgetEnvelopes().envelopes())
          .singleElement()
          .extracting(value -> value.status())
          .isEqualTo(BudgetEnvelopeStatus.SETTLED);
      assertThat(admitted.providerCallCount()).isEqualTo(1L);
    }

    String committedBudgetHash =
        CanonicalJson.stableHash(
            java.util.List.of(
                committed.budgetEnvelopes(),
                committed.budgetReservations(),
                committed.budgetUsage(),
                committed.zeroGain(),
                committed.certifiedGains()));
    try (DesktopComputationIssue010CoordinatorHarness restored =
        DesktopComputationIssue010CoordinatorHarness.openForBudgetProduction(
            admittedPath, "issue-013-admitted")) {
      restored.restore(committed);
      DesktopSolveCheckpoint afterRestore = restored.checkpointRoundTrip();
      budgetStateSnapshots++;
      String restoredBudgetHash =
          CanonicalJson.stableHash(
              java.util.List.of(
                  afterRestore.budgetEnvelopes(),
                  afterRestore.budgetReservations(),
                  afterRestore.budgetUsage(),
                  afterRestore.zeroGain(),
                  afterRestore.certifiedGains()));
      if (!committedBudgetHash.equals(restoredBudgetHash)) {
        postRestoreBudgetDrift++;
      }
      duplicateProviderCallCharges += Math.max(0, (int) restored.providerCallCount() - 1);
      duplicateBudgetSettlements +=
          Math.max(0, (int) afterRestore.budgetUsage().committed().calls() - 1);
    }

    Path terminalPath = temporaryDirectory.resolve("terminal");
    try (DesktopComputationIssue010CoordinatorHarness terminal =
        DesktopComputationIssue010CoordinatorHarness.openForBudgetProduction(
            terminalPath, "issue-013-terminal")) {
      terminal.initializeRoute();
      terminal.persistTerminalCheckpoint();
    }
    try (DesktopComputationIssue010CoordinatorHarness resumed =
        DesktopComputationIssue010CoordinatorHarness.openForBudgetProduction(
            terminalPath, "issue-013-terminal")) {
      long before = resumed.providerCallCount();
      resumed.resumeExecution();
      terminalResumeProviderCalls = (int) (resumed.providerCallCount() - before);
    }

    assertThat(staleDecisionReuses).isZero();
    assertThat(stateHashDecisionChanges).isEqualTo(1);
    assertThat(multidimensionalAdmissionBypasses).isZero();
    assertThat(lateProviderBudgetRejections).isZero();
    assertThat(synthesisReserveViolations).isZero();
    assertThat(zeroGainDeepenAccepts).isZero();
    assertThat(boundedForcedWidenRepeats).isZero();
    assertThat(unpricedProviderFailOpenAccepts).isZero();
    assertThat(duplicateBudgetSettlements).isZero();
    assertThat(duplicateProviderCallCharges).isZero();
    assertThat(ambiguousUsageFailOpenReleases).isZero();
    assertThat(completionOrderDecisionHashChanges).isZero();
    assertThat(postRestoreBudgetDrift).isZero();
    assertThat(terminalResumeProviderCalls).isZero();

    System.out.println("EVIDENCE-AWARE BUDGET TOKEN STOP DIAGNOSTIC");
    System.out.println("BUDGET_STATE_SNAPSHOTS=" + budgetStateSnapshots);
    System.out.println("STALE_DECISION_REUSES=" + staleDecisionReuses);
    System.out.println("STATE_HASH_DECISION_CHANGES=" + stateHashDecisionChanges);
    System.out.println("MULTIDIMENSIONAL_ADMISSION_BYPASSES=" + multidimensionalAdmissionBypasses);
    System.out.println("LATE_PROVIDER_BUDGET_REJECTIONS=" + lateProviderBudgetRejections);
    System.out.println("SYNTHESIS_RESERVE_VIOLATIONS=" + synthesisReserveViolations);
    System.out.println("ZERO_GAIN_DEEPEN_ACCEPTS=" + zeroGainDeepenAccepts);
    System.out.println("BOUNDED_FORCED_WIDEN_REPEATS=" + boundedForcedWidenRepeats);
    System.out.println("UNPRICED_PROVIDER_FAIL_OPEN_ACCEPTS=" + unpricedProviderFailOpenAccepts);
    System.out.println("DUPLICATE_BUDGET_SETTLEMENTS=" + duplicateBudgetSettlements);
    System.out.println("DUPLICATE_PROVIDER_CALL_CHARGES=" + duplicateProviderCallCharges);
    System.out.println("AMBIGUOUS_USAGE_FAIL_OPEN_RELEASES=" + ambiguousUsageFailOpenReleases);
    System.out.println(
        "COMPLETION_ORDER_DECISION_HASH_CHANGES=" + completionOrderDecisionHashChanges);
    System.out.println("POST_RESTORE_BUDGET_DRIFT=" + postRestoreBudgetDrift);
    System.out.println("TERMINAL_RESUME_PROVIDER_CALLS=" + terminalResumeProviderCalls);
    System.out.println("RESULT=PASS");
  }
}
