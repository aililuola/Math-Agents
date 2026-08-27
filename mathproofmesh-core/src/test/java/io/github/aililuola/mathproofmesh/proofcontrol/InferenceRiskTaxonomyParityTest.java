package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InferenceRiskTaxonomyParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_deterministic_risk_taxonomy",
        "test_necessary_only_used_as_sufficient_is_risk",
        "test_textual_risk_is_not_a_direct_verification_rejection");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("InferenceRiskTaxonomyParityTest", authorityFunction);
  }
}
