package io.github.aililuola.mathproofmesh.computation;

public record ComputationExecutionState(
    ComputationCapabilitySnapshot capabilities,
    ComputationExecutionSnapshot executions,
    ComputationArtifactSnapshot artifacts,
    ComputationVerificationSnapshot verifications,
    ComputationOutcomeReceiptSnapshot outcomeReceipts) {
  public ComputationExecutionState {
    capabilities =
        capabilities == null ? ComputationCapabilitySnapshot.empty() : capabilities;
    executions = executions == null ? ComputationExecutionSnapshot.empty() : executions;
    artifacts = artifacts == null ? ComputationArtifactSnapshot.empty() : artifacts;
    verifications =
        verifications == null ? ComputationVerificationSnapshot.empty() : verifications;
    outcomeReceipts =
        outcomeReceipts == null ? ComputationOutcomeReceiptSnapshot.empty() : outcomeReceipts;
  }

  public static ComputationExecutionState empty() {
    return new ComputationExecutionState(null, null, null, null, null);
  }
}
