package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtTestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LemmaMemoryProofFailureDoesNotRejectTest {
  @Test
  void legacyProofFailureIsConservativelyUncertain() {
    LemmaMemory memory = new LemmaMemory();
    var claim = ClaimCourtTestFixtures.linearClaim();
    memory.addMany(List.of(claim));
    memory.applyClaimReviewDecision(
        claim.claimId(),
        new ClaimReviewDecision(
            claim.claimId(),
            VerificationVerdict.FAIL,
            1.0d,
            List.of(),
            true,
            true,
            true,
            true,
            false,
            List.of(),
            "invalid proof"));
    assertThat(memory.claims().getFirst().status()).isEqualTo(ClaimStatus.UNCERTAIN);
  }
}
