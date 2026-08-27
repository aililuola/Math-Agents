package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InductionMeasureSelectorParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_occurrence_barrier_selects_occurrence_count_not_plain_index",
        "test_invalid_measure_cannot_be_accepted");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("InductionMeasureSelectorParityTest", authorityFunction);
  }
}
