package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.MetaPivotEffect;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.MetaPivotStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetaPivotRequiresSemanticDeltaTest {
  @Test
  void proposalReferencesCannotMarkAPivotApplied() {
    MetaPivotController controller = new MetaPivotController();
    MetaPivotController.Pivot requested =
        controller.request("route-1", 2, List.of("representation_switch"));
    MetaPivotController.Pivot proposal =
        controller.recordProposal(
            requested.pivotId(),
            List.of("representation_switch"),
            List.of("proposal://one"),
            "proposal only");
    assertThat(proposal.status()).isEqualTo(MetaPivotStatus.EVALUATED);
    assertThat(proposal.outcome().effect()).isEqualTo(MetaPivotEffect.PROPOSAL_ONLY);

    MetaPivotController another = new MetaPivotController();
    MetaPivotController.Pivot admitted =
        another.request("route-2", 3, List.of("reverse_goal_analysis"));
    another.admit(admitted.pivotId(), true, "review");
    SemanticPivotApplyReceipt unapplied =
        new SemanticPivotApplyReceipt(
            null,
            "pivot-semantic",
            "delta-hash",
            "route-2",
            "source",
            "epoch",
            List.of(),
            List.of(),
            3,
            false);
    assertThatThrownBy(
            () ->
                another.execute(
                    admitted.pivotId(), List.of(), unapplied, List.of(), "not applied"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
