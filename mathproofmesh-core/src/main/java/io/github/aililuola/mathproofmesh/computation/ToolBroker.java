package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.contract.ToolResult;
import java.util.ArrayList;
import java.util.List;

/** Backward-compatible facade for typed reviewer calculation requests. */
public final class ToolBroker {
  private final ComputationBroker broker;

  public ToolBroker(ComputationBroker broker) {
    this.broker = java.util.Objects.requireNonNull(broker, "broker");
  }

  ComputationBroker computationBroker() {
    return broker;
  }

  public ToolResult execute(ToolRequest request) {
    try {
      ExperimentSpec spec = compile(request);
      ComputationBroker.PreparedDecision prepared =
          broker.decide(
              spec,
              ComputationContext.initial(
                  spec.pathId() == null ? "tool:" + request.requestId() : spec.pathId(),
                  1));
      if (prepared.decision().decision()
          != io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus.ALLOW) {
        return new ToolResult(
            prepared.decision().reason(),
            null,
            request.kind(),
            false,
            request.requestId(),
            null);
      }
      ExperimentResult result =
          broker.runExperiment(prepared.spec(), prepared.decision());
      boolean ok =
          result.outcome() != ExperimentOutcome.ERROR
              && result.outcome() != ExperimentOutcome.INCONCLUSIVE;
      return new ToolResult(
          ok ? null : result.error(),
          null,
          request.kind(),
          ok,
          request.requestId(),
          resultPayload(result));
    } catch (RuntimeException exception) {
      return new ToolResult(
          exception.getMessage(),
          null,
          request.kind(),
          false,
          request.requestId(),
          null);
    }
  }

  public List<ToolResult> executeMany(List<ToolRequest> requests) {
    List<ToolResult> results = new ArrayList<>(requests.size());
    requests.forEach(request -> results.add(execute(request)));
    return List.copyOf(results);
  }

  public ExperimentSpec compile(ToolRequest request) {
    ComputationMethod method = ComputationMethod.fromValue(request.kind());
    ComputationPurpose purpose =
        request.purpose().equals("discover_pattern")
            ? ComputationPurpose.DISCOVER_PATTERN
            : ComputationPurpose.CHECK_DERIVED_IDENTITY;
    boolean broad = purpose == ComputationPurpose.DISCOVER_PATTERN;
    boolean exact = method != ComputationMethod.NUMERIC_COUNTEREXAMPLE;
    String target = "Typed calculation request: " + request.purpose();
    return new ExperimentSpec(
        request.arguments(),
        List.of("All variables use exactly the declared finite domains."),
        broad,
        "Retain the result only within its declared finite scope.",
        "Reject the affected numerical premise and repair the proof route.",
        request.domains(),
        exact,
        null,
        request.requestId(),
        request.maxCases(),
        method,
        "Continue with a symbolic derivation that does not depend on this calculation.",
        null,
        "tool:" + request.requestId(),
        purpose,
        "A route-critical typed calculation must be checked before it is used.",
        null,
        "typed-reviewer",
        ComputationJson.object(),
        20260719,
        target,
        null,
        "The server can replay the declared calculation deterministically.");
  }

  private static ObjectNode resultPayload(ExperimentResult result) {
    ObjectNode payload =
        ComputationJson.object()
            .put("outcome", result.outcome().value())
            .put("evidence_strength", result.evidenceStrength().value())
            .put("exact_arithmetic", result.exactArithmetic())
            .put("cases_checked", result.casesChecked())
            .put("independently_verified", result.independentlyVerified());
    if (result.counterexample() != null) {
      payload.set("counterexample", result.counterexample());
      payload.put("counterexample_found", true);
    } else {
      payload.put("counterexample_found", false);
    }
    if (result.certificate() != null) {
      payload.set("certificate", result.certificate());
      if (result.method() == ComputationMethod.SYMPY_EQUIVALENT
          && result.certificate().has("equivalent")) {
        payload.set("equivalent", result.certificate().get("equivalent"));
      }
    }
    return payload;
  }
}
