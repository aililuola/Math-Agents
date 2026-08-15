package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactUtilityAttributionTest {
  @Test
  void utilityUsesPromptTimeDebtBaselineAndPostIntegrationState() {
    var scenario = BrokerArtifactTestFixtures.delivered(2.0d);
    var receipt =
        scenario
            .broker()
            .acknowledge(
                "provider-request-1",
                BrokerArtifactTestFixtures.useManifest(
                    scenario.artifact(), BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
                Set.of("downstream-step"))
            .getFirst();
    var utility =
        scenario
            .broker()
            .verifyEffect(
                receipt.deliveryId(),
                new BrokerArtifactEffectObservation(
                    Set.of("downstream-step"),
                    Set.of(),
                    Set.of(),
                    Set.of("target-tree"),
                    Set.of(),
                    "target-tree",
                    null,
                    null,
                    null,
                    false,
                    0.5d))
            .orElseThrow();
    assertThat(utility.proofDebtBefore()).isEqualTo(2.0d);
    assertThat(utility.proofDebtAfter()).isEqualTo(0.5d);
    assertThat(utility.proofDebtReduction()).isEqualTo(1.5d);
  }
}
