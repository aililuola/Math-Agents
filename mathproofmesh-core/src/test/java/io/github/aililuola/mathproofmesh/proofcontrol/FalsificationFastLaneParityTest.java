package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FalsificationFastLaneParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_exact_targeted_falsification_bypasses_soft_meta_review_only",
        "test_fast_lane_requires_explicit_target_and_respects_resource_caps",
        "test_fast_lane_result_policy_never_promotes_a_fact");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("FalsificationFastLaneParityTest", authorityFunction);
  }
}
