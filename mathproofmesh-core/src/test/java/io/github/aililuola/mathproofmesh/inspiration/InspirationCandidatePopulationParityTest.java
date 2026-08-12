package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationCandidatePopulationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_warm_and_cold_contexts_are_bounded_and_distinct",
        "test_meta_context_receives_metrics_but_not_proof_transcripts",
        "test_inspiration_context_honors_the_hard_character_budget",
        "test_structural_analogy_is_deferred_when_global_records_do_not_match",
        "test_candidate_population_deduplicates_before_bounded_review",
        "test_proposer_assignment_plan_survives_checkpoint_restore");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationCandidatePopulationParityTest", authorityFunction);
  }
}
