package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ZeroUtilityBroadcastGateParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_zero_utility_normal_message_kept_local",
        "test_critical_counterexample_broadcasts_without_debt_estimate",
        "test_high_priority_fact_broadcasts",
        "test_positive_expected_reduction_broadcasts_normal_message",
        "test_local_message_does_not_consume_neighbor_quota",
        "test_broadcast_decision_resume_is_stable");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ZeroUtilityBroadcastGateParityTest", authorityFunction);
  }
}
