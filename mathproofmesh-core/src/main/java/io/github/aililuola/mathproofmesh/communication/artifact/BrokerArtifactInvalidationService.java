package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BrokerArtifactInvalidationService {
  private final Map<String, BrokerArtifactInvalidationRecord> invalidations = new LinkedHashMap<>();
  private long version;

  public synchronized BrokerArtifactInvalidationRecord invalidate(
      String artifactId,
      String sourceAuthorityId,
      String reason,
      int round,
      List<String> deliveryIds,
      List<String> lineageIds) {
    BrokerArtifactInvalidationRecord existing = invalidations.get(artifactId);
    if (existing != null) return existing;
    String id = "broker-invalidation-" + CanonicalJson.stableHash(
        List.of(artifactId, sourceAuthorityId)).substring(0, 24);
    String task = lineageIds.isEmpty() ? null
        : "broker-revalidation-" + CanonicalJson.stableHash(lineageIds).substring(0, 24);
    BrokerArtifactInvalidationRecord record = new BrokerArtifactInvalidationRecord(
        id, artifactId, sourceAuthorityId, reason, round, deliveryIds, lineageIds, task);
    invalidations.put(artifactId, record); version++;
    return record;
  }

  public synchronized List<BrokerArtifactInvalidationRecord> records() {
    return List.copyOf(invalidations.values());
  }

  public synchronized BrokerArtifactInvalidationSnapshot snapshot() {
    return new BrokerArtifactInvalidationSnapshot(invalidations, version);
  }

  public synchronized void restore(BrokerArtifactInvalidationSnapshot snapshot) {
    BrokerArtifactInvalidationSnapshot safe = snapshot == null
        ? BrokerArtifactInvalidationSnapshot.empty() : snapshot;
    invalidations.clear(); invalidations.putAll(safe.invalidations()); version = safe.version();
  }
}
