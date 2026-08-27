package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RealizerRepairParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_replace_realizer_preserves_structure_and_requires_falsification",
        "test_repair_budget_and_duplicate_candidate_are_enforced");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("RealizerRepairParityTest", authorityFunction);
  }
}
