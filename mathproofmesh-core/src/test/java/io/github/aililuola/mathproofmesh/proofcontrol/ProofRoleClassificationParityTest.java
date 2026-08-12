package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProofRoleClassificationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_role_priority_covers_counterexample_equivalence_and_core_bridge",
        "test_necessary_and_heuristic_are_not_core_progress");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ProofRoleClassificationParityTest", authorityFunction);
  }
}
