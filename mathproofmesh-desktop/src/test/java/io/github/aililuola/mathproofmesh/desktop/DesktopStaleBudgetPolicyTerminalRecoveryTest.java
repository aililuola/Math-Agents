package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.orchestration.AdaptiveBudgetManager;
import io.github.aililuola.mathproofmesh.orchestration.BudgetActionCandidate;
import io.github.aililuola.mathproofmesh.orchestration.BudgetBucket;
import io.github.aililuola.mathproofmesh.orchestration.BudgetDecisionIdentity;
import io.github.aililuola.mathproofmesh.orchestration.BudgetDecisionSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector;
import io.github.aililuola.mathproofmesh.orchestration.EvidenceAwareBudgetDecision;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopStaleBudgetPolicyTerminalRecoveryTest {
  @Test
  void terminalNoCandidateFromAnOlderPolicyReopensWithoutChangingAuthority(
      @TempDir Path temporaryDirectory) throws Exception {
    Path runDirectory = temporaryDirectory.resolve("stale-budget-policy");
    String runId = "stale-budget-policy";
    DesktopSolveCheckpoint staleTerminal;
    String rootHash;

    try (var original =
        DesktopComputationIssue010CoordinatorHarness.openForBudgetProduction(
            runDirectory, runId)) {
      original.initializeRoute();
      original.markRouteAsCompletedStructuralFailure();
      DesktopSolveCheckpoint checkpoint = original.checkpointRoundTrip();
      var state = original.budgetState();
      rootHash = checkpoint.problem().goalHash();

      EvidenceAwareBudgetDecision legacyDecision =
          new EvidenceAwareBudgetDecision(
              new BudgetDecisionIdentity(
                  state.snapshotHash(), "evidence-budget-v2", "legacy-v2-verify-decision"),
              List.of(
                  new BudgetActionCandidate(
                      ActionKind.VERIFY,
                      original.routeId(),
                      checkpoint.routes().getFirst().strategy().strategyId(),
                      "legacy policy selected a route whose review was already complete",
                      true,
                      "",
                      3.0d,
                      BudgetResourceVector.zero(),
                      BudgetBucket.VERIFICATION,
                      1,
                      false,
                      true)),
              "legacy policy decision",
              "");

      ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(checkpoint);
      json.put("terminal", true);
      json.put("workflowCursor", "terminal");
      json.set(
          "schedulerStop",
          ContractObjectMapper.toTree(
              new DesktopSolveCheckpoint.SchedulerStop(
                  "no_candidate",
                  "legacy policy selected an inapplicable completed verification",
                  1,
                  1,
                  6,
                  10,
                  100_000,
                  1.0d,
                  3,
                  1)));
      json.set(
          "budgetDecisions",
          ContractObjectMapper.toTree(
              new BudgetDecisionSnapshot(
                  BudgetDecisionSnapshot.CURRENT_SCHEMA_VERSION, List.of(legacyDecision))));
      staleTerminal = ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
    }

    long callsBefore;
    long callsAfter;
    DesktopSolveCheckpoint recovered;
    try (var restored =
        DesktopComputationIssue010CoordinatorHarness.openForBudgetProduction(
            runDirectory, runId)) {
      callsBefore = restored.providerCallCount();
      restored.restore(staleTerminal);
      callsAfter = restored.providerCallCount();
      var restoredDecision = restored.decideBudget();

      assertThat(restored.workflowCursor()).isEqualTo("scheduler_decision");
      assertThat(restored.schedulerStop()).isNull();
      assertThat(restoredDecision.identity().policyVersion())
          .isEqualTo(AdaptiveBudgetManager.currentPolicyVersion());
      assertThat(restoredDecision.selectedActions())
          .extracting(BudgetActionCandidate::action)
          .containsExactly(ActionKind.REVISE);
      recovered = restored.checkpointRoundTrip();
    }

    int rootHashChanges = recovered.problem().goalHash().equals(rootHash) ? 0 : 1;
    int providerCallsDuringRestore = Math.toIntExact(callsAfter - callsBefore);
    long currentPolicyDecisions =
        recovered.budgetDecisions().decisions().stream()
            .filter(
                decision ->
                    AdaptiveBudgetManager.currentPolicyVersion()
                        .equals(decision.identity().policyVersion()))
            .count();

    assertThat(rootHashChanges).isZero();
    assertThat(providerCallsDuringRestore).isZero();
    assertThat(currentPolicyDecisions).isEqualTo(1L);
    assertThat(recovered.terminal()).isFalse();
    assertThat(recovered.workflowCursor()).isEqualTo("scheduler_decision");

    System.out.println("STALE BUDGET POLICY TERMINAL RECOVERY DIAGNOSTIC");
    System.out.println("STALE_POLICY_TERMINALS_REOPENED=1");
    System.out.println("CURRENT_POLICY_DECISIONS=" + currentPolicyDecisions);
    System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
    System.out.println("PROVIDER_CALLS_DURING_RESTORE=" + providerCallsDuringRestore);
    System.out.println("RESULT=PASS");
  }
}
