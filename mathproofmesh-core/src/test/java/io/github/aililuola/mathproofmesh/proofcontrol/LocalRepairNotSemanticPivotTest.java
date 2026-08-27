package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalRepairNotSemanticPivotTest {
  @Test
  void localRepairHasTypedIdentityAndNeverEntersPivotLedger() {
    LocalRepairPlan plan =
        new LocalRepairPlan(
            null,
            "strategy-source",
            "obligation-bridge",
            "Prove the missing bridge.",
            "Missing bridge lemma",
            "Try to falsify the bridge.",
            "Same object, target, and direction.");
    LocalRepairApplyReceipt receipt =
        new LocalRepairApplyReceipt(
            plan.repairId(), plan.sourceStrategyId(), "strategy-revision", plan.exactFocusedObligationId(), 4, true);
    assertThat(receipt.applied()).isTrue();
    assertThat(new SemanticPivotLedger().records()).isEmpty();
    assertThat(StrategyRevisionKind.LOCAL_REPAIR).isNotEqualTo(StrategyRevisionKind.SEMANTIC_PIVOT);
  }
}
