package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.FailureLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardsAndContextParityTest {
  @Test
  void problemHashGuardOverridesAModelPass() {
    AgentGuardPolicies.GuardResult result =
        AgentGuardPolicies.applyProblemIntegrity(
            "expected-hash", "wrong-hash", true);

    assertThat(result.passed()).isFalse();
    assertThat(result.problemIntegrityOk()).isFalse();
    assertThat(result.issues()).isNotEmpty();
  }

  @Test
  void deterministicCounterexampleOverridesAModelPass() {
    AgentGuardPolicies.GuardResult result =
        AgentGuardPolicies.applyDeterministicCounterexamples(
            true,
            List.of(
                new AgentGuardPolicies.DeterministicToolEvidence(true, true)));

    assertThat(result.passed()).isFalse();
    assertThat(result.firstErrorStep())
        .isEqualTo("deterministic_tool_check");
  }

  @Test
  void claimContextKeepsDependencyClosure() {
    AgentGuardPolicies.ClaimContextItem base =
        new AgentGuardPolicies.ClaimContextItem(
            "base", "base algebra fact", List.of(), true);
    AgentGuardPolicies.ClaimContextItem derived =
        new AgentGuardPolicies.ClaimContextItem(
            "derived",
            "target theorem from A",
            List.of("base"),
            true);

    assertThat(
            AgentGuardPolicies.selectClaimContext(
                List.of(base, derived), "target theorem", 10_000))
        .extracting(AgentGuardPolicies.ClaimContextItem::claimId)
        .containsExactly("base", "derived");
  }

  @Test
  void metaSelectedExecutionFailureCanEnterFinalAuditedRepair() {
    assertThat(
            AgentGuardPolicies.mayEnterFinalAuditedRepair(
                FailureLevel.EXECUTION,
                true,
                ActionKind.SYNTHESIZE,
                null,
                List.of("completeness")))
        .isTrue();
  }

  @Test
  void strategyFailureCannotEnterSynthesisEvenWhenMetaRequestsIt() {
    assertThat(
            AgentGuardPolicies.mayEnterFinalAuditedRepair(
                FailureLevel.STRATEGY,
                true,
                ActionKind.SYNTHESIZE,
                null,
                List.of("completeness")))
        .isFalse();
  }

  @Test
  void executionFailureRequiresExplicitMetaSynthesisApproval() {
    assertThat(
            AgentGuardPolicies.mayEnterFinalAuditedRepair(
                FailureLevel.EXECUTION,
                true,
                ActionKind.DEEPEN,
                null,
                List.of("completeness")))
        .isFalse();
  }

  @Test
  void executionFailureWithABrokenStepCannotEnterSynthesis() {
    assertThat(
            AgentGuardPolicies.mayEnterFinalAuditedRepair(
                FailureLevel.EXECUTION,
                true,
                ActionKind.SYNTHESIZE,
                "verified-step",
                List.of("deduction")))
        .isFalse();
  }
}
