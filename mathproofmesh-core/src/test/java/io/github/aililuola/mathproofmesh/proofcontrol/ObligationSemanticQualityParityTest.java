package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ObligationSemanticQualityParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_self_implication_obligation_rejected",
        "test_internal_self_implication_obligation_rejected",
        "test_symbolic_self_implication_obligation_rejected",
        "test_placeholder_search_text_not_mathematical_obligation",
        "test_truth_apt_subgoal_enters_graph",
        "test_main_goal_copy_not_counted_as_bridge",
        "test_invalid_obligation_is_quarantined_not_clusterable",
        "test_invalid_generated_obligation_is_quarantined_before_graph_write",
        "test_truth_apt_generated_subgoal_enters_graph");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ObligationSemanticQualityParityTest", authorityFunction);
  }
}
