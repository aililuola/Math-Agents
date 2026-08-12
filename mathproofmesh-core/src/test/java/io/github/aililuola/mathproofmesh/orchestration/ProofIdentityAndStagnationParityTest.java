package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProofIdentityAndStagnationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_feedback_wrappers_and_attempt_ids_do_not_change_proof_identity",
        "test_obligation_store_collapses_nested_provenance_duplicates",
        "test_exploration_signature_ignores_checkpoint_and_obligation_ids",
        "test_low_tier_partial_also_gets_only_one_bounded_repair",
        "test_goal_integrity_failure_cannot_pass_schema",
        "test_duplicate_attempt_and_global_plateau_freeze_route",
        "test_confirmed_counterexample_refutes_required_critical_claim",
        "test_provider_http_circuit_distinguishes_terminal_auth_and_rate_limit");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("ProofIdentityAndStagnationParityTest", authorityFunction);
  }
}
