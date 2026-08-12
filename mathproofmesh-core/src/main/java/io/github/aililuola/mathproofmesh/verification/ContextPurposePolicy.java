package io.github.aililuola.mathproofmesh.verification;

/** Immutable field and budget policy for one context purpose. */
public record ContextPurposePolicy(
    double maxGlobalCharFraction,
    double relevanceWeight,
    double evidenceWeight,
    double confidenceWeight,
    double centralityWeight,
    boolean includeRawArtifactRefs,
    boolean includeReviewProvenance,
    boolean includeNormalizationConfidence) {

  public ContextPurposePolicy {
    if (maxGlobalCharFraction <= 0.0
        || maxGlobalCharFraction > 1.0
        || relevanceWeight < 0.0
        || evidenceWeight < 0.0
        || confidenceWeight < 0.0
        || centralityWeight < 0.0) {
      throw new IllegalArgumentException("context policy weights are invalid");
    }
  }

  public static ContextPurposePolicy forPurpose(ContextPurpose purpose) {
    return switch (java.util.Objects.requireNonNull(purpose, "purpose")) {
      case INSPIRATION ->
          new ContextPurposePolicy(0.20, 0.55, 0.20, 0.15, 0.10, false, false, false);
      case DELTA_VERIFICATION, ATTEMPT_VERIFICATION ->
          new ContextPurposePolicy(0.25, 0.45, 0.25, 0.20, 0.10, true, true, true);
      case FINAL_VERIFICATION ->
          new ContextPurposePolicy(0.35, 0.40, 0.30, 0.20, 0.10, true, true, true);
      case SYNTHESIS ->
          new ContextPurposePolicy(0.30, 0.40, 0.20, 0.10, 0.30, false, false, false);
      case BLIND_REVIEW ->
          new ContextPurposePolicy(0.45, 0.40, 0.30, 0.20, 0.10, false, true, true);
      case FINAL_REVISION ->
          new ContextPurposePolicy(0.30, 0.50, 0.25, 0.15, 0.10, false, true, true);
    };
  }

  public int clampChars(int globalMaxChars, int requestedMaxChars) {
    if (globalMaxChars <= 0 || requestedMaxChars <= 0) {
      return 0;
    }
    return Math.min(requestedMaxChars, Math.max(1, (int) (globalMaxChars * maxGlobalCharFraction)));
  }
}
