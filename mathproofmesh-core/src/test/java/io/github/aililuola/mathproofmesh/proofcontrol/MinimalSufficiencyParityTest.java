package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MinimalSufficiencyParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_weaker_lower_cost_sufficient_target_dominates_overstrong_target",
        "test_weaker_bridge_is_candidate_metadata_not_goal_replacement");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("MinimalSufficiencyParityTest", authorityFunction);
  }
}
