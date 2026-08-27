package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrokerArtifactPromptAuthorityIsolationTest {
  @Test
  void reviewedAndBoundedArtifactsCannotBeUsedAsUnrestrictedPremises() {
    assertThat(PromptCatalog.instruction("independent_exploration"))
        .contains(
            "REVIEWED_OBSTRUCTION artifacts cannot use PREMISE_IN_PROOF_STEP",
            "BOUNDED_OBSERVATION artifacts are limited to SUPPORTS_COMPUTATION_PLAN");
  }
}
