package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RunReliabilityRecoveryParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_all_model_authored_cryptographic_hashes_are_discarded",
        "test_transport_failure_does_not_lower_agent_trust",
        "test_length_empty_response_skips_repair_and_full_semantic_failover",
        "test_schema_repair_uses_a_small_budget_and_a_valid_cross_field_example",
        "test_route_delta_is_normalized_locally_without_another_deep_call",
        "test_unknown_claim_step_id_is_not_guessed_by_local_normalization",
        "test_shared_provider_failure_pauses_run_without_a_false_math_result");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    TemporalParityScenarios.verify("RunReliabilityRecoveryParityTest", authorityFunction);
  }
}
