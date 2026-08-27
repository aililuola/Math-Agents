package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class VerifierIssueRiskMappingParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_verifier_issue_maps_to_inference_risk",
        "test_wrong_direction_issue_classifies_plan_failure",
        "test_unknown_scope_high_centrality_opens_ambiguous_risk",
        "test_risk_blocks_fact_promotion",
        "test_cleared_risk_allows_promotion",
        "test_verification_report_issue_enters_control_state");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("VerifierIssueRiskMappingParityTest", authorityFunction);
  }
}
