package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class ComputationFixtures {
  private static final AtomicInteger IDS = new AtomicInteger();

  private ComputationFixtures() {}

  static ObjectNode object(String json) {
    JsonNode parsed = ContractObjectMapper.parseTree(json);
    if (!parsed.isObject()) {
      throw new IllegalArgumentException("fixture must be an object");
    }
    return (ObjectNode) parsed;
  }

  static ExperimentSpec spec(ComputationMethod method, String arguments) {
    return spec(method, arguments, "{}", ComputationPurpose.CHECK_DERIVED_IDENTITY, false, 1_000);
  }

  static ExperimentSpec spec(
      ComputationMethod method, String arguments, String domains) {
    return spec(method, arguments, domains, ComputationPurpose.CHECK_DERIVED_IDENTITY, false, 1_000);
  }

  static ExperimentSpec spec(
      ComputationMethod method,
      String arguments,
      String domains,
      ComputationPurpose purpose,
      boolean broadSearch,
      int maxCases) {
    return new ExperimentSpec(
        object(arguments),
        List.of("All variables use exactly the declared finite domains."),
        broadSearch,
        "Retain the result only within its declared bounded evidence scope.",
        "Reject the affected numerical premise and repair the proof route.",
        object(domains),
        method != ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        null,
        "experiment-" + IDS.incrementAndGet(),
        maxCases,
        method,
        "Continue with a symbolic derivation without this calculation.",
        null,
        "path-computation",
        purpose,
        "A concrete intermediate claim has one deterministic bounded test.",
        null,
        "junit",
        object("{}"),
        20260719,
        "The declared bounded computation checks one precise mathematical claim.",
        null,
        "The check prevents an unchecked numerical premise from entering the proof.");
  }

  static ComputationBroker broker(String runId) {
    return new ComputationBroker(
        runId,
        ComputationLimits.defaultsEnabled(),
        ComputationHandlerRegistry.javaOnly(),
        new InMemoryComputationCache());
  }

  static ExperimentResult run(ComputationBroker broker, ExperimentSpec spec) {
    ComputationBroker.PreparedDecision prepared =
        broker.decide(
            spec,
            ComputationContext.initial(
                spec.pathId() == null ? "path-computation" : spec.pathId(), 5));
    return broker.runExperiment(prepared.spec(), prepared.decision());
  }

  static ToolRequest request(
      String kind, String purpose, String arguments, String domains) {
    return new ToolRequest(
        object(arguments),
        object(domains),
        kind,
        1_000,
        purpose,
        null);
  }
}
