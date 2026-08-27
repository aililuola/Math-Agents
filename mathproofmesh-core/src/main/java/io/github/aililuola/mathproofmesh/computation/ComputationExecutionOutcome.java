package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationOutcomeApplicationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;

public record ComputationExecutionOutcome(
    String executionId,
    ExperimentResult result,
    ComputationCertificateEnvelope certificate,
    ComputationVerificationReceipt verificationReceipt,
    ComputationOutcomeApplicationReceipt applicationReceipt,
    ComputationArtifactBundle artifacts,
    ComputationEvidenceGate.EvidenceAuthority authority,
    boolean cacheHit) {}
