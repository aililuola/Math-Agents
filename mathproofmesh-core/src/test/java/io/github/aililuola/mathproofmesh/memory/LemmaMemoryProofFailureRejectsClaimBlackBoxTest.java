package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LemmaMemoryProofFailureRejectsClaimBlackBoxTest {
  @Test
  void invalidProofDoesNotDeclareTheLinearAlgebraStatementFalse() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard claim =
        new ClaimCard(
            List.of("T is linear", "ker(T)={0}"),
            "injective-kernel-claim",
            "T is injective",
            "",
            "low",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0.9d,
            "claim-author",
            "attempt-linear",
            null,
            "If a linear map T has ker(T)={0}, then T is injective.",
            ClaimStatus.PROPOSED,
            List.of("local_lemma"),
            null);
    memory.addMany(List.of(claim));

    memory.applyClaimReviewDecision(
        claim.claimId(),
        new ClaimReviewDecision(
            claim.claimId(),
            VerificationVerdict.FAIL,
            0.99d,
            List.of(),
            true,
            true,
            true,
            true,
            false,
            List.of(),
            "The proof skips T(x-y)=0 and x-y in ker(T)."));

    ClaimStatus actual = memory.claims().getFirst().status();
    System.out.println("TRUE_STATEMENTS=1");
    System.out.println("PROOF_ERRORS=1");
    System.out.println("EXPECTED_REJECTED_CLAIMS=0");
    System.out.println("ACTUAL_REJECTED_CLAIMS=" + (actual == ClaimStatus.REJECTED ? 1 : 0));
    assertThat(actual).isEqualTo(ClaimStatus.UNCERTAIN);
  }
}
