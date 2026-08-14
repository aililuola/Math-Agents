package io.github.aililuola.mathproofmesh.proofgraph;

public record CanonicalSchedulingTransitionResult(
    CanonicalSchedulingTransitionCode code,
    String canonicalTargetId,
    String obligationId,
    CanonicalObligationSchedulingState schedulingState,
    String reason) {

  public CanonicalSchedulingTransitionResult {
    code = java.util.Objects.requireNonNull(code, "code");
    canonicalTargetId = normalize(canonicalTargetId);
    obligationId = normalize(obligationId);
    schedulingState =
        schedulingState == null ? CanonicalObligationSchedulingState.RETIRED : schedulingState;
    reason = normalize(reason);
  }

  public boolean transitioned() {
    return code == CanonicalSchedulingTransitionCode.REACTIVATED
        || code == CanonicalSchedulingTransitionCode.RETIRED;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }
}
