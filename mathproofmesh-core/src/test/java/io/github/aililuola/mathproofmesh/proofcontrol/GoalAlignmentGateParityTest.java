package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GoalAlignmentGateParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_same_canonical_statement_and_scope_is_equivalent",
        "test_lexical_overlap_alone_is_not_sufficiency",
        "test_graph_implication_is_sufficient_and_reverse_is_necessary");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("GoalAlignmentGateParityTest", authorityFunction);
  }
}
