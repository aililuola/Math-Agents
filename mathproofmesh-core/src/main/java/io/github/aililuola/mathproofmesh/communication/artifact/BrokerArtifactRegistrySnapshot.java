package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import java.util.Map;
import java.util.Set;

public record BrokerArtifactRegistrySnapshot(
    Map<String, BrokerArtifactEnvelope> artifacts,
    Map<String, String> semanticIndex,
    Set<String> invalidatedArtifactIds,
    long version) {
  public BrokerArtifactRegistrySnapshot {
    artifacts = BrokerArtifactValues.map(artifacts);
    semanticIndex = BrokerArtifactValues.map(semanticIndex);
    invalidatedArtifactIds = BrokerArtifactValues.set(invalidatedArtifactIds);
    if (version < 0L) throw new IllegalArgumentException("version must be nonnegative");
  }
  public static BrokerArtifactRegistrySnapshot empty() {
    return new BrokerArtifactRegistrySnapshot(Map.of(), Map.of(), Set.of(), 0L);
  }
}
