package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.BrokerVerifiedEffectType;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerArtifactUtilityRecord(
    String utilityId,
    String artifactId,
    String deliveryId,
    String lineageId,
    List<BrokerVerifiedEffectType> verifiedEffectTypes,
    List<String> affectedDownstreamIds,
    double proofDebtBefore,
    double proofDebtAfter,
    double proofDebtReduction,
    boolean finalProofCitation,
    double utilityScore,
    boolean invalidated) {
  public BrokerArtifactUtilityRecord {
    utilityId = BrokerArtifactValues.required(utilityId, "utilityId");
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    deliveryId = BrokerArtifactValues.required(deliveryId, "deliveryId");
    lineageId = BrokerArtifactValues.required(lineageId, "lineageId");
    verifiedEffectTypes = BrokerArtifactValues.list(verifiedEffectTypes);
    affectedDownstreamIds = BrokerArtifactValues.list(affectedDownstreamIds);
    if (proofDebtBefore < 0.0d || proofDebtAfter < 0.0d || proofDebtReduction < 0.0d
        || utilityScore < 0.0d || utilityScore > 1.0d) {
      throw new IllegalArgumentException("utility values are invalid");
    }
  }

  public BrokerArtifactUtilityRecord invalidate() {
    return new BrokerArtifactUtilityRecord(utilityId, artifactId, deliveryId, lineageId,
        verifiedEffectTypes, affectedDownstreamIds, proofDebtBefore, proofDebtAfter,
        proofDebtReduction, finalProofCitation, utilityScore, true);
  }
}
