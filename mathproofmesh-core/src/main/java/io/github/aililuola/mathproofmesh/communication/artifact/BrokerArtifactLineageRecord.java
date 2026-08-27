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
    String computationPlanId,
    String providerRequestId,
    boolean effectVerified) {
  public BrokerArtifactLineageRecord(
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
    this(
        lineageId,
        artifactId,
        deliveryId,
        useKind,
        downstreamProofStepIds,
        downstreamClaimIds,
        downstreamObligationIds,
        pivotId,
        repairId,
        null,
        providerRequestId,
        effectVerified);
  }

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
    computationPlanId = BrokerArtifactValues.nullable(computationPlanId);
    providerRequestId = BrokerArtifactValues.required(providerRequestId, "providerRequestId");
  }

  public BrokerArtifactLineageRecord verified() {
    return new BrokerArtifactLineageRecord(lineageId, artifactId, deliveryId, useKind,
        downstreamProofStepIds, downstreamClaimIds, downstreamObligationIds, pivotId, repairId,
        computationPlanId, providerRequestId, true);
  }

  public BrokerArtifactLineageRecord bindEffectTarget(String effectId) {
    String required = BrokerArtifactValues.required(effectId, "effectId");
    return switch (useKind) {
      case TRIGGERS_LOCAL_REPAIR ->
          new BrokerArtifactLineageRecord(
              lineageId, artifactId, deliveryId, useKind, downstreamProofStepIds,
              downstreamClaimIds, downstreamObligationIds, pivotId, required,
              computationPlanId, providerRequestId, effectVerified);
      case TRIGGERS_SEMANTIC_PIVOT ->
          new BrokerArtifactLineageRecord(
              lineageId, artifactId, deliveryId, useKind, downstreamProofStepIds,
              downstreamClaimIds, downstreamObligationIds, required, repairId,
              computationPlanId, providerRequestId, effectVerified);
      case SUPPORTS_COMPUTATION_PLAN ->
          new BrokerArtifactLineageRecord(
              lineageId, artifactId, deliveryId, useKind, downstreamProofStepIds,
              downstreamClaimIds, downstreamObligationIds, pivotId, repairId, required,
              providerRequestId, effectVerified);
      default -> throw new IllegalStateException("use kind has no effect target identity");
    };
  }
}
