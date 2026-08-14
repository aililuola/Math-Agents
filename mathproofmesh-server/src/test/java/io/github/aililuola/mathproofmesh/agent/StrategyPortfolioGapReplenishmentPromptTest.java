package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategySet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyPortfolioGapReplenishmentPromptTest {
  @Test
  void replenishmentPromptCarriesStructuralGapsAndForbidsRepeats() {
    PromptBundle<StrategySet> prompt =
        new PromptFactory("English")
            .typedStage(
                "strategy_generation",
                StrategySet.class,
                Map.of(
                    "generation_mode", "portfolio_gap_replenishment",
                    "selected_hard_mechanism_signatures", List.of("signature-a"),
                    "rejected_required_claim_keys", List.of("claim-refuted"),
                    "missing_soft_mechanism_profiles", List.of("CONTRADICTION"),
                    "unresolved_common_mode_groups", List.of("claim-u"),
                    "forbidden_structural_signatures", List.of("signature-a"),
                    "strategies_requested", 2));

    assertThat(prompt.user())
        .contains("portfolio_gap_replenishment")
        .contains("selected_hard_mechanism_signatures")
        .contains("rejected_required_claim_keys")
        .contains("never repeat a forbidden signature");
  }
}
