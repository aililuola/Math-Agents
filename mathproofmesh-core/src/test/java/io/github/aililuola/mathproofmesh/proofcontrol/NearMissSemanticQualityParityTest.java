package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NearMissSemanticQualityParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_incomplete_attempt_alone_does_not_create_near_miss",
        "test_checkpoint_policy_failure_is_process_diagnostic",
        "test_mathematical_candidate_failure_creates_near_miss",
        "test_near_miss_routes_to_correct_repair_module",
        "test_near_miss_enters_route_prompt_as_non_authoritative_hint");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("NearMissSemanticQualityParityTest", authorityFunction);
  }
}
