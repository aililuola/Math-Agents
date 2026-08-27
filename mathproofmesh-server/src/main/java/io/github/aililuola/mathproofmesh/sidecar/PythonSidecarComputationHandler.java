package io.github.aililuola.mathproofmesh.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.computation.ExternalComputationHandler;
import io.github.aililuola.mathproofmesh.computation.HandlerEvidence;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict adapter from typed experiment contracts to the fixed sidecar protocol. */
public final class PythonSidecarComputationHandler
    implements ExternalComputationHandler {
  private static final Set<ComputationMethod> METHODS =
      EnumSet.of(
          ComputationMethod.SYMPY_SIMPLIFY,
          ComputationMethod.SYMPY_EQUIVALENT,
          ComputationMethod.POLYNOMIAL_FACTOR,
          ComputationMethod.NUMERIC_COUNTEREXAMPLE,
          ComputationMethod.REAL_INEQUALITY);
  private static final Set<String> EVIDENCE_FIELDS =
      Set.of(
          "outcome",
          "evidence_strength",
          "scope",
          "counterexample",
          "certificate",
          "exact_arithmetic",
          "cases_checked",
          "independently_verified",
          "verification_notes");

  private final PythonSidecarWorkerPool workers;
  private final String expectedToolVersion;

  public PythonSidecarComputationHandler(
      PythonSidecarWorkerPool workers, String expectedToolVersion) {
    this.workers = java.util.Objects.requireNonNull(workers, "workers");
    if (expectedToolVersion == null
        || !expectedToolVersion.startsWith(
            "mathproofmesh-python-compute/0.8.0;")) {
      throw new IllegalArgumentException("expectedToolVersion is invalid");
    }
    this.expectedToolVersion = expectedToolVersion;
  }

  @Override
  public boolean supports(ComputationMethod method) {
    return METHODS.contains(method);
  }

  @Override
  public String toolIdentity(ComputationMethod method) {
    if (!supports(method)) {
      throw new IllegalArgumentException("method is not a sidecar method");
    }
    return expectedToolVersion + "/" + method.value();
  }

  @Override
  public HandlerEvidence execute(ExperimentSpec spec, ExperimentProgram program) {
    if (!supports(spec.method())) {
      throw new IllegalArgumentException("method is not a sidecar method");
    }
    if (program != null) {
      throw new IllegalArgumentException(
          "typed sidecar methods do not accept arbitrary program source");
    }
    ObjectNode params = spec.arguments();
    if (spec.method() == ComputationMethod.REAL_INEQUALITY) {
      params.set("domains", spec.domains());
    }
    int responseLimit =
        Math.max(256, Math.min(2_000_000, spec.maxCases() > 1_000_000 ? 200_000 : 100_000));
    SidecarLimits limits =
        new SidecarLimits(
            spec.maxCases(),
            spec.seed(),
            Duration.ofSeconds(10),
            responseLimit);
    ObjectNode envelope =
        workers.execute(
            sidecarRequestId(spec),
            spec.method().value(),
            params,
            limits);
    JsonNode error = envelope.get("error");
    if (error != null && !error.isNull()) {
      String message = protocolErrorMessage(error);
      return HandlerEvidence.inconclusive(
          "Python sidecar rejected or could not complete the request: " + message,
          object().put("method", spec.method().value()));
    }
    String actualVersion = envelope.path("tool_version").asText();
    if (!expectedToolVersion.equals(actualVersion)) {
      throw new SidecarProtocolException(
          "sidecar tool version does not match the locked runtime");
    }
    ObjectNode result = requireObject(envelope.get("result"), "result");
    Set<String> fields = new HashSet<>();
    result.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(EVIDENCE_FIELDS)) {
      throw new SidecarProtocolException("sidecar evidence has an invalid field set");
    }
    ExperimentOutcome outcome =
        ExperimentOutcome.fromValue(requiredText(result, "outcome"));
    EvidenceStrength strength =
        EvidenceStrength.fromValue(requiredText(result, "evidence_strength"));
    ObjectNode scope = requireObject(result.get("scope"), "scope");
    ObjectNode counterexample = nullableObject(result.get("counterexample"), "counterexample");
    ObjectNode certificate = nullableObject(result.get("certificate"), "certificate");
    boolean exact = requiredBoolean(result, "exact_arithmetic");
    int casesChecked = requiredNonnegativeInt(result, "cases_checked");
    if (casesChecked > spec.maxCases()) {
      throw new SidecarProtocolException(
          "sidecar cases_checked exceeds the admitted request");
    }
    boolean replayed = requiredBoolean(result, "independently_verified");
    List<String> notes = requiredTextList(result.get("verification_notes"));
    validateEvidence(outcome, strength, counterexample, certificate, replayed);
    return new HandlerEvidence(
        outcome,
        strength,
        scope,
        counterexample,
        certificate,
        exact,
        casesChecked,
        replayed,
        notes,
        result);
  }

  private static void validateEvidence(
      ExperimentOutcome outcome,
      EvidenceStrength strength,
      ObjectNode counterexample,
      ObjectNode certificate,
      boolean replayed) {
    if (outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        && (counterexample == null
            || strength != EvidenceStrength.COUNTEREXAMPLE
            || !replayed)) {
      throw new SidecarProtocolException(
          "sidecar counterexample is not independently replayable");
    }
    if (outcome == ExperimentOutcome.CERTIFIED
        && (certificate == null
            || (strength != EvidenceStrength.EXHAUSTIVE_CERTIFICATE
                && strength != EvidenceStrength.FORMAL_CERTIFICATE))) {
      throw new SidecarProtocolException(
          "sidecar certified outcome has no valid certificate");
    }
    if (outcome == ExperimentOutcome.NOT_REFUTED
        && strength != EvidenceStrength.HEURISTIC
        && strength != EvidenceStrength.BOUNDED_EVIDENCE) {
      throw new SidecarProtocolException(
          "not_refuted sidecar evidence is overstated");
    }
  }

  private static String sidecarRequestId(ExperimentSpec spec) {
    String value = spec.experimentId().replaceAll("[^\\x21-\\x7e]", "_");
    return value.length() <= 128 ? value : value.substring(0, 128);
  }

  private static String protocolErrorMessage(JsonNode error) {
    if (!error.isObject()
        || !error.path("code").isIntegralNumber()
        || !error.path("message").isTextual()) {
      throw new SidecarProtocolException("sidecar error object is malformed");
    }
    String message = error.path("message").textValue().replace('\r', ' ').replace('\n', ' ');
    return message.length() <= 1_000 ? message : message.substring(0, 1_000);
  }

  private static ObjectNode requireObject(JsonNode value, String field) {
    if (value == null || !value.isObject()) {
      throw new SidecarProtocolException("sidecar " + field + " must be an object");
    }
    return ((ObjectNode) value).deepCopy();
  }

  private static ObjectNode nullableObject(JsonNode value, String field) {
    if (value == null || value.isNull()) {
      return null;
    }
    return requireObject(value, field);
  }

  private static String requiredText(ObjectNode value, String field) {
    JsonNode node = value.get(field);
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      throw new SidecarProtocolException(
          "sidecar " + field + " must be a non-empty string");
    }
    return node.textValue();
  }

  private static boolean requiredBoolean(ObjectNode value, String field) {
    JsonNode node = value.get(field);
    if (node == null || !node.isBoolean()) {
      throw new SidecarProtocolException("sidecar " + field + " must be boolean");
    }
    return node.booleanValue();
  }

  private static int requiredNonnegativeInt(ObjectNode value, String field) {
    JsonNode node = value.get(field);
    if (node == null
        || !node.isIntegralNumber()
        || !node.canConvertToInt()
        || node.intValue() < 0) {
      throw new SidecarProtocolException(
          "sidecar " + field + " must be a nonnegative integer");
    }
    return node.intValue();
  }

  private static List<String> requiredTextList(JsonNode value) {
    if (!(value instanceof ArrayNode array)) {
      throw new SidecarProtocolException(
          "sidecar verification_notes must be an array");
    }
    List<String> result = new ArrayList<>(array.size());
    for (JsonNode item : array) {
      if (!item.isTextual() || item.textValue().length() > 2_000) {
        throw new SidecarProtocolException(
            "sidecar verification note is invalid");
      }
      result.add(item.textValue());
    }
    return List.copyOf(result);
  }

  private static ObjectNode object() {
    return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
  }
}
