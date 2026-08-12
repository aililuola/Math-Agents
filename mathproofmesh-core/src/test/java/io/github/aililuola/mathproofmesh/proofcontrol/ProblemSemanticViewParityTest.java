package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProblemSemanticViewParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_usable_english_view_is_sidecar_and_does_not_change_goal_hash",
        "test_translation_that_changes_formula_or_domain_is_rejected",
        "test_translation_cannot_reverse_the_requested_task",
        "test_translation_cannot_reverse_quantifier_or_unmarked_domain",
        "test_translation_cannot_reverse_implication_order",
        "test_legacy_usable_view_without_deterministic_audit_is_quarantined",
        "test_triage_prompt_requests_a_non_authoritative_english_view",
        "test_orchestrator_attaches_only_an_audited_view");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ProblemSemanticViewParityTest", authorityFunction);
  }
}
