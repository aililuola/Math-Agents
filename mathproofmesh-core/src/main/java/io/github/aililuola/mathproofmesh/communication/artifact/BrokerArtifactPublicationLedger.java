package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BrokerArtifactPublicationLedger {
  private final Map<String, BrokerArtifactPublicationRecord> publications = new LinkedHashMap<>();
  private final Map<String, String> sourceRevisionIndex = new LinkedHashMap<>();
  private long version;

  public synchronized BrokerArtifactPublicationRecord publish(
      BrokerArtifactEnvelope artifact, List<String> targetRouteIds, int round) {
    String sourceRevision = artifact.sourceClaimRevisionId() == null
        ? "content:" + artifact.contentHash()
        : artifact.sourceClaimRevisionId();
    String existingId = sourceRevisionIndex.get(sourceRevision);
    if (existingId != null) return publications.get(existingId);
    String publicationId = "broker-publication-" + CanonicalJson.stableHash(
        List.of(artifact.artifactId(), sourceRevision)).substring(0, 24);
    BrokerArtifactPublicationRecord record = new BrokerArtifactPublicationRecord(
        publicationId, artifact.artifactId(), sourceRevision, round, targetRouteIds);
    publications.put(publicationId, record);
    sourceRevisionIndex.put(sourceRevision, publicationId);
    version++;
    return record;
  }

  public synchronized List<BrokerArtifactPublicationRecord> records() {
    return List.copyOf(publications.values());
  }

  public synchronized BrokerArtifactPublicationSnapshot snapshot() {
    return new BrokerArtifactPublicationSnapshot(publications, sourceRevisionIndex, version);
  }

  public synchronized void restore(BrokerArtifactPublicationSnapshot snapshot) {
    BrokerArtifactPublicationSnapshot safe = snapshot == null ? BrokerArtifactPublicationSnapshot.empty() : snapshot;
    publications.clear(); publications.putAll(safe.publications());
    sourceRevisionIndex.clear(); sourceRevisionIndex.putAll(safe.sourceRevisionIndex());
    version = safe.version();
  }
}
