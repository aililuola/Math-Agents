package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.Map;

public record BrokerArtifactPublicationSnapshot(
    Map<String, BrokerArtifactPublicationRecord> publications,
    Map<String, String> sourceRevisionIndex,
    long version) {
  public BrokerArtifactPublicationSnapshot {
    publications = BrokerArtifactValues.map(publications);
    sourceRevisionIndex = BrokerArtifactValues.map(sourceRevisionIndex);
    if (version < 0L) throw new IllegalArgumentException("version must be nonnegative");
  }
  public static BrokerArtifactPublicationSnapshot empty() {
    return new BrokerArtifactPublicationSnapshot(Map.of(), Map.of(), 0L);
  }
}
