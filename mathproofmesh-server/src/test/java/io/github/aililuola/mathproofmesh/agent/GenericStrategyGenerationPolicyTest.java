package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.strategydiversity.GenericStrategyGenerationPolicy;
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

  @Test
  void typedReferenceContractSeparatesOperationAndClaimBindingSelectors() {
    String prompt =
        new PromptFactory("English")
            .typedStage(
                "strategy_generation",
                StrategySet.class,
                Map.of(
                    "problem_hash",
                    "hash",
                    "migration_parity_requirements",
                    new GenericStrategyGenerationPolicy().guidance()))
            .user();

    assertThat(prompt)
        .contains("mechanism_operation_selectors")
        .contains("@roots", "@direct_targets", "@all_intermediates", "@main_goal")
        .contains("critical_claim_node_selector")
        .contains("@claim")
        .contains("claim_local_assumption_selectors")
        .contains("Never invent blueprint node IDs")
        .contains("Never use @all_intermediates in local_assumption_node_ids")
        .contains("Do not repeat root assumptions, quantifiers, or variable bindings");
  }
}
