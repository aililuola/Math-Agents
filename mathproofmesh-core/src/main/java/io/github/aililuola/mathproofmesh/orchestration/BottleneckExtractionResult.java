package io.github.aililuola.mathproofmesh.orchestration;

import java.util.List;

/** Public-state diagnosis produced after a no-artifact failure. */
public record BottleneckExtractionResult(
    String diagnosticId,
    String checkpointId,
    String failureType,
    List<String> preservedVerifiedStepIds,
    List<String> relatedObligationIds,
    boolean privateReasoningRecovered,
    boolean reused) {
  public BottleneckExtractionResult {
    diagnosticId = required(diagnosticId, "diagnosticId");
    checkpointId = required(checkpointId, "checkpointId");
    failureType = required(failureType, "failureType");
    preservedVerifiedStepIds =
        preservedVerifiedStepIds == null ? List.of() : List.copyOf(preservedVerifiedStepIds);
    relatedObligationIds =
        relatedObligationIds == null ? List.of() : List.copyOf(relatedObligationIds);
    if (privateReasoningRecovered) {
      throw new IllegalArgumentException("private reasoning must never be reconstructed");
    }
  }

  @Override
  public List<String> preservedVerifiedStepIds() {
    return List.copyOf(preservedVerifiedStepIds);
  }

  @Override
  public List<String> relatedObligationIds() {
    return List.copyOf(relatedObligationIds);
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
