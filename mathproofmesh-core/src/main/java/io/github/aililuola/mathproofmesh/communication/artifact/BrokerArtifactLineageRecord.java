package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerArtifactLineageRecord(
    String lineageId,
    String artifactId,
    String deliveryId,
    BrokerArtifactUseKind useKind,
    List<String> downstreamProofStepIds,
    List<String> downstreamClaimIds,
    List<String> downstreamObligationIds,
    String pivotId,
    String repairId,
    String providerRequestId,
    boolean effectVerified) {
  public BrokerArtifactLineageRecord {
    lineageId = BrokerArtifactValues.required(lineageId, "lineageId");
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    deliveryId = BrokerArtifactValues.required(deliveryId, "deliveryId");
    useKind = java.util.Objects.requireNonNull(useKind, "useKind");
    downstreamProofStepIds = BrokerArtifactValues.list(downstreamProofStepIds);
    downstreamClaimIds = BrokerArtifactValues.list(downstreamClaimIds);
    downstreamObligationIds = BrokerArtifactValues.list(downstreamObligationIds);
    pivotId = BrokerArtifactValues.nullable(pivotId);
    repairId = BrokerArtifactValues.nullable(repairId);
    providerRequestId = BrokerArtifactValues.required(providerRequestId, "providerRequestId");
  }

  public BrokerArtifactLineageRecord verified() {
    return new BrokerArtifactLineageRecord(lineageId, artifactId, deliveryId, useKind,
        downstreamProofStepIds, downstreamClaimIds, downstreamObligationIds, pivotId, repairId,
        providerRequestId, true);
  }
}
