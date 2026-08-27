package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerVerifiedEffectType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactEffectVerifierTest {
  @Test
  void modelSummaryAloneCannotVerifyAnEffect() {
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
    assertThat(
            scenario
                .broker()
                .verifyEffect(
                    receipt.deliveryId(),
                    new BrokerArtifactEffectObservation(
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        "target-tree",
                        null,
                        null,
                        null,
                        false,
                        2.0d)))
        .isEmpty();
    var verified =
        scenario
            .broker()
            .verifyEffect(
                receipt.deliveryId(),
                new BrokerArtifactEffectObservation(
                    Set.of("downstream-step"),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    "target-tree",
                    null,
                    null,
                    null,
                    false,
                    1.0d));
    assertThat(verified).isPresent();
    assertThat(verified.orElseThrow().verifiedEffectTypes())
        .contains(BrokerVerifiedEffectType.COMMITTED_STEP_REUSE);
  }
}
