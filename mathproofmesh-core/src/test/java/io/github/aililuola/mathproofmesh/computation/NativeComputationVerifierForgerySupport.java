package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;

final class NativeComputationVerifierForgerySupport {
  private NativeComputationVerifierForgerySupport() {}

  static ComputationVerificationReceipt forgedCounterexample(
      ComputationMethod method, String arguments, ObjectNode counterexample) {
    return verify(
        ComputationFixtures.spec(method, arguments),
        ExperimentOutcome.COUNTEREXAMPLE_FOUND,
        EvidenceStrength.COUNTEREXAMPLE,
        ComputationJson.object(),
        counterexample,
        null);
  }

  static ComputationVerificationReceipt forgedCertificate(
      ComputationMethod method,
      String arguments,
      ObjectNode scope,
      ObjectNode certificate) {
    return verify(
        ComputationFixtures.spec(method, arguments),
        ExperimentOutcome.CERTIFIED,
        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
        scope,
        null,
        certificate);
  }

  private static ComputationVerificationReceipt verify(
      ExperimentSpec spec,
      ExperimentOutcome outcome,
      EvidenceStrength strength,
      ObjectNode scope,
      ObjectNode counterexample,
      ObjectNode certificate) {
    RegisteredComputationCapability capability =
        ComputationIssue010TestSupport.registry().capability(spec.method());
    ValidatedComputationRequest request =
        new ValidatedComputationRequest(
            spec, capability.descriptor(), null, "forged-" + spec.executionHash());
    ComputationResultArtifact result =
        new ComputationResultArtifact(
            spec.requestHash(),
            spec.executionHash(),
            outcome,
            strength,
            scope,
            counterexample,
            certificate,
            true,
            1,
            0.001d,
            capability.descriptor().producerId(),
            capability.descriptor().producerVersion(),
            "",
            null);
    return capability
        .verifier()
        .verify(request, result, ComputationCertificateFactory.create(request, result));
  }
}
