package io.github.aililuola.mathproofmesh.computation;

public record ComputationArtifactRecord(
    String executionId,
    String reference,
    String contentHash,
    ComputationArtifactKind kind,
    String mediaType,
    int byteLength) {
  public ComputationArtifactRecord {
    executionId = required(executionId, "executionId");
    reference = required(reference, "reference");
    contentHash = required(contentHash, "contentHash");
    if (!contentHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("contentHash must be SHA-256");
    }
    if (kind == null) {
      throw new IllegalArgumentException("kind is required");
    }
    mediaType = required(mediaType, "mediaType");
    if (byteLength < 0) {
      throw new IllegalArgumentException("byteLength must be nonnegative");
    }
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
