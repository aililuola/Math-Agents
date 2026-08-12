package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationAdvancedMechanismsParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_domain_operator_plugins_supply_auditable_contracts",
        "test_controlled_surprise_mutations_are_replayable_and_slot_diverse",
        "test_operator_and_mutation_admission_are_checkpointed",
        "test_active_artifact_cannot_invent_an_unadmitted_domain_operator",
        "test_reverse_goal_meets_only_admitted_forward_facts",
        "test_reverse_goal_does_not_turn_lexical_overlap_into_false_implication",
        "test_reverse_goal_exact_text_cannot_bypass_scope_compatibility",
        "test_composer_queues_a_separately_reviewed_next_round_proposal",
        "test_composer_rejects_unfalsified_or_noncomplementary_pairs",
        "test_composer_honors_zero_candidate_cap",
        "test_composer_can_build_a_bounded_three_source_composition");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationAdvancedMechanismsParityTest", authorityFunction);
  }
}
