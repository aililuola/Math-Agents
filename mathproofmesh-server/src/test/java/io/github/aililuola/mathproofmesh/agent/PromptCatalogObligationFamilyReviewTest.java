package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptCatalogObligationFamilyReviewTest {
  @Test
  void familyReviewPromptExplicitlyDeniesMathematicalAuthority() {
    assertThat(PromptCatalog.instruction("obligation_family_review"))
        .contains("scheduling")
        .contains("never establishes mathematical equivalence")
        .contains("cannot close or refute");
  }
}
