package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationBatch;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClaimStatementFalsificationPromptTest {
  @Test
  void falsifierCannotClaimRefutationAuthority() {
    var prompt =
        new PromptFactory("English")
            .typedStage(
                "claim_statement_falsification",
                ClaimStatementFalsificationBatch.class,
                Map.of("frozen_claim", Map.of("claim_id", "claim-1", "statement", "P")));
    assertThat(prompt.user())
        .contains("COUNTEREXAMPLE_CANDIDATE")
        .contains("non-authoritative")
        .contains("must never claim verified refutation");
  }
}
