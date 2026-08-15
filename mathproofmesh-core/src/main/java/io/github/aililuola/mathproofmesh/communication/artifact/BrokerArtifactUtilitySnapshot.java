package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.Map;

public record BrokerArtifactUtilitySnapshot(
    Map<String, BrokerArtifactUtilityRecord> utilities, long version) {
  public BrokerArtifactUtilitySnapshot {
    utilities = BrokerArtifactValues.map(utilities);
    if (version < 0L) throw new IllegalArgumentException("version must be nonnegative");
  }
  public static BrokerArtifactUtilitySnapshot empty() {
    return new BrokerArtifactUtilitySnapshot(Map.of(), 0L);
  }
}
