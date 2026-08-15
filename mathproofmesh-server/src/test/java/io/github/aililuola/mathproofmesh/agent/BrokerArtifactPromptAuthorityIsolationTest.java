package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactPromptProjectionService;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import org.junit.jupiter.api.Test;

class BrokerArtifactPromptAuthorityIsolationTest {
  @Test
  void reviewedAndBoundedArtifactsCannotBeUsedAsUnrestrictedPremises() {
    assertThat(
            BrokerArtifactPromptProjectionService.allowedUses(
                BrokerArtifactType.REVIEWED_OBSTRUCTION))
        .doesNotContain(BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP);
    assertThat(
            BrokerArtifactPromptProjectionService.allowedUses(
                BrokerArtifactType.BOUNDED_OBSERVATION))
        .containsExactly(BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN);
  }
}
