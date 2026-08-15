package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BrokerArtifactUseLedger {
  private final Map<String, BrokerArtifactUseManifest> manifests = new LinkedHashMap<>();
  private final Map<String, BrokerArtifactLineageRecord> lineage = new LinkedHashMap<>();
  private long version;

  public synchronized BrokerArtifactUseManifest recordManifest(BrokerArtifactUseManifest manifest) {
    java.util.Objects.requireNonNull(manifest, "manifest");
    BrokerArtifactUseManifest existing = manifests.get(manifest.providerRequestId());
    if (existing != null) {
      if (!existing.equals(manifest)) {
        throw new IllegalStateException("BROKER_USE_MANIFEST_ID_COLLISION");
      }
      return existing;
    }
    manifests.put(manifest.providerRequestId(), manifest);
    version++;
    return manifest;
  }

  public synchronized Optional<BrokerArtifactUseManifest> manifest(String providerRequestId) {
    return Optional.ofNullable(manifests.get(providerRequestId));
  }

  public synchronized BrokerArtifactLineageRecord recordLineage(
      String deliveryId, BrokerArtifactUseClaim use, String providerRequestId) {
    String id = "broker-lineage-" + CanonicalJson.stableHash(
        List.of(deliveryId, use.artifactId(), use.useKind(), providerRequestId)).substring(0, 24);
    BrokerArtifactLineageRecord existing = lineage.get(id);
    if (existing != null) return existing;
    BrokerArtifactLineageRecord record = new BrokerArtifactLineageRecord(
        id, use.artifactId(), deliveryId, use.useKind(), use.referencedProofStepIds(),
        use.affectedClaimIds(), use.affectedObligationIds(), null, null, providerRequestId, false);
    lineage.put(id, record);
    version++;
    return record;
  }

  public synchronized Optional<BrokerArtifactLineageRecord> forDelivery(String deliveryId) {
    return lineage.values().stream().filter(value -> value.deliveryId().equals(deliveryId)).findFirst();
  }

  public synchronized void markVerified(String lineageId) {
    BrokerArtifactLineageRecord record = lineage.get(lineageId);
    if (record != null && !record.effectVerified()) {
      lineage.put(lineageId, record.verified());
      version++;
    }
  }

  public synchronized List<BrokerArtifactLineageRecord> records() {
    return List.copyOf(lineage.values());
  }

  public synchronized BrokerArtifactUseSnapshot snapshot() {
    return new BrokerArtifactUseSnapshot(manifests, lineage, version);
  }

  public synchronized void restore(BrokerArtifactUseSnapshot snapshot) {
    BrokerArtifactUseSnapshot safe = snapshot == null ? BrokerArtifactUseSnapshot.empty() : snapshot;
    manifests.clear(); manifests.putAll(safe.manifests());
    lineage.clear(); lineage.putAll(safe.lineage());
    version = safe.version();
  }
}
