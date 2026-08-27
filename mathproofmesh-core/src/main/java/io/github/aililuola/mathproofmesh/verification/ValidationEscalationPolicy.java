package io.github.aililuola.mathproofmesh.verification;

public record ValidationEscalationPolicy(
    boolean enabled,
    boolean deterministicChecksFirst,
    boolean blindSameModelReview,
    boolean adversarialPromptReview,
    boolean crossProviderReview,
    boolean toolOrFormalCheckOnHighRisk,
    double highRiskThreshold,
    boolean escalateOnReviewerDisagreement,
    boolean escalateBeforeFactPromotion,
    boolean escalateFinalProof) {

  public ValidationEscalationPolicy {
    if (!Double.isFinite(highRiskThreshold)
        || highRiskThreshold < 0.0
        || highRiskThreshold > 1.0) {
      throw new IllegalArgumentException("high-risk threshold is invalid");
    }
  }

  public static ValidationEscalationPolicy defaults() {
    return new ValidationEscalationPolicy(
        true, true, true, true, false, true, 0.70, true, true, true);
  }
}
