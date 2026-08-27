package io.github.aililuola.mathproofmesh.verification;

/** A verified delta may enter its own Fact gate even when a later attempt is incomplete. */
public final class ScopedClaimPromotionGate {
  private ScopedClaimPromotionGate() {}

  public static boolean canPromote(
      ClaimVerificationState claimState,
      boolean wholeAttemptComplete,
      DeltaReview review) {
    java.util.Objects.requireNonNull(claimState, "claimState");
    java.util.Objects.requireNonNull(review, "review");
    boolean independentlyVerified =
        claimState == ClaimVerificationState.INDEPENDENTLY_VERIFIED
            || claimState == ClaimVerificationState.FACT_CANDIDATE
            || claimState == ClaimVerificationState.FACT;
    return independentlyVerified
        && review.globalShareAllowed()
        && review.validationPassed()
        && !review.deltaId().isBlank()
        && (wholeAttemptComplete || review.scopedClaimOnly());
  }

  public record DeltaReview(
      String deltaId,
      boolean globalShareAllowed,
      boolean validationPassed,
      boolean scopedClaimOnly) {

    public DeltaReview {
      deltaId = deltaId == null ? "" : deltaId.trim();
    }
  }
}
