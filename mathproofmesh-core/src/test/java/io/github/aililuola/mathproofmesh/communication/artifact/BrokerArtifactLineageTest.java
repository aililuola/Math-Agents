package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactLineageTest {
  @Test
  void validatedManifestCreatesExactDownstreamLineageOnly() {
    var scenario = BrokerArtifactTestFixtures.delivered(2.0d);
    scenario
        .broker()
        .acknowledge(
            "provider-request-1",
            BrokerArtifactTestFixtures.useManifest(
                scenario.artifact(), BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
            Set.of("downstream-step"));
    assertThat(scenario.broker().lineage()).singleElement()
        .satisfies(
            lineage -> {
              assertThat(lineage.downstreamProofStepIds()).containsExactly("downstream-step");
              assertThat(lineage.downstreamClaimIds()).containsExactly("downstream-claim");
              assertThat(lineage.providerRequestId()).isEqualTo("provider-request-1");
            });
  }
}
