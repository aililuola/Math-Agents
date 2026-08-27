package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactEffectObservation;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactUtilityRecord;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopBrokerUtilityBaselineBlackBoxTest {
  @Test
  void proofDebtBaselineMustPrecedeDownstreamIntegration() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    BrokerArtifactEnvelope artifact = fixture.artifact("debt", "route-a");
    fixture.broker.publish(artifact, List.of(fixture.related("route-b", "debt")), 0, 8);
    var batch =
        fixture.broker.consumeForPrompt(
            "route-b", "debt-request", 0, 8, 2.0d, Set.of("target-debt"),
            Set.of(), Set.of(), "strategy-route-b", "target-debt");
    fixture.broker.acknowledge(
        "debt-request",
        fixture.use("debt-request", artifact, BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
        Set.of("step-use"));
    BrokerArtifactUtilityRecord utility =
        fixture.broker
            .verifyEffect(
                batch.deliveries().getFirst().deliveryId(),
                new BrokerArtifactEffectObservation(
                    Set.of("step-use"), Set.of(), Set.of(), Set.of("target-debt"), Set.of(),
                    "target-debt", null, null, null, false, 0.5d))
            .orElseThrow();

    System.out.println("ACTUAL_DEBT_REDUCTION=" + utility.proofDebtReduction());
    System.out.println("EXPECTED_DEBT_REDUCTION>0");
    assertThat(utility.proofDebtBefore()).isEqualTo(2.0d);
    assertThat(utility.proofDebtAfter()).isEqualTo(0.5d);
    assertThat(utility.proofDebtReduction()).isEqualTo(1.5d);
  }
}
