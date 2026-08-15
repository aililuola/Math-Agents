package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import java.util.Map;

public record BrokerArtifactUseSnapshot(
    Map<String, BrokerArtifactUseManifest> manifests,
    Map<String, BrokerArtifactLineageRecord> lineage,
    long version) {
  public BrokerArtifactUseSnapshot {
    manifests = BrokerArtifactValues.map(manifests);
    lineage = BrokerArtifactValues.map(lineage);
    if (version < 0L) throw new IllegalArgumentException("version must be nonnegative");
  }
  public static BrokerArtifactUseSnapshot empty() {
    return new BrokerArtifactUseSnapshot(Map.of(), Map.of(), 0L);
  }
}
