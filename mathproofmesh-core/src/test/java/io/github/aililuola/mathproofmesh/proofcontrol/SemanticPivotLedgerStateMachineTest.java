package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SemanticPivotLedgerStateMachineTest {
  @Test
  void validLifecycleAppliesExactlyOnceAndRestores() {
    SemanticPivotController controller = new SemanticPivotController();
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    SemanticPivotController.Preparation prepared =
        controller.prepare(
            delta,
            SemanticPivotTestFixtures.sourceSignature(),
            SemanticPivotTestFixtures.proposedSignature(),
            SemanticPivotTestFixtures.authority(),
            "proposer",
            SemanticPivotTestFixtures.acceptedReview(delta),
            0.9d);
    SemanticPivotApplyReceipt receipt =
        SemanticPivotApplyReceipt.applied(
            delta, java.util.List.of(SemanticPivotTestFixtures.NEW_OBLIGATION), java.util.List.of("task-1"), 4);
    SemanticPivotRecord first = controller.apply(prepared.plan(), ignored -> receipt);
    SemanticPivotRecord duplicate = controller.apply(prepared.plan(), ignored -> receipt);

    assertThat(first.status()).isEqualTo(PivotDeltaStatus.APPLIED);
    assertThat(duplicate).isEqualTo(first);
    assertThat(controller.ledger().records()).hasSize(1);

    SemanticPivotLedger restored = new SemanticPivotLedger();
    restored.restore(controller.ledger().snapshot());
    assertThat(restored.stableHash()).isEqualTo(controller.ledger().stableHash());
  }
}
