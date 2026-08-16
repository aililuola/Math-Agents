package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable producer output before any verifier-derived authority is attached. */
public record ComputationResultArtifact(
    String requestHash,
    String executionHash,
    ExperimentOutcome outcome,
    EvidenceStrength evidenceStrength,
    ObjectNode scope,
    ObjectNode counterexample,
    ObjectNode certificate,
    boolean exactArithmetic,
    int casesChecked,
    double runtimeSeconds,
    String producerId,
    String producerVersion,
    String error,
    String artifactHash) {
  public ComputationResultArtifact {
    requestHash = required(requestHash, "requestHash");
    executionHash = required(executionHash, "executionHash");
    if (outcome == null || evidenceStrength == null) {
      throw new IllegalArgumentException("outcome and evidenceStrength are required");
    }
    scope = scope == null ? JsonNodeFactory.instance.objectNode() : scope.deepCopy();
    counterexample = counterexample == null ? null : counterexample.deepCopy();
    certificate = certificate == null ? null : certificate.deepCopy();
    if (casesChecked < 0 || !Double.isFinite(runtimeSeconds) || runtimeSeconds < 0.0d) {
      throw new IllegalArgumentException("casesChecked and runtimeSeconds must be nonnegative");
    }
    producerId = required(producerId, "producerId");
    producerVersion = required(producerVersion, "producerVersion");
    error = error == null ? "" : error.strip();
    String expected =
        CanonicalJson.stableHash(
            payload(
                requestHash,
                executionHash,
                outcome,
                evidenceStrength,
                scope,
                counterexample,
                certificate,
                exactArithmetic,
                casesChecked,
                runtimeSeconds,
                producerId,
                producerVersion,
                error));
    artifactHash = artifactHash == null || artifactHash.isBlank() ? expected : artifactHash.strip();
    if (!ComputationJson.hashesEqual(expected, artifactHash)) {
      throw new IllegalArgumentException("result artifact hash mismatch");
    }
  }

  @Override
  public ObjectNode scope() {
    return scope.deepCopy();
  }

  @Override
  public ObjectNode counterexample() {
    return counterexample == null ? null : counterexample.deepCopy();
  }

  @Override
  public ObjectNode certificate() {
    return certificate == null ? null : certificate.deepCopy();
  }

  private static Map<String, Object> payload(
      String requestHash,
      String executionHash,
      ExperimentOutcome outcome,
      EvidenceStrength evidenceStrength,
      ObjectNode scope,
      ObjectNode counterexample,
      ObjectNode certificate,
      boolean exactArithmetic,
      int casesChecked,
      double runtimeSeconds,
      String producerId,
      String producerVersion,
      String error) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("request_hash", requestHash);
    values.put("execution_hash", executionHash);
    values.put("outcome", outcome.value());
    values.put("evidence_strength", evidenceStrength.value());
    values.put("scope", scope);
    values.put("counterexample", counterexample);
    values.put("certificate", certificate);
    values.put("exact_arithmetic", exactArithmetic);
    values.put("cases_checked", casesChecked);
    values.put("runtime_seconds", runtimeSeconds);
    values.put("producer_id", producerId);
    values.put("producer_version", producerVersion);
    values.put("error", error);
    return values;
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
