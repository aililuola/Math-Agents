package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import org.junit.jupiter.api.Test;

class BrokerArtifactContractTest {
  @Test
  void typedEnvelopeRoundTripsWithoutLosingSemanticIdentity() {
    BrokerArtifactEnvelope source = BrokerArtifactTestFixtures.verifiedClaim();
    BrokerArtifactEnvelope restored =
        ContractObjectMapper.read(
            ContractObjectMapper.write(source), BrokerArtifactEnvelope.class);
    assertThat(restored).isEqualTo(source);
    assertThat(restored.semanticHash()).isNotEqualTo(restored.contentHash());
  }
}
