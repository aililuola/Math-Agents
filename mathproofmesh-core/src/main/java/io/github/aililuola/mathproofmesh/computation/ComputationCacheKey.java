package io.github.aililuola.mathproofmesh.computation;

public record ComputationCacheKey(
    String runId,
    String executionHash,
    String capabilityId,
    String capabilityVersion,
    String producerVersion,
    String verifierVersion,
    String inputSchemaHash,
    String runtimeFingerprintHash) {
  public ComputationCacheKey {
    runId = required(runId, "runId");
    executionHash = required(executionHash, "executionHash");
    capabilityId = required(capabilityId, "capabilityId");
    capabilityVersion = required(capabilityVersion, "capabilityVersion");
    producerVersion = required(producerVersion, "producerVersion");
    verifierVersion = required(verifierVersion, "verifierVersion");
    inputSchemaHash = required(inputSchemaHash, "inputSchemaHash");
    runtimeFingerprintHash = required(runtimeFingerprintHash, "runtimeFingerprintHash");
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
