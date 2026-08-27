package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NoProofControlHashRegressionParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_sidecar_registration_preserves_all_hash_critical_payloads");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("NoProofControlHashRegressionParityTest", authorityFunction);
  }
}
