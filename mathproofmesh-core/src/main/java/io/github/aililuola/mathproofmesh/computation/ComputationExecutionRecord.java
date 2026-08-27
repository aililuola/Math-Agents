package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import java.util.List;

public record ComputationExecutionRecord(
    String executionId,
    String routeId,
    String claimId,
    String obligationId,
    String requestHash,
    String executionHash,
    String capabilityId,
    String capabilityVersion,
    ComputationBackendKind backend,
    ComputationExecutionStatus status,
    String requestArtifactRef,
    String resultArtifactRef,
    String certificateArtifactRef,
    String verificationReceiptRef,
    String outcomeApplicationReceiptRef,
    String authorityMutationReceiptRef,
    ComputationVerifiedAuthority authority,
    int attemptCount,
    int producerExecutions,
    int verifierExecutions,
    int authorityProjections,
    int cacheHits,
    double cpuSeconds,
    int createdRound,
    int lastUpdatedRound,
    String errorCode,
    List<ComputationExecutionAuditEvent> history,
    int version) {
  public ComputationExecutionRecord {
    executionId = required(executionId, "executionId");
    routeId = required(routeId, "routeId");
    claimId = normalize(claimId);
    obligationId = normalize(obligationId);
    requestHash = required(requestHash, "requestHash");
    executionHash = required(executionHash, "executionHash");
    capabilityId = required(capabilityId, "capabilityId");
    capabilityVersion = required(capabilityVersion, "capabilityVersion");
    if (backend == null || status == null || authority == null) {
      throw new IllegalArgumentException("backend, status, and authority are required");
    }
    requestArtifactRef = normalize(requestArtifactRef);
    resultArtifactRef = normalize(resultArtifactRef);
    certificateArtifactRef = normalize(certificateArtifactRef);
    verificationReceiptRef = normalize(verificationReceiptRef);
    outcomeApplicationReceiptRef = normalize(outcomeApplicationReceiptRef);
    authorityMutationReceiptRef = normalize(authorityMutationReceiptRef);
    if (attemptCount < 0
        || producerExecutions < 0
        || verifierExecutions < 0
        || authorityProjections < 0
        || cacheHits < 0
        || !Double.isFinite(cpuSeconds)
        || cpuSeconds < 0.0d
        || createdRound < 0
        || lastUpdatedRound < createdRound
        || version < 1) {
      throw new IllegalArgumentException("invalid computation execution counters");
    }
    errorCode = normalize(errorCode);
    history = history == null ? List.of() : List.copyOf(history);
    if (history.size() != version) {
      throw new IllegalArgumentException("execution history and version must agree");
    }
    if (status.ordinal() >= ComputationExecutionStatus.RESULT_DURABLE.ordinal()
        && status.ordinal() <= ComputationExecutionStatus.AUTHORITY_APPLIED.ordinal()
        && (resultArtifactRef.isEmpty() || certificateArtifactRef.isEmpty())) {
      throw new IllegalArgumentException("durable result state requires result and certificate artifacts");
    }
    if (status.ordinal() >= ComputationExecutionStatus.VERIFICATION_DURABLE.ordinal()
        && status.ordinal() <= ComputationExecutionStatus.AUTHORITY_APPLIED.ordinal()
        && verificationReceiptRef.isEmpty()) {
      throw new IllegalArgumentException("verified state requires a verification receipt");
    }
    if (status.ordinal() >= ComputationExecutionStatus.PROJECTION_READY.ordinal()
        && status.ordinal() <= ComputationExecutionStatus.AUTHORITY_APPLIED.ordinal()
        && outcomeApplicationReceiptRef.isEmpty()) {
      throw new IllegalArgumentException("projection state requires an outcome receipt");
    }
    if (status == ComputationExecutionStatus.AUTHORITY_MUTATION_DURABLE
        && authorityMutationReceiptRef.isEmpty()) {
      throw new IllegalArgumentException("applied authority state requires a mutation receipt");
    }
  }

  @Override
  public List<ComputationExecutionAuditEvent> history() {
    return List.copyOf(history);
  }

  private static String required(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }
}
