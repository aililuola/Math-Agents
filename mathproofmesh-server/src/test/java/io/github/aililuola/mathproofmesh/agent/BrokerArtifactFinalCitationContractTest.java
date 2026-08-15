package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactPromptProjectionService;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import org.junit.jupiter.api.Test;

class BrokerArtifactFinalCitationContractTest {
  @Test
  void finalCitationIsReservedForVerifiedMathematicalAuthority() {
    assertThat(
            BrokerArtifactPromptProjectionService.allowedUses(BrokerArtifactType.VERIFIED_CLAIM))
        .contains(BrokerArtifactUseKind.CITED_IN_FINAL_PROOF);
    assertThat(
            BrokerArtifactPromptProjectionService.allowedUses(
                BrokerArtifactType.FORMAL_CERTIFICATE))
        .contains(BrokerArtifactUseKind.CITED_IN_FINAL_PROOF);
    assertThat(
            BrokerArtifactPromptProjectionService.allowedUses(
                BrokerArtifactType.REVIEWED_OBSTRUCTION))
        .doesNotContain(BrokerArtifactUseKind.CITED_IN_FINAL_PROOF);
  }
}
