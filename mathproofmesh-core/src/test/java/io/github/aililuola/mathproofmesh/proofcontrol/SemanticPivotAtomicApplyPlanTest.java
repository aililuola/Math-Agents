package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticPivotAtomicApplyPlanTest {
  @Test
  void planExistsOnlyAfterAuditReviewAndExternalGatesPass() {
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    SemanticPivotController controller = new SemanticPivotController();
    SemanticPivotController.Preparation blocked =
        controller.prepare(
            delta,
            SemanticPivotTestFixtures.sourceSignature(),
            SemanticPivotTestFixtures.proposedSignature(),
            SemanticPivotTestFixtures.authority(),
            "proposer",
            SemanticPivotTestFixtures.acceptedReview(delta),
            0.9d,
            () -> List.of("CAPACITY_OR_QUOTA_BLOCK"));
    assertThat(blocked.admitted()).isFalse();
    assertThat(blocked.record().reviewDecision()).isNotNull();
    assertThat(blocked.record().status()).isEqualTo(PivotDeltaStatus.FAILED);

    PivotDeltaAudit failedAudit =
        new PivotDeltaAudit(
            delta.pivotId(),
            PivotDeltaStatus.DETERMINISTICALLY_REJECTED,
            SemanticPivotTestFixtures.sourceSignature(),
            SemanticPivotTestFixtures.proposedSignature(),
            List.of("ROOT_GOAL_MISMATCH"),
            java.util.Map.of());
    assertThatThrownBy(
            () ->
                new SemanticPivotApplyPlan(
                    null,
                    delta,
                    failedAudit,
                    SemanticPivotTestFixtures.acceptedReview(delta).decisions().getFirst(),
                    "proposer",
                    "reviewer",
                    List.of(),
                    delta.proposedStrategyId()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
