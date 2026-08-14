package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDelta;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotCompiler;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticPivotReviewPromptTest {
  @Test
  void reviewPromptDeniesClaimVerificationAuthority() {
    PivotDelta delta =
        new SemanticPivotCompiler()
            .compile(
                SemanticPivotServerTestFixtures.proposal(),
                SemanticPivotServerTestFixtures.obstructions());
    PromptBundle<SemanticPivotReviewBatch> prompt =
        new PromptFactory("English")
            .typedStage(
                "semantic_pivot_review",
                SemanticPivotReviewBatch.class,
                Map.of("compiled_pivot_delta", delta, "proposer_agent_id", "proposer"));

    assertThat(prompt.user())
        .contains("[STAGE:semantic_pivot_review]")
        .contains(delta.pivotId())
        .contains("does not verify any proposed mathematical claim")
        .contains("reviewer_agent_id")
        .contains("no_authority_escalation");
  }
}
