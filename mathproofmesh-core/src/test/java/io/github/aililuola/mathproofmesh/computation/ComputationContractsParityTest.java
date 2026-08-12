package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ComputationContractsParityTest {

  @Test
  void test_every_registered_method_has_a_preexecution_contract() {
    Set<String> methods =
        Arrays.stream(ComputationMethod.values())
            .map(ComputationMethod::value)
            .collect(Collectors.toSet());
    assertThat(ContractsFunctions.experimentToolCatalog(methods))
        .extracting(node -> node.path("method").asText())
        .containsAll(methods);
  }

  @Test
  void test_sandbox_catalog_requires_a_complete_json_input_object() {
    var sandbox =
        ContractsFunctions.experimentToolCatalog(Set.of("sandboxed_python")).getFirst();

    assertThat(sandbox.path("required_arguments").get(0).asText()).isEqualTo("input");
    assertThat(sandbox.path("domains").asText()).contains("Must be empty");
    assertThat(sandbox.path("constraints").toString())
        .contains("arguments.input must be the complete JSON object");
  }

  @Test
  void test_batch_request_is_not_silently_reinterpreted_as_one_sequence() {
    ExperimentSpec batch =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"a1_values\":[2,3,5,6],\"max_terms\":200,"
                + "\"description\":\"aggregate independent sequences\"}");
    assertThat(ContractsFunctions.validateExperimentContract(batch))
        .anyMatch(value -> value.contains("a1_values"))
        .anyMatch(value -> value.contains("initial_values"))
        .anyMatch(value -> value.contains("length"));
  }

  @Test
  void test_sequence_discovery_and_assertion_contracts_are_distinct() {
    ExperimentSpec discovery =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[6],\"length\":12,"
                + "\"rule\":\"gcd_overlap_all_prior\"}",
            "{}",
            ComputationPurpose.DISCOVER_PATTERN,
            true,
            1_000);
    ExperimentSpec assertion = asAssertion(discovery);

    assertThat(ContractsFunctions.validateExperimentContract(discovery)).isEmpty();
    assertThat(ContractsFunctions.validateExperimentContract(assertion))
        .anyMatch(value -> value.contains("assertion-mode"))
        .anyMatch(value -> value.contains("claimed_values"));
  }

  @Test
  void test_unclaimed_sequence_request_is_safely_downgraded_to_discovery() {
    ExperimentSpec assertion =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[6],\"length\":12,"
                + "\"rule\":\"gcd_overlap_all_prior\"}");
    ContractsFunctions.Normalization normalization =
        ContractsFunctions.normalizeExploratoryContract(assertion);

    assertThat(normalization.changed()).isTrue();
    assertThat(normalization.spec().purpose())
        .isEqualTo(ComputationPurpose.DISCOVER_PATTERN);
    assertThat(normalization.spec().broadSearch()).isTrue();
  }

  @Test
  void test_contract_repair_schema_cannot_carry_a_proof_delta() {
    String payload =
        "{\"action\":\"abandon_as_unrepresentable\","
            + "\"repaired_spec\":null,"
            + "\"reason\":\"No bounded request is available.\","
            + "\"semantic_equivalence\":null,"
            + "\"delta\":{\"proof_complete\":true}}";
    assertThatThrownBy(
            () ->
                ContractObjectMapper.read(
                    payload,
                    io.github.aililuola.mathproofmesh.contract
                        .ComputationContractRepair.class))
        .isInstanceOf(ContractValidationException.class);
  }

  @Test
  void test_unrepresentable_typed_batch_can_repair_to_sandbox_without_execution() {
    ComputationLimits enabledSandbox =
        new ComputationLimits(
            true,
            true,
            true,
            true,
            true,
            25_000,
            2,
            6,
            120,
            1_000_000,
            20_000,
            1,
            true,
            true);
    ExperimentSpec sandbox = sandboxSpec();
    ComputationPolicy policy = new ComputationPolicy(enabledSandbox);

    assertThat(
            policy.evaluate(
                    sandbox,
                    new ComputationContext("repair", 1, true, 5, 0, 0),
                    new ComputationLedger.Usage(0, 0),
                    true,
                    java.util.Optional.empty())
                .decision())
        .isEqualTo(ComputationDecisionStatus.ALLOW);
  }

  private static ExperimentSpec asAssertion(ExperimentSpec source) {
    return new ExperimentSpec(
        source.arguments(),
        source.assumptions(),
        false,
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        source.domains(),
        source.exactArithmetic(),
        null,
        source.experimentId() + "-assertion",
        source.maxCases(),
        source.method(),
        source.noncomputationalAlternative(),
        source.parentCheckpointId(),
        source.pathId(),
        ComputationPurpose.CHECK_DERIVED_IDENTITY,
        source.reasoningBasis(),
        null,
        source.requestedBy(),
        source.runtimeFingerprint(),
        source.seed(),
        source.targetClaim(),
        source.typedToolGap(),
        source.whyComputationIsNeeded());
  }

  private static ExperimentSpec sandboxSpec() {
    ExperimentSpec base =
        ComputationFixtures.spec(
            ComputationMethod.SANDBOXED_PYTHON,
            "{\"input\":{\"a1_values\":[2,3,5,6],\"max_terms\":200}}",
            "{}",
            ComputationPurpose.DISCOVER_PATTERN,
            true,
            2_000);
    return new ExperimentSpec(
        base.arguments(),
        base.assumptions(),
        base.broadSearch(),
        base.decisionIfConfirmed(),
        base.decisionIfRefuted(),
        base.domains(),
        base.exactArithmetic(),
        null,
        base.experimentId(),
        base.maxCases(),
        base.method(),
        base.noncomputationalAlternative(),
        base.parentCheckpointId(),
        base.pathId(),
        base.purpose(),
        base.reasoningBasis(),
        null,
        base.requestedBy(),
        base.runtimeFingerprint(),
        base.seed(),
        base.targetClaim(),
        "The typed sequence tool has no batch aggregation contract.",
        base.whyComputationIsNeeded());
  }
}
