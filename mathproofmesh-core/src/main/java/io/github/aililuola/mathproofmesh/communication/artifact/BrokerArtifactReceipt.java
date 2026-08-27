package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerArtifactReceipt(
    String receiptId,
    String deliveryId,
    String artifactId,
    String routeId,
    String providerRequestId,
    BrokerArtifactReceiptStatus status,
    BrokerArtifactUseKind useKind,
    List<String> referencedProofStepIds,
    List<String> affectedClaimIds,
    List<String> affectedObligationIds,
    String decisionCode,
    long version) {
  public BrokerArtifactReceipt {
    receiptId = BrokerArtifactValues.required(receiptId, "receiptId");
    deliveryId = BrokerArtifactValues.required(deliveryId, "deliveryId");
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    routeId = BrokerArtifactValues.required(routeId, "routeId");
    providerRequestId = BrokerArtifactValues.required(providerRequestId, "providerRequestId");
    status = java.util.Objects.requireNonNull(status, "status");
    referencedProofStepIds = BrokerArtifactValues.list(referencedProofStepIds);
    affectedClaimIds = BrokerArtifactValues.list(affectedClaimIds);
    affectedObligationIds = BrokerArtifactValues.list(affectedObligationIds);
    decisionCode = BrokerArtifactValues.required(decisionCode, "decisionCode");
    if (version < 0L) throw new IllegalArgumentException("version must be nonnegative");
  }

  public BrokerArtifactReceipt transition(BrokerArtifactReceiptStatus next, String code) {
    return new BrokerArtifactReceipt(receiptId, deliveryId, artifactId, routeId, providerRequestId,
        next, useKind, referencedProofStepIds, affectedClaimIds, affectedObligationIds, code,
        version + 1L);
  }
}
