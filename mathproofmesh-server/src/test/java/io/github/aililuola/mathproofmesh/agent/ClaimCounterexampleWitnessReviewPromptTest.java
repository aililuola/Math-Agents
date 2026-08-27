package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationBatch;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClaimCounterexampleWitnessReviewPromptTest {
  @Test
  void witnessReviewRequiresExactReplayableBinding() {
    var prompt =
        new PromptFactory("English")
            .typedStage(
                "claim_counterexample_witness_review",
                ClaimStatementFalsificationBatch.class,
                Map.of("candidate_id", "candidate-1", "claim_hash", "claim-hash"));
    assertThat(prompt.user())
        .contains("frozen claim hash")
        .contains("ordered quantifiers")
        .contains("registered evidence")
        .contains("replayable exact evidence");
  }
}
