package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Content-addressed durable public results. Hidden reasoning is never accepted by this ledger. */
public final class ResearchResultLedger {
  private final Map<String, ResearchWorkResultArtifact> byWorkItem = new LinkedHashMap<>();
  private long version;

  public synchronized ResearchWorkResultArtifact store(ResearchWorkResultEnvelope result) {
    Objects.requireNonNull(result, "result");
    String artifactRef = "research-result://" + result.resultHash();
    ResearchWorkResultArtifact artifact = new ResearchWorkResultArtifact(artifactRef, result);
    ResearchWorkResultArtifact existing = byWorkItem.get(result.workItemId());
    if (existing != null) {
      if (!existing.envelope().resultHash().equals(result.resultHash())) {
        throw new IllegalStateException("durable result identity conflict: " + result.workItemId());
      }
      return existing;
    }
    byWorkItem.put(result.workItemId(), artifact);
    version++;
    return artifact;
  }

  public synchronized ResearchWorkResultArtifact require(String workItemId) {
    ResearchWorkResultArtifact artifact = byWorkItem.get(workItemId);
    if (artifact == null) {
      throw new IllegalArgumentException("unknown durable result: " + workItemId);
    }
    return artifact;
  }

  public synchronized ResearchResultSnapshot snapshot() {
    return new ResearchResultSnapshot(
        byWorkItem.values().stream()
            .sorted(Comparator.comparing(value -> value.envelope().workItemId()))
            .toList(),
        version);
  }

  public synchronized void restore(ResearchResultSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    byWorkItem.clear();
    for (ResearchWorkResultArtifact artifact : snapshot.artifacts()) {
      ResearchWorkResultArtifact prior =
          byWorkItem.put(artifact.envelope().workItemId(), artifact);
      if (prior != null) {
        throw new IllegalArgumentException("duplicate work result in snapshot");
      }
    }
    version = snapshot.version();
  }
}
