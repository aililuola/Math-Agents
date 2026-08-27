package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationBatch;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClaimBlindAdjudicationPromptTest {
  @Test
  void blindAdjudicatorReceivesOnlyScrubbedProofPacket() {
    var prompt =
        new PromptFactory("English")
            .typedStage(
                "claim_blind_adjudication",
                ClaimBlindAdjudicationBatch.class,
                Map.of("blind_packet", Map.of("claim_id", "claim-1", "proof_hash", "hash")));
    assertThat(prompt.user())
        .contains("identity-scrubbed")
        .contains("FAIL_PROOF")
        .contains("Do not infer prior audit results")
        .doesNotContain("author-agent")
        .doesNotContain("repairer-agent");
  }
}
