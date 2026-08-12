package io.github.aililuola.mathproofmesh.workflow;

import java.util.List;

/** Small workflow result; proof bodies remain in the database/artifact store. */
public record VerificationBundle(
    String runId,
    List<String> verifiedClaimIds,
    String finalArtifactRef,
    boolean blindFinalReviewPassed) {
  public VerificationBundle {
    runId = required(runId, "runId");
    verifiedClaimIds =
        verifiedClaimIds == null ? List.of() : List.copyOf(verifiedClaimIds);
    finalArtifactRef = required(finalArtifactRef, "finalArtifactRef");
  }

  @Override
  public List<String> verifiedClaimIds() {
    return List.copyOf(verifiedClaimIds);
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
