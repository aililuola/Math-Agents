package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;

@FunctionalInterface
public interface ComputationCertificateVerifier {
  ComputationVerificationReceipt verify(
      ValidatedComputationRequest request,
      ComputationResultArtifact result,
      ComputationCertificateEnvelope certificate);
}
