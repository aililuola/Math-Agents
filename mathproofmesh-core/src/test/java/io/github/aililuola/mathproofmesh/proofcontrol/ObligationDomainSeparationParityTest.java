package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ObligationDomainSeparationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_protocol_text_not_mathematical_assumption",
        "test_search_task_not_in_core_debt",
        "test_tool_task_not_route_target",
        "test_bottleneck_ignores_nonmathematical_obligation",
        "test_common_mode_ignores_goal_hash_protocol");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ObligationDomainSeparationParityTest", authorityFunction);
  }
}
