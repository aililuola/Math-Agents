package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BrokerArtifactRegistry {
  private final Map<String, BrokerArtifactEnvelope> artifacts = new LinkedHashMap<>();
  private final Map<String, String> semanticIndex = new LinkedHashMap<>();
  private final Set<String> invalidated = new LinkedHashSet<>();
  private long version;

  public BrokerArtifactRegistry() {}

  public BrokerArtifactRegistry(BrokerArtifactRegistrySnapshot snapshot) {
    restore(snapshot);
  }

  public synchronized BrokerArtifactEnvelope admit(BrokerArtifactEnvelope artifact) {
    BrokerArtifactEnvelope sameId = artifacts.get(artifact.artifactId());
    if (sameId != null && !sameId.contentHash().equals(artifact.contentHash())) {
      throw new IllegalStateException("artifact ID already has different content");
    }
    String existingId = semanticIndex.get(artifact.semanticHash());
    if (existingId != null) return artifacts.get(existingId);
    artifacts.put(artifact.artifactId(), artifact);
    semanticIndex.put(artifact.semanticHash(), artifact.artifactId());
    version++;
    return artifact;
  }

  public synchronized Optional<BrokerArtifactEnvelope> find(String artifactId) {
    return Optional.ofNullable(artifacts.get(artifactId));
  }

  public synchronized List<BrokerArtifactEnvelope> activeArtifacts() {
    return artifacts.values().stream().filter(value -> !invalidated.contains(value.artifactId())).toList();
  }

  public synchronized boolean invalidate(String artifactId) {
    if (!artifacts.containsKey(artifactId)) throw new IllegalArgumentException("unknown artifact");
    boolean changed = invalidated.add(artifactId);
    if (changed) version++;
    return changed;
  }

  public synchronized boolean active(String artifactId) {
    return artifacts.containsKey(artifactId) && !invalidated.contains(artifactId);
  }

  public synchronized BrokerArtifactRegistrySnapshot snapshot() {
    return new BrokerArtifactRegistrySnapshot(artifacts, semanticIndex, invalidated, version);
  }

  public synchronized void restore(BrokerArtifactRegistrySnapshot snapshot) {
    BrokerArtifactRegistrySnapshot safe = snapshot == null ? BrokerArtifactRegistrySnapshot.empty() : snapshot;
    artifacts.clear(); artifacts.putAll(safe.artifacts());
    semanticIndex.clear(); semanticIndex.putAll(safe.semanticIndex());
    invalidated.clear(); invalidated.addAll(safe.invalidatedArtifactIds());
    version = safe.version();
  }
}
