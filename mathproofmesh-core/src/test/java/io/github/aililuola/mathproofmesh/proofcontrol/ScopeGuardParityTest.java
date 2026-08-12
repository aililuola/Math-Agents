package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ScopeGuardParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_eventual_or_bounded_scope_cannot_close_all_scope",
        "test_pointwise_and_projection_cannot_close_uniform_full_object",
        "test_quantifier_order_mismatch_is_incomparable",
        "test_low_confidence_unstructured_claim_cannot_promote_fact");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ScopeGuardParityTest", authorityFunction);
  }
}
