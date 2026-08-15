package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.Map;

public record BrokerArtifactInvalidationSnapshot(
    Map<String, BrokerArtifactInvalidationRecord> invalidations, long version) {
  public BrokerArtifactInvalidationSnapshot {
    invalidations = BrokerArtifactValues.map(invalidations);
    if (version < 0L) throw new IllegalArgumentException("version must be nonnegative");
  }
  public static BrokerArtifactInvalidationSnapshot empty() {
    return new BrokerArtifactInvalidationSnapshot(Map.of(), 0L);
  }
}
