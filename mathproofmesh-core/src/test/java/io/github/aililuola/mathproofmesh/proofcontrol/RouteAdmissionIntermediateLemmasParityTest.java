package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RouteAdmissionIntermediateLemmasParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_intermediate_lemma_weaker_than_final_goal_is_admitted",
        "test_necessary_only_without_bridge_is_blocked",
        "test_rewrite_verdict_always_has_request");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("RouteAdmissionIntermediateLemmasParityTest", authorityFunction);
  }
}
