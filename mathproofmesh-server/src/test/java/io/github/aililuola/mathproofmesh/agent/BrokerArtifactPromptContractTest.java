package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrokerArtifactPromptContractTest {
  @Test
  void explorationPromptRequiresExplicitTypedUse() {
    assertThat(PromptCatalog.instruction("independent_exploration"))
        .contains(
            "merely receiving one does not mean it was used",
            "artifact_id",
            "broker_artifact_use_manifest",
            "REVIEWED_OPEN",
            "BOUNDED");
  }
}
