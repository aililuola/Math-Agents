package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.MetaPivotController;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Captures the old material-reference-is-application defect through the production controller. */
final class DesktopMetaPivotNoDeltaBlackBoxTest {
  @Test
  void proposalReferenceWithoutSemanticDeltaCannotMarkPivotApplied() {
    MetaPivotController controller = new MetaPivotController();
    MetaPivotController.Pivot requested =
        controller.request("route-1", 0, List.of("switch_representation"));
    MetaPivotController.Pivot admitted =
        controller.admit(requested.pivotId(), true, "directive-review-1");
    MetaPivotController.Pivot result =
        controller.execute(
            admitted.pivotId(),
            List.of("switch_representation"),
            List.of("inspiration-proposal-1"),
            List.of(),
            "proposal changed only title, reason, and core idea wording");

    int applied = result.status() == ProofControlModels.MetaPivotStatus.APPLIED ? 1 : 0;
    System.out.println("MATERIAL_PROPOSALS=1");
    System.out.println("SEMANTIC_DELTAS=0");
    System.out.println("PIVOTS_MARKED_APPLIED=" + applied);
    System.out.println("EXPECTED_PIVOTS_MARKED_APPLIED=0");

    assertThat(result.status()).isNotEqualTo(ProofControlModels.MetaPivotStatus.APPLIED);
  }
}
