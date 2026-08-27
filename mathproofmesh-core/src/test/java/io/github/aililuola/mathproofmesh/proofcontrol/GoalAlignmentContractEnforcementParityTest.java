package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GoalAlignmentContractEnforcementParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_low_alignment_score_cannot_pass",
        "test_missing_implication_outline_cannot_pass",
        "test_alignment_exception_requires_enum_and_verified_evidence",
        "test_unknown_relation_creates_countermodel_action");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("GoalAlignmentContractEnforcementParityTest", authorityFunction);
  }
}
