package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimMinimalRepairBatch;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClaimMinimalRepairPromptTest {
  @Test
  void repairPromptFreezesClaimSemanticsAndAuthority() {
    var prompt =
        new PromptFactory("English")
            .typedStage(
                "claim_minimal_repair",
                ClaimMinimalRepairBatch.class,
                Map.of("claim_semantic_hash", "semantic-hash", "audit_issue_ids", "issue-1"));
    assertThat(prompt.user())
        .contains("bounded proof-step patch")
        .contains("Preserve the frozen statement")
        .contains("Do not create a revision ID")
        .contains("unverified dependency");
  }
}
