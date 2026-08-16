package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationContext;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;

final class ComputationIssue010BlackBoxFixtures {
  private ComputationIssue010BlackBoxFixtures() {}

  static ObjectNode object(String json) {
    JsonNode parsed = ContractObjectMapper.parseTree(json);
    if (!parsed.isObject()) {
      throw new IllegalArgumentException("fixture must be an object");
    }
    return (ObjectNode) parsed;
  }

  static ExperimentSpec spec(
      String experimentId, ComputationMethod method, String arguments) {
    return new ExperimentSpec(
        object(arguments),
        List.of("All values use the declared exact finite input."),
        false,
        "Retain only the typed conclusion allowed by the result scope.",
        "Reject the exact target claim and request a proof repair.",
        object("{}"),
        method != ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        null,
        experimentId,
        1_000,
        method,
        "Continue with a symbolic proof without this computation.",
        null,
        "route-010",
        ComputationPurpose.CHECK_DERIVED_IDENTITY,
        "A precise intermediate claim has one deterministic finite check.",
        null,
        "issue-010-black-box",
        object("{}"),
        20260816,
        "The exact finite computation checks one precise mathematical claim.",
        null,
        "The result decides whether this exact local proof dependency survives.");
  }

  static ExperimentSpec graphSpec(String experimentId) {
    return spec(
        experimentId,
        ComputationMethod.GRAPH_CERTIFICATE,
        """
        {
          "graph": {
            "nodes": ["u", "v"],
            "edges": [["u", "v"]],
            "directed": false
          },
          "property": "connected",
          "certificate": {}
        }
        """);
  }

  static ComputationBroker broker(
      String runId, ComputationHandlerRegistry registry) {
    return new ComputationBroker(
        runId,
        ComputationLimits.defaultsEnabled(),
        registry,
        new InMemoryComputationCache());
  }

  static ExperimentResult run(ComputationBroker broker, ExperimentSpec spec) {
    ComputationBroker.PreparedDecision prepared =
        broker.decide(spec, ComputationContext.initial("route-010", 8));
    return broker.runExperiment(prepared.spec(), prepared.decision());
  }
}
