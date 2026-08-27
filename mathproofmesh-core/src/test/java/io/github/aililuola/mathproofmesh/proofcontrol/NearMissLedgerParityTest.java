package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NearMissLedgerParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_deterministic_near_miss_preserves_salvageable_structure",
        "test_problem_integrity_failure_is_not_a_near_miss");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("NearMissLedgerParityTest", authorityFunction);
  }
}
