package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Estimates a whole scheduler action, including its bounded verification and repair fan-out. */
public final class ActionCostEstimator {
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

  private final Profile profile;
  private final PricingSnapshot pricing;
  private final boolean strictPricing;

  public ActionCostEstimator(Profile profile, PricingSnapshot pricing) {
    this(profile, pricing, true);
  }

  public ActionCostEstimator(
      Profile profile, PricingSnapshot pricing, boolean strictPricing) {
    this.profile = Objects.requireNonNull(profile, "profile");
    this.pricing = Objects.requireNonNull(pricing, "pricing");
    this.strictPricing = strictPricing;
  }

  public BudgetResourceVector estimate(ActionKind action) {
    Objects.requireNonNull(action, "action");
    long calls = calls(action);
    long input = Math.multiplyExact(calls, profile.estimatedInputTokensPerCall());
    long output = outputTokens(action);
    long total = Math.addExact(input, output);
    return new BudgetResourceVector(calls, input, output, total, cost(input, output));
  }

  private long deltaVerificationCalls() {
    return profile.verifyEachDelta()
        ? Math.multiplyExact(
            profile.continuationSegments(), profile.deltaVerifierReplicas())
        : 0L;
  }

  private long calls(ActionKind action) {
    return switch (action) {
      case WIDEN ->
          1L
              + profile.widenPaths()
                  * (profile.continuationSegments()
                      + deltaVerificationCalls()
                      + profile.claimExtractionCalls()
                      + profile.postActionVerificationCalls())
              + profile.metaReviewCalls();
      case DEEPEN ->
          profile.continuationSegments()
              + deltaVerificationCalls()
              + profile.claimExtractionCalls()
              + profile.postActionVerificationCalls()
              + profile.metaReviewCalls();
      case VERIFY ->
          profile.structuralVerificationCalls()
              + profile.detailedVerifierReplicas()
              + profile.highRiskVerifierReplicas()
              + profile.verificationSafetyCalls();
      case REVISE ->
          1L + profile.structuralVerificationCalls() + profile.detailedVerifierReplicas();
      case SYNTHESIZE ->
          1L
              + profile.finalVerificationCalls()
              + profile.finalRevisionReserveCalls()
              + profile.finalAuditCalls();
      case STOP -> 0L;
      default -> 1L;
    };
  }

  private long outputTokens(ActionKind action) {
    long verificationCalls = deltaVerificationCalls();
    return switch (action) {
      case WIDEN ->
          Math.addExact(
              profile.plannerMaxOutputTokens(),
              Math.addExact(
                  Math.multiplyExact(
                      profile.widenPaths(),
                      Math.addExact(
                          Math.multiplyExact(
                              profile.continuationSegments(),
                              profile.continuationMaxOutputTokens()),
                          Math.addExact(
                              Math.multiplyExact(
                                  verificationCalls,
                                  profile.verificationMaxOutputTokens()),
                              Math.addExact(
                                  Math.multiplyExact(
                                      profile.claimExtractionCalls(),
                                      profile.claimExtractionMaxOutputTokens()),
                                  Math.multiplyExact(
                                      profile.postActionVerificationCalls(),
                                      profile.verificationMaxOutputTokens()))))),
                  Math.multiplyExact(
                      profile.metaReviewCalls(), profile.metaReviewMaxOutputTokens())));
      case DEEPEN ->
          Math.addExact(
              Math.multiplyExact(
                  profile.continuationSegments(), profile.continuationMaxOutputTokens()),
              Math.addExact(
                  Math.multiplyExact(
                      verificationCalls, profile.verificationMaxOutputTokens()),
                  Math.addExact(
                      Math.multiplyExact(
                          profile.claimExtractionCalls(),
                          profile.claimExtractionMaxOutputTokens()),
                      Math.addExact(
                          Math.multiplyExact(
                              profile.postActionVerificationCalls(),
                              profile.verificationMaxOutputTokens()),
                          Math.multiplyExact(
                              profile.metaReviewCalls(),
                              profile.metaReviewMaxOutputTokens())))));
      case VERIFY ->
          Math.multiplyExact(
              profile.structuralVerificationCalls()
                  + profile.detailedVerifierReplicas()
                  + profile.highRiskVerifierReplicas()
                  + profile.verificationSafetyCalls(),
              profile.verificationMaxOutputTokens());
      case REVISE ->
          Math.addExact(
              profile.revisionMaxOutputTokens(),
              Math.multiplyExact(
                  profile.structuralVerificationCalls() + profile.detailedVerifierReplicas(),
                  profile.verificationMaxOutputTokens()));
      case SYNTHESIZE ->
          Math.addExact(
              profile.synthesisMaxOutputTokens(),
              Math.addExact(
                  Math.multiplyExact(
                      profile.finalVerificationCalls(),
                      profile.verificationMaxOutputTokens()),
                  Math.addExact(
                      Math.multiplyExact(
                          profile.finalRevisionReserveCalls(),
                          profile.revisionMaxOutputTokens()),
                      Math.multiplyExact(
                          profile.finalAuditCalls(), profile.metaReviewMaxOutputTokens()))));
      case STOP -> 0L;
      default -> profile.continuationMaxOutputTokens();
    };
  }

  private BigDecimal cost(long input, long output) {
    if (pricing.billingMode() == PricingSnapshot.BillingMode.BILLING_EXEMPT) {
      return BigDecimal.ZERO;
    }
    if (pricing.billingMode() == PricingSnapshot.BillingMode.UNKNOWN) {
      if (strictPricing) {
        throw new IllegalStateException("UNPRICED_PROVIDER");
      }
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(input)
        .multiply(pricing.inputPerMillion())
        .add(BigDecimal.valueOf(output).multiply(pricing.outputPerMillion()))
        .divide(MILLION, 12, RoundingMode.CEILING)
        .stripTrailingZeros();
  }

  public record Profile(
      int widenPaths,
      int continuationSegments,
      boolean verifyEachDelta,
      int deltaVerifierReplicas,
      int claimExtractionCalls,
      int postActionVerificationCalls,
      int metaReviewCalls,
      int structuralVerificationCalls,
      int detailedVerifierReplicas,
      int highRiskVerifierReplicas,
      int verificationSafetyCalls,
      int finalVerificationCalls,
      int finalRevisionReserveCalls,
      int finalAuditCalls,
      long estimatedInputTokensPerCall,
      long plannerMaxOutputTokens,
      long continuationMaxOutputTokens,
      long claimExtractionMaxOutputTokens,
      long verificationMaxOutputTokens,
      long revisionMaxOutputTokens,
      long synthesisMaxOutputTokens,
      long metaReviewMaxOutputTokens) {

    public Profile(
        int widenPaths,
        int continuationSegments,
        boolean verifyEachDelta,
        int deltaVerifierReplicas,
        int claimExtractionCalls,
        int postActionVerificationCalls,
        int metaReviewCalls,
        int structuralVerificationCalls,
        int detailedVerifierReplicas,
        int highRiskVerifierReplicas,
        int verificationSafetyCalls,
        int finalVerificationCalls,
        int finalRevisionReserveCalls,
        int finalAuditCalls,
        long estimatedInputTokensPerCall,
        long maxOutputTokensPerCall) {
      this(
          widenPaths,
          continuationSegments,
          verifyEachDelta,
          deltaVerifierReplicas,
          claimExtractionCalls,
          postActionVerificationCalls,
          metaReviewCalls,
          structuralVerificationCalls,
          detailedVerifierReplicas,
          highRiskVerifierReplicas,
          verificationSafetyCalls,
          finalVerificationCalls,
          finalRevisionReserveCalls,
          finalAuditCalls,
          estimatedInputTokensPerCall,
          maxOutputTokensPerCall,
          maxOutputTokensPerCall,
          maxOutputTokensPerCall,
          maxOutputTokensPerCall,
          maxOutputTokensPerCall,
          maxOutputTokensPerCall,
          maxOutputTokensPerCall);
    }

    public Profile {
      if (widenPaths < 1
          || continuationSegments < 1
          || deltaVerifierReplicas < 0
          || claimExtractionCalls < 0
          || postActionVerificationCalls < 0
          || metaReviewCalls < 0
          || structuralVerificationCalls < 0
          || detailedVerifierReplicas < 0
          || highRiskVerifierReplicas < 0
          || verificationSafetyCalls < 0
          || finalVerificationCalls < 0
          || finalRevisionReserveCalls < 0
          || finalAuditCalls < 0
          || estimatedInputTokensPerCall < 0
          || plannerMaxOutputTokens < 0
          || continuationMaxOutputTokens < 0
          || claimExtractionMaxOutputTokens < 0
          || verificationMaxOutputTokens < 0
          || revisionMaxOutputTokens < 0
          || synthesisMaxOutputTokens < 0
          || metaReviewMaxOutputTokens < 0) {
        throw new IllegalArgumentException("invalid action cost profile");
      }
    }

    public static Profile defaults() {
      return new Profile(
          1, 1, true, 1, 1, 1, 0, 1, 1, 0, 1, 1, 1, 1, 2_000L, 8_000L);
    }
  }
}
