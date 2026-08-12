package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CalculationGateVerdict;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriticalCalculationGateParityTest {

  @Test
  void test_high_precision_trigger_detects_explicit_computed_values() {
    assertThat(
            CriticalCalculationGate.calculationTrigger(
                List.of("The first terms are 15, 18, 21, 24.")))
        .isPresent();
    assertThat(
            CriticalCalculationGate.calculationTrigger(
                List.of("Use induction to prove a_n = a_1 + (n-1)d.")))
        .isEmpty();
    assertThat(
            CriticalCalculationGate.calculationTrigger(
                List.of("By AM-GM, the minimum is 3.")))
        .isEmpty();
    assertThat(
            CriticalCalculationGate.calculationTrigger(
                List.of("Direct computation gives the minimum 3.")))
        .isPresent();
  }

  @Test
  void test_strategy_with_undeclared_numeric_premise_is_blocked() {
    CriticalCalculationGate gate = gate("missing-strategy");
    StrategyCard strategy =
        strategy(
            "numeric-route",
            "The first terms are 15, 18, 21, 24, so infer a recurrence.",
            List.of());
    CalculationGateBatch result =
        gate.evaluateStrategy(strategy, "path-numeric-route", "planner");

    assertThat(result.passed()).isFalse();
    assertThat(result.failures().getFirst().verdict())
        .isEqualTo(CalculationGateVerdict.MISSING_DECLARATION);
  }

  @Test
  void test_exact_declared_prefix_receives_certificate_without_model_call() {
    CriticalCalculationGate gate = gate("passing-prefix");
    ProofStep step =
        step(
            "prefix",
            "The first five terms are 6, 8, 10, 12, 14.",
            greedyCheck(List.of(6, 8, 10, 12, 14)));
    CalculationGateBatch result =
        gate.evaluateSteps(
            List.of(step),
            "proof_step",
            "path-prefix",
            "checkpoint-parent",
            "explorer-a");

    assertThat(result.passed()).isTrue();
    assertThat(result.records().getFirst().verdict())
        .isEqualTo(CalculationGateVerdict.PASSED);
    assertThat(result.evidenceRefs()).hasSize(1);
  }

  @Test
  void test_wrong_declared_prefix_is_refuted_before_checkpoint_review() {
    CriticalCalculationGate gate = gate("refuted-prefix");
    ProofStep step =
        step(
            "bad-prefix",
            "The first five terms are 6, 8, 9, 12, 14.",
            greedyCheck(List.of(6, 8, 9, 12, 14)));
    CalculationGateBatch result =
        gate.evaluateSteps(
            List.of(step),
            "proof_step",
            "path-bad-prefix",
            "checkpoint-parent",
            "explorer-a");

    assertThat(result.passed()).isFalse();
    assertThat(result.failures().getFirst().verdict())
        .isEqualTo(CalculationGateVerdict.REFUTED);
    assertThat(result.failures().getFirst().reason()).contains("generated");
  }

  @Test
  void test_generated_request_id_does_not_duplicate_the_calculation_node() {
    CriticalCalculationGate gate = gate("stable-calculation-node");
    CalculationGateBatch first =
        gate.evaluateSteps(
            List.of(
                step(
                    "prefix",
                    "The first five terms are 6, 8, 10, 12, 14.",
                    greedyCheck(List.of(6, 8, 10, 12, 14)))),
            "proof_step",
            "path-prefix",
            "checkpoint-parent",
            "explorer-a");
    CalculationGateBatch second =
        gate.evaluateSteps(
            List.of(
                step(
                    "prefix",
                    "The first five terms are 6, 8, 10, 12, 14.",
                    greedyCheck(List.of(6, 8, 10, 12, 14)))),
            "proof_step",
            "path-prefix",
            "checkpoint-parent",
            "explorer-a");

    assertThat(first.records().getFirst().experimentId())
        .isEqualTo(second.records().getFirst().experimentId());
    assertThat(first.records().getFirst().requestHash())
        .isEqualTo(second.records().getFirst().requestHash());
  }

  @Test
  void test_discover_pattern_request_uses_discovery_contract() {
    CriticalCalculationGate gate = gate("discovery");
    ToolRequest request =
        ComputationFixtures.request(
            "candidate_period_check",
            "discover_pattern",
            "{\"values\":[2,5,2,5],\"candidate_period\":2,"
                + "\"start_index\":0}",
            "{}");
    CalculationGateBatch result =
        gate.evaluateStrategy(
            strategy(
                "strategy-candidate-period",
                "Use a finite exact check to propose a candidate period.",
                List.of(request)),
            "path-strategy-candidate-period",
            "explorer-a");

    assertThat(result.passed()).isTrue();
    assertThat(result.records().getFirst().verdict())
        .isEqualTo(CalculationGateVerdict.PASSED);
  }

  @Test
  void test_result_only_symbolic_method_is_not_admitted_as_assertion_check() {
    CriticalCalculationGate gate = gate("result-only");
    ToolRequest request =
        ComputationFixtures.request(
            "polynomial_factor",
            "Factor the polynomial x^2-1.",
            "{\"expression\":\"x^2-1\"}",
            "{}");
    CalculationGateBatch result =
        gate.evaluateSteps(
            List.of(
                step(
                    "factor",
                    "The factorization is (x-1)(x+1).",
                    request)),
            "proof_step",
            "path-factor",
            null,
            "explorer-a");

    assertThat(result.passed()).isFalse();
    assertThat(result.failures().getFirst().verdict())
        .isEqualTo(CalculationGateVerdict.INVALID_CONTRACT);
    assertThat(result.failures().getFirst().reason())
        .contains("assertion-checking");
  }

  @Test
  void test_prompts_require_checks_in_the_existing_model_call() {
    assertThat(ComputationPromptInstructions.strategyInstructions())
        .contains(
            "strategy.calculation_checks",
            "REGISTERED TYPED CALCULATION CONTRACTS",
            "adds no model call");
    assertThat(ComputationPromptInstructions.explorationInstructions())
        .contains(
            "ProofStep.calculation_checks",
            "REGISTERED TYPED COMPUTATION CONTRACTS",
            "adds no model call");
  }

  private static CriticalCalculationGate gate(String runId) {
    return new CriticalCalculationGate(
        new ToolBroker(ComputationFixtures.broker(runId)));
  }

  private static ToolRequest greedyCheck(List<Integer> claimed) {
    String values =
        claimed.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    return ComputationFixtures.request(
        "bounded_greedy_sequence",
        "The declared first five greedy-sequence values are exact.",
        "{\"initial_values\":[6],\"length\":5,\"candidate_min\":2,"
            + "\"candidate_max\":30,\"rule\":\"gcd_overlap_all_prior\","
            + "\"claimed_values\":["
            + values
            + "]}",
        "{}");
  }

  private static StrategyCard strategy(
      String id, String coreIdea, List<ToolRequest> checks) {
    return new StrategyCard(
        null,
        "Turn the finite observation into a universal proof.",
        checks,
        List.of(),
        List.of(),
        coreIdea,
        List.of(),
        0.5,
        0.4,
        List.of(),
        "Recompute the finite premise.",
        "An independent deterministic calculation.",
        null,
        null,
        List.of(),
        List.of(),
        id,
        List.of(),
        "Computed route");
  }

  private static ProofStep step(
      String id, String statement, ToolRequest request) {
    return new ProofStep(
        null,
        List.of(request),
        List.of(),
        List.of("Generate the exact finite prefix."),
        List.of(),
        0.8,
        List.of(),
        List.of(),
        true,
        "Apply the declared greedy rule successively.",
        statement,
        id,
        null);
  }
}
