package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
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
    assertThat(PromptCatalog.instruction("independent_exploration"))
        .contains("server-compiled mathematical sidecars")
        .doesNotContain("broker_messages", "FAILURE_RECORD", "REPAIR_REQUEST");
    assertThat(ContractObjectMapper.toTree(InitialExplorationTurn.class.getName())).isNotNull();
  }
}
