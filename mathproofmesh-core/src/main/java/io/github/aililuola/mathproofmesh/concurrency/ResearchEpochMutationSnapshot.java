package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Map;

public record ResearchEpochMutationSnapshot(
    String authorityHash, Map<String, String> projectionHashes) {
  public ResearchEpochMutationSnapshot {
    authorityHash = authorityHash == null ? "" : authorityHash.strip();
    projectionHashes = projectionHashes == null ? Map.of() : Map.copyOf(projectionHashes);
  }

  public static ResearchEpochMutationSnapshot empty() {
    return new ResearchEpochMutationSnapshot("", Map.of());
  }
}
