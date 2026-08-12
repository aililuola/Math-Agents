package io.github.aililuola.mathproofmesh.verification;

public record ProofMutation(
    String mutationId,
    MutationKind kind,
    String originalStepId,
    String mutatedStatement,
    String expectedVerdict,
    String faultDescription) {

  public ProofMutation {
    mutationId = required(mutationId, "mutationId");
    kind = java.util.Objects.requireNonNull(kind, "kind");
    originalStepId = required(originalStepId, "originalStepId");
    mutatedStatement = required(mutatedStatement, "mutatedStatement");
    expectedVerdict = required(expectedVerdict, "expectedVerdict");
    faultDescription = required(faultDescription, "faultDescription");
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.trim();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
