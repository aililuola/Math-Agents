package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationDecision;
import io.github.aililuola.mathproofmesh.contract.StatementFalsificationDisposition;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimStatementNoCounterexampleBoundaryTest {
  @Test
  void noCounterexampleFoundLeavesStatementOpenWithoutVerificationAuthority() {
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(ClaimCourtTestFixtures.linearClaim());
    ClaimStatementAuthorityService.Result result =
        new ClaimStatementAuthorityService()
            .assess(
                frozen,
                new ClaimStatementFalsificationDecision(
                    frozen.claimId(),
                    StatementFalsificationDisposition.NO_COUNTEREXAMPLE_FOUND,
                    List.of(),
                    "No witness found"),
                new NegativeKnowledgeRegistry(),
                0,
                List.of());
    assertThat(result.assessment())
        .isEqualTo(ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION);
    assertThat(result.evidenceIds()).isEmpty();
    assertThat(result.detail()).contains("DOES_NOT_GRANT_AUTHORITY");
  }
}
