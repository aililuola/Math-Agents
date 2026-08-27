package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScopedClaimPromotionParityTest {

  @Test
  void accepted_delta_claim_enters_fact_gate_when_whole_attempt_is_incomplete() {
    ScopedClaimPromotionGate.DeltaReview accepted =
        new ScopedClaimPromotionGate.DeltaReview(
            "delta-local-verified", true, true, true);
    ScopedClaimPromotionGate.DeltaReview unrelatedRejected =
        new ScopedClaimPromotionGate.DeltaReview(
            "different-rejected-delta", false, false, true);

    assertThat(
            ScopedClaimPromotionGate.canPromote(
                ClaimVerificationState.INDEPENDENTLY_VERIFIED,
                false,
                accepted))
        .isTrue();
    assertThat(
            ScopedClaimPromotionGate.canPromote(
                ClaimVerificationState.INDEPENDENTLY_VERIFIED,
                false,
                unrelatedRejected))
        .isFalse();
  }
}
