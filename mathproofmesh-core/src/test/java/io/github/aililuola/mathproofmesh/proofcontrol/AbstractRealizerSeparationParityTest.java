package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AbstractRealizerSeparationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_failed_candidate_does_not_refute_abstract_structure",
        "test_second_realizer_can_validate_preserved_structure");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("AbstractRealizerSeparationParityTest", authorityFunction);
  }
}
