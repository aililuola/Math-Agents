package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrokerArtifactFinalCitationContractTest {
  @Test
  void finalCitationIsReservedForVerifiedMathematicalAuthority() {
    assertThat(PromptCatalog.instruction("independent_exploration"))
        .contains(
            "Only VERIFIED_CLAIM and FORMAL_CERTIFICATE artifacts may use CITED_IN_FINAL_PROOF",
            "REVIEWED_OBSTRUCTION artifacts cannot use PREMISE_IN_PROOF_STEP or CITED_IN_FINAL_PROOF");
  }
}
