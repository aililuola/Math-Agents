package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReasoningFirstSequenceToolsParityTest {

  @Test
  void test_bounded_greedy_sequence_never_promotes_a_finite_prefix_to_proof() {
    HandlerEvidence evidence =
        SequenceFunctions.runBoundedGreedySequence(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
                "{\"initial_values\":[0],\"length\":4,"
                    + "\"candidate_min\":0,\"candidate_max\":20,"
                    + "\"rule\":\"avoid_forbidden_differences\","
                    + "\"forbidden_differences\":[1]}"));

    assertThat(evidence.outcome()).isEqualTo(ExperimentOutcome.NOT_REFUTED);
    assertThat(evidence.evidenceStrength())
        .isEqualTo(EvidenceStrength.BOUNDED_EVIDENCE);
    assertThat(evidence.certificate().path("values").toString())
        .isEqualTo("[0,2,4,6]");
  }

  @Test
  void test_typed_sequence_tools_independently_recheck_counterexamples() {
    HandlerEvidence greedy =
        SequenceFunctions.runBoundedGreedySequence(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
                "{\"initial_values\":[0],\"length\":4,"
                    + "\"candidate_min\":0,\"candidate_max\":20,"
                    + "\"rule\":\"avoid_forbidden_differences\","
                    + "\"forbidden_differences\":[1],"
                    + "\"claimed_values\":[0,2,5,7]}"));
    HandlerEvidence period =
        SequenceFunctions.runCandidatePeriodCheck(
            ComputationFixtures.spec(
                ComputationMethod.CANDIDATE_PERIOD_CHECK,
                "{\"values\":[1,2,1,2,1,3],\"candidate_period\":2}"));

    assertThat(greedy.outcome())
        .isEqualTo(ExperimentOutcome.COUNTEREXAMPLE_FOUND);
    assertThat(greedy.independentlyVerified()).isTrue();
    assertThat(period.counterexample().path("index").intValue()).isEqualTo(5);
    assertThat(period.independentlyVerified()).isTrue();
  }

  @Test
  void test_matching_candidate_period_is_only_bounded_not_refuted() {
    HandlerEvidence evidence =
        SequenceFunctions.runCandidatePeriodCheck(
            ComputationFixtures.spec(
                ComputationMethod.CANDIDATE_PERIOD_CHECK,
                "{\"values\":[1,2,1,2,1,2],\"candidate_period\":2}"));
    assertThat(evidence.outcome()).isEqualTo(ExperimentOutcome.NOT_REFUTED);
    assertThat(evidence.evidenceStrength())
        .isEqualTo(EvidenceStrength.BOUNDED_EVIDENCE);
  }

  @Test
  void test_bounded_greedy_sequence_supports_gcd_overlap_with_every_prior_term() {
    HandlerEvidence evidence =
        SequenceFunctions.runBoundedGreedySequence(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
                "{\"initial_values\":[6],\"length\":5,"
                    + "\"candidate_min\":2,\"candidate_max\":30,"
                    + "\"rule\":\"gcd_overlap_all_prior\"}"));
    assertThat(evidence.certificate().path("values").toString())
        .isEqualTo("[6,8,10,12,14]");
    assertThat(evidence.certificate().path("rule").asText())
        .isEqualTo("gcd_overlap_all_prior");
  }

  @Test
  void test_malformed_bounded_greedy_request_is_rejected_before_execution() {
    ExperimentSpec malformed =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"a1\":6,\"max_n\":2000}");
    assertThat(ContractsFunctions.validateExperimentContract(malformed))
        .anyMatch(value -> value.contains("a1"))
        .anyMatch(value -> value.contains("initial_values"))
        .anyMatch(value -> value.contains("length"));
  }

  @Test
  void test_bounded_greedy_rejects_domain_sweeps_and_control_aliases() {
    ExperimentSpec sweep =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"check_early_dn_one\":true,\"max_terms\":1000,"
                + "\"report_dn\":true}",
            "{\"a1_min\":2,\"a1_max\":100}");
    assertThat(ContractsFunctions.validateExperimentContract(sweep))
        .anyMatch(value -> value.contains("does not accept domains"))
        .anyMatch(value -> value.contains("check_early_dn_one"));
  }

  @Test
  void test_explorer_receives_exact_bounded_greedy_argument_contract() {
    String catalog =
        ContractsFunctions.experimentToolCatalog(
                Set.of("bounded_greedy_sequence"))
            .getFirst()
            .toString();
    assertThat(catalog)
        .contains(
            "initial_values",
            "gcd_overlap_all_prior",
            "claimed_values");
  }

  @Test
  void test_planner_numerical_language_becomes_an_inert_computation_hint() {
    ComputationPlanningHints.Hint hint =
        ComputationPlanningHints.infer(
                "First derive a recurrence, then test a period on a finite prefix.")
            .orElseThrow();
    assertThat(hint.suggestedMethod())
        .isEqualTo(ComputationMethod.CANDIDATE_PERIOD_CHECK);
    assertThat(hint.broadSearch()).isFalse();
  }

  @Test
  void test_reviewer_can_execute_the_new_typed_sequence_requests() {
    ToolBroker broker =
        new ToolBroker(ComputationFixtures.broker("sequence-reviewer"));
    List<ToolResult> results =
        broker.executeMany(
            List.of(
                ComputationFixtures.request(
                    "bounded_greedy_sequence",
                    "Check the claimed deterministic finite prefix.",
                    "{\"initial_values\":[0],\"length\":4,"
                        + "\"candidate_min\":0,\"candidate_max\":20,"
                        + "\"rule\":\"avoid_forbidden_differences\","
                        + "\"forbidden_differences\":[1],"
                        + "\"claimed_values\":[0,2,5,7]}",
                    "{}"),
                ComputationFixtures.request(
                    "candidate_period_check",
                    "Check whether the declared finite list has period two.",
                    "{\"values\":[1,2,1,2,1,3],\"candidate_period\":2}",
                    "{}")));

    assertThat(results).allMatch(ToolResult::ok);
    assertThat(results)
        .allMatch(
            result ->
                "counterexample_found"
                    .equals(result.result().path("outcome").asText()));
  }

  @Test
  void test_new_sequence_tools_are_advertised_to_reviewers_when_typed_tools_are_on() {
    assertThat(ComputationServiceRegistry.javaNativeMethods())
        .contains(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            ComputationMethod.CANDIDATE_PERIOD_CHECK);
    assertThat(ComputationPromptInstructions.explorationInstructions())
        .contains("REGISTERED TYPED COMPUTATION CONTRACTS");
  }
}
