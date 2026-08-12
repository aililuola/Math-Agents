package io.github.aililuola.mathproofmesh.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationContext;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class PythonSidecarDifferentialTest {

  @Test
  void sympy_exact_simplification_matches_the_frozen_python_handler() {
    ExperimentResult result =
        run(
            "sympy-simplify",
            spec(
                ComputationMethod.SYMPY_SIMPLIFY,
                "{\"expression\":\"(x+1)^2-(x^2+2*x+1)\"}",
                "{}"));

    assertThat(result.outcome()).isEqualTo(ExperimentOutcome.CERTIFIED);
    assertThat(result.certificate().path("simplified").asText()).isEqualTo("0");
    assertThat(result.certificate().path("is_zero").asBoolean()).isTrue();
  }

  @Test
  void z3_unsat_negation_certifies_the_declared_real_domain() {
    ExperimentResult result =
        run(
            "z3-unsat",
            spec(
                ComputationMethod.REAL_INEQUALITY,
                "{\"lhs\":\"x^2\",\"rhs\":\"0\",\"relation\":\"ge\","
                    + "\"variables\":[\"x\"],\"max_runtime_ms\":5000}",
                "{\"x\":{\"min\":-5,\"max\":5}}"));

    assertThat(result.outcome()).isEqualTo(ExperimentOutcome.CERTIFIED);
    assertThat(result.evidenceStrength())
        .isEqualTo(EvidenceStrength.FORMAL_CERTIFICATE);
    assertThat(result.certificate().path("solver_status").asText())
        .isEqualTo("unsat");
  }

  @Test
  void z3_counterexample_is_replayed_with_exact_sympy_rationals() {
    ExperimentResult result =
        run(
            "z3-counterexample",
            spec(
                ComputationMethod.REAL_INEQUALITY,
                "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"ge\","
                    + "\"variables\":[\"x\"],\"max_runtime_ms\":5000}",
                "{\"x\":{\"min\":-1,\"max\":1}}"));

    assertThat(result.outcome())
        .isEqualTo(ExperimentOutcome.COUNTEREXAMPLE_FOUND);
    assertThat(result.independentlyVerified()).isTrue();
    assertThat(result.counterexample().path("assignment").path("x").asText())
        .startsWith("-");
  }

  @Test
  void sampled_numeric_no_counterexample_never_becomes_a_proof() {
    ExperimentResult result =
        run(
            "numeric-not-refuted",
            spec(
                ComputationMethod.NUMERIC_COUNTEREXAMPLE,
                "{\"lhs\":\"x\",\"rhs\":\"x\",\"relation\":\"eq\","
                    + "\"variables\":[\"x\"],\"ranges\":{\"x\":[-2,2]},"
                    + "\"samples\":20}",
                "{}"));

    assertThat(result.outcome()).isEqualTo(ExperimentOutcome.NOT_REFUTED);
    assertThat(result.evidenceStrength()).isEqualTo(EvidenceStrength.HEURISTIC);
  }

  private static ExperimentResult run(String runId, ExperimentSpec requested) {
    ComputationBroker broker = SidecarTestFixtures.broker(runId);
    ComputationBroker.PreparedDecision prepared =
        broker.decide(
            requested,
            ComputationContext.initial(requested.pathId(), 5));
    return broker.runExperiment(prepared.spec(), prepared.decision());
  }

  private static ExperimentSpec spec(
      ComputationMethod method, String arguments, String domains) {
    return new ExperimentSpec(
        object(arguments),
        List.of("All variables use exactly the declared domains."),
        false,
        "Retain the checked result only within its declared exact scope.",
        "Reject the affected claim and repair the proof route.",
        object(domains),
        method != ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        null,
        "sidecar-" + method.value(),
        1_000,
        method,
        "Continue with an independent symbolic proof.",
        null,
        "path-" + method.value(),
        ComputationPurpose.CHECK_DERIVED_IDENTITY,
        "The route has reduced the question to one exact relation.",
        null,
        "junit",
        object("{}"),
        20260719,
        "The declared mathematical relation holds over its exact domain.",
        null,
        "The result prevents an unchecked premise from entering the proof.");
  }

  private static ObjectNode object(String json) {
    return (ObjectNode) ContractObjectMapper.parseTree(json);
  }
}
