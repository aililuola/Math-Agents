package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationCertificateType;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;

final class ComputationCertificateFactory {
  private ComputationCertificateFactory() {}

  static ComputationCertificateEnvelope create(
      ValidatedComputationRequest request, ComputationResultArtifact result) {
    ObjectNode witness =
        result.counterexample() != null
            ? result.counterexample()
            : result.certificate() != null ? result.certificate() : result.scope();
    int expected =
        result.scope().path("complete_domain").asBoolean(false)
            ? result.casesChecked()
            : Math.max(0, result.scope().path("cases_expected").asInt(result.casesChecked()));
    return new ComputationCertificateEnvelope(
        result.requestHash(),
        result.executionHash(),
        request.capability().capabilityId(),
        request.capability().capabilityVersion(),
        CanonicalJson.stableHash(result.scope()),
        CanonicalJson.stableHash(request.spec().domains()),
        result.artifactHash(),
        type(request.spec().method(), result.outcome(), result.evidenceStrength()),
        expected,
        result.casesChecked(),
        witness,
        CanonicalJson.stableHash(
            java.util.Map.of(
                "scope", result.scope(),
                "cases_expected", expected,
                "cases_checked", result.casesChecked())),
        result.producerId(),
        result.producerVersion(),
        null);
  }

  private static ComputationCertificateType type(
      ComputationMethod method,
      ExperimentOutcome outcome,
      EvidenceStrength strength) {
    if (outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND) {
      return ComputationCertificateType.EXACT_WITNESS;
    }
    if (method == ComputationMethod.GRAPH_CERTIFICATE) {
      return ComputationCertificateType.GRAPH_CERTIFICATE;
    }
    if (method == ComputationMethod.EXACT_LINEAR_ALGEBRA) {
      return ComputationCertificateType.LINEAR_ALGEBRA_CERTIFICATE;
    }
    if (method == ComputationMethod.FINITE_SET_MAP_CHECK) {
      return ComputationCertificateType.SET_MAP_CERTIFICATE;
    }
    if (method == ComputationMethod.HYPERGRAPH_TRANSVERSAL) {
      return ComputationCertificateType.HYPERGRAPH_TRANSVERSAL_CERTIFICATE;
    }
    if (method == ComputationMethod.LEAN_CHECK
        && strength == EvidenceStrength.FORMAL_CERTIFICATE) {
      return ComputationCertificateType.FORMAL_KERNEL_CERTIFICATE;
    }
    if (outcome == ExperimentOutcome.CERTIFIED) {
      return strength == EvidenceStrength.EXHAUSTIVE_CERTIFICATE
          ? ComputationCertificateType.FINITE_EXHAUSTIVE_COVERAGE
          : ComputationCertificateType.ALGEBRAIC_IDENTITY;
    }
    return ComputationCertificateType.BOUNDED_OBSERVATION;
  }
}
