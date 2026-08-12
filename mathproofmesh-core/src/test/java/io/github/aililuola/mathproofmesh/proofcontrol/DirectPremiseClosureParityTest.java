package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DirectPremiseClosureParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_premise_closure_requires_an_exact_match",
        "test_direct_premise_action_does_not_close_obligation_itself");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("DirectPremiseClosureParityTest", authorityFunction);
  }
}
