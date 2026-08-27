package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrokerArtifactUseManifestContractsTest {
  @Test
  void duplicateArtifactUseClaimsAreRejectedAtTheContractBoundary() {
    BrokerArtifactUseClaim use =
        new BrokerArtifactUseClaim(
            "artifact-1",
            BrokerArtifactUseKind.SUPPORTS_CLAIM,
            List.of(),
            List.of("claim-1"),
            List.of(),
            "supports the exact claim");
    assertThatThrownBy(() -> new BrokerArtifactUseManifest("request-1", List.of(use, use)))
        .isInstanceOf(ContractValidationException.class)
        .hasMessageContaining("duplicate artifact use claim");
  }
}
