package io.github.aililuola.mathproofmesh.proofgraph;

public record DuplicateRouteMatch(
    String sourceRouteId,
    String targetRouteId,
    double similarity,
    String survivorRouteId,
    String reason) {

  public DuplicateRouteMatch {
    if (sourceRouteId == null
        || sourceRouteId.isBlank()
        || targetRouteId == null
        || targetRouteId.isBlank()
        || survivorRouteId == null
        || survivorRouteId.isBlank()) {
      throw new IllegalArgumentException("duplicate route identifiers are required");
    }
    if (similarity < 0.0 || similarity > 1.0) {
      throw new IllegalArgumentException("similarity must be in [0, 1]");
    }
    reason = reason == null ? "" : reason;
  }
}
