package io.github.aililuola.mathproofmesh.orchestration;

/** Certified local pivot; time expiry alone cannot create one. */
public record BottleneckPivotRecord(
    String routeId,
    String bottleneckId,
    String priorSignature,
    String replacementSignature,
    boolean basedOnVerifiedEvidence,
    String reason) {
  public BottleneckPivotRecord {
    routeId = required(routeId, "routeId");
    bottleneckId = required(bottleneckId, "bottleneckId");
    priorSignature = required(priorSignature, "priorSignature");
    replacementSignature = required(replacementSignature, "replacementSignature");
    reason = required(reason, "reason");
    if (!basedOnVerifiedEvidence) {
      throw new IllegalArgumentException("bottleneck pivots require certified evidence");
    }
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
