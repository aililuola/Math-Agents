package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerControlBoundaryPolicy;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BrokerArtifactContractsTest {
  @Test
  void modelOutputCanDeclareUseButCannotDeclareBrokerAuthority() {
    assertThat(
            Arrays.stream(InitialExplorationTurn.class.getRecordComponents())
                .map(component -> component.getName()))
        .contains("brokerArtifactUseManifest")
        .doesNotContain("brokerArtifactAuthority", "brokerArtifactId", "brokerSemanticHash");
    assertThat(BrokerArtifactEnvelope.class.getRecordComponents()).isNotEmpty();
    assertThat(new BrokerControlBoundaryPolicy().audit(MessageType.FAILURE_RECORD, "").allowed())
        .isFalse();
    assertThat(ContractObjectMapper.toTree(InitialExplorationTurn.class.getName())).isNotNull();
  }
}
