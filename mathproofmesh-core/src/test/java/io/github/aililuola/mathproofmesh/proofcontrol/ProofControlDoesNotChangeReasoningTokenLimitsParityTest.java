package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProofControlDoesNotChangeReasoningTokenLimitsParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_proof_control_profiles_only_change_control_configuration",
        "test_all_reasoning_and_deep_exploration_limits_are_frozen",
        "test_release_reasoning_budgets_and_stop_threshold_remain_frozen");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ProofControlDoesNotChangeReasoningTokenLimitsParityTest", authorityFunction);
  }
}
