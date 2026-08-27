package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.MetaPivotController;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesktopMetaDirectiveRequiresPivotDeltaTest {
  @Test
  void materializedProposalReferenceRemainsProposalOnly() {
    MetaPivotController controller = new MetaPivotController();
    var intent = controller.request("route-1", 7, List.of("switch_representation"));
    var result =
        controller.recordProposal(
            intent.pivotId(),
            List.of("representation_switch"),
            List.of("inspiration://proposal"),
            "No typed semantic delta was applied.");
    assertThat(result.outcome().effect()).isEqualTo(ProofControlModels.MetaPivotEffect.PROPOSAL_ONLY);
    assertThat(result.status()).isEqualTo(ProofControlModels.MetaPivotStatus.EVALUATED);
  }
}
