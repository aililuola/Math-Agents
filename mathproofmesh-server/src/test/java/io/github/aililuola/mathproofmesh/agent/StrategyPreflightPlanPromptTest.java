package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyPreflightPlanPromptTest {
  @Test
  void promptMakesPlanNonAuthoritativeAndRegisteredContractOnly() {
    PromptBundle<StrategyPreflightPlan> prompt =
        new PromptFactory("English")
            .typedStage(
                "strategy_preflight_plan",
                StrategyPreflightPlan.class,
                Map.of(
                    "problem_hash", "problem-hash",
                    "strategy_id", "strategy-a",
                    "critical_claims", List.of("claim-a"),
                    "registered_computation_contracts", List.of("finite_graph_enumeration")));

    assertThat(prompt.user())
        .contains("already registered computation contract")
        .contains("Do not submit code")
        .contains("verified status")
        .contains("server scores")
        .contains("un-testable".replace("-", ""));
  }
}
