package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerVerifiedEffectType;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BrokerArtifactUtilityLedger {
  private final Map<String, BrokerArtifactUtilityRecord> utilities = new LinkedHashMap<>();
  private long version;

  public synchronized BrokerArtifactUtilityRecord record(
      BrokerArtifactLineageRecord lineage,
      BrokerDeliveryBaseline baseline,
      BrokerArtifactEffectObservation observation,
      BrokerArtifactEffectVerifier.Verification verification) {
    BrokerArtifactUtilityRecord existing = utilities.get(lineage.deliveryId());
    if (existing != null) return existing;
    if (!verification.verified()) throw new IllegalArgumentException("verified effect is required");
    String id = "broker-utility-" + CanonicalJson.stableHash(lineage.deliveryId()).substring(0, 24);
    boolean finalCitation = verification.effectTypes().contains(BrokerVerifiedEffectType.FINAL_PROOF_CITATION);
    double score = Math.min(1.0d,
        0.2d * verification.effectTypes().size()
            + Math.min(0.3d, verification.proofDebtReduction())
            + (finalCitation ? 0.3d : 0.0d));
    BrokerArtifactUtilityRecord record = new BrokerArtifactUtilityRecord(
        id, lineage.artifactId(), lineage.deliveryId(), lineage.lineageId(),
        verification.effectTypes(), verification.affectedDownstreamIds(),
        baseline.canonicalProofDebtBefore(), observation.canonicalProofDebtAfter(),
        verification.proofDebtReduction(), finalCitation, score, false);
    utilities.put(lineage.deliveryId(), record);
    version++;
    return record;
  }

  public synchronized Optional<BrokerArtifactUtilityRecord> forDelivery(String deliveryId) {
    return Optional.ofNullable(utilities.get(deliveryId));
  }

  public synchronized List<BrokerArtifactUtilityRecord> records() {
    return List.copyOf(utilities.values());
  }

  public synchronized void invalidateArtifact(String artifactId) {
    utilities.replaceAll((key, value) ->
        value.artifactId().equals(artifactId) && !value.invalidated() ? value.invalidate() : value);
    version++;
  }

  public synchronized BrokerArtifactUtilitySnapshot snapshot() {
    return new BrokerArtifactUtilitySnapshot(utilities, version);
  }

  public synchronized void restore(BrokerArtifactUtilitySnapshot snapshot) {
    BrokerArtifactUtilitySnapshot safe = snapshot == null ? BrokerArtifactUtilitySnapshot.empty() : snapshot;
    utilities.clear(); utilities.putAll(safe.utilities()); version = safe.version();
  }
}
