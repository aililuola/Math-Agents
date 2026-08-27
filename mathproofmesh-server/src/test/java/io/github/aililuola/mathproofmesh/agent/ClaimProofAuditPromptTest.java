package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditBatch;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClaimProofAuditPromptTest {
  @Test
  void auditorMustBindIssuesAndCannotDeclareStatementFalse() {
    var prompt =
        new PromptFactory("English")
            .typedStage(
                "claim_proof_audit",
                ClaimProofAuditBatch.class,
                Map.of("frozen_claim", "P", "proof_revision", "revision-1"));
    assertThat(prompt.user())
        .contains("exact step ID")
        .contains("Proof invalidity does not establish")
        .contains("no refutation or verification authority");
  }
}
