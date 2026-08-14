package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategySet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenericStrategyGenerationPolicyTest {
  @Test
  void defaultStrategyPromptIsDomainNeutralAndMechanismAware() {
    String prompt =
        new PromptFactory("English")
            .typedStage("strategy_generation", StrategySet.class, Map.of("problem_hash", "hash"))
            .user();

    assertThat(prompt)
        .contains("mathematical objects")
        .contains("dependency DAGs")
        .contains("REQUIRED or SUPPORTING")
        .contains("falsification plan")
        .doesNotContain(
            "bounded gaps",
            "finite-state periodicity",
            "prime support",
            "hitting sets",
            "translation periodicity");
  }
}
